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
 * {@code /native/}.
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

        // All decisions are made at configuration time so the tasks stay configuration-cache safe.
        var skipped = project.hasProperty("skipRust") || "true" == System.getenv("AE2_SKIP_RUST")
        var required = project.hasProperty("requireRust") || "true" == System.getenv("AE2_REQUIRE_RUST")
        var hasRustSources = rustDir.asFile.exists()
        var cargo = hasRustSources ? findCargo() : null
        var cargoAvailable = cargo != null

        if (required && !cargoAvailable) {
            throw new GradleException(
                    "Native ae2store build was required, but cargo is unavailable " +
                            "(skipped=${skipped}, sources=${hasRustSources})")
        }

        var nativeBuildRequested = !skipped && cargoAvailable
        if (!nativeBuildRequested) {
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
            into(outputDir)
        }

        project.plugins.withType(org.gradle.api.plugins.JavaPlugin) {
            project.tasks.named("processResources") {
                dependsOn copyNativeLibrary
                from(outputDir) {
                    into RESOURCE_DIR
                }
            }
        }

        // Keep the native sources visible to IDEs by registering them with the main source set.
        project.extensions.configure(SourceSetContainer) { sourceSets ->
            sourceSets.named("main") {
                resources {
                    srcDir rustDir
                    include "ae2store/src/**"
                    include "ae2store-jni/src/**"
                    include "Cargo.toml"
                    include "*/Cargo.toml"
                }
            }
        }
    }

    /**
     * Resolves the cargo executable.
     *
     * Only the filesystem is inspected: starting a process at configuration time is incompatible with
     * Gradle's configuration cache. If the file exists but cannot actually run, {@code cargoBuild}
     * fails with cargo's own error message, which is more useful than a generic "toolchain missing".
     */
    private static String findCargo() {
        var candidates = new ArrayList<String>()
        var path = System.getenv("PATH")
        if (path) {
            for (entry in path.split(File.pathSeparator)) {
                if (entry) {
                    candidates.add(new File(entry, "cargo").absolutePath)
                }
            }
        }
        var home = System.getProperty("user.home", "")
        candidates.add("${home}/.cargo/bin/cargo")
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
        if (name.contains("win")) {
            return "${LIBRARY_NAME}.dll"
        } else if (name.contains("mac") || name.contains("darwin")) {
            return "lib${LIBRARY_NAME}.dylib"
        }
        return "lib${LIBRARY_NAME}.so"
    }
}
