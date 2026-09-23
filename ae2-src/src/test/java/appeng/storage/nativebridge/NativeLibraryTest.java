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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NativeLibraryTest {
    @Test
    void resolvesSupportedPlatformsAndArchitectureAliases() {
        assertThat(NativeLibrary.platformId("Linux", "amd64")).isEqualTo("linux-x86_64");
        assertThat(NativeLibrary.platformId("Windows 11", "x86_64")).isEqualTo("windows-x86_64");
        assertThat(NativeLibrary.platformId("Mac OS X", "aarch64")).isEqualTo("macos-aarch64");
        assertThat(NativeLibrary.platformId("Darwin", "arm64")).isEqualTo("macos-aarch64");
        assertThat(NativeLibrary.platformId("Mac OS X", "x86_64")).isEqualTo("macos-x86_64");
        assertThat(NativeLibrary.bundledResourcePath("Linux", "amd64"))
                .isEqualTo("/native/linux-x86_64/libae2store_jni.so");
        assertThat(NativeLibrary.bundledResourcePath("Windows 11", "x86_64"))
                .isEqualTo("/native/windows-x86_64/ae2store_jni.dll");
        assertThat(NativeLibrary.bundledResourcePath("Mac OS X", "aarch64"))
                .isEqualTo("/native/macos-aarch64/libae2store_jni.dylib");
        assertThat(NativeLibrary.bundledResourcePath("Darwin", "x86_64"))
                .isEqualTo("/native/macos-x86_64/libae2store_jni.dylib");
    }

    @Test
    void unknownPlatformHasNoBundledResource() {
        assertThat(NativeLibrary.platformId("Linux", "riscv64")).isNull();
        assertThat(NativeLibrary.platformId("Plan 9", "amd64")).isNull();
        assertThat(NativeLibrary.bundledResourcePath("Linux", "riscv64")).isNull();
    }
}
