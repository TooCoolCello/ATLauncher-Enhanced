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

/**
 * A saved maximum memory above this machine's RAM is kept when settings load: it may come from a machine with more.
 * Only a 32 bit system's hard limit still lowers it, as before.
 */
public class SettingsMaximumMemoryTest {
    @Test
    void valueAboveDetectedRamIsKept() {
        assertEquals(65536, Settings.validatedMaximumMemory(65536, 16384, true));
        assertEquals(16384, Settings.validatedMaximumMemory(16384, 16384, true));
        assertEquals(4096, Settings.validatedMaximumMemory(4096, 16384, true));
    }

    @Test
    void unknownRamChangesNothing() {
        assertEquals(65536, Settings.validatedMaximumMemory(65536, 0, true));
        assertEquals(65536, Settings.validatedMaximumMemory(65536, 0, false));
    }

    @Test
    void thirtyTwoBitSystemsKeepTheirExistingLimit() {
        assertEquals(1024, Settings.validatedMaximumMemory(4096, 1024, false));
        assertEquals(1024, Settings.validatedMaximumMemory(1024, 1024, false));
        assertEquals(768, Settings.validatedMaximumMemory(768, 1024, false));
    }

    @Test
    void valueAboveDetectedRamSurvivesLoadAndSave() {
        Settings saved = new Settings();
        saved.maximumMemory = 65536;

        Settings loaded = Gsons.DEFAULT.fromJson(Gsons.DEFAULT.toJson(saved), Settings.class);
        // what load's validation keeps on a 64 bit machine with 16 GiB
        loaded.maximumMemory = Settings.validatedMaximumMemory(loaded.maximumMemory, 16384, true);

        assertEquals(65536, loaded.maximumMemory);
        assertEquals(65536,
                Gsons.DEFAULT.fromJson(Gsons.DEFAULT.toJson(loaded), Settings.class).maximumMemory);
    }
}
