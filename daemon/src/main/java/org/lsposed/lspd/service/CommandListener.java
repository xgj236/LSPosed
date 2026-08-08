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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

    private final ConfigManager configManager;
    private volatile boolean running = true;

    public CommandListener(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public void start() {
        Thread t = new Thread(this::serve, "lspd-cli-listener");
        t.setDaemon(true);
        t.start();
    }

    private void serve() {
        LocalServerSocket server;
        try {
            server = new LocalServerSocket(SOCKET_NAME);
        } catch (IOException e) {
            Log.e(TAG, "failed to open cli socket", e);
            return;
        }
        Log.i(TAG, "cli control socket listening on @" + SOCKET_NAME);
        while (running) {
            try (LocalSocket socket = server.accept()) {
                handle(socket);
            } catch (Throwable e) {
                Log.w(TAG, "cli connection error", e);
            }
        }
        try {
            server.close();
        } catch (IOException ignored) {
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
            return;
        }
        var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        OutputStream os = socket.getOutputStream();
        String line = reader.readLine();
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
                boolean ok = configManager.enableModule(pkg);
                reply.append(ok ? "OK enabled " + pkg + "\n"
                        : "ERR cannot enable " + pkg + " (not installed or not an Xposed module?)\n");
                break;
            }
            case "disable": {
                String pkg = requireArg(tokens, 1, "module package");
                boolean ok = configManager.disableModule(pkg);
                reply.append(ok ? "OK disabled " + pkg + "\n" : "ERR cannot disable " + pkg + "\n");
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
                applyScope(pkg, scope, flags, cmd.equals("activate"), reply);
                break;
            }
            case "unscope": {
                String pkg = requireArg(tokens, 1, "module package");
                List<Application> scope = parseScope(requireArg(tokens, 2, "scope spec"));
                int removed = 0;
                for (Application a : scope) {
                    if (configManager.removeModuleScope(pkg, a.packageName, a.userId)) removed++;
                }
                reply.append("OK unscoped ").append(removed).append(" app(s) from ").append(pkg).append('\n');
                break;
            }
            default:
                reply.append("ERR unknown command: ").append(cmd).append('\n');
        }
    }

    private void applyScope(String pkg, List<Application> scope, Flags flags,
                            boolean enableFirst, StringBuilder reply) throws Exception {
        if (flags.replace) {
            // setModuleScope(List) enables the module and replaces the whole scope set.
            if (!configManager.setModuleScope(pkg, scope)) {
                reply.append("ERR cannot set scope for ").append(pkg)
                        .append(" (not installed or not an Xposed module?)\n");
                return;
            }
        } else {
            if (enableFirst && !configManager.enableModule(pkg) && !isEnabled(pkg)) {
                // enableModule returns false both when nothing changed (already
                // enabled) and when the module does not exist; distinguish the two.
                reply.append("ERR cannot enable ").append(pkg)
                        .append(" (not installed or not an Xposed module?)\n");
                return;
            }
            for (Application a : scope) {
                // setModuleScope returns false silently when the module has no
                // row in `modules` yet, or for the system/non-owner-user case.
                // Never report OK for a write that did not happen.
                if (!configManager.setModuleScope(pkg, a.packageName, a.userId)) {
                    reply.append("ERR cannot add scope ").append(a.packageName)
                            .append('/').append(a.userId).append(" to ").append(pkg);
                    if ("system".equals(a.packageName) && a.userId != 0) {
                        reply.append(" (the system scope is only valid for user 0)");
                    } else {
                        reply.append(" (module not registered; is it a valid Xposed module?)");
                    }
                    reply.append('\n');
                    return;
                }
            }
        }
        // Read the state back instead of trusting the writes: the caller needs to
        // know whether the module is actually enabled and scoped, since several
        // ConfigManager setters report success for partially applied changes.
        if (enableFirst && !isEnabled(pkg)) {
            reply.append("ERR ").append(pkg)
                    .append(" is still not in the enabled list after writing")
                    .append(" (check `logcat -s LSPosedService`)\n");
            return;
        }
        var applied = configManager.getModuleScope(pkg);
        var missing = new ArrayList<String>();
        for (Application a : scope) {
            boolean found = false;
            if (applied != null) {
                for (Application b : applied) {
                    if (a.packageName.equals(b.packageName) && a.userId == b.userId) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found) missing.add(a.packageName + "/" + a.userId);
        }
        if (!missing.isEmpty()) {
            reply.append("ERR scope not persisted for ").append(pkg).append(": ")
                    .append(String.join(", ", missing)).append('\n');
            return;
        }
        if (flags.forceStop) {
            for (Application a : scope) {
                try {
                    ActivityManagerService.forceStopPackage(a.packageName, a.userId);
                } catch (Throwable e) {
                    Log.w(TAG, "force-stop " + a.packageName + " failed", e);
                }
            }
        }
        reply.append(enableFirst ? "OK activated " : "OK scoped ").append(pkg)
                .append(" -> ").append(scope.size()).append(" app(s)")
                .append(flags.replace ? " (replaced)" : "")
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

    private static List<Application> parseScope(String spec) {
        List<Application> result = new ArrayList<>();
        if (spec == null || spec.equals("-") || spec.isEmpty()) return result;
        for (String part : spec.split(",")) {
            if (part.isEmpty()) continue;
            Application app = new Application();
            int slash = part.indexOf('/');
            if (slash >= 0) {
                app.packageName = part.substring(0, slash);
                app.userId = Integer.parseInt(part.substring(slash + 1));
            } else {
                app.packageName = part;
                app.userId = 0;
            }
            result.add(app);
        }
        return result;
    }

    private static Flags parseFlags(String spec) {
        Flags f = new Flags();
        if (spec == null || spec.isEmpty() || spec.equals("-")) return f;
        for (String part : spec.split(",")) {
            switch (part.trim().toLowerCase()) {
                case "replace":
                    f.replace = true;
                    break;
                case "forcestop":
                case "force-stop":
                    f.forceStop = true;
                    break;
                default:
                    break;
            }
        }
        return f;
    }

    private static String requireArg(String[] tokens, int idx, String name) {
        if (tokens.length <= idx) throw new IllegalArgumentException("missing " + name);
        return tokens[idx];
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String msg = t.getMessage();
        return (msg == null ? t.getClass().getSimpleName() : msg).replace('\n', ' ');
    }

    private static final class Flags {
        boolean replace = false;
        boolean forceStop = false;
    }
}
