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
package com.atlauncher.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * RAM detection used by the memory slider and its warnings: a failed detection, including the Java 8 path's 1024 MB
 * placeholder, is unknown rather than taken as this machine's RAM.
 */
public class OSMemoryDetectionTest {
    @Test
    public void beanValueIsReadAsMegabytes() {
        assertEquals(16384, OS.ramFromBeanValue("17179869184"));
        assertEquals(16384, OS.ramFromBeanValue(17179869184L));
        assertEquals(8192, OS.ramFromBeanValue(" 8589934592 "));
    }

    @Test
    public void unreadableBeanValuesAreUnknown() {
        assertEquals(0, OS.ramFromBeanValue(null));
        assertEquals(0, OS.ramFromBeanValue("lots"));
        assertEquals(0, OS.ramFromBeanValue("0"));
        assertEquals(0, OS.ramFromBeanValue("-1"));
        assertEquals(0, OS.ramFromBeanValue("1024"));
    }

    @Test
    public void onlyActuallyDetectedRamIsReported() {
        assertEquals(Integer.valueOf(16384), OS.detectedOrUnknown(16384, true));
        // the Java 8 path's placeholder, or nothing at all
        assertNull(OS.detectedOrUnknown(1024, false));
        assertNull(OS.detectedOrUnknown(0, false));
        assertNull(OS.detectedOrUnknown(0, true));
    }
}
