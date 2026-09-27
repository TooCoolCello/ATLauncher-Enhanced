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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.atlauncher.constants.Constants;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthVersion;

public class SinytraConnectorTest {
    private static final LoaderVersion NEOFORGE = new LoaderVersion("21.1.77", false, "NeoForge");
    private static final LoaderVersion FORGE = new LoaderVersion("47.3.0", false, "Forge");
    private static final LoaderVersion FABRIC = new LoaderVersion("0.16.9", false, "Fabric");

    private static DisableableMod curseForgeConnector() {
        DisableableMod mod = new DisableableMod();
        mod.curseForgeProjectId = Constants.CURSEFORGE_SINYTRA_CONNECTOR_MOD_ID;
        mod.curseForgeFileId = 5555555;
        return mod;
    }

    private static DisableableMod modrinthConnector() {
        DisableableMod mod = new DisableableMod();
        mod.modrinthProject = new ModrinthProject();
        mod.modrinthProject.id = Constants.MODRINTH_SINYTRA_CONNECTOR_MOD_ID.toLowerCase();
        mod.modrinthVersion = new ModrinthVersion();
        return mod;
    }

    // loader tags of the files that already passed the Minecraft version filter
    @SafeVarargs
    private static List<List<String>> files(List<String>... tags) {
        return Arrays.asList(tags);
    }

    @Test
    void installedEnabledConnectorFromEitherPlatformCounts() {
        assertTrue(SinytraConnector.isInstalledAndEnabled(Collections.singletonList(curseForgeConnector())));
        assertTrue(SinytraConnector.isInstalledAndEnabled(Collections.singletonList(modrinthConnector())));
    }

    @Test
    void disabledSkippedOrUnselectedConnectorDoesNotCount() {
        DisableableMod disabled = curseForgeConnector();
        disabled.disabled = true;
        DisableableMod skipped = modrinthConnector();
        skipped.skipped = true;
        DisableableMod unselected = curseForgeConnector();
        unselected.wasSelected = false;

        assertFalse(SinytraConnector.isInstalledAndEnabled(Arrays.asList(disabled, skipped, unselected)));
    }

    @Test
    void absentConnectorDoesNotCount() {
        DisableableMod other = new DisableableMod();
        other.curseForgeProjectId = Constants.CURSEFORGE_FORGIFIED_FABRIC_API_MOD_ID;
        other.curseForgeFileId = Constants.CURSEFORGE_SINYTRA_CONNECTOR_MOD_ID;
        DisableableMod manual = new DisableableMod();
        manual.file = "Connector-1.0.0-beta.46+1.20.1.jar";

        assertFalse(SinytraConnector.isInstalledAndEnabled(Arrays.asList(other, manual, null)));
        assertFalse(SinytraConnector.isInstalledAndEnabled(Collections.emptyList()));
        assertFalse(SinytraConnector.isInstalledAndEnabled(null));
    }

    @Test
    void nativeFileForThisMinecraftVersionWinsOverFabric() {
        assertFalse(SinytraConnector.allowsFabricFallback(true, NEOFORGE, false,
                files(Arrays.asList("1.21.1", "Fabric"), Arrays.asList("1.21.1", "NeoForge"))));
        assertFalse(SinytraConnector.allowsFabricFallback(true, NEOFORGE, false,
                files(Collections.singletonList("fabric"), Collections.singletonList("neoforge"))));
    }

    @Test
    void nativeFilesOnlyForOtherMinecraftVersionsDoNotHideFabric() {
        // the selectors pass only version-matched files, so a 1.20.4 NeoForge build never reaches this list
        assertTrue(SinytraConnector.allowsFabricFallback(true, NEOFORGE, false,
                files(Arrays.asList("1.21.1", "Fabric"))));
        assertTrue(SinytraConnector.allowsFabricFallback(true, NEOFORGE, false, files()));
    }

    @Test
    void inactiveConnectorNeverAllowsFabric() {
        assertFalse(SinytraConnector.allowsFabricFallback(false, NEOFORGE, false, files(Arrays.asList("Fabric"))));
        assertFalse(SinytraConnector.allowsFabricFallback(false, FORGE, false, files()));
    }

    @Test
    void onlyForgeAndNeoForgeUseTheFallback() {
        assertFalse(SinytraConnector.allowsFabricFallback(true, FABRIC, false, files(Arrays.asList("Fabric"))));
        assertFalse(SinytraConnector.allowsFabricFallback(true, null, false, files(Arrays.asList("Fabric"))));
    }

    @Test
    void forgeFilesCountAsNativeOnlyWhereNeoForgeLoadsThem() {
        List<List<String>> forgeAndFabric = files(Arrays.asList("1.20.1", "Forge"), Arrays.asList("1.20.1", "Fabric"));

        assertFalse(SinytraConnector.allowsFabricFallback(true, NEOFORGE, true, forgeAndFabric));
        assertTrue(SinytraConnector.allowsFabricFallback(true, NEOFORGE, false, forgeAndFabric));
        assertFalse(SinytraConnector.allowsFabricFallback(true, FORGE, false, forgeAndFabric));
        // NeoForge files are never native on Forge
        assertTrue(SinytraConnector.allowsFabricFallback(true, FORGE, false,
                files(Arrays.asList("NeoForge"), Arrays.asList("Fabric"))));
    }

    @Test
    void mixedLoaderFilesAreNative() {
        List<String> mixed = Arrays.asList("1.21.1", "NeoForge", "Fabric");

        assertTrue(SinytraConnector.isNativeFile(mixed, NEOFORGE, false));
        assertFalse(SinytraConnector.isFabricFallbackFile(mixed, NEOFORGE, false));
        assertFalse(SinytraConnector.allowsFabricFallback(true, NEOFORGE, false, files(mixed)));
    }

    @Test
    void fabricOnlyFilesAreFallbacksOnlyOnForgeLikeLoaders() {
        assertTrue(SinytraConnector.isFabricFallbackFile(Arrays.asList("1.21.1", "Fabric"), NEOFORGE, false));
        assertTrue(SinytraConnector.isFabricFallbackFile(Collections.singletonList("fabric"), FORGE, false));
        assertFalse(SinytraConnector.isFabricFallbackFile(Arrays.asList("1.21.1", "Fabric"), FABRIC, false));
        assertFalse(SinytraConnector.isFabricFallbackFile(Arrays.asList("1.21.1", "Quilt"), NEOFORGE, false));
    }

    @Test
    void missingMetadataIsNeverNativeOrFabric() {
        assertFalse(SinytraConnector.isNativeFile(null, NEOFORGE, true));
        assertFalse(SinytraConnector.isNativeFile(Collections.emptyList(), NEOFORGE, true));
        assertFalse(SinytraConnector.isNativeFile(Arrays.asList("NeoForge"), null, false));
        assertFalse(SinytraConnector.isFabricFallbackFile(null, NEOFORGE, false));
        assertFalse(SinytraConnector.isFabricFallbackFile(Arrays.asList("Fabric"), null, false));

        // untagged files can't rule a fallback out, and can't be one
        assertTrue(SinytraConnector.allowsFabricFallback(true, NEOFORGE, false, files(null, Arrays.asList("1.21.1"))));
        assertTrue(SinytraConnector.allowsFabricFallback(true, NEOFORGE, false, null));
    }
}
