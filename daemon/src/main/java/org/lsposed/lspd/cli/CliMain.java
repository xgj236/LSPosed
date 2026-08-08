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

package org.lsposed.lspd.cli;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Command line front end for the LSPosed daemon control socket.
 *
 * <p>Launched by the {@code lspd-cli} helper script as a short-lived
 * {@code app_process} instance. It translates a friendly argument list into the
 * one-line wire protocol understood by
 * {@link org.lsposed.lspd.service.CommandListener} in the running daemon,
 * prints the reply and exits with 0 on success or 1 on failure.</p>
 *
 * <p>This class deliberately does not touch any LSPosed internals: it only
 * speaks to the daemon over the abstract {@code lspd_ctl} socket, so it works
 * even though it runs in a completely separate process.</p>
 */
public class CliMain {
    // Keep in sync with CommandListener.SOCKET_NAME.
    private static final String SOCKET_NAME = "lspd_ctl";

    public static void main(String[] args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        if (args.length == 0 || isHelp(args[0])) {
            printUsage(out);
            System.exit(args.length == 0 ? 1 : 0);
            return;
        }

        String request;
        try {
            request = buildRequest(args);
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            printUsage(err);
            System.exit(2);
            return;
        }

        try {
            String reply = send(request);
            // The reply's first line carries the OK/ERR status.
            String[] lines = reply.split("\n", -1);
            boolean ok = lines.length > 0 && lines[0].startsWith("OK");
            for (String l : lines) {
                if (l.isEmpty()) continue;
                out.println(l);
            }
            System.exit(ok ? 0 : 1);
        } catch (Throwable e) {
            err.println("error: cannot talk to LSPosed daemon: " + e.getMessage());
            err.println("hint: is LSPosed running? try `lspd-cli ping`");
            System.exit(1);
        }
    }

    private static boolean isHelp(String a) {
        return a.equals("-h") || a.equals("--help") || a.equals("help");
    }

    /** Translate friendly CLI args into the daemon wire protocol line. */
    private static String buildRequest(String[] args) {
        String cmd = args[0].toLowerCase();
        switch (cmd) {
            case "ping":
            case "list":
                return cmd;
            case "enable":
            case "disable":
            case "getscope":
                return cmd + " " + requirePkg(args);
            case "unscope": {
                String pkg = requirePkg(args);
                Parsed p = parseOptions(args);
                if (p.scope == null) throw new IllegalArgumentException("unscope requires --scope");
                return "unscope " + pkg + " " + p.scope;
            }
            case "scope":
            case "activate": {
                String pkg = requirePkg(args);
                Parsed p = parseOptions(args);
                if (p.scope == null) throw new IllegalArgumentException(cmd + " requires --scope");
                String flags = joinFlags(p);
                return cmd + " " + pkg + " " + p.scope + " " + flags;
            }
            default:
                throw new IllegalArgumentException("unknown command: " + cmd);
        }
    }

    private static String requirePkg(String[] args) {
        if (args.length < 2 || args[1].startsWith("-")) {
            throw new IllegalArgumentException(args[0] + " requires a module package name");
        }
        return args[1];
    }

    private static Parsed parseOptions(String[] args) {
        Parsed p = new Parsed();
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--scope":
                case "-s":
                    if (i + 1 >= args.length) throw new IllegalArgumentException("--scope needs a value");
                    p.scope = normalizeScope(args[++i]);
                    break;
                case "--replace":
                case "-r":
                    p.replace = true;
                    break;
                case "--force-stop":
                case "-f":
                    p.forceStop = true;
                    break;
                default:
                    throw new IllegalArgumentException("unexpected argument: " + a);
            }
        }
        return p;
    }

    /** The wire protocol uses ',' as separators and forbids spaces, so validate here. */
    private static String normalizeScope(String raw) {
        if (raw.contains(" ")) throw new IllegalArgumentException("scope must not contain spaces");
        List<String> parts = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isEmpty()) parts.add(part);
        }
        if (parts.isEmpty()) throw new IllegalArgumentException("empty --scope");
        return String.join(",", parts);
    }

    private static String joinFlags(Parsed p) {
        List<String> flags = new ArrayList<>();
        if (p.replace) flags.add("replace");
        if (p.forceStop) flags.add("forcestop");
        return flags.isEmpty() ? "-" : String.join(",", flags);
    }

    private static String send(String request) throws Exception {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            OutputStream os = socket.getOutputStream();
            os.write((request + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            try {
                socket.shutdownOutput();
            } catch (Throwable ignored) {
            }
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private static void printUsage(PrintStream o) {
        o.println("LSPosed command line tool");
        o.println();
        o.println("Usage:");
        o.println("  lspd-cli activate <module> --scope <a,b/uid,...> [--replace] [--force-stop]");
        o.println("  lspd-cli scope    <module> --scope <a,b/uid,...> [--replace] [--force-stop]");
        o.println("  lspd-cli unscope  <module> --scope <a,b/uid,...>");
        o.println("  lspd-cli enable   <module>");
        o.println("  lspd-cli disable  <module>");
        o.println("  lspd-cli getscope <module>");
        o.println("  lspd-cli list");
        o.println("  lspd-cli ping");
        o.println();
        o.println("Notes:");
        o.println("  <module>  the module app package name, e.g. io.github.example");
        o.println("  --scope   comma separated target apps; each is pkg or pkg/userId (userId default 0)");
        o.println("  activate  = enable the module AND tick the given scope apps in one call");
        o.println("  --replace overwrites the whole scope set instead of adding to it");
        o.println("  --force-stop kills the scope apps so hooks apply immediately");
        o.println();
        o.println("Example:");
        o.println("  lspd-cli activate com.example.mod --scope com.android.settings,com.tencent.mm/0");
    }

    private static final class Parsed {
        String scope = null;
        boolean replace = false;
        boolean forceStop = false;
    }
}
