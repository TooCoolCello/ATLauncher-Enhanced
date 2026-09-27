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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.atlauncher.constants.Constants;
import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthVersion;

public class ModrinthDependencyCheckTest {
    private static DisableableMod fromCurseForge(int projectId, int fileId) {
        DisableableMod mod = new DisableableMod();
        mod.curseForgeProjectId = projectId;
        mod.curseForgeFileId = fileId;
        return mod;
    }

    private static DisableableMod fromModrinth(String projectId) {
        DisableableMod mod = new DisableableMod();
        mod.modrinthProject = new ModrinthProject();
        mod.modrinthProject.id = projectId;
        mod.modrinthVersion = new ModrinthVersion();
        return mod;
    }

    @Test
    void librariesInstalledFromCurseForgeAreMatchedByModId() {
        assertTrue(ModrinthVersionSelectorDialog.providesDependency(
                fromCurseForge(Constants.CURSEFORGE_FABRIC_MOD_ID, 5000001), Constants.MODRINTH_FABRIC_MOD_ID));
        assertTrue(ModrinthVersionSelectorDialog.providesDependency(
                fromCurseForge(Constants.CURSEFORGE_LEGACY_FABRIC_MOD_ID, 5000002),
                Constants.MODRINTH_LEGACY_FABRIC_MOD_ID));
        assertTrue(ModrinthVersionSelectorDialog.providesDependency(
                fromCurseForge(Constants.CURSEFORGE_FORGIFIED_FABRIC_API_MOD_ID, 5000003),
                Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID));
    }

    @Test
    void aFileIdThatHappensToEqualTheModIdDoesNotMatch() {
        // the old check compared the CurseForge file ID against the library's mod ID
        assertFalse(ModrinthVersionSelectorDialog.providesDependency(
                fromCurseForge(111111, Constants.CURSEFORGE_FORGIFIED_FABRIC_API_MOD_ID),
                Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID));
        assertFalse(ModrinthVersionSelectorDialog.providesDependency(
                fromCurseForge(111111, Constants.CURSEFORGE_FABRIC_MOD_ID), Constants.MODRINTH_FABRIC_MOD_ID));
    }

    @Test
    void modrinthMatchesAndQslStillWork() {
        assertTrue(ModrinthVersionSelectorDialog.providesDependency(
                fromModrinth(Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID),
                Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID));
        assertTrue(ModrinthVersionSelectorDialog.providesDependency(fromModrinth(Constants.MODRINTH_QSL_MOD_ID),
                Constants.MODRINTH_FABRIC_MOD_ID));
        assertFalse(ModrinthVersionSelectorDialog.providesDependency(fromModrinth("AANobbMI"),
                Constants.MODRINTH_FABRIC_MOD_ID));
        assertFalse(ModrinthVersionSelectorDialog.providesDependency(
                fromCurseForge(Constants.CURSEFORGE_FABRIC_MOD_ID, 5000001),
                Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID));
    }
}
