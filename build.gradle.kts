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
 * Copyright (C) 2021 - 2022 LSPosed Contributors
 */

import com.android.build.api.dsl.ApplicationDefaultConfig
import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.api.AndroidBasePlugin

plugins {
    alias(libs.plugins.lsplugin.cmaker)
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.nav.safeargs) apply false
}

cmaker {
    default {
        arguments.addAll(
            arrayOf(
                "-DEXTERNAL_ROOT=${File(rootDir.absolutePath, "external")}",
                "-DCORE_ROOT=${File(rootDir.absolutePath, "core/src/main/jni")}",
                "-DANDROID_STL=none"
            )
        )
        val flags = arrayOf(
            "-DINJECTED_AID=$injectedPackageUid",
            "-Wno-gnu-string-literal-operator-template",
            "-Wno-c++2b-extensions",
        )
        cFlags.addAll(flags)
        cppFlags.addAll(flags)
        abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    }
    buildTypes {
        if (it.name == "release") {
            arguments += "-DDEBUG_SYMBOLS_PATH=${
                layout.buildDirectory.dir("symbols").get().asFile.absolutePath
            }"
        }
    }
}

// Pinned to match upstream LSPosed 1.9.2 instead of being derived from the git
// history. Upstream computes these from the commit count on origin/master
// (2824 commits at tag v1.9.2, + the 4200 offset = 7024), which makes the
// version depend on clone depth and on which branch happens to be fetched — a
// shallow clone silently produces a wrong, much lower code. Hardcoding keeps
// this fork's builds reporting the same version as the official 1.9.2 release.
val verCodePinned = 7024
val verNamePinned = "1.9.2"

val injectedPackageName by extra("com.android.shell")
val injectedPackageUid by extra(2000)

val defaultManagerPackageName by extra("org.lsposed.manager")
val verCode by extra(verCodePinned)
val verName by extra(verNamePinned)
val androidTargetSdkVersion by extra(34)
val androidMinSdkVersion by extra(27)
val androidBuildToolsVersion by extra("34.0.0")
val androidCompileSdkVersion by extra(34)
val androidCompileNdkVersion by extra("26.1.10909125")
val androidSourceCompatibility by extra(JavaVersion.VERSION_17)
val androidTargetCompatibility by extra(JavaVersion.VERSION_17)

tasks.register("Delete", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

subprojects {
    plugins.withType(AndroidBasePlugin::class.java) {
        extensions.configure(CommonExtension::class.java) {
            compileSdk = androidCompileSdkVersion
            ndkVersion = androidCompileNdkVersion
            buildToolsVersion = androidBuildToolsVersion

            externalNativeBuild {
                cmake {
                    version = "3.22.1+"
                }
            }

            defaultConfig {
                minSdk = androidMinSdkVersion
                if (this is ApplicationDefaultConfig) {
                    targetSdk = androidTargetSdkVersion
                    versionCode = verCode
                    versionName = verName
                }
            }

            lint {
                abortOnError = true
                checkReleaseBuilds = false
            }

            // JVM unit tests run against a stubbed android.jar whose methods throw by default.
            // Returning defaults instead lets a test construct a plain ApplicationInfo and set its
            // fields, which is all the module-recognition code actually reads. Configured here
            // rather than per-module so a new module's tests do not fail for a reason that has
            // nothing to do with the code under test.
            testOptions {
                unitTests.isReturnDefaultValues = true
            }

            compileOptions {
                sourceCompatibility = androidSourceCompatibility
                targetCompatibility = androidTargetCompatibility
            }
        }
    }
    plugins.withType(JavaPlugin::class.java) {
        extensions.configure(JavaPluginExtension::class.java) {
            sourceCompatibility = androidSourceCompatibility
            targetCompatibility = androidTargetCompatibility
        }
    }
}
