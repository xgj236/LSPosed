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

package org.lsposed.manager.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.pm.ApplicationInfo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Fixture tests for module recognition.
 *
 * <p>These exist because recognising a module and deciding which metadata layout to read it with
 * are two different questions, and conflating them broke every traditional module: nearly all of
 * them ship {@code assets/xposed_init}, so treating that file as a modern marker sent the manager
 * looking for descriptions and scopes under {@code META-INF/xposed}, where it found nothing. The
 * failure was invisible -- modules still loaded, they just lost their metadata -- which is exactly
 * the kind of regression that needs a test rather than a careful reader.
 *
 * <p>Runs on the JVM. {@link ModuleUtil#scanModuleApk} reads only {@link ApplicationInfo} fields
 * and opens archives with {@code java.util.zip}, so no device and no framework is involved.
 */
public class ModuleUtilTest {

    private static final String MODERN_ENTRY = "META-INF/xposed/java_init.list";
    private static final String LEGACY_ENTRY = "assets/xposed_init";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // --- Fixtures ----------------------------------------------------------

    /** Writes a syntactically valid apk (a zip) containing exactly the named entries. */
    private File apk(String name, String... entries) throws IOException {
        File f = tmp.newFile(name);
        try (var zip = new ZipOutputStream(new FileOutputStream(f))) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(("placeholder for " + entry).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return f;
    }

    /** An apk-shaped file that is not a zip at all. */
    private File corruptApk(String name) throws IOException {
        File f = tmp.newFile(name);
        try (var out = new FileOutputStream(f)) {
            out.write(new byte[]{'n', 'o', 't', ' ', 'a', ' ', 'z', 'i', 'p'});
        }
        return f;
    }

    private static ApplicationInfo info(File base, File... splits) {
        var info = new ApplicationInfo();
        info.sourceDir = base == null ? null : base.getAbsolutePath();
        if (splits.length > 0) {
            info.splitSourceDirs = new String[splits.length];
            for (int i = 0; i < splits.length; i++) {
                info.splitSourceDirs[i] = splits[i].getAbsolutePath();
            }
        }
        return info;
    }

    // --- Recognition -------------------------------------------------------

    @Test
    public void modernMarkerAloneIsRecognisedAsModern() throws IOException {
        var result = ModuleUtil.scanModuleApk(info(apk("modern.apk", MODERN_ENTRY, "classes.dex")));
        try {
            assertTrue("a java_init.list makes this a module", result.isModule());
            assertNotNull("and a modern one, so the archive is handed back open", result.modernApk);
            assertFalse("no legacy marker was present", result.legacyEntry);
        } finally {
            result.close();
        }
    }

    /**
     * The BUG-001 regression: a module whose only marker is {@code assets/xposed_init} must be
     * recognised as a module and must NOT be classified as modern. Getting the second half wrong is
     * what silently emptied the metadata of nearly every traditional module.
     */
    @Test
    public void legacyEntryAloneIsAModuleButNotModern() throws IOException {
        var result = ModuleUtil.scanModuleApk(info(apk("legacy.apk", LEGACY_ENTRY, "classes.dex")));
        try {
            assertTrue("an assets/xposed_init makes this a module", result.isModule());
            assertNull("but not a modern one", result.modernApk);
            assertTrue(result.legacyEntry);
        } finally {
            result.close();
        }
    }

    @Test
    public void bothMarkersInOneApkResolveToModern() throws IOException {
        // Modules that support both APIs ship both files. Modern wins: it is the layout that
        // carries the richer metadata.
        var result = ModuleUtil.scanModuleApk(info(apk("both.apk", LEGACY_ENTRY, MODERN_ENTRY)));
        try {
            assertTrue(result.isModule());
            assertNotNull(result.modernApk);
        } finally {
            result.close();
        }
    }

    @Test
    public void nonModuleApkIsNotAModule() throws IOException {
        var result = ModuleUtil.scanModuleApk(
                info(apk("plain.apk", "AndroidManifest.xml", "classes.dex", "assets/config.json")));
        try {
            assertFalse(result.isModule());
            assertNull(result.modernApk);
            assertFalse(result.legacyEntry);
        } finally {
            result.close();
        }
    }

    // --- Split packages ----------------------------------------------------

    @Test
    public void modernMarkerInASplitIsFound() throws IOException {
        var base = apk("base.apk", "AndroidManifest.xml", "classes.dex");
        var split = apk("split_feature.apk", MODERN_ENTRY);
        var result = ModuleUtil.scanModuleApk(info(base, split));
        try {
            assertTrue("a marker in any apk of the package counts", result.isModule());
            assertNotNull(result.modernApk);
        } finally {
            result.close();
        }
    }

    @Test
    public void legacyMarkerInASplitIsFound() throws IOException {
        var base = apk("base2.apk", "AndroidManifest.xml");
        var split = apk("split_legacy.apk", LEGACY_ENTRY);
        var result = ModuleUtil.scanModuleApk(info(base, split));
        try {
            assertTrue(result.isModule());
            assertNull(result.modernApk);
            assertTrue(result.legacyEntry);
        } finally {
            result.close();
        }
    }

    @Test
    public void aLegacyMarkerFoundBeforeAModernOneDoesNotSuppressIt() throws IOException {
        // Splits are scanned before the base apk, so this puts the legacy marker first in scan
        // order and the modern marker last. The scan must keep going after the legacy hit.
        var split = apk("split_legacy_first.apk", LEGACY_ENTRY);
        var base = apk("base_modern.apk", MODERN_ENTRY);
        var result = ModuleUtil.scanModuleApk(info(base, split));
        try {
            assertTrue(result.isModule());
            assertNotNull("the modern marker is still reached", result.modernApk);
            assertTrue("and the legacy marker seen on the way is remembered", result.legacyEntry);
        } finally {
            result.close();
        }
    }

    // --- Damaged and missing inputs ----------------------------------------

    @Test
    public void corruptApkContributesNoMarkers() throws IOException {
        // An unreadable archive must not throw out of the scan: one broken package would otherwise
        // abort the reload of every other module.
        var result = ModuleUtil.scanModuleApk(info(corruptApk("corrupt.apk")));
        try {
            assertFalse(result.isModule());
        } finally {
            result.close();
        }
    }

    @Test
    public void corruptApkDoesNotHideAMarkerInASibling() throws IOException {
        var broken = corruptApk("corrupt_split.apk");
        var good = apk("good_base.apk", MODERN_ENTRY);
        var result = ModuleUtil.scanModuleApk(info(good, broken));
        try {
            assertTrue("the readable apk is still scanned", result.isModule());
            assertNotNull(result.modernApk);
        } finally {
            result.close();
        }
    }

    @Test
    public void missingApkPathContributesNoMarkers() {
        var info = new ApplicationInfo();
        info.sourceDir = new File(tmp.getRoot(), "does-not-exist.apk").getAbsolutePath();
        var result = ModuleUtil.scanModuleApk(info);
        try {
            assertFalse(result.isModule());
        } finally {
            result.close();
        }
    }

    @Test
    public void nullSourceDirIsSkipped() {
        // PackageManager can hand back an ApplicationInfo with no sourceDir for a partially
        // installed package.
        var result = ModuleUtil.scanModuleApk(info(null));
        try {
            assertFalse(result.isModule());
        } finally {
            result.close();
        }
    }

    // --- Archive ownership -------------------------------------------------

    /**
     * The scan opens one archive per apk but returns at most one. Every other archive has to be
     * closed before the scan returns, or a package with several splits leaks a file handle per
     * reload -- and reloads happen on every package broadcast.
     *
     * <p>Asserted by deleting the files: Windows refuses to delete a file that is still open, so
     * this is a real check on the platform this fork is built on. On kernels that allow it the
     * deletes succeed either way, which makes the test weaker there but never wrong.
     */
    @Test
    public void archivesTheScanDoesNotReturnAreClosed() throws IOException {
        var split1 = apk("split_a.apk", "AndroidManifest.xml");
        var split2 = apk("split_b.apk", LEGACY_ENTRY);
        // Last in scan order, so both splits get opened before the marker is found.
        var base = apk("base_last.apk", MODERN_ENTRY);

        var result = ModuleUtil.scanModuleApk(info(base, split1, split2));
        try {
            assertNotNull(result.modernApk);
            assertTrue("split_a should have been closed", split1.delete());
            assertTrue("split_b should have been closed", split2.delete());
        } finally {
            result.close();
        }
        assertTrue("the returned archive should be closed by close()", base.delete());
    }

    @Test
    public void closeIsSafeWhenNothingWasReturned() throws IOException {
        var result = ModuleUtil.scanModuleApk(info(apk("nothing.apk", "classes.dex")));
        result.close();
        result.close(); // idempotent: callers should not have to track whether they closed already
    }

    // --- extractIntPart ----------------------------------------------------
    //
    // xposedminversion is a free-text manifest value; every module author writes it differently.

    @Test
    public void extractIntPartReadsTheLeadingDigits() {
        assertEquals(93, ModuleUtil.extractIntPart("93"));
        assertEquals(93, ModuleUtil.extractIntPart("93-beta"));
        assertEquals(93, ModuleUtil.extractIntPart("93.2"));
        assertEquals(2, ModuleUtil.extractIntPart("2 (Xposed API 82)"));
    }

    @Test
    public void extractIntPartReturnsZeroWhenThereAreNoLeadingDigits() {
        assertEquals(0, ModuleUtil.extractIntPart(""));
        assertEquals(0, ModuleUtil.extractIntPart("v93"));
        assertEquals(0, ModuleUtil.extractIntPart("api-93"));
    }
}
