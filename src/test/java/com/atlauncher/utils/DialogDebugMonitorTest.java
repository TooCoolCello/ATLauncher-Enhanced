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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.atlauncher.managers.LogManager;

public class DialogDebugMonitorTest {
    @Test
    void enabledByPropertyOrDebugFlagOnly() {
        boolean originalDebug = LogManager.showDebug;
        try {
            LogManager.showDebug = false;
            System.clearProperty(DialogDebugMonitor.PROPERTY);
            assertFalse(DialogDebugMonitor.isEnabled());

            System.setProperty(DialogDebugMonitor.PROPERTY, "true");
            assertTrue(DialogDebugMonitor.isEnabled());

            System.clearProperty(DialogDebugMonitor.PROPERTY);
            LogManager.showDebug = true;
            assertTrue(DialogDebugMonitor.isEnabled());
        } finally {
            System.clearProperty(DialogDebugMonitor.PROPERTY);
            LogManager.showDebug = originalDebug;
        }
    }

    @Test
    void detectsWindowsOutsideAllScreens() {
        Rectangle primary = new Rectangle(0, 0, 1920, 1080);
        Rectangle secondary = new Rectangle(1920, 0, 1920, 1080);

        assertTrue(DialogDebugMonitor.intersectsAnyScreen(new Rectangle(100, 100, 300, 100),
                Arrays.asList(primary, secondary)));
        assertTrue(DialogDebugMonitor.intersectsAnyScreen(new Rectangle(2000, 100, 300, 100),
                Arrays.asList(primary, secondary)));
        // remembered position from a now-disconnected second monitor
        assertFalse(DialogDebugMonitor.intersectsAnyScreen(new Rectangle(2000, 100, 300, 100),
                Arrays.asList(primary)));
        assertFalse(DialogDebugMonitor.intersectsAnyScreen(new Rectangle(-32000, -32000, 160, 28),
                Arrays.asList(primary)));
    }

    @Test
    void redactsPathsAndUrlsInTitles() {
        assertEquals("", DialogDebugMonitor.sanitizeTitle(null));
        assertEquals("Installing Sodium", DialogDebugMonitor.sanitizeTitle("Installing Sodium"));
        assertEquals("<redacted>", DialogDebugMonitor.sanitizeTitle("C:\\Users\\someone\\mods"));
        assertEquals("<redacted>", DialogDebugMonitor.sanitizeTitle("/home/someone/mods"));
        assertEquals("<redacted>", DialogDebugMonitor.sanitizeTitle("https://example.com/file.jar"));

        String longTitle = new String(new char[200]).replace('\0', 'a');
        assertEquals(83, DialogDebugMonitor.sanitizeTitle(longTitle).length());
    }

    @Test
    void noRecoveryLogForSlowProbeThatWasNeverReported() {
        assertFalse(DialogDebugMonitor.claimRecovery(111L));
    }

    @Test
    void recoveryLoggedOnceOnlyForTheReportedProbe() {
        DialogDebugMonitor.markBlockedReported(222L);

        assertFalse(DialogDebugMonitor.claimRecovery(333L));
        assertTrue(DialogDebugMonitor.claimRecovery(222L));
        assertFalse(DialogDebugMonitor.claimRecovery(222L));
    }

    @Test
    void rateLimitsBlockedReports() {
        long start = TimeUnit.SECONDS.toNanos(1000);

        assertTrue(DialogDebugMonitor.shouldReport(start, Long.MIN_VALUE));
        assertFalse(DialogDebugMonitor.shouldReport(start + TimeUnit.SECONDS.toNanos(10), start));
        assertTrue(DialogDebugMonitor.shouldReport(
                start + TimeUnit.MILLISECONDS.toNanos(DialogDebugMonitor.REPORT_COOLDOWN_MS), start));
    }
}
