/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2021 - 2024 LSPosed Contributors
 */

package org.lsposed.lspd.service;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;

import org.lsposed.lspd.models.Application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A tiny line-based control channel exposed by the LSPosed daemon over an
 * abstract {@link LocalServerSocket}. It lets a privileged shell process (see
 * the {@code lspd-cli} helper script) enable modules and tick scopes without
 * going through the manager UI.
 *
 * <p>Every request is a single UTF-8 line of space separated tokens terminated
 * by '\n'. The response is one or more lines; the first line is {@code OK} or
 * {@code ERR <message>}. Package names never contain spaces so a plain split is
 * enough. The socket lives in the abstract namespace so no file needs to be
 * created or labelled; access is restricted by checking the peer's uid, which
 * must be root or shell.</p>
 *
 * <p>Supported commands:</p>
 * <pre>
 *   ping
 *   list
 *   enable   &lt;modulePkg&gt;
 *   disable  &lt;modulePkg&gt;
 *   getscope &lt;modulePkg&gt;
 *   scope    &lt;modulePkg&gt; &lt;scopeSpec&gt; [flags]   # additive by default
 *   unscope  &lt;modulePkg&gt; &lt;scopeSpec&gt;
 *   activate &lt;modulePkg&gt; &lt;scopeSpec&gt; [flags]   # enable + tick scopes
 * </pre>
 * where {@code scopeSpec} is a comma separated list of {@code pkg[/userId]}
 * (userId defaults to 0), and {@code flags} is a comma separated subset of
 * {@code replace,forcestop} (or {@code -} for none).
 */
public class CommandListener {
    private static final String TAG = ServiceManager.TAG;
    // Abstract namespace name; the client connects with the same name.
    static final String SOCKET_NAME = "lspd_ctl";
    // Only root and the adb shell user may issue commands.
    private static final int SHELL_UID = 2000;

    // The three limits below are part of the documented wire protocol, and public for that
    // reason: MAX_REQUEST_BYTES is derived from MAX_SCOPE_TARGETS, so a public constant defined
    // by a package-private one would be incoherent, and the CLI checks requests against them.
    // A single command cannot sensibly touch more apps than this; a longer list is a
    // malformed spec, not a real request.
    public static final int MAX_SCOPE_TARGETS = 512;
    // Android user ids are small; anything larger is a typo, not a real user.
    public static final int MAX_USER_ID = 100000;
    // The longest package name Android accepts.
    private static final int MAX_PACKAGE_NAME_BYTES = 255;
    // One target as it appears on the wire: "pkg/userId," -- MAX_USER_ID is six digits, plus
    // the '/' and the ',' separator.
    private static final int MAX_TARGET_BYTES = MAX_PACKAGE_NAME_BYTES + 8;
    // Room for the verb, the module package and the trailing flags token around the scope list.
    private static final int MAX_REQUEST_OVERHEAD_BYTES = 1024;
    /**
     * How much may be buffered while waiting for the request's terminating newline.
     *
     * <p>Derived from {@link #MAX_SCOPE_TARGETS} rather than chosen independently. It used to be
     * a flat 8 KiB, which refused at the transport layer what the parser was documented to
     * accept: 512 targets is roughly 15 KB of spec, so a legal command failed as
     * "request exceeds 8192 bytes" against a limit nothing documented. Deriving one from the
     * other means the socket can always carry a request the parser would have taken.</p>
     *
     * <p>Public because the CLI checks a request against it before connecting; see
     * {@code CliMain.MAX_REQUEST_BYTES}.</p>
     */
    public static final int MAX_REQUEST_BYTES =
            MAX_REQUEST_OVERHEAD_BYTES + MAX_SCOPE_TARGETS * MAX_TARGET_BYTES;
    // Enough for a handful of concurrent shells, few enough that stalled clients cannot
    // exhaust the daemon's threads. Each may buffer up to MAX_REQUEST_BYTES, so this also
    // bounds the listener's peak request memory at roughly 530 KiB.
    private static final int MAX_CONCURRENT_CLIENTS = 4;
    // A client that connects and then says nothing must not hold a worker indefinitely.
    private static final int SOCKET_TIMEOUT_MS = 5000;
    // How long to keep reading from a client whose request has already been refused. Short: the
    // point is to let it finish writing so it can get to its read, not to read the request.
    private static final int DRAIN_TIMEOUT_MS = 500;
    // Sockets queued for a busy reply. Small on purpose -- a flood is a flood, and dropping the
    // reply then is no worse than the behaviour this queue replaced.
    private static final int MAX_PENDING_REJECTS = 8;

    private final ConfigManager configManager;
    private volatile boolean running = true;
    private volatile LocalServerSocket server;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            0, MAX_CONCURRENT_CLIENTS, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
            r -> {
                var t = new Thread(r, "lspd-cli-worker");
                t.setDaemon(true);
                return t;
            });
    // Rejections deliberately do not run on the accept loop. Telling a client the daemon is busy
    // means writing a reply and then draining whatever it is still sending (see drainQuietly),
    // and blocking accept() for that would turn "busy" into "unreachable" for everyone behind it.
    private final ThreadPoolExecutor rejecters = new ThreadPoolExecutor(
            0, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(MAX_PENDING_REJECTS),
            r -> {
                var t = new Thread(r, "lspd-cli-rejecter");
                t.setDaemon(true);
                return t;
            });

    public CommandListener(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public void start() {
        Thread t = new Thread(this::serve, "lspd-cli-listener");
        t.setDaemon(true);
        t.start();
    }

    /** Stops accepting new clients and releases the listening socket. */
    public void stop() {
        running = false;
        var listening = server;
        if (listening != null) {
            try {
                listening.close();
            } catch (IOException ignored) {
            }
        }
        workers.shutdownNow();
        rejecters.shutdownNow();
    }

    private void serve() {
        LocalServerSocket listening;
        try {
            listening = new LocalServerSocket(SOCKET_NAME);
        } catch (IOException e) {
            Log.e(TAG, "failed to open cli socket", e);
            return;
        }
        server = listening;
        Log.i(TAG, "cli control socket listening on @" + SOCKET_NAME);
        while (running) {
            LocalSocket socket;
            try {
                socket = listening.accept();
            } catch (Throwable e) {
                // A failing accept() is a problem with the listening socket, not with one
                // client, so retrying it in a tight loop would just spin a core forever.
                if (running) Log.e(TAG, "cli accept failed, listener stopping", e);
                break;
            }
            // Hand each client to a worker so one stalled shell cannot block the next command.
            try {
                workers.execute(() -> serveClient(socket));
            } catch (RejectedExecutionException e) {
                queueReject(socket);
            }
        }
        try {
            listening.close();
        } catch (IOException ignored) {
        }
    }

    private void serveClient(LocalSocket socket) {
        try (socket) {
            handle(socket);
        } catch (Throwable e) {
            Log.w(TAG, "cli connection error", e);
        }
    }

    /** Hands an over-limit client to the rejecter thread so the accept loop keeps running. */
    private void queueReject(LocalSocket socket) {
        try {
            rejecters.execute(() -> rejectBusy(socket));
        } catch (RejectedExecutionException e) {
            // Even the reject queue is full, or the listener is shutting down. Close without a
            // reply rather than stall accept() -- there is nothing better left to do.
            Log.w(TAG, "cli reject queue full, dropping client without a reply");
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void rejectBusy(LocalSocket socket) {
        try (socket) {
            var os = socket.getOutputStream();
            os.write("ERR busy, too many concurrent cli clients\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            // Without this the reply is destroyed by the close; see drainQuietly.
            drainQuietly(socket);
        } catch (Throwable e) {
            Log.w(TAG, "cli busy reply failed", e);
        }
    }

    private void handle(LocalSocket socket) throws IOException {
        // The abstract namespace has no filesystem permissions, so authorise the
        // peer explicitly: only root and the adb shell user may drive the CLI.
        // Without this any app that SELinux happens to let through could enable
        // arbitrary modules against arbitrary scopes.
        if (!isAuthorized(socket)) {
            socket.getOutputStream().write("ERR permission denied\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            drainQuietly(socket);
            return;
        }
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        OutputStream os = socket.getOutputStream();
        String line;
        try {
            line = readRequest(socket.getInputStream());
        } catch (IOException e) {
            // A read timeout or an oversized request: answer instead of dropping the
            // connection silently, then release the worker.
            Log.w(TAG, "cli request read failed", e);
            os.write(("ERR " + rootMessage(e) + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            // The client is probably still writing the request we just refused; without this
            // the close resets the connection and takes the reply with it. See drainQuietly.
            drainQuietly(socket);
            return;
        }
        StringBuilder reply = new StringBuilder();
        try {
            dispatch(line, reply);
        } catch (Throwable e) {
            Log.w(TAG, "cli command failed: " + line, e);
            reply.setLength(0);
            reply.append("ERR ").append(rootMessage(e)).append('\n');
        }
        os.write(reply.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /** Reads one newline-terminated request, refusing to buffer past {@link #MAX_REQUEST_BYTES}. */
    private static String readRequest(InputStream in) throws IOException {
        var buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (buf.size() >= MAX_REQUEST_BYTES) {
                throw new IOException("request exceeds " + MAX_REQUEST_BYTES + " bytes");
            }
            buf.write(c);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Reads and discards whatever the client is still sending, then returns.
     *
     * <p>Needed on every path that answers and closes without having read the whole request.
     * Closing a socket that still has unread inbound data queued makes the close abortive: the
     * peer's receive buffer is discarded, and that buffer is exactly where the reply just
     * written is sitting. So a client sending a multi-KB request saw
     * "Connection reset by peer" instead of the specific error the daemon had already told it --
     * which is how both the oversized request and the busy rejection came to look like a dead
     * daemon rather than a refused command.</p>
     *
     * <p>Bounded by {@link #DRAIN_TIMEOUT_MS} and {@link #MAX_REQUEST_BYTES}: the goal is to
     * unblock the client's write so it can reach its read, not to accept the request.</p>
     */
    private static void drainQuietly(LocalSocket socket) {
        try {
            socket.setSoTimeout(DRAIN_TIMEOUT_MS);
            var in = socket.getInputStream();
            var scratch = new byte[4096];
            int remaining = MAX_REQUEST_BYTES;
            while (remaining > 0) {
                int n = in.read(scratch, 0, Math.min(scratch.length, remaining));
                if (n <= 0) break;
                remaining -= n;
            }
        } catch (Throwable ignored) {
            // Timed out, or the client hung up. Either way there is nothing left to protect.
        }
    }

    private static boolean isAuthorized(LocalSocket socket) {
        try {
            var creds = socket.getPeerCredentials();
            int uid = creds.getUid();
            if (uid == 0 || uid == SHELL_UID) return true;
            Log.w(TAG, "rejected cli connection from uid " + uid);
            return false;
        } catch (Throwable e) {
            // Fail closed: if the peer cannot be identified, do not serve it.
            Log.w(TAG, "cannot read cli peer credentials, rejecting", e);
            return false;
        }
    }

    private void dispatch(String line, StringBuilder reply) throws Exception {
        if (line == null || line.trim().isEmpty()) {
            reply.append("ERR empty command\n");
            return;
        }
        String[] tokens = line.trim().split("\\s+");
        String cmd = tokens[0].toLowerCase();
        switch (cmd) {
            case "ping":
                reply.append("OK pong\n");
                break;
            case "list": {
                String[] modules = configManager.enabledModules();
                reply.append("OK\n");
                if (modules != null) {
                    for (String m : modules) reply.append(m).append('\n');
                }
                break;
            }
            case "enable": {
                String pkg = requireArg(tokens, 1, "module package");
                // Same atomic operation the manager uses: enabled flag plus the module's own
                // scope row, in one transaction, so both front-ends behave identically.
                String error = configManager.applyScopeBatch(pkg, List.of(), true, false, false);
                reply.append(error == null ? "OK enabled " + pkg + "\n"
                        : "ERR cannot enable " + pkg + ": " + error + "\n");
                break;
            }
            case "disable": {
                String pkg = requireArg(tokens, 1, "module package");
                if (!configManager.isModuleRegistered(pkg)) {
                    reply.append("ERR cannot disable ").append(pkg)
                            .append(" (not a registered module)\n");
                    break;
                }
                configManager.disableModule(pkg);
                // disableModule() returns false both when the module was already disabled and
                // when it does not exist, so read the state back instead of trusting it.
                reply.append(isEnabled(pkg) ? "ERR cannot disable " + pkg + "\n"
                        : "OK disabled " + pkg + "\n");
                break;
            }
            case "getscope": {
                String pkg = requireArg(tokens, 1, "module package");
                List<Application> scope = configManager.getModuleScope(pkg);
                reply.append("OK\n");
                if (scope != null) {
                    for (Application a : scope) reply.append(a.packageName).append('/').append(a.userId).append('\n');
                }
                break;
            }
            case "scope":
            case "activate": {
                String pkg = requireArg(tokens, 1, "module package");
                List<Application> scope = parseScope(requireArg(tokens, 2, "scope spec"));
                Flags flags = parseFlags(tokens.length > 3 ? tokens[3] : "");
                applyScope(pkg, scope, flags, cmd.equals("activate"), false, reply);
                break;
            }
            case "unscope": {
                String pkg = requireArg(tokens, 1, "module package");
                List<Application> scope = parseScope(requireArg(tokens, 2, "scope spec"));
                applyScope(pkg, scope, parseFlags(tokens.length > 3 ? tokens[3] : ""),
                        false, true, reply);
                break;
            }
            default:
                reply.append("ERR unknown command: ").append(cmd).append('\n');
        }
    }

    /**
     * Hands the whole batch to the daemon, which validates every target and applies them in one
     * transaction, then force-stops the affected apps if asked. The database result and the
     * force-stop result are reported separately: a failed kill still leaves the scope written,
     * so it is a partial success, not a success and not a rollback.
     *
     * <p>The count in the reply is the number of targets written, which is not always the number
     * of rows that survive. {@code ConfigManager.cacheScopes()} runs immediately after the commit
     * and drops any target that currently resolves to no processes, so scoping a package that is
     * not installed for that user reports one target and leaves zero rows behind -- which is the
     * documented "silently skipped" behaviour, reached by insert-then-prune rather than by
     * skipping the insert. The reply says "target(s)" rather than "app(s)" for that reason:
     * reading the number as apps now hooked overstates what happened.</p>
     */
    private void applyScope(String pkg, List<Application> scope, Flags flags,
                            boolean enableFirst, boolean remove, StringBuilder reply) throws Exception {
        if (scope.isEmpty() && !enableFirst) {
            reply.append("ERR no scope targets given for ").append(pkg).append('\n');
            return;
        }
        String error = configManager.applyScopeBatch(pkg, scope, enableFirst, flags.replace, remove);
        if (error != null) {
            // Nothing was committed, so nothing needs undoing and nothing gets force-stopped.
            reply.append("ERR cannot ").append(remove ? "unscope " : "scope ").append(pkg)
                    .append(": ").append(error).append('\n');
            return;
        }
        var killFailures = new ArrayList<String>();
        if (flags.forceStop) {
            for (Application a : scope) {
                try {
                    ActivityManagerService.forceStopPackage(a.packageName, a.userId);
                } catch (Throwable e) {
                    Log.w(TAG, "force-stop " + a.packageName + " failed", e);
                    killFailures.add(a.packageName + "/" + a.userId);
                }
            }
        }
        String action = remove ? "unscoped " : enableFirst ? "activated " : "scoped ";
        if (!killFailures.isEmpty()) {
            // Deliberately not "OK": the CLI turns a non-OK first line into a non-zero exit
            // code, which is what a half-applied change deserves.
            reply.append("WARN ").append(action).append(pkg).append(" -> ")
                    .append(scope.size()).append(" target(s), but force-stop failed for ")
                    .append(String.join(", ", killFailures))
                    .append(" (the change applies when they next restart)\n");
            return;
        }
        reply.append("OK ").append(action).append(pkg)
                .append(" -> ").append(scope.size()).append(" target(s)")
                .append(flags.replace && !remove ? " (replaced)" : "")
                .append(flags.forceStop ? " (force-stopped)" : "")
                .append('\n');
    }

    private boolean isEnabled(String pkg) {
        String[] modules = configManager.enabledModules();
        if (modules == null) return false;
        for (String m : modules) {
            if (pkg.equals(m)) return true;
        }
        return false;
    }

    /**
     * Parses {@code pkg[/userId],pkg[/userId],...}. Anything malformed throws instead of being
     * silently reinterpreted: a typo in a user id used to parse as user 0 or abort the whole
     * command with a bare NumberFormatException, and both hid the real mistake from the caller.
     *
     * <p>This and the other parse helpers below are package-private rather than private so
     * CommandListenerParserTest can exercise them on the JVM. Reaching them through the socket
     * instead would need a daemon, a ConfigManager and root, which is why they went untested
     * long enough to ship the reinterpretation described above.</p>
     */
    static List<Application> parseScope(String spec) {
        List<Application> result = new ArrayList<>();
        if (spec == null || spec.equals("-") || spec.isEmpty()) return result;
        for (String part : spec.split(",")) {
            if (part.isEmpty()) continue;
            Application app = new Application();
            int slash = part.indexOf('/');
            if (slash >= 0) {
                app.packageName = part.substring(0, slash);
                app.userId = parseUserId(part.substring(slash + 1), part);
            } else {
                app.packageName = part;
                app.userId = 0;
            }
            if (app.packageName.isEmpty()) {
                throw new IllegalArgumentException("empty package name in scope entry '" + part + "'");
            }
            result.add(app);
        }
        if (result.size() > MAX_SCOPE_TARGETS) {
            throw new IllegalArgumentException("too many scope targets (" + result.size()
                    + " > " + MAX_SCOPE_TARGETS + ")");
        }
        return result;
    }

    static int parseUserId(String raw, String entry) {
        int userId;
        try {
            userId = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad user id '" + raw + "' in scope entry '" + entry
                    + "' (expected a non-negative integer)");
        }
        if (userId < 0 || userId > MAX_USER_ID) {
            throw new IllegalArgumentException("user id " + userId + " in scope entry '" + entry
                    + "' is out of range 0.." + MAX_USER_ID);
        }
        return userId;
    }

    /** Parses the flag list, refusing unknown flags rather than applying a subset of the request. */
    static Flags parseFlags(String spec) {
        Flags f = new Flags();
        if (spec == null || spec.isEmpty() || spec.equals("-")) return f;
        for (String part : spec.split(",")) {
            var flag = part.trim().toLowerCase();
            if (flag.isEmpty()) continue;
            switch (flag) {
                case "replace":
                    f.replace = true;
                    break;
                case "forcestop":
                case "force-stop":
                    f.forceStop = true;
                    break;
                default:
                    // Ignoring a typo here would silently drop `replace` and leave the old scope
                    // in place while still answering OK.
                    throw new IllegalArgumentException("unknown flag '" + part.trim()
                            + "' (expected replace and/or forcestop, or - for none)");
            }
        }
        return f;
    }

    static String requireArg(String[] tokens, int idx, String name) {
        if (tokens.length <= idx) throw new IllegalArgumentException("missing " + name);
        return tokens[idx];
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String msg = t.getMessage();
        return (msg == null ? t.getClass().getSimpleName() : msg).replace('\n', ' ');
    }

    static final class Flags {
        boolean replace = false;
        boolean forceStop = false;
    }
}
