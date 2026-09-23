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

public class SettingsAddModsViewModeTest {
    @Test
    void defaultsToCards() {
        assertEquals(AddModsViewMode.CARDS, new Settings().addModsViewMode);
    }

    @Test
    void olderSettingsWithoutTheFieldLoadCards() {
        Settings settings = Gsons.DEFAULT.fromJson("{\"firstTimeRun\": false}", Settings.class);

        assertEquals(AddModsViewMode.CARDS, settings.addModsViewMode);
    }

    @Test
    void keepsValidValues() {
        for (AddModsViewMode mode : AddModsViewMode.values()) {
            Settings settings = Gsons.DEFAULT.fromJson("{\"addModsViewMode\": \"" + mode.name() + "\"}",
                    Settings.class);
            settings.validateAddModsViewMode();

            assertEquals(mode, settings.addModsViewMode);
        }
    }

    @Test
    void normalizesUnknownOrNullValuesToCards() {
        for (String json : new String[] { "{\"addModsViewMode\": \"GRID\"}", "{\"addModsViewMode\": null}" }) {
            Settings settings = Gsons.DEFAULT.fromJson(json, Settings.class);
            settings.validateAddModsViewMode();

            assertEquals(AddModsViewMode.CARDS, settings.addModsViewMode, json);
        }
    }
}
