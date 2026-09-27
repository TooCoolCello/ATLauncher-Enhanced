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
package com.atlauncher.gui.dialogs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.atlauncher.data.SinytraConnector;
import com.atlauncher.data.curseforge.CurseForgeFile;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;

public class SelectorLoaderFilterTest {
    private static final LoaderVersion NEOFORGE = new LoaderVersion("21.1.77", false, "NeoForge");
    private static final LoaderVersion FABRIC = new LoaderVersion("0.16.9", false, "Fabric");
    private static final LoaderVersion PAPER = new LoaderVersion("1.21.1-130", false, "Paper");
    private static final LoaderVersion PURPUR = new LoaderVersion("1.21.1-2329", false, "Purpur");

    private static boolean accepts(List<String> loaders, LoaderVersion loader, boolean forgeCompatible,
            boolean fabricFallback) {
        return ModrinthVersionSelectorDialog.acceptsLoaders(loaders, loader, forgeCompatible, fabricFallback);
    }

    @Test
    void fabricOnlyVersionOnNeoForgeNeedsTheConnectorFallback() {
        // with restriction NONE this filter used to be skipped, so Fabric versions were offered without Connector
        assertFalse(accepts(Collections.singletonList("fabric"), NEOFORGE, false, false));
        assertTrue(accepts(Collections.singletonList("fabric"), NEOFORGE, false, true));
    }

    @Test
    void nativeAndMixedVersionsAreAcceptedOnNeoForge() {
        assertTrue(accepts(Collections.singletonList("neoforge"), NEOFORGE, false, false));
        assertTrue(accepts(Arrays.asList("fabric", "neoforge"), NEOFORGE, false, false));
        assertTrue(accepts(Collections.singletonList("forge"), NEOFORGE, true, false));
        assertFalse(accepts(Collections.singletonList("forge"), NEOFORGE, false, false));
    }

    @Test
    void otherLoadersKeepTheirRules() {
        assertTrue(accepts(Collections.singletonList("fabric"), FABRIC, false, false));
        assertFalse(accepts(Collections.singletonList("neoforge"), FABRIC, false, false));
        assertTrue(accepts(Collections.singletonList("bukkit"), PAPER, false, false));
        assertFalse(accepts(Collections.singletonList("purpur"), PAPER, false, false));
        assertTrue(accepts(Collections.singletonList("paper"), PURPUR, false, false));
    }

    @Test
    void versionsWithoutLoaderMetadataAreNeverOffered() {
        assertFalse(accepts(null, NEOFORGE, true, true));
        assertFalse(accepts(Collections.emptyList(), NEOFORGE, true, true));
        assertFalse(accepts(null, FABRIC, false, false));
    }

    @Test
    void curseForgeFilesWithoutTagsBecomeUntaggedNotConnectorFiles() {
        CurseForgeFile missing = new CurseForgeFile();
        CurseForgeFile tagged = new CurseForgeFile();
        tagged.gameVersions = new ArrayList<>(Arrays.asList("1.21.1", "Fabric"));
        List<CurseForgeFile> files = new ArrayList<>(Arrays.asList(missing, tagged, null));

        CurseForgeProjectFileSelectorDialog.markMissingTagsAsUntagged(files);

        assertTrue(missing.gameVersions.isEmpty());
        assertEquals(Arrays.asList("1.21.1", "Fabric"), tagged.gameVersions);
        assertFalse(SinytraConnector.isFabricFallbackFile(missing.gameVersions, NEOFORGE, false));
        assertTrue(SinytraConnector.isFabricFallbackFile(tagged.gameVersions, NEOFORGE, false));
    }
}
