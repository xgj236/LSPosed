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
 * Copyright (C) 2026 LSPosed Contributors
 */

package org.lsposed.lspd.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.lsposed.lspd.service.CommandListener;

import org.junit.Test;

/**
 * Guards the agreement between the CLI's request limit and the daemon's.
 *
 * <p>Two limits used to be picked independently: the parser accepted
 * {@link CommandListener#MAX_SCOPE_TARGETS} targets while the socket refused to buffer more than
 * 8 KiB, and a 512-target command needs roughly 15 KB. So a command the daemon documented as
 * legal died at the transport layer, and it died badly -- the daemon wrote a precise error and
 * then closed with the request half-read, which reset the connection and destroyed the reply. The
 * client reported "cannot talk to LSPosed daemon: Connection reset by peer" and suggested the
 * daemon might not be running.
 *
 * <p>The fix derives the byte limit from the target limit, so the assertion worth keeping is not
 * the arithmetic itself but that the derived limit really does cover a worst-case legal request,
 * and that the copy of it in {@link CliMain} still matches.</p>
 */
public class CliRequestLimitTest {

    /**
     * {@link CliMain} keeps its own copy so the CLI depends on no daemon service class (the same
     * arrangement as the socket name). A copy is only safe while something notices it drifting.
     */
    @Test
    public void theClientAndDaemonAgreeOnTheRequestLimit() {
        assertEquals("CliMain.MAX_REQUEST_BYTES drifted from CommandListener.MAX_REQUEST_BYTES",
                CommandListener.MAX_REQUEST_BYTES, CliMain.MAX_REQUEST_BYTES);
    }

    /**
     * The property that was actually broken: the largest request the parser would accept has to
     * fit through the socket. Built at the real worst case -- MAX_SCOPE_TARGETS targets, each a
     * maximum-length package name with the longest legal user id, plus the longest verb, a
     * maximum-length module name and every flag.
     */
    @Test
    public void theWorstCaseLegalRequestFitsWithinTheByteLimit() {
        String maxPkg = "a".repeat(MAX_PACKAGE_NAME_LENGTH);
        var spec = new StringBuilder();
        for (int i = 0; i < CommandListener.MAX_SCOPE_TARGETS; i++) {
            if (i > 0) spec.append(',');
            spec.append(maxPkg).append('/').append(CommandListener.MAX_USER_ID);
        }
        String request = "activate " + maxPkg + " " + spec + " replace,forcestop";

        assertTrue("a legal " + CommandListener.MAX_SCOPE_TARGETS + "-target request is "
                        + request.length() + " bytes, over the " + CommandListener.MAX_REQUEST_BYTES
                        + " byte transport limit",
                request.length() <= CommandListener.MAX_REQUEST_BYTES);
    }

    /** Android's own cap on a package name, which the byte limit is sized against. */
    private static final int MAX_PACKAGE_NAME_LENGTH = 255;
}
