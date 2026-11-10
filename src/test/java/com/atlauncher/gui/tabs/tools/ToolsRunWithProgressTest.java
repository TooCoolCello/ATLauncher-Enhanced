/*
 * ATLauncher - https://github.com/ATLauncher/ATLauncher
 * Copyright (C) 2013-2026 ATLauncher
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
package com.atlauncher.gui.tabs.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Tools tab's deleters run their deleting off the UI thread, behind a progress dialog, and only then say they're
 * done. Run with {@code gradlew uiTest}.
 */
@Tag("ui")
public class ToolsRunWithProgressTest {
    private JFrame owner;

    @BeforeEach
    void createOwner() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A display is required for modal dialog tests");
        SwingUtilities.invokeAndWait(() -> {
            owner = new JFrame("Tools test");
            owner.setSize(320, 160);
            owner.setVisible(true);
        });
    }

    @AfterEach
    void disposeOwner() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (owner != null) {
                owner.dispose();
            }
        });
    }

    @Test
    public void theWorkRunsOffTheUiThreadAndDoneFollowsIt() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());

        SwingUtilities.invokeAndWait(() -> ToolsViewModel.runWithProgress(owner, "Deleter",
            () -> events.add("work on UI thread: " + SwingUtilities.isEventDispatchThread()),
            () -> events.add("done on UI thread: " + SwingUtilities.isEventDispatchThread())));

        assertEquals(2, events.size());
        assertEquals("work on UI thread: false", events.get(0));
        assertEquals("done on UI thread: true", events.get(1));
    }

    @Test
    public void failedWorkClosesTheProgressAndIsntReportedDone() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());

        SwingUtilities.invokeAndWait(() -> ToolsViewModel.runWithProgress(owner, "Deleter", () -> {
            throw new NullPointerException("no folder");
        }, () -> events.add("done")));

        assertFalse(events.contains("done"));
    }
}
