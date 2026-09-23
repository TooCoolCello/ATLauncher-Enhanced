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
package com.atlauncher.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JDialog;

import org.junit.jupiter.api.Test;

import com.atlauncher.gui.dialogs.BrowserDownloadDialog;
import com.atlauncher.gui.dialogs.CurseForgeProjectFileSelectorDialog;
import com.atlauncher.gui.dialogs.EditModsDialog;
import com.atlauncher.gui.dialogs.ModrinthVersionSelectorDialog;
import com.atlauncher.gui.dialogs.ProgressDialog;

public class DialogManagerTest {
    private static final List<String> ROOTS = Arrays.asList("launcher", "sharedOwner");

    // window -> owner, mirroring Edit Mods -> Add Mods -> selector, a prompt and a console-owned dialog
    private static Map<String, String> owners() {
        Map<String, String> owners = new HashMap<>();
        owners.put("editMods", "launcher");
        owners.put("addMods", "editMods");
        owners.put("selector", "addMods");
        owners.put("optionPane", "sharedOwner");
        owners.put("consoleDialog", "console");
        return owners;
    }

    @Test
    void recoveryClosesLauncherOwnedDialogsDeepestFirstWhenNoOperationIsActive() {
        Map<String, String> owners = owners();

        List<String> ordered = DialogManager.deepestFirst(
                Arrays.asList("editMods", "optionPane", "addMods", "consoleDialog", "selector", "unowned"),
                owners::get, ROOTS);

        assertEquals(Arrays.asList("selector", "addMods", "editMods", "optionPane"), ordered);
        assertEquals(ordered, DialogManager.recoverable(ordered, "progress"::equals));
    }

    @Test
    void equalDepthDialogsKeepNewestFirstOrder() {
        // Remove Selected prompt is owned by the launcher like Edit Mods, but opened after it; recovery passes
        // dialogs newest-first so the prompt must stay ahead of Edit Mods
        Map<String, String> owners = owners();
        owners.put("deletePrompt", "launcher");

        List<String> ordered = DialogManager.deepestFirst(Arrays.asList("deletePrompt", "editMods"), owners::get,
                ROOTS);

        assertEquals(Arrays.asList("deletePrompt", "editMods"), ordered);
    }

    @Test
    void activeOperationSuppressesClosingAllLauncherOwnedDialogs() {
        Map<String, String> owners = owners();
        // install running under the selector; optionPane stands in for its browser-download prompt, which is
        // owned by the shared frame rather than the progress dialog
        owners.put("progress", "selector");

        List<String> ordered = DialogManager.deepestFirst(
                Arrays.asList("editMods", "optionPane", "addMods", "consoleDialog", "progress", "selector"),
                owners::get, ROOTS);

        assertEquals(Arrays.asList("progress", "selector", "addMods", "editMods", "optionPane"), ordered);
        assertTrue(DialogManager.recoverable(ordered, "progress"::equals).isEmpty());
    }

    @Test
    void progressAndBrowserDownloadDialogsAreActiveOperations() {
        assertTrue(DialogManager.isActiveOperationDialog(ProgressDialog.class));
        assertTrue(DialogManager.isActiveOperationDialog(BrowserDownloadDialog.class));

        assertFalse(DialogManager.isActiveOperationDialog(JDialog.class));
        assertFalse(DialogManager.isActiveOperationDialog(CurseForgeProjectFileSelectorDialog.class));
        assertFalse(DialogManager.isActiveOperationDialog(ModrinthVersionSelectorDialog.class));
        assertFalse(DialogManager.isActiveOperationDialog(EditModsDialog.class));
    }
}
