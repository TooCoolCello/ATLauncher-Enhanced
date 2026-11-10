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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.atlauncher.Gsons;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.minecraft.loaders.LoaderType;
import com.atlauncher.data.modrinth.ModrinthSearchHit;

public class LoaderIndicatorsTest {
    private static CurseForgeProject curseForge(String json) {
        return Gsons.DEFAULT.fromJson(json, CurseForgeProject.class);
    }

    private static ModrinthSearchHit modrinth(String json) {
        return Gsons.DEFAULT.fromJson(json, ModrinthSearchHit.class);
    }

    @Test
    void curseForgeMapsKnownLoaderIdsInDisplayOrderWithoutDuplicates() {
        CurseForgeProject project = curseForge("{\"latestFilesIndexes\": ["
                + "{\"gameVersion\": \"1.21.1\", \"modLoader\": 6},"
                + "{\"gameVersion\": \"1.20.1\", \"modLoader\": 1},"
                + "{\"gameVersion\": \"1.21.1\", \"modLoader\": 4},"
                + "{\"gameVersion\": \"1.20.1\", \"modLoader\": 4},"
                + "{\"gameVersion\": \"1.20.1\", \"modLoader\": 5}]}");

        assertEquals(Arrays.asList(LoaderType.FABRIC, LoaderType.FORGE, LoaderType.NEOFORGE, LoaderType.QUILT),
                new ArrayList<>(LoaderIndicators.fromCurseForge(project)));
    }

    @Test
    void curseForgeIgnoresUnknownAndUnsetLoaderIds() {
        // 0 = any/unset (resource packs, shaders), 2 = Cauldron, 3 = LiteLoader, 99 = future id
        CurseForgeProject project = curseForge("{\"latestFilesIndexes\": ["
                + "{\"gameVersion\": \"1.20.1\"},"
                + "{\"gameVersion\": \"1.20.1\", \"modLoader\": 0},"
                + "{\"gameVersion\": \"1.7.10\", \"modLoader\": 2},"
                + "{\"gameVersion\": \"1.12.2\", \"modLoader\": 3},"
                + "{\"gameVersion\": \"1.21.1\", \"modLoader\": 99}]}");

        assertTrue(LoaderIndicators.fromCurseForge(project).isEmpty());
    }

    @Test
    void curseForgeHandlesMissingMetadata() {
        assertTrue(LoaderIndicators.fromCurseForge(null).isEmpty());
        assertTrue(LoaderIndicators.fromCurseForge(curseForge("{}")).isEmpty());
        assertTrue(LoaderIndicators.fromCurseForge(curseForge("{\"latestFilesIndexes\": null}")).isEmpty());
        assertTrue(LoaderIndicators.fromCurseForge(curseForge("{\"latestFilesIndexes\": []}")).isEmpty());
        assertTrue(LoaderIndicators.fromCurseForge(curseForge("{\"latestFilesIndexes\": [null]}")).isEmpty());
    }

    @Test
    void curseForgeReadsTheLegacyFieldName() {
        CurseForgeProject project = curseForge("{\"gameVersionLatestFiles\": [{\"modLoader\": 4}]}");

        assertEquals(Arrays.asList(LoaderType.FABRIC), new ArrayList<>(LoaderIndicators.fromCurseForge(project)));
    }

    @Test
    void modrinthMapsKnownLoadersInDisplayOrderWithoutDuplicates() {
        ModrinthSearchHit hit = modrinth("{\"categories\": [\"quilt\", \"purpur\", \"neoforge\", \"paper\","
                + " \"legacy-fabric\", \"forge\", \"fabric\", \"fabric\", \"quilt\"]}");

        List<LoaderType> expected = Arrays.asList(LoaderType.FABRIC, LoaderType.FORGE, LoaderType.LEGACY_FABRIC,
                LoaderType.NEOFORGE, LoaderType.PAPER, LoaderType.PURPUR, LoaderType.QUILT);
        assertEquals(expected, new ArrayList<>(LoaderIndicators.fromModrinth(hit)));
    }

    @Test
    void modrinthIgnoresTopicsAndNonModLoaders() {
        ModrinthSearchHit hit = modrinth("{\"categories\": [\"optimization\", \"library\", \"fabric\", \"minecraft\","
                + " \"iris\", \"optifine\", \"datapack\", \"bukkit\", \"spigot\", \"Forge\", \"\", null]}");

        assertEquals(Arrays.asList(LoaderType.FABRIC), new ArrayList<>(LoaderIndicators.fromModrinth(hit)));
    }

    @Test
    void modsSectionShowsOnlyModLoaders() {
        EnumSet<LoaderType> reported = EnumSet.of(LoaderType.FABRIC, LoaderType.PAPER, LoaderType.NEOFORGE,
                LoaderType.PURPUR, LoaderType.LEGACY_FABRIC);

        assertEquals(EnumSet.of(LoaderType.FABRIC, LoaderType.LEGACY_FABRIC, LoaderType.NEOFORGE),
                LoaderIndicators.forSection(reported, "Mods"));
    }

    @Test
    void pluginsSectionShowsOnlyServerPlatforms() {
        EnumSet<LoaderType> reported = EnumSet.of(LoaderType.FABRIC, LoaderType.PAPER, LoaderType.PURPUR);

        assertEquals(EnumSet.of(LoaderType.PAPER, LoaderType.PURPUR), LoaderIndicators.forSection(reported, "Plugins"));
    }

    @Test
    void otherSectionsShowNothing() {
        EnumSet<LoaderType> reported = EnumSet.allOf(LoaderType.class);

        for (String section : Arrays.asList("Resource Packs", "Shaders", "Data Packs", "Worlds", null)) {
            assertTrue(LoaderIndicators.forSection(reported, section).isEmpty(), String.valueOf(section));
        }
    }

    @Test
    void forSectionHandlesMissingAndLeavesInputAlone() {
        assertTrue(LoaderIndicators.forSection(null, "Mods").isEmpty());

        EnumSet<LoaderType> reported = EnumSet.of(LoaderType.FABRIC, LoaderType.PAPER);
        LoaderIndicators.forSection(reported, "Plugins");
        assertEquals(EnumSet.of(LoaderType.FABRIC, LoaderType.PAPER), reported);
    }

    @Test
    void modrinthHandlesMissingMetadata() {
        assertTrue(LoaderIndicators.fromModrinth(null).isEmpty());
        assertTrue(LoaderIndicators.fromModrinth(modrinth("{}")).isEmpty());
        assertTrue(LoaderIndicators.fromModrinth(modrinth("{\"categories\": null}")).isEmpty());
        assertTrue(LoaderIndicators.fromModrinth(modrinth("{\"categories\": []}")).isEmpty());
    }
}
