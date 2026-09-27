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
package com.atlauncher.mclauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.atlauncher.constants.Constants;

/**
 * The JVM heap arguments: -Xmx exactly as before, and -Xms only when a minimum is set, capped at -Xmx and never
 * alongside an initial heap the Java parameters already set.
 */
public class HeapArgumentsTest {
    private static final int SYSTEM_RAM = 16384;

    @Test
    public void unsetMinimumKeepsTheOldArguments() {
        assertEquals(Collections.singletonList("-Xmx4096M"),
                MCLauncher.heapArguments(4096, 0, 0, SYSTEM_RAM, Constants.DEFAULT_JAVA_PARAMETERS));

        // the pack's required memory still raises -Xmx when there's room, and doesn't when there isn't
        assertEquals(Collections.singletonList("-Xmx6144M"),
                MCLauncher.heapArguments(2048, 0, 6144, SYSTEM_RAM, Constants.DEFAULT_JAVA_PARAMETERS));
        assertEquals(Collections.singletonList("-Xmx2048M"),
                MCLauncher.heapArguments(2048, 0, 6144, 8192, Constants.DEFAULT_JAVA_PARAMETERS));
        assertEquals(Collections.singletonList("-Xmx2048M"),
                MCLauncher.heapArguments(2048, 0, 6144, 0, Constants.DEFAULT_JAVA_PARAMETERS));
    }

    @Test
    public void setMinimumComesFirstInMegabytes() {
        assertEquals(Arrays.asList("-Xms1024M", "-Xmx4096M"),
                MCLauncher.heapArguments(4096, 1024, 0, SYSTEM_RAM, Constants.DEFAULT_JAVA_PARAMETERS));
        assertEquals(Arrays.asList("-Xms4096M", "-Xmx4096M"),
                MCLauncher.heapArguments(4096, 4096, 0, SYSTEM_RAM, ""));
    }

    @Test
    public void minimumIsNeverMoreThanTheActualMaximum() {
        assertEquals(Arrays.asList("-Xms4096M", "-Xmx4096M"),
                MCLauncher.heapArguments(4096, 8192, 0, SYSTEM_RAM, null));

        // measured against the -Xmx actually passed, including when the pack's requirement raised it
        assertEquals(Arrays.asList("-Xms5120M", "-Xmx6144M"),
                MCLauncher.heapArguments(2048, 5120, 6144, SYSTEM_RAM, null));
        assertEquals(Arrays.asList("-Xms2048M", "-Xmx2048M"),
                MCLauncher.heapArguments(2048, 5120, 6144, 8192, null));
    }

    @Test
    public void initialHeapInTheJavaParametersIsNotDuplicated() {
        for (String params : Arrays.asList("-Xms512M", "-XX:+UseG1GC -Xms2G -Dfoo=bar",
                "-XX:InitialHeapSize=1073741824")) {
            assertTrue(MCLauncher.setsInitialHeap(params), params);
            assertEquals(Collections.singletonList("-Xmx4096M"),
                    MCLauncher.heapArguments(4096, 1024, 0, SYSTEM_RAM, params), params);
        }

        for (String params : Arrays.asList(null, "", Constants.DEFAULT_JAVA_PARAMETERS, "-Xss2M -XX:+UseG1GC")) {
            assertFalse(MCLauncher.setsInitialHeap(params), String.valueOf(params));
        }
    }

    @Test
    public void instanceSettingWinsOverTheLauncherSetting() {
        assertEquals(2048, MCLauncher.resolveInitialMemory(null, 2048));
        assertEquals(1024, MCLauncher.resolveInitialMemory(1024, 2048));
        // an instance set to 0 leaves it to Java even when the launcher has a minimum
        assertEquals(0, MCLauncher.resolveInitialMemory(0, 2048));
        assertEquals(Collections.singletonList("-Xmx4096M"), MCLauncher.heapArguments(4096,
                MCLauncher.resolveInitialMemory(0, 2048), 0, SYSTEM_RAM, null));
    }
}
