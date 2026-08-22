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
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.manager.util;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;

import org.lsposed.lspd.models.UserInfo;
import org.lsposed.manager.App;
import org.lsposed.manager.ConfigManager;
import org.lsposed.manager.repo.RepoLoader;
import org.lsposed.manager.repo.model.OnlineModule;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

public final class ModuleUtil {
    // xposedminversion below this
    public static int MIN_MODULE_VERSION = 2; // reject modules with
    private static ModuleUtil instance = null;
    private final PackageManager pm;
    private final Set<ModuleListener> listeners = ConcurrentHashMap.newKeySet();
    private final Object moduleLock = new Object();
    private Set<String> enabledModules = Collections.emptySet();
    private List<UserInfo> users = Collections.emptyList();
    private Map<Pair<String, Integer>, InstalledModule> installedModules = Collections.emptyMap();
    private boolean modulesLoaded = false;
    private int reloadAttempt = 0;

    private static final int MAX_RELOAD_ATTEMPTS = 5;

    static final int MATCH_ANY_USER = 0x00400000; // PackageManager.MATCH_ANY_USER

    static final int MATCH_ALL_FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE | PackageManager.MATCH_UNINSTALLED_PACKAGES | MATCH_ANY_USER;

    private ModuleUtil() {
        pm = App.getInstance().getPackageManager();
    }

    public boolean isModulesLoaded() {
        synchronized (moduleLock) {
            return modulesLoaded;
        }
    }

    public static synchronized ModuleUtil getInstance() {
        if (instance == null) {
            Log.i(App.TAG, "ModuleUtil first use, scheduling initial load");
            instance = new ModuleUtil();
            App.getExecutorService().submit(instance::reloadInstalledModules);
        }
        return instance;
    }

    public static synchronized void reloadIfInitialized() {
        if (instance != null) {
            App.getExecutorService().submit(instance::reloadInstalledModules);
        }
    }

    public static int extractIntPart(String str) {
        int result = 0, length = str.length();
        for (int offset = 0; offset < length; offset++) {
            char c = str.charAt(offset);
            if ('0' <= c && c <= '9')
                result = result * 10 + (c - '0');
            else
                break;
        }
        return result;
    }

    /** Where a modern module lists its entry classes. This is the only modern marker. */
    private static final String MODERN_ENTRY = "META-INF/xposed/java_init.list";
    /** Where a traditional module lists its entry class. Recognises a module, but not a modern one. */
    private static final String LEGACY_ENTRY = "assets/xposed_init";

    /**
     * What a package's APKs say about it, which is two independent questions: whether it is a
     * module at all, and which metadata layout to read it with.
     * <p>
     * Conflating the two is what hid module descriptions and recommended apps: nearly every
     * traditional module ships {@link #LEGACY_ENTRY}, so treating that as a modern marker made
     * the manager look for its description and scope under META-INF/xposed, find nothing, and
     * cache the nothing.
     */
    public static final class ModuleApk {
        /**
         * Open handle to the APK carrying {@link #MODERN_ENTRY}, or null when this is not a
         * modern module. Ownership passes to the receiver, which must close it.
         */
        @Nullable
        public final ZipFile modernApk;
        /** Whether any APK carries {@link #LEGACY_ENTRY}. */
        public final boolean legacyEntry;

        private ModuleApk(@Nullable ZipFile modernApk, boolean legacyEntry) {
            this.modernApk = modernApk;
            this.legacyEntry = legacyEntry;
        }

        /** Whether the packaging alone identifies this package as a module. */
        public boolean isModule() {
            return modernApk != null || legacyEntry;
        }

        /** Releases {@link #modernApk} when no {@link InstalledModule} took it over. */
        public void close() {
            if (modernApk == null) return;
            try {
                modernApk.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String[] collectApks(ApplicationInfo info) {
        if (info.splitSourceDirs == null) return new String[]{info.sourceDir};
        var apks = Arrays.copyOf(info.splitSourceDirs, info.splitSourceDirs.length + 1);
        apks[info.splitSourceDirs.length] = info.sourceDir;
        return apks;
    }

    /**
     * Scans a package's APKs -- base plus splits -- for packaging markers. Closes every archive
     * it opens except the one it hands back, so scanning a split package no longer leaks one
     * ZipFile per APK it had to look inside.
     */
    public static ModuleApk scanModuleApk(ApplicationInfo info) {
        boolean legacyEntry = false;
        for (var apk : collectApks(info)) {
            if (apk == null) continue;
            ZipFile zip = null;
            try {
                zip = new ZipFile(apk);
                if (zip.getEntry(MODERN_ENTRY) != null) {
                    var modern = zip;
                    zip = null; // ownership passes to the returned ModuleApk
                    return new ModuleApk(modern, legacyEntry);
                }
                if (zip.getEntry(LEGACY_ENTRY) != null) legacyEntry = true;
            } catch (IOException | SecurityException | IllegalStateException ignored) {
                // Unreadable or corrupt APK: it contributes no markers.
            } finally {
                if (zip != null) {
                    try {
                        zip.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return new ModuleApk(null, legacyEntry);
    }

    public static boolean isLegacyModule(ApplicationInfo info) {
        return info.metaData != null && info.metaData.containsKey("xposedminversion");
    }

    /**
     * Wrapper around {@link #doReloadInstalledModules()} that reports failures.
     * Reloads run through {@code ExecutorService.submit()}, which stores a thrown
     * exception in the Future and never prints it -- so any failure in here used to
     * leave an empty module list with no trace of why anywhere in the log.
     */
    public void reloadInstalledModules() {
        try {
            doReloadInstalledModules();
        } catch (Throwable t) {
            Log.e(App.TAG, "Module reload threw", t);
        }
    }

    private synchronized void doReloadInstalledModules() {
        // These outcomes are logged at INFO on purpose: release builds strip Log.d
        // via -assumenosideeffects, so a debug-level message here is invisible in
        // exactly the builds where an empty module list needs diagnosing.
        Log.i(App.TAG, "Module reload starting");
        if (!ConfigManager.isBinderAlive()) {
            Log.i(App.TAG, "Module reload deferred: manager service is not ready");
            retryReload();
            return;
        }

        var state = ConfigManager.getModuleState(PackageManager.GET_META_DATA | MATCH_ALL_FLAGS);
        if (state == null) {
            Log.w(App.TAG, "Module reload failed: retaining previous module state");
            retryReload();
            return;
        }

        Map<Pair<String, Integer>, InstalledModule> modules = new HashMap<>();
        for (PackageInfo pkg : state.packages) {
            ApplicationInfo app = pkg.applicationInfo;

            var apkInfo = scanModuleApk(app);
            var key = Pair.create(pkg.packageName, app.uid / App.PER_USER_RANGE);
            // Only InstalledModule closes the handle, so anything that does not reach the
            // constructor -- a non-module, or a duplicate key -- has to close it here.
            if ((apkInfo.isModule() || isLegacyModule(app)) && !modules.containsKey(key)) {
                modules.put(key, new InstalledModule(pkg, apkInfo));
            } else {
                apkInfo.close();
            }
        }

        synchronized (moduleLock) {
            installedModules = Collections.unmodifiableMap(modules);
            users = Collections.unmodifiableList(new ArrayList<>(state.users));
            enabledModules = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(state.enabledModules)));
            modulesLoaded = true;
            reloadAttempt = 0;
        }
        Log.i(App.TAG, "Module reload completed: " + modules.size() + " modules");
        listeners.forEach(ModuleListener::onModulesReloaded);
    }

    /**
     * A reload that gives up silently leaves the list empty until something else
     * happens to trigger another one, which is why the module list would sometimes
     * never appear. Nothing retries on our behalf: the binder-ready callback fires
     * once, and package events only arrive if a package actually changes.
     */
    private void retryReload() {
        int attempt;
        synchronized (moduleLock) {
            if (modulesLoaded || reloadAttempt >= MAX_RELOAD_ATTEMPTS) return;
            attempt = ++reloadAttempt;
        }
        long delay = 500L * attempt;
        Log.i(App.TAG, "Retrying module reload in " + delay + "ms (attempt " + attempt + ")");
        App.getExecutorService().submit(() -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            reloadInstalledModules();
        });
    }

    @Nullable
    public List<UserInfo> getUsers() {
        synchronized (moduleLock) {
            return modulesLoaded ? users : null;
        }
    }

    public InstalledModule reloadSingleModule(String packageName, int userId) {
        return reloadSingleModule(packageName, userId, false);
    }

    public synchronized InstalledModule reloadSingleModule(String packageName, int userId, boolean packageFullyRemoved) {
        if (!ConfigManager.isBinderAlive()) {
            Log.d(App.TAG, "Single module reload deferred: manager service is not ready");
            return null;
        }

        PackageInfo pkg;
        try {
            pkg = ConfigManager.getPackageInfoStrict(packageName, PackageManager.GET_META_DATA, userId);
        } catch (RemoteException e) {
            Log.w(App.TAG, "Single module reload failed: retaining previous module state", e);
            return null;
        } catch (NameNotFoundException e) {
            InstalledModule old;
            boolean enabledChanged;
            synchronized (moduleLock) {
                old = installedModules.get(Pair.create(packageName, userId));
                enabledChanged = packageFullyRemoved && enabledModules.contains(packageName);
                if (old != null) {
                    var modules = new HashMap<>(installedModules);
                    modules.remove(Pair.create(packageName, userId));
                    installedModules = Collections.unmodifiableMap(modules);
                }
                if (enabledChanged) {
                    var enabled = new HashSet<>(enabledModules);
                    enabled.remove(packageName);
                    enabledModules = Collections.unmodifiableSet(enabled);
                }
            }
            if (enabledChanged) listeners.forEach(ModuleListener::onModulesReloaded);
            if (old != null) listeners.forEach(i -> i.onSingleModuleReloaded(old));
            return null;
        }

        ApplicationInfo app = pkg.applicationInfo;
        var apkInfo = scanModuleApk(app);
        InstalledModule module;
        if (apkInfo.isModule() || isLegacyModule(app)) {
            module = new InstalledModule(pkg, apkInfo);
        } else {
            apkInfo.close();
            module = null;
        }
        InstalledModule old;
        boolean enabledChanged;
        synchronized (moduleLock) {
            old = installedModules.get(Pair.create(packageName, userId));
            enabledChanged = packageFullyRemoved && enabledModules.contains(packageName);
            var modules = new HashMap<>(installedModules);
            if (module != null) {
                modules.put(Pair.create(packageName, userId), module);
            } else {
                modules.remove(Pair.create(packageName, userId));
            }
            installedModules = Collections.unmodifiableMap(modules);
            if (enabledChanged) {
                var enabled = new HashSet<>(enabledModules);
                enabled.remove(packageName);
                enabledModules = Collections.unmodifiableSet(enabled);
            }
        }
        if (enabledChanged) listeners.forEach(ModuleListener::onModulesReloaded);
        if (module != null) {
            listeners.forEach(i -> i.onSingleModuleReloaded(module));
            return module;
        }
        if (old != null) listeners.forEach(i -> i.onSingleModuleReloaded(old));
        return null;
    }

    @Nullable
    public InstalledModule getModule(String packageName, int userId) {
        synchronized (moduleLock) {
            return modulesLoaded ? installedModules.get(Pair.create(packageName, userId)) : null;
        }
    }

    @Nullable
    public InstalledModule getModule(String packageName) {
        return getModule(packageName, 0);
    }

    @Nullable
    public Map<Pair<String, Integer>, InstalledModule> getModules() {
        synchronized (moduleLock) {
            return modulesLoaded ? installedModules : null;
        }
    }

    public boolean setModuleEnabled(String packageName, boolean enabled) {
        if (!ConfigManager.setModuleEnabled(packageName, enabled)) {
            return false;
        }
        // ConfigManager.setModuleEnabled() returned true, so the daemon has committed both the
        // enabled flag and -- when enabling -- the module's own scope row. Nothing to add here.
        synchronized (moduleLock) {
            var updatedEnabledModules = new HashSet<>(enabledModules);
            if (enabled) {
                updatedEnabledModules.add(packageName);
            } else {
                updatedEnabledModules.remove(packageName);
            }
            enabledModules = Collections.unmodifiableSet(updatedEnabledModules);
        }
        return true;
    }

    public boolean isModuleEnabled(String packageName) {
        synchronized (moduleLock) {
            return enabledModules.contains(packageName);
        }
    }

    public int getEnabledModulesCount() {
        synchronized (moduleLock) {
            return modulesLoaded ? enabledModules.size() : -1;
        }
    }

    public void addListener(ModuleListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ModuleListener listener) {
        listeners.remove(listener);
    }

    public interface ModuleListener {
        /**
         * Called whenever one (previously or now) installed module has been
         * reloaded
         */
        default void onSingleModuleReloaded(InstalledModule module) {

        }

        default void onModulesReloaded() {

        }
    }

    public class InstalledModule {
        //private static final int FLAG_FORWARD_LOCK = 1 << 29;
        public final int userId;
        public final String packageName;
        public final String versionName;
        public final long versionCode;
        public final boolean legacy;
        public final int minVersion;
        public final int targetVersion;
        public final boolean staticScope;
        public final long installTime;
        public final long updateTime;
        public final ApplicationInfo app;
        public final PackageInfo pkg;
        private String appName; // loaded lazily
        private String description; // loaded lazily
        private List<String> scopeList; // loaded lazily
        /** Scope shipped inside the APK, or null when the APK ships none. */
        private final List<String> packagedScopeList;

        private InstalledModule(PackageInfo pkg, ModuleApk apkInfo) {
            app = pkg.applicationInfo;
            this.pkg = pkg;
            userId = pkg.applicationInfo.uid / App.PER_USER_RANGE;
            packageName = pkg.packageName;
            versionName = pkg.versionName;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                versionCode = pkg.versionCode;
            } else {
                versionCode = pkg.getLongVersionCode();
            }
            installTime = pkg.firstInstallTime;
            updateTime = pkg.lastUpdateTime;
            legacy = apkInfo.modernApk == null;

            if (legacy) {
                // A module recognised only by assets/xposed_init has no Manifest metadata at
                // all, so this cannot assume app.metaData is present.
                Object minVersionRaw = app.metaData == null ? null : app.metaData.get("xposedminversion");
                if (minVersionRaw instanceof Integer) {
                    minVersion = (Integer) minVersionRaw;
                } else if (minVersionRaw instanceof String) {
                    minVersion = extractIntPart((String) minVersionRaw);
                } else {
                    minVersion = 0;
                }
                targetVersion = minVersion; // legacy modules don't have a target version
                staticScope = false;
                packagedScopeList = null;
            } else {
                int minVersion = 100;
                int targetVersion = 100;
                boolean staticScope = false;
                List<String> packagedScope = null;
                try (var modernModuleApk = apkInfo.modernApk) {
                    var propEntry = modernModuleApk.getEntry("META-INF/xposed/module.prop");
                    if (propEntry != null) {
                        var prop = new Properties();
                        prop.load(modernModuleApk.getInputStream(propEntry));
                        minVersion = extractIntPart(prop.getProperty("minApiVersion"));
                        targetVersion = extractIntPart(prop.getProperty("targetApiVersion"));
                        staticScope = TextUtils.equals(prop.getProperty("staticScope"), "true");
                    }
                    var scopeEntry = modernModuleApk.getEntry("META-INF/xposed/scope.list");
                    if (scopeEntry != null) {
                        try (var reader = new BufferedReader(new InputStreamReader(modernModuleApk.getInputStream(scopeEntry)))) {
                            packagedScope = reader.lines().collect(Collectors.toList());
                        }
                    }
                    // No scope.list stays null rather than an empty list: an empty list would
                    // be cached as the final answer and shadow the repository fallback.
                } catch (IOException | OutOfMemoryError e) {
                    Log.e(App.TAG, "Error while reading modern module APK", e);
                }
                this.minVersion = minVersion;
                this.targetVersion = targetVersion;
                this.staticScope = staticScope;
                packagedScopeList = packagedScope;
            }
        }

        public boolean isInstalledOnExternalStorage() {
            return (app.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
        }

        public String getAppName() {
            if (appName == null)
                appName = app.loadLabel(pm).toString();
            return appName;
        }

        /**
         * Description fallback chain: packaged metadata first, then the online repository.
         * An empty result is never cached, so a description that only the repository knows
         * still shows up once the repository has loaded.
         */
        public String getDescription() {
            if (this.description != null) return this.description;
            String descriptionTmp = "";
            if (legacy) {
                Object descriptionRaw = app.metaData == null ? null : app.metaData.get("xposeddescription");
                if (descriptionRaw instanceof String) {
                    descriptionTmp = ((String) descriptionRaw).trim();
                } else if (descriptionRaw instanceof Integer) {
                    try {
                        int resId = (Integer) descriptionRaw;
                        if (resId != 0)
                            descriptionTmp = pm.getResourcesForApplication(app).getString(resId).trim();
                    } catch (Exception ignored) {
                    }
                }
            } else {
                var des = app.loadDescription(pm);
                if (des != null) descriptionTmp = des.toString().trim();
            }
            if (descriptionTmp.isEmpty()) {
                // loadDescription() is empty for most modules, and an asset-only legacy module
                // has no Manifest metadata at all; the repository is the last source.
                OnlineModule online = RepoLoader.getInstance().getOnlineModule(packageName);
                // getSummary() is the description text -- getDescription() is the display title.
                if (online != null && online.getSummary() != null) {
                    descriptionTmp = online.getSummary().trim();
                }
            }
            if (descriptionTmp.isEmpty()) {
                // Don't cache: the repository may not have loaded yet.
                return "";
            }
            this.description = descriptionTmp;
            return this.description;
        }

        /**
         * Recommended-scope fallback chain: packaged {@code META-INF/xposed/scope.list},
         * then legacy {@code xposedscope} Manifest metadata, then the online repository.
         * An empty result is never cached, for the same reason as {@link #getDescription()}.
         */
        public List<String> getScopeList() {
            if (scopeList != null) return scopeList;

            if (packagedScopeList != null) {
                // A modern module ships its scope verbatim; the historical name swap below
                // applies to legacy conventions only.
                scopeList = packagedScopeList;
                return scopeList;
            }

            List<String> list = null;
            try {
                if (app.metaData != null) {
                    int scopeListResourceId = app.metaData.getInt("xposedscope");
                    if (scopeListResourceId != 0) {
                        list = Arrays.asList(pm.getResourcesForApplication(app).getStringArray(scopeListResourceId));
                    } else {
                        String scopeListString = app.metaData.getString("xposedscope");
                        if (scopeListString != null)
                            list = Arrays.asList(scopeListString.split(";"));
                    }
                }
            } catch (Exception ignored) {
            }
            if (list == null) {
                OnlineModule module = RepoLoader.getInstance().getOnlineModule(packageName);
                if (module != null && module.getScope() != null) {
                    list = module.getScope();
                }
            }
            if (list == null || list.isEmpty()) {
                // Don't cache: packaged metadata is absent, so the repository is the only
                // remaining source and it may not have loaded yet.
                return null;
            }
            // Copy before rewriting: Arrays.asList() writes through to the resource array and
            // the repository list is shared with RepoLoader's cached model.
            var normalized = new ArrayList<>(list);
            //For historical reasons, legacy modules use the opposite name.
            //https://github.com/rovo89/XposedBridge/commit/6b49688c929a7768f3113b4c65b429c7a7032afa
            normalized.replaceAll(s ->
                switch (s) {
                    case "android" -> "system";
                    case "system" -> "android";
                    default -> s;
                }
            );
            scopeList = normalized;
            return scopeList;
        }

        public PackageInfo getPackageInfo() {
            return pkg;
        }

        @NonNull
        @Override
        public String toString() {
            return getAppName();
        }
    }
}
