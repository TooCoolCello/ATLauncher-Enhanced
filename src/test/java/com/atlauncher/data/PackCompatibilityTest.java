/*
 * ATLauncher - https://github.com/ATLauncher/ATLauncher
 * Copyright (C) 2026 ATLauncher
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.atlauncher.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.atlauncher.Gsons;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.ftb.FTBPackVersion;
import com.atlauncher.data.ftb.FTBPackVersionSpecs;

public class PackCompatibilityTest {
    private static CurseForgeProject curseForge(String indexes) {
        return Gsons.DEFAULT.fromJson("{\"latestFilesIndexes\":[" + indexes + "]}", CurseForgeProject.class);
    }

    private static FTBPackVersion ftbVersion(Integer minimumMb) {
        FTBPackVersion version = new FTBPackVersion();
        if (minimumMb != null) {
            version.specs = new FTBPackVersionSpecs();
            version.specs.minimum = minimumMb;
        }
        return version;
    }

    @Test
    void curseForgeFlagsOnlyPacksWhoseEveryFileUsesAnUnsupportedLoader() {
        // LiteLoader (3) and Cauldron (2) only
        assertNotNull(PackCompatibility.curseForge(
            curseForge("{\"gameVersion\":\"1.7.10\",\"modLoader\":3},{\"gameVersion\":\"1.7.10\",\"modLoader\":2}")));

        // one supported loader is enough
        assertNull(PackCompatibility.curseForge(
            curseForge("{\"gameVersion\":\"1.7.10\",\"modLoader\":3},{\"gameVersion\":\"1.7.10\",\"modLoader\":1}")));
        assertNull(PackCompatibility.curseForge(curseForge("{\"gameVersion\":\"1.21.1\",\"modLoader\":6}")));

        // a file with no stated loader might be fine, so nothing is proven
        assertNull(PackCompatibility.curseForge(
            curseForge("{\"gameVersion\":\"1.7.10\",\"modLoader\":3},{\"gameVersion\":\"1.7.10\"}")));

        // no data, no verdict
        assertNull(PackCompatibility.curseForge(curseForge("")));
        assertNull(PackCompatibility.curseForge(new CurseForgeProject()));
        assertNull(PackCompatibility.curseForge(null));
    }

    @Test
    void ftbFlagsPacksWhoseEveryVersionNeedsMoreMemoryThanTheComputerHas() {
        assertEquals("Needs at least 6144 MB of memory, but this computer has 4096 MB.",
            PackCompatibility.ftb(Arrays.asList(ftbVersion(8192), ftbVersion(6144)), 4096));

        // one version that fits keeps the pack
        assertNull(PackCompatibility.ftb(Arrays.asList(ftbVersion(8192), ftbVersion(4096)), 4096));
        assertNull(PackCompatibility.ftb(Collections.singletonList(ftbVersion(4096)), 16384));

        // a version without a stated minimum, or unknown RAM, proves nothing
        assertNull(PackCompatibility.ftb(Arrays.asList(ftbVersion(8192), ftbVersion(null)), 4096));
        assertNull(PackCompatibility.ftb(Arrays.asList(ftbVersion(8192), ftbVersion(0)), 4096));
        assertNull(PackCompatibility.ftb(Collections.singletonList(ftbVersion(8192)), null));
        assertNull(PackCompatibility.ftb(Collections.singletonList(ftbVersion(8192)), 0));
    }

    @Test
    void ftbFlagsPacksWithNothingToInstall() {
        assertNotNull(PackCompatibility.ftb(new ArrayList<>(), 16384));
        assertNotNull(PackCompatibility.ftb(null, null));
    }

    @Test
    void unifiedFlagsPlatformsTheInstallerCantHandleAndUnavailableATLauncherPacks() {
        for (String platform : Arrays.asList("CURSEFORGE", "FTB", "MODRINTH", "TECHNIC")) {
            assertNull(PackCompatibility.unified(platform, false), platform);
        }
        assertNull(PackCompatibility.unified("ATLAUNCHER", true));

        assertNotNull(PackCompatibility.unified("ATLAUNCHER", false));
        // Apollo's name for a platform added to the API after this launcher was built
        assertNotNull(PackCompatibility.unified("$UNKNOWN", true));
        assertNotNull(PackCompatibility.unified(null, true));
    }
}
