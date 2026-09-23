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

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.curseforge.CurseForgeAuthor;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.modrinth.ModrinthProject;

public class EditModsDialogSearchTest {
    // a manually added jar: no platform metadata at all
    private static DisableableMod manualMod() {
        DisableableMod mod = new DisableableMod();
        mod.name = "Just Enough Items";
        mod.file = "jei-1.20.1-forge-15.2.0.27.jar";
        return mod;
    }

    private static DisableableMod curseForgeMod() {
        CurseForgeAuthor author = new CurseForgeAuthor();
        author.name = "jaredlll08";

        CurseForgeProject project = new CurseForgeProject();
        project.id = 238222;
        project.slug = "clumps";
        project.authors = Arrays.asList(author, null);

        DisableableMod mod = new DisableableMod();
        mod.name = "Clumps";
        mod.file = "Clumps-forge-1.20.1.jar";
        mod.curseForgeProjectId = 238222;
        mod.curseForgeProject = project;
        return mod;
    }

    private static DisableableMod modrinthMod() {
        ModrinthProject project = new ModrinthProject();
        project.id = "AANobbMI";
        project.slug = "sodium";
        project.team = "4reLOAKe";

        DisableableMod mod = new DisableableMod();
        mod.name = "Sodium";
        mod.file = "sodium-fabric-0.5.8.jar";
        mod.modrinthProject = project;
        return mod;
    }

    private static boolean matches(DisableableMod mod, String query) {
        return EditModsDialog.matchesSearch(mod, mod.name, query);
    }

    @Test
    void matchesNameCaseInsensitively() {
        assertTrue(matches(manualMod(), "enough"));
        assertTrue(matches(manualMod(), "JUST ENOUGH"));
        assertTrue(matches(modrinthMod(), "sOdIuM"));
    }

    @Test
    void matchesCheckboxLabelIncludingPluginPrefix() {
        DisableableMod plugin = manualMod();
        plugin.name = "EssentialsX";

        assertTrue(EditModsDialog.matchesSearch(plugin, "[Plugin] EssentialsX", "[plugin]"));
        assertFalse(EditModsDialog.matchesSearch(plugin, plugin.name, "[plugin]"));
    }

    @Test
    void matchesFilename() {
        assertTrue(matches(manualMod(), "forge-15.2"));
        assertTrue(matches(modrinthMod(), ".JAR"));
    }

    @Test
    void emptyOrWhitespaceQueryMatchesEverything() {
        assertTrue(matches(manualMod(), ""));
        assertTrue(matches(manualMod(), "   "));
        assertTrue(matches(manualMod(), null));
    }

    @Test
    void queryIsTrimmed() {
        assertTrue(matches(manualMod(), "  items  "));
    }

    @Test
    void matchesCurseForgeProjectIdSlugAndAuthor() {
        assertTrue(matches(curseForgeMod(), "238222"));
        assertTrue(matches(curseForgeMod(), "clumps"));
        assertTrue(matches(curseForgeMod(), "JAREDLLL08"));
    }

    @Test
    void matchesCurseForgeProjectIdWithoutFullProjectInformation() {
        DisableableMod mod = curseForgeMod();
        mod.curseForgeProject = null;

        assertTrue(matches(mod, "238222"));
        assertFalse(matches(mod, "jaredlll08"));
    }

    @Test
    void matchesModrinthProjectIdAndSlug() {
        assertTrue(matches(modrinthMod(), "aanobbmi"));
        assertTrue(matches(modrinthMod(), "sodium"));
    }

    @Test
    void modrinthTeamIdIsNotSearchedAsAnAuthor() {
        assertFalse(matches(modrinthMod(), "4reLOAKe"));
    }

    @Test
    void manualJarWithMissingMetadataDoesNotThrow() {
        DisableableMod mod = new DisableableMod();

        assertFalse(EditModsDialog.matchesSearch(mod, null, "anything"));
        assertTrue(EditModsDialog.matchesSearch(mod, null, ""));
        assertFalse(matches(manualMod(), "238222"));
    }

    @Test
    void noMatchReturnsFalse() {
        assertFalse(matches(manualMod(), "sodium"));
        assertFalse(matches(curseForgeMod(), "fabric-api"));
        assertFalse(matches(modrinthMod(), "clumps"));
    }
}
