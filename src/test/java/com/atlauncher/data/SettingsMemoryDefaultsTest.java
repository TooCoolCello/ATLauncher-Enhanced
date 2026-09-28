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
import com.atlauncher.mclauncher.MCLauncher;

/**
 * A new install starts with the minimum and maximum memory linked at 4 GiB; settings saved before that keep meaning
 * what they did. Nothing here is read from or written to disk.
 */
public class SettingsMemoryDefaultsTest {
    private static final String PARAMS = "-XX:+UseG1GC";

    private static String flags(Settings settings) {
        return String.join(" ", MCLauncher.previewHeapArguments(settings.maximumMemory, settings.initialMemory, null,
            32768, PARAMS).flags);
    }

    @Test
    public void aNewInstallStartsLinkedAt4GiB() {
        Settings settings = Settings.forNewInstall(32768, true);

        assertEquals(4096, settings.maximumMemory);
        assertEquals(4096, settings.initialMemory);
        assertEquals(Boolean.TRUE, settings.memoryLinked);
        assertEquals("-Xms4096M -Xmx4096M", flags(settings));

        // and it's saved and read back as that
        Settings reopened = Gsons.DEFAULT.fromJson(Gsons.DEFAULT.toJson(settings), Settings.class);
        assertEquals(4096, reopened.maximumMemory);
        assertEquals(4096, reopened.initialMemory);
        assertEquals(Boolean.TRUE, reopened.memoryLinked);
    }

    @Test
    public void theDefaultsThemselvesStayAsTheyWere() {
        // what a settings file missing a value is read with
        Settings settings = new Settings();

        assertEquals(4096, settings.maximumMemory);
        assertEquals(0, settings.initialMemory);
        assertNull(settings.memoryLinked);
    }

    @Test
    public void anOlderFileWithNoMinimumOrLinkKeepsAutoAndUnlinked() {
        Settings settings = Gsons.DEFAULT.fromJson("{\"maximumMemory\": 6144, \"firstTimeRun\": false}",
            Settings.class);

        assertEquals(6144, settings.maximumMemory);
        assertEquals(0, settings.initialMemory);
        assertNull(settings.memoryLinked);
        assertEquals("-Xmx6144M", flags(settings));

        // saving it again doesn't add either
        String saved = Gsons.DEFAULT.toJson(settings);
        assertFalse(saved.contains("memoryLinked"), saved);
        assertTrue(saved.contains("\"initialMemory\": 0"), saved);
    }

    @Test
    public void equalValuesWithNoLinkSavedArentTakenAsLinked() {
        Settings settings = Gsons.DEFAULT.fromJson("{\"maximumMemory\": 4096, \"initialMemory\": 4096}",
            Settings.class);

        assertEquals(4096, settings.initialMemory);
        assertNull(settings.memoryLinked);
        assertFalse(Boolean.TRUE.equals(settings.memoryLinked));
    }

    @Test
    public void anUnlinkIsSavedAndReadBack() {
        Settings settings = Settings.forNewInstall(32768, true);
        settings.memoryLinked = false;

        Settings reopened = Gsons.DEFAULT.fromJson(Gsons.DEFAULT.toJson(settings), Settings.class);
        assertEquals(Boolean.FALSE, reopened.memoryLinked);
        assertEquals(4096, reopened.initialMemory);
        assertEquals(4096, reopened.maximumMemory);
    }

    @Test
    public void anOlderInstanceWithNoLinkUsesTheLaunchers() {
        InstanceLauncher launcher = Gsons.DEFAULT.fromJson("{\"maximumMemory\": 8192}", InstanceLauncher.class);

        assertEquals(Integer.valueOf(8192), launcher.maximumMemory);
        assertNull(launcher.initialMemory);
        assertNull(launcher.memoryLinked);
    }

    @Test
    public void lessRamStartsLowerAndStillLinked() {
        // 8 GiB and up: the usual 4 GiB
        assertEquals(4096, Settings.newInstallMemory(65536, true));
        assertEquals(4096, Settings.newInstallMemory(8192, true));
        // less: half, in 512 MB steps, never below 512 MB
        assertEquals(3584, Settings.newInstallMemory(8000, true));
        assertEquals(3072, Settings.newInstallMemory(6144, true));
        assertEquals(2048, Settings.newInstallMemory(4096, true));
        assertEquals(1024, Settings.newInstallMemory(2048, true));
        assertEquals(512, Settings.newInstallMemory(900, true));
        // a 32 bit Java can use at most 1 GiB
        assertEquals(512, Settings.newInstallMemory(32768, false));
        assertEquals(512, Settings.newInstallMemory(null, false));

        Settings small = Settings.forNewInstall(4096, true);
        assertEquals(2048, small.maximumMemory);
        assertEquals(2048, small.initialMemory);
        assertEquals(Boolean.TRUE, small.memoryLinked);
    }

    @Test
    public void unknownRamStartsAtTheUsual4GiB() {
        assertEquals(4096, Settings.newInstallMemory(null, true));
        assertEquals(4096, Settings.newInstallMemory(0, true));
        assertEquals("-Xms4096M -Xmx4096M", flags(Settings.forNewInstall(null, true)));
    }
}
