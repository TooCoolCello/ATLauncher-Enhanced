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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.atlauncher.constants.Constants;
import com.atlauncher.data.ModCompatibility.State;
import com.atlauncher.data.ModCompatibility.Target;
import com.atlauncher.data.curseforge.CurseForgeGameVersionLatestFiles;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthVersion;

public class ModCompatibilityTest {
    private static final String MC = "1.20.1";
    private static final LoaderVersion NEOFORGE = new LoaderVersion("47.1.106", false, "NeoForge");
    private static final LoaderVersion FORGE = new LoaderVersion("47.3.0", false, "Forge");
    private static final LoaderVersion FABRIC = new LoaderVersion("0.16.9", false, "Fabric");
    private static final LoaderVersion QUILT = new LoaderVersion("0.26.0", false, "Quilt");
    private static final LoaderVersion PAPER = new LoaderVersion("1.20.1-196", false, "Paper");

    private static Target target(LoaderVersion loader, boolean forgeCompatible, boolean connector) {
        return new Target(MC, loader, forgeCompatible, connector);
    }

    private static CurseForgeGameVersionLatestFiles index(String gameVersion, int modLoader) {
        CurseForgeGameVersionLatestFiles file = new CurseForgeGameVersionLatestFiles();
        file.gameVersion = gameVersion;
        file.modLoader = modLoader;
        return file;
    }

    private static CurseForgeProject curseForge(CurseForgeGameVersionLatestFiles... files) {
        CurseForgeProject project = new CurseForgeProject();
        project.latestFilesIndexes = new ArrayList<>(Arrays.asList(files));
        return project;
    }

    private static ModrinthVersion version(List<String> gameVersions, String... loaders) {
        ModrinthVersion version = new ModrinthVersion();
        version.gameVersions = gameVersions;
        version.loaders = Arrays.asList(loaders);
        return version;
    }

    private static State curseForgeState(Target target, CurseForgeGameVersionLatestFiles... files) {
        return ModCompatibility.forCurseForgeProject(target, "Mods", curseForge(files)).state;
    }

    private static State modrinthState(Target target, String section, ModrinthVersion... versions) {
        return ModCompatibility.forModrinthVersions(target, section, Arrays.asList(versions)).state;
    }

    @Test
    void nativeFileForThisVersionIsCompatible() {
        assertEquals(State.COMPATIBLE, curseForgeState(target(NEOFORGE, false, false),
                index(MC, Constants.CURSEFORGE_NEOFORGE_MODLOADER_ID)));
        assertEquals(State.COMPATIBLE, modrinthState(target(FABRIC, false, false), "Mods",
                version(Collections.singletonList(MC), "fabric")));
    }

    @Test
    void projectLevelTagsAloneAreNotEnough() {
        // the project has a NeoForge file and a 1.20.1 file, but not one file that is both
        Target neoForge = target(NEOFORGE, false, false);
        assertEquals(State.NOT_AVAILABLE, curseForgeState(neoForge,
                index("1.21.1", Constants.CURSEFORGE_NEOFORGE_MODLOADER_ID),
                index(MC, Constants.CURSEFORGE_FORGE_MODLOADER_ID)));
        assertEquals(State.NOT_AVAILABLE, modrinthState(neoForge, "Mods",
                version(Collections.singletonList("1.21.1"), "neoforge"),
                version(Collections.singletonList(MC), "forge")));
    }

    @Test
    void forgeFilesCountOnNeoForgeOnlyWhereItStillLoadsThem() {
        CurseForgeGameVersionLatestFiles forgeFile = index(MC, Constants.CURSEFORGE_FORGE_MODLOADER_ID);

        assertEquals(State.COMPATIBLE, curseForgeState(target(NEOFORGE, true, false), forgeFile));
        assertEquals(State.NOT_AVAILABLE, curseForgeState(target(NEOFORGE, false, false), forgeFile));
    }

    @Test
    void fabricOnlyFileRunsViaConnectorOnlyWhileItIsActive() {
        CurseForgeGameVersionLatestFiles fabricFile = index(MC, Constants.CURSEFORGE_FABRIC_MODLOADER_ID);

        assertEquals(State.VIA_CONNECTOR, curseForgeState(target(NEOFORGE, false, true), fabricFile));
        assertEquals(State.VIA_CONNECTOR, curseForgeState(target(FORGE, false, true), fabricFile));
        assertEquals(State.NOT_AVAILABLE, curseForgeState(target(NEOFORGE, false, false), fabricFile));
        assertTrue(ModCompatibility.forCurseForgeProject(target(NEOFORGE, false, true), "Mods",
                curseForge(fabricFile)).reason.contains("compatibility is not guaranteed"));
    }

    @Test
    void nativeFileWinsOverConnector() {
        assertEquals(State.COMPATIBLE, modrinthState(target(NEOFORGE, false, true), "Mods",
                version(Collections.singletonList(MC), "fabric"),
                version(Collections.singletonList(MC), "neoforge")));
    }

    @Test
    void quiltLoadsFabricFiles() {
        assertEquals(State.COMPATIBLE, curseForgeState(target(QUILT, false, false),
                index(MC, Constants.CURSEFORGE_FABRIC_MODLOADER_ID)));
    }

    @Test
    void untaggedCurseForgeFileIsUnknownNotCompatible() {
        ModCompatibility.Verdict verdict = ModCompatibility.forCurseForgeProject(target(FORGE, false, false), "Mods",
                curseForge(index(MC, 0)));

        assertEquals(State.UNKNOWN, verdict.state);
        // apostrophes survive formatting with arguments
        assertTrue(verdict.reason.contains("doesn't say"), verdict.reason);
    }

    @Test
    void missingOrFailedDataIsUnknown() {
        assertEquals(State.UNKNOWN,
                ModCompatibility.forCurseForgeProject(target(FORGE, false, false), "Mods", new CurseForgeProject())
                        .state);
        assertEquals(State.UNKNOWN,
                ModCompatibility.forModrinthVersions(target(FORGE, false, false), "Mods", null).state);
    }

    @Test
    void dataPacksCheckTheMinecraftVersionOfDataPackFilesOnly() {
        Target vanilla = target(null, false, false);

        assertEquals(State.COMPATIBLE, ModCompatibility.forCurseForgeProject(vanilla, "Data Packs",
                curseForge(index(MC, 0))).state);
        assertEquals(State.NOT_AVAILABLE, modrinthState(vanilla, "Data Packs",
                version(Collections.singletonList("1.21"), "datapack"),
                version(Collections.singletonList(MC), "fabric")));
        assertEquals(State.COMPATIBLE, modrinthState(vanilla, "Data Packs",
                version(Collections.singletonList(MC), "datapack")));
    }

    @Test
    void pluginsCheckTheServerPlatform() {
        Target paper = target(PAPER, false, false);

        assertEquals(State.COMPATIBLE, modrinthState(paper, "Plugins",
                version(Collections.singletonList(MC), "bukkit")));
        assertEquals(State.NOT_AVAILABLE, modrinthState(paper, "Plugins",
                version(Collections.singletonList(MC), "velocity")));
        // CurseForge's plugin files name no platform
        assertNull(ModCompatibility.forCurseForgeProject(paper, "Plugins", curseForge(index(MC, 0))));
    }

    @Test
    void sectionsWithoutMeaningfulDataAreNotChecked() {
        Target forge = target(FORGE, false, false);

        for (String section : Arrays.asList("Resource Packs", "Shaders", "Worlds")) {
            assertNull(ModCompatibility.forCurseForgeProject(forge, section, curseForge(index(MC, 0))), section);
            assertNull(ModCompatibility.forModrinthVersions(forge, section, Collections.emptyList()), section);
            assertNull(ModCompatibility.modrinthProjectLevel(forge, section, null, null), section);
        }
    }

    @Test
    void modrinthSearchResultSettlesOnlyWhatItsProjectWideDataRulesOut() {
        Target neoForge = target(NEOFORGE, false, false);

        assertEquals(State.NOT_AVAILABLE, ModCompatibility.modrinthProjectLevel(neoForge, "Mods",
                Collections.singletonList("1.21.1"), Collections.singletonList("neoforge")).state);
        assertEquals(State.NOT_AVAILABLE, ModCompatibility.modrinthProjectLevel(neoForge, "Mods",
                Collections.singletonList(MC), Arrays.asList("fabric", "optimization")).state);
        // with Connector a Fabric project needs its versions checked
        assertEquals(State.CHECKING, ModCompatibility.modrinthProjectLevel(target(NEOFORGE, false, true), "Mods",
                Collections.singletonList(MC), Collections.singletonList("fabric")).state);
        // categories that name no loader rule nothing out
        assertEquals(State.CHECKING, ModCompatibility.modrinthProjectLevel(neoForge, "Mods", null,
                Collections.singletonList("optimization")).state);
    }

    @Test
    void modrinthRequestAsksForFabricOnForgeLikeLoadersWhateverConnector() {
        assertEquals(Arrays.asList("neoforge", "forge", "fabric"),
                ModCompatibility.modrinthLoaderFilter(target(NEOFORGE, true, false), "Mods"));
        assertEquals(Arrays.asList("quilt", "fabric"),
                ModCompatibility.modrinthLoaderFilter(target(QUILT, false, false), "Mods"));
        assertEquals(Collections.singletonList("datapack"),
                ModCompatibility.modrinthLoaderFilter(target(FORGE, false, false), "Data Packs"));
    }

    @Test
    void hidingOnlyEverHidesResultsKnownToHaveNoFile() {
        assertTrue(ModCompatibility.hiddenWhenFiltering(true, State.NOT_AVAILABLE));
        for (State state : Arrays.asList(State.COMPATIBLE, State.VIA_CONNECTOR, State.CHECKING, State.UNKNOWN,
                null)) {
            assertFalse(ModCompatibility.hiddenWhenFiltering(true, state), String.valueOf(state));
        }
        assertFalse(ModCompatibility.hiddenWhenFiltering(false, State.NOT_AVAILABLE));
    }
}
