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

import org.junit.jupiter.api.Test;

import com.atlauncher.Gsons;

public class SettingsDefaultGameOptionsTest {
    @Test
    void everyOptionStartsAsDontChange() {
        assertTrue(new Settings().defaultGameOptions.isEmpty());
    }

    @Test
    void settingsSavedWithoutItGetDontChange() {
        Settings settings = Gsons.DEFAULT.fromJson("{\"firstTimeRun\": false, \"defaultGameOptions\": null}",
            Settings.class);

        settings.validateDefaultGameOptions();

        assertTrue(settings.defaultGameOptions.isEmpty());
    }

    @Test
    void savedValuesLoadAndOutOfRangeOnesAreDropped() {
        Settings settings = Gsons.DEFAULT.fromJson(
            "{\"defaultGameOptions\": {\"fov\": 95, \"guiScale\": 40, \"renderDistance\": 1, \"autoJump\": false}}",
            Settings.class);

        settings.validateDefaultGameOptions();

        assertEquals(95, settings.defaultGameOptions.fov);
        assertEquals(false, settings.defaultGameOptions.autoJump);
        assertNull(settings.defaultGameOptions.guiScale);
        assertNull(settings.defaultGameOptions.renderDistance);
        assertFalse(settings.defaultGameOptions.isEmpty());
    }
}
