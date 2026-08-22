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

package org.lsposed.lspd.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Fixture tests for the lspd-cli command parsers.
 *
 * <p>The control socket takes text typed by a human at an adb shell, so malformed input is the
 * normal case rather than the exceptional one. What made it worth testing is that the failure
 * modes were silent: a mistyped user id parsed as user 0 and a mistyped flag was ignored, so the
 * daemon answered OK while applying something other than what was asked for. Every case below is
 * therefore about what happens to bad input, not about the happy path.</p>
 *
 * <p>These run on the JVM. The parsers are static and touch no daemon state; the only Android
 * type involved is the AIDL-generated {@link org.lsposed.lspd.models.Application}, which is used
 * purely as a field holder here and never parcelled.</p>
 */
public class CommandListenerParserTest {

    // --- parseScope: shape ---------------------------------------------------

    @Test
    public void aBarePackageNameDefaultsToUserZero() {
        var scope = CommandListener.parseScope("com.example.app");
        assertEquals(1, scope.size());
        assertEquals("com.example.app", scope.get(0).packageName);
        assertEquals(0, scope.get(0).userId);
    }

    @Test
    public void anExplicitUserIdIsKept() {
        var scope = CommandListener.parseScope("com.example.app/10");
        assertEquals(1, scope.size());
        assertEquals("com.example.app", scope.get(0).packageName);
        assertEquals(10, scope.get(0).userId);
    }

    @Test
    public void severalEntriesKeepTheirOrderAndTheirOwnUserIds() {
        var scope = CommandListener.parseScope("a.pkg,b.pkg/11,c.pkg");
        assertEquals(3, scope.size());
        assertEquals("a.pkg", scope.get(0).packageName);
        assertEquals(0, scope.get(0).userId);
        assertEquals("b.pkg", scope.get(1).packageName);
        assertEquals(11, scope.get(1).userId);
        assertEquals("c.pkg", scope.get(2).packageName);
        assertEquals(0, scope.get(2).userId);
    }

    /**
     * A scope of nothing has to be expressible: `scope pkg -` is how a caller clears a module's
     * scope, and it must not be confused with a missing argument, which requireArg rejects.
     */
    @Test
    public void dashAndEmptyAndNullAllMeanAnEmptyScope() {
        assertTrue(CommandListener.parseScope("-").isEmpty());
        assertTrue(CommandListener.parseScope("").isEmpty());
        assertTrue(CommandListener.parseScope(null).isEmpty());
    }

    /**
     * Stray commas come from shell quoting and from hand-editing a long list. They are noise, not
     * entries -- an empty entry must not become a package with an empty name.
     */
    @Test
    public void strayCommasAreSkippedRatherThanBecomingEmptyEntries() {
        var scope = CommandListener.parseScope("a.pkg,,b.pkg,");
        assertEquals(2, scope.size());
        assertEquals("a.pkg", scope.get(0).packageName);
        assertEquals("b.pkg", scope.get(1).packageName);
    }

    // --- parseScope: rejection ----------------------------------------------

    @Test
    public void anEntryWithNoPackageNameIsRejected() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> CommandListener.parseScope("/10"));
        assertTrue("message should quote the offending entry, was: " + e.getMessage(),
                e.getMessage().contains("/10"));
    }

    /**
     * The regression this file exists for: "com.example.app/1O" with a letter O used to be
     * accepted as user 0, so the command reported OK after scoping a different user than the one
     * the caller named.
     */
    @Test
    public void aNonNumericUserIdIsRejectedRatherThanTreatedAsZero() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> CommandListener.parseScope("com.example.app/1O"));
        assertTrue("message should name the bad id, was: " + e.getMessage(),
                e.getMessage().contains("1O"));
        assertTrue("message should name the entry, was: " + e.getMessage(),
                e.getMessage().contains("com.example.app/1O"));
    }

    @Test
    public void aTrailingSlashWithNoUserIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandListener.parseScope("com.example.app/"));
    }

    @Test
    public void aSecondSlashIsRejectedRatherThanTruncated() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandListener.parseScope("com.example.app/1/2"));
    }

    @Test
    public void aNegativeUserIdIsRejected() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> CommandListener.parseScope("com.example.app/-1"));
        assertTrue("message should say the id is out of range, was: " + e.getMessage(),
                e.getMessage().contains("out of range"));
    }

    @Test
    public void theHighestAcceptedUserIdIsTakenAndOneMoreIsRejected() {
        var scope = CommandListener.parseScope("com.example.app/" + CommandListener.MAX_USER_ID);
        assertEquals(CommandListener.MAX_USER_ID, scope.get(0).userId);

        assertThrows(IllegalArgumentException.class, () ->
                CommandListener.parseScope("com.example.app/" + (CommandListener.MAX_USER_ID + 1)));
    }

    /**
     * A list longer than the cap is a malformed spec -- a paste accident, or a glob the shell
     * expanded -- and the daemon force-stops every app in the scope, so accepting one would take
     * the device down. Both sides of the boundary are checked so the cap cannot drift off by one.
     */
    @Test
    public void theScopeSizeCapIsEnforcedAtItsBoundary() {
        assertEquals(CommandListener.MAX_SCOPE_TARGETS,
                CommandListener.parseScope(scopeSpec(CommandListener.MAX_SCOPE_TARGETS)).size());

        var e = assertThrows(IllegalArgumentException.class, () ->
                CommandListener.parseScope(scopeSpec(CommandListener.MAX_SCOPE_TARGETS + 1)));
        assertTrue("message should say what the limit was, was: " + e.getMessage(),
                e.getMessage().contains(String.valueOf(CommandListener.MAX_SCOPE_TARGETS)));
    }

    private static String scopeSpec(int n) {
        var sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append("pkg").append(i);
        }
        return sb.toString();
    }

    // --- parseUserId --------------------------------------------------------

    @Test
    public void parseUserIdNamesTheWholeEntryAndNotJustTheNumber() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> CommandListener.parseUserId("x", "some.pkg/x"));
        assertTrue("message should quote the entry so it can be found in a long list, was: "
                + e.getMessage(), e.getMessage().contains("some.pkg/x"));
    }

    @Test
    public void parseUserIdAcceptsZero() {
        assertEquals(0, CommandListener.parseUserId("0", "some.pkg/0"));
    }

    // --- parseFlags ---------------------------------------------------------

    @Test
    public void noFlagsMeansNeitherReplaceNorForceStop() {
        for (String spec : new String[]{"", "-", null}) {
            var f = CommandListener.parseFlags(spec);
            assertFalse("replace should be off for spec " + spec, f.replace);
            assertFalse("forceStop should be off for spec " + spec, f.forceStop);
        }
    }

    @Test
    public void replaceIsRecognisedOnItsOwn() {
        var f = CommandListener.parseFlags("replace");
        assertTrue(f.replace);
        assertFalse(f.forceStop);
    }

    /** Both spellings exist because the flag reads as one word but is often typed as two. */
    @Test
    public void bothSpellingsOfForceStopAreRecognised() {
        assertTrue(CommandListener.parseFlags("forcestop").forceStop);
        assertTrue(CommandListener.parseFlags("force-stop").forceStop);
        assertFalse(CommandListener.parseFlags("forcestop").replace);
    }

    @Test
    public void flagsCombine() {
        var f = CommandListener.parseFlags("replace,force-stop");
        assertTrue(f.replace);
        assertTrue(f.forceStop);
    }

    @Test
    public void flagsToleratePaddingAndCaseAndStrayCommas() {
        var f = CommandListener.parseFlags(" REPLACE , ,ForceStop ");
        assertTrue(f.replace);
        assertTrue(f.forceStop);
    }

    /**
     * The second silent failure mode: "replce" was ignored, so a command meant to replace a scope
     * appended to it instead and still answered OK. A typo has to fail loudly, and it has to fail
     * even when it sits next to a flag that did parse -- applying the recognised half of a request
     * is worse than applying none of it, because the caller has no way to tell which half ran.
     */
    @Test
    public void anUnknownFlagIsRejectedEvenAlongsideValidOnes() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> CommandListener.parseFlags("replace,replce"));
        assertTrue("message should quote the typo, was: " + e.getMessage(),
                e.getMessage().contains("replce"));
        assertTrue("message should say what is accepted, was: " + e.getMessage(),
                e.getMessage().contains("replace"));
    }

    @Test
    public void anUnknownFlagIsQuotedWithoutItsSurroundingWhitespace() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> CommandListener.parseFlags("  bogus  "));
        assertTrue("message should quote the flag trimmed, was: " + e.getMessage(),
                e.getMessage().contains("'bogus'"));
    }

    // --- requireArg ---------------------------------------------------------

    @Test
    public void requireArgReturnsThePresentToken() {
        var tokens = new String[]{"scope", "some.pkg", "target.pkg"};
        assertEquals("target.pkg", CommandListener.requireArg(tokens, 2, "scope spec"));
    }

    /**
     * A missing argument is a different error from an empty one: `scope pkg` forgot to say what to
     * scope, while `scope pkg -` deliberately asked for nothing. The message names the argument so
     * the reply is usable without reading the source.
     */
    @Test
    public void requireArgNamesTheMissingArgument() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> CommandListener.requireArg(new String[]{"scope", "some.pkg"}, 2, "scope spec"));
        assertTrue("message should name the argument, was: " + e.getMessage(),
                e.getMessage().contains("scope spec"));
    }
}
