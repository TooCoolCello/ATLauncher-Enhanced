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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.DisableableMod.UpdateStatus;
import com.atlauncher.gui.dialogs.EditModsDialog.SortOrder;

public class EditModsDialogSortTest {
    private final Map<DisableableMod, Long> addedTimes = new HashMap<>();
    private final Map<DisableableMod, UpdateStatus> updateStatuses = new HashMap<>();

    private static DisableableMod mod(String name) {
        DisableableMod mod = new DisableableMod();
        mod.name = name;
        return mod;
    }

    private List<String> sort(SortOrder order, DisableableMod... mods) {
        return Arrays.stream(mods).sorted(EditModsDialog.sortComparator(order, addedTimes, updateStatuses))
            .map(m -> m.name).collect(Collectors.toList());
    }

    @Test
    public void nameIgnoresCase() {
        assertEquals(Arrays.asList("Alpha", "beta", "Charlie"),
            sort(SortOrder.NAME, mod("Charlie"), mod("beta"), mod("Alpha")));
    }

    @Test
    public void dateAddedPutsNewestFirstAndUnreadableFilesLast() {
        DisableableMod old = mod("Old");
        DisableableMod bravo = mod("Bravo");
        DisableableMod alpha = mod("Alpha");
        DisableableMod unreadable = mod("Unreadable");
        addedTimes.put(old, 100L);
        addedTimes.put(bravo, 300L);
        addedTimes.put(alpha, 300L);

        assertEquals(Arrays.asList("Alpha", "Bravo", "Old", "Unreadable"),
            sort(SortOrder.DATE_ADDED, unreadable, old, bravo, alpha));
    }

    @Test
    public void updateAvailablePutsUpdatesFirstThenModsStillBeingChecked() {
        DisableableMod upToDate = mod("Up To Date");
        DisableableMod updateB = mod("Update B");
        DisableableMod updateA = mod("Update A");
        DisableableMod checking = mod("Checking");
        DisableableMod unknown = mod("Unknown");
        DisableableMod manual = mod("Manual Jar");
        updateStatuses.put(upToDate, UpdateStatus.UP_TO_DATE);
        updateStatuses.put(updateB, UpdateStatus.AVAILABLE);
        updateStatuses.put(updateA, UpdateStatus.AVAILABLE);
        updateStatuses.put(checking, UpdateStatus.CHECKING);
        updateStatuses.put(unknown, UpdateStatus.UNKNOWN);

        assertEquals(Arrays.asList("Update A", "Update B", "Checking", "Manual Jar", "Unknown", "Up To Date"),
            sort(SortOrder.UPDATE_AVAILABLE, upToDate, updateB, manual, checking, unknown, updateA));
    }
}
