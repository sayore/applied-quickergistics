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

package appeng.storage.nativebridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

/**
 * Loads the optional {@code ae2store} native library.
 * <p>
 * Resolution order:
 * <ol>
 * <li>an explicit path from the {@code ae2.native.library} system property,</li>
 * <li>the library bundled in the mod jar (extracted to a temporary file and loaded),</li>
 * <li>a development build in {@code rust/target/release} relative to the working directory, so a plain
 * {@code cargo build --release} is enough while developing,</li>
 * <li>the ordinary {@link System#loadLibrary(String)} search path.</li>
 * </ol>
 * Loading is best-effort: if none of these succeed, {@link #isAvailable()} is false and callers are expected to fall
 * back to the pure-Java implementation.
 */
public final class NativeLibrary {
    public static final String LIBRARY_NAME = "ae2store_jni";
    public static final String EXPLICIT_PATH_PROPERTY = "ae2.native.library";

    private static final boolean AVAILABLE;
    private static final String STATUS;
    private static final String LOADED_FROM;

    static {
        String loadedFrom = null;
        String failure = null;
        try {
            loadedFrom = load();
        } catch (Throwable t) {
            failure = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOADED_FROM = loadedFrom;
        AVAILABLE = loadedFrom != null;
        STATUS = AVAILABLE ? "loaded from " + loadedFrom : "not available (" + failure + ")";
    }

    private NativeLibrary() {
    }

    /**
     * @return whether the native library could be loaded in this JVM.
     */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * @return a human-readable description of the load attempt, for diagnostics.
     */
    public static String getStatus() {
        return STATUS;
    }

    /**
     * @return where the library was loaded from, or {@code null} if it was not loaded.
     */
    @Nullable
    public static String getLoadedFrom() {
        return LOADED_FROM;
    }

    /**
     * @return the platform-specific file name of the native library.
     */
    public static String platformLibraryName() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return LIBRARY_NAME + ".dll";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "lib" + LIBRARY_NAME + ".dylib";
        }
        return "lib" + LIBRARY_NAME + ".so";
    }

    /**
     * @return the resource path the native library is bundled under inside the mod jar.
     */
    public static String bundledResourcePath() {
        return "/native/" + platformLibraryName();
    }

    @Nullable
    private static String load() throws IOException {
        var explicit = System.getProperty(EXPLICIT_PATH_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            var path = Path.of(explicit).toAbsolutePath();
            if (!Files.isRegularFile(path)) {
                throw new IOException(EXPLICIT_PATH_PROPERTY + " points at a missing file: " + path);
            }
            System.load(path.toString());
            return path.toString();
        }

        var bundled = bundledResourcePath();
        try (InputStream in = NativeLibrary.class.getResourceAsStream(bundled)) {
            if (in != null) {
                var path = extractToTempFile(in);
                System.load(path.toString());
                return "mod jar (" + bundled + ")";
            }
        }

        var devBuild = findDevelopmentBuild();
        if (devBuild != null) {
            System.load(devBuild.toString());
            return devBuild.toString();
        }

        try {
            System.loadLibrary(LIBRARY_NAME);
            return "java.library.path";
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("no bundled native library and java.library.path lookup failed");
        }
    }

    private static Path extractToTempFile(InputStream in) throws IOException {
        var dir = Files.createTempDirectory("ae2store-native");
        var target = dir.resolve(platformLibraryName());
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        target.toFile().deleteOnExit();
        dir.toFile().deleteOnExit();
        return target;
    }

    /**
     * Looks for a development build relative to the working directory.
     */
    @Nullable
    private static Path findDevelopmentBuild() {
        var name = platformLibraryName();
        var candidates = new String[] {
                "rust/target/release/" + name,
                "../rust/target/release/" + name,
                "../../rust/target/release/" + name,
                "build/native/" + name
        };
        for (var candidate : candidates) {
            var path = Path.of(candidate).toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }
}
