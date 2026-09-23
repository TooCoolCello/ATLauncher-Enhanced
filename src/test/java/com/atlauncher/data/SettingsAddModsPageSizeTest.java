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

import org.junit.jupiter.api.Test;

import com.atlauncher.Gsons;

public class SettingsAddModsPageSizeTest {
    @Test
    void defaultsTo25() {
        assertEquals(25, new Settings().addModsPageSize);
    }

    @Test
    void olderSettingsWithoutTheFieldLoadTheDefault() {
        Settings settings = Gsons.DEFAULT.fromJson("{\"firstTimeRun\": false}", Settings.class);

        assertEquals(25, settings.addModsPageSize);
    }

    @Test
    void keepsAllowedValues() {
        Settings settings = new Settings();

        for (int size : new int[] { 25, 50, 100, 200 }) {
            settings.addModsPageSize = size;
            settings.validateAddModsPageSize();
            assertEquals(size, settings.addModsPageSize);
        }
    }

    @Test
    void resetsInvalidValuesTo25() {
        Settings settings = new Settings();

        for (int size : new int[] { 0, -25, 20, 75, 150, 201, 1000 }) {
            settings.addModsPageSize = size;
            settings.validateAddModsPageSize();
            assertEquals(25, settings.addModsPageSize, "page size " + size);
        }
    }
}
