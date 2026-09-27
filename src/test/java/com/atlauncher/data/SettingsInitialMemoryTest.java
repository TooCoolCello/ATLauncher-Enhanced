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

import org.junit.jupiter.api.Test;

import com.atlauncher.Gsons;

public class SettingsInitialMemoryTest {
    @Test
    void defaultsToUnset() {
        assertEquals(0, new Settings().initialMemory);
        assertNull(new InstanceLauncher().initialMemory);
    }

    @Test
    void olderSettingsWithoutTheFieldLoadUnset() {
        assertEquals(0, Gsons.DEFAULT.fromJson("{\"maximumMemory\": 4096}", Settings.class).initialMemory);
        assertNull(Gsons.DEFAULT.fromJson("{\"maximumMemory\": 4096}", InstanceLauncher.class).initialMemory);
    }

    @Test
    void savesAndLoads() {
        Settings settings = new Settings();
        settings.initialMemory = 2048;
        assertEquals(2048, Gsons.DEFAULT.fromJson(Gsons.DEFAULT.toJson(settings), Settings.class).initialMemory);

        InstanceLauncher launcher = new InstanceLauncher();
        launcher.initialMemory = 1024;
        assertEquals(Integer.valueOf(1024),
                Gsons.DEFAULT.fromJson(Gsons.DEFAULT.toJson(launcher), InstanceLauncher.class).initialMemory);

        // an instance explicitly set to 0 keeps that, rather than falling back to the launcher's setting
        launcher.initialMemory = 0;
        assertEquals(Integer.valueOf(0),
                Gsons.DEFAULT.fromJson(Gsons.DEFAULT.toJson(launcher), InstanceLauncher.class).initialMemory);
    }

    @Test
    void clampKeepsTheMinimumWithinTheMaximum() {
        assertEquals(0, Settings.clampInitialMemory(0, 4096));
        assertEquals(0, Settings.clampInitialMemory(-512, 4096));
        assertEquals(1024, Settings.clampInitialMemory(1024, 4096));
        assertEquals(4096, Settings.clampInitialMemory(4096, 4096));
        assertEquals(4096, Settings.clampInitialMemory(8192, 4096));
    }

    @Test
    void validationLowersAMinimumAboveTheMaximum() {
        Settings settings = new Settings();
        settings.maximumMemory = 2048;

        settings.initialMemory = 3072;
        settings.validateInitialMemory();
        assertEquals(2048, settings.initialMemory);

        settings.initialMemory = -1;
        settings.validateInitialMemory();
        assertEquals(0, settings.initialMemory);

        settings.initialMemory = 1024;
        settings.validateInitialMemory();
        assertEquals(1024, settings.initialMemory);
    }
}
