/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2025, TeamAppliedEnergistics, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appengbuild

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer

import java.util.ArrayList
import java.util.Locale

/**
 * Builds the optional {@code ae2store} Rust library and bundles it into the mod jar under
 * {@code /native/<os>-<arch>/}. CI can supply a directory of prebuilt libraries with
 * {@code -PnativeArtifactsDir=...} to assemble a multi-platform jar.
 *
 * The build is optional: without a Rust toolchain the tasks are skipped and the mod runs with the
 * pure-Java storage implementation. Use {@code -PskipRust=true} (or {@code AE2_SKIP_RUST=true}) to
 * skip it explicitly, and {@code -PrequireRust=true} (or {@code AE2_REQUIRE_RUST=true}) to make a
 * missing toolchain a build failure.
 */
class RustNativePlugin implements Plugin<Project> {
    static final String LIBRARY_NAME = "ae2store_jni"
    static final String RESOURCE_DIR = "native"

    @Override
    void apply(Project project) {
        var rustDir = project.layout.projectDirectory.dir("rust")
        var outputDir = project.layout.buildDirectory.dir("native")
        var stagedPath = project.findProperty("nativeArtifactsDir")
        var stagedDir = stagedPath ? project.file(stagedPath.toString()) : null
        if (stagedDir != null && !stagedDir.isDirectory()) {
            throw new GradleException("Native artifacts directory does not exist: ${stagedDir}")
        }

        // All decisions are made at configuration time so the tasks stay configuration-cache safe.
        var skipped = project.hasProperty("skipRust") || "true" == System.getenv("AE2_SKIP_RUST")
        var required = project.hasProperty("requireRust") || "true" == System.getenv("AE2_REQUIRE_RUST")
        var hasRustSources = rustDir.asFile.exists()
        var cargo = hasRustSources ? findCargo() : null
        var cargoAvailable = cargo != null

        var platform = platformId()
        if (required && stagedDir == null && (!cargoAvailable || platform == null)) {
            throw new GradleException(
                    "Native ae2store build was required, but cargo is unavailable " +
                            "(skipped=${skipped}, sources=${hasRustSources})")
        }

        var nativeBuildRequested = stagedDir == null && !skipped && cargoAvailable && platform != null
        if (!nativeBuildRequested && stagedDir == null) {
            project.logger.lifecycle(
                    "Native ae2store acceleration will not be built " +
                            "(cargo: ${cargo}, skipped: ${skipped}); " +
                            "AE2 falls back to its Java storage implementation")
        }

        var cargoBuild = project.tasks.register("cargoBuild", Exec) {
            group = "build"
            description = "Builds the native ae2store library with cargo."
            onlyIf { nativeBuildRequested }
            workingDir = rustDir.asFile
            // Use the absolute path: Gradle daemons can have a narrower PATH than a login shell.
            commandLine cargo, "build", "--release"
            inputs.dir(rustDir.dir("ae2store/src"))
            inputs.dir(rustDir.dir("ae2store-jni/src"))
            inputs.file(rustDir.file("Cargo.toml"))
            inputs.file(rustDir.file("ae2store/Cargo.toml"))
            inputs.file(rustDir.file("ae2store-jni/Cargo.toml"))
            outputs.dir(rustDir.dir("target/release"))
            outputs.cacheIf { false }
        }

        var copyNativeLibrary = project.tasks.register("copyNativeLibrary", Copy) {
            group = "build"
            description = "Copies the built native ae2store library into the mod resources."
            dependsOn cargoBuild
            onlyIf { nativeBuildRequested }
            from(rustDir.dir("target/release").file(libraryFileName()))
            into(outputDir.map { it.dir(platform) })
        }

        project.plugins.withType(org.gradle.api.plugins.JavaPlugin) {
            project.tasks.named("processResources") {
                if (stagedDir == null) {
                    dependsOn copyNativeLibrary
                }
                from(stagedDir ?: outputDir) {
                    into RESOURCE_DIR
                }
            }
        }
    }

    private static String findCargo() {
        var candidates = new ArrayList<String>()
        var path = System.getenv("PATH")
        if (path) {
            for (entry in path.split(File.pathSeparator)) {
                if (entry) {
                    candidates.add(new File(entry, isWindows() ? "cargo.exe" : "cargo").absolutePath)
                }
            }
        }
        var home = System.getProperty("user.home", "")
        candidates.add("${home}/.cargo/bin/${isWindows() ? 'cargo.exe' : 'cargo'}")
        candidates.add("/usr/local/bin/cargo")
        candidates.add("/usr/bin/cargo")
        candidates.add("/sbin/cargo")

        for (candidate in candidates) {
            var file = new File(candidate)
            if (file.isFile() && file.canExecute()) {
                return file.absolutePath
            }
        }
        return null
    }

    private static String libraryFileName() {
        var name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
        if (name.startsWith("windows")) {
            return "${LIBRARY_NAME}.dll"
        } else if (name.contains("mac") || name.contains("darwin")) {
            return "lib${LIBRARY_NAME}.dylib"
        }
        return "lib${LIBRARY_NAME}.so"
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
    }

    private static String platformId() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
        var arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)
        var normalizedArch
        if (arch == "amd64" || arch == "x86_64") {
            normalizedArch = "x86_64"
        } else if (arch == "aarch64" || arch == "arm64") {
            normalizedArch = "aarch64"
        } else {
            return null
        }
        if (os.startsWith("windows")) {
            return "windows-${normalizedArch}"
        } else if (os.contains("mac") || os.contains("darwin")) {
            return "macos-${normalizedArch}"
        } else if (os.contains("linux")) {
            return "linux-${normalizedArch}"
        }
        return null
    }
}
