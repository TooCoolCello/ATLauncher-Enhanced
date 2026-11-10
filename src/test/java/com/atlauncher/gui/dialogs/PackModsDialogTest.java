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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

public class PackModsDialogTest {
    @Test
    public void aBigAddListsSomeNamesAndCountsTheRest() {
        // "Select All" on a 300-mod pack: the report must still fit on the screen, so its OK button can be reached
        List<String> added = IntStream.rangeClosed(1, 250).mapToObj(i -> "Mod " + i).collect(Collectors.toList());

        String listed = PackModsDialog.listed(added, ", ");

        assertTrue(listed.startsWith("Mod 1, Mod 2, "), listed);
        assertTrue(listed.endsWith("and 230 more"), listed);
        assertFalse(listed.contains("Mod 21,"), listed);
    }

    @Test
    public void aShortListIsListedInFull() {
        assertEquals("A; B", PackModsDialog.listed(Arrays.asList("A", "B"), "; "));
    }
}
