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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.atlauncher.constants.Constants;
import com.atlauncher.data.curseforge.CurseForgeFile;
import com.atlauncher.data.curseforge.CurseForgeFileDependency;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthDependency;
import com.atlauncher.data.modrinth.ModrinthDependencyType;
import com.atlauncher.data.modrinth.ModrinthVersion;

public class RelatedModsTest {
    private static final String MC = "1.20.1";
    private static final LoaderVersion NEOFORGE = new LoaderVersion("47.1.106", false, "NeoForge");
    private static final ModCompatibility.Target CONNECTOR = new ModCompatibility.Target(MC, NEOFORGE, true, true);
    private static final ModCompatibility.Target NATIVE = new ModCompatibility.Target(MC, NEOFORGE, false, false);

    private static ModrinthVersion version(String loader) {
        ModrinthVersion version = new ModrinthVersion();
        version.name = loader;
        version.gameVersions = Collections.singletonList(MC);
        version.loaders = Collections.singletonList(loader);
        version.dependencies = new ArrayList<>();
        return version;
    }

    private static ModrinthDependency dependency(String projectId, ModrinthDependencyType type) {
        ModrinthDependency dependency = new ModrinthDependency();
        dependency.projectId = projectId;
        dependency.dependencyType = type;
        return dependency;
    }

    private static CurseForgeFileDependency curseForge(int modId, int relationType) {
        CurseForgeFileDependency dependency = new CurseForgeFileDependency();
        dependency.modId = modId;
        dependency.relationType = relationType;
        return dependency;
    }

    @Test
    public void searchesTheInstancesLoaders() {
        assertEquals(Arrays.asList("neoforge", "forge", "fabric"), RelatedMods.modrinthSearchLoaders(CONNECTOR));
        assertEquals(Collections.singletonList("neoforge"), RelatedMods.modrinthSearchLoaders(NATIVE));
        assertEquals(Arrays.asList(Constants.CURSEFORGE_NEOFORGE_MODLOADER_ID, Constants.CURSEFORGE_FORGE_MODLOADER_ID,
                Constants.CURSEFORGE_FABRIC_MODLOADER_ID), RelatedMods.curseForgeLoaderTypes(CONNECTOR));
        assertEquals("Create addon", RelatedMods.addonQuery(" Create "));
    }

    @Test
    public void dependenciesComeFromTheFileTheSelectorWouldOffer() {
        ModrinthVersion fabric = version("fabric");
        ModrinthVersion neoforge = version("neoforge");

        assertEquals(neoforge, RelatedMods.versionForDependencies(Arrays.asList(fabric, neoforge), CONNECTOR));
        assertEquals(fabric, RelatedMods.versionForDependencies(Collections.singletonList(fabric), CONNECTOR));
        assertNull(RelatedMods.versionForDependencies(Collections.singletonList(fabric), NATIVE));
    }

    @Test
    public void needsAreRequiredFirstWithoutItselfOrIncompatibleOnes() {
        ModrinthVersion version = version("neoforge");
        version.dependencies.add(dependency("lib", ModrinthDependencyType.OPTIONAL));
        version.dependencies.add(dependency("api", ModrinthDependencyType.REQUIRED));
        version.dependencies.add(dependency("lib", ModrinthDependencyType.REQUIRED));
        version.dependencies.add(dependency("self", ModrinthDependencyType.REQUIRED));
        // "incompatible" and "embedded" aren't in the enum, so Gson leaves them null
        version.dependencies.add(dependency("clash", null));

        RelatedMods.Needs<String> needs = RelatedMods.modrinthNeeds(version, "self");
        assertEquals(Arrays.asList("api", "lib"), needs.required);
        assertEquals(Collections.emptyList(), needs.optional);

        CurseForgeFile file = new CurseForgeFile();
        file.dependencies = Arrays.asList(curseForge(1, 2), curseForge(2, 3), curseForge(3, 5), curseForge(9, 3));
        RelatedMods.Needs<Integer> cf = RelatedMods.curseForgeNeeds(file, 9);
        assertEquals(Collections.singletonList(2), cf.required);
        assertEquals(Collections.singletonList(1), cf.optional);
    }

    @Test
    public void onlySafeModrinthUsernames() {
        assertEquals("jellysquid3", RelatedMods.modrinthUser("jellysquid3"));
        assertNull(RelatedMods.modrinthUser("../admin"));
        assertNull(RelatedMods.modrinthUser(null));
    }
}
