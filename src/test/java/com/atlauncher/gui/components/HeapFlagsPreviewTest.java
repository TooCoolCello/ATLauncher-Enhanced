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
package com.atlauncher.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.atlauncher.mclauncher.MCLauncher;

/**
 * The greyed-out heap flags shown below the Java parameters come from the same heap arguments a launch builds, with
 * a note wherever they aren't simply the settings.
 */
public class HeapFlagsPreviewTest {
    private static final int RAM = 16384;
    private static final String PARAMS = "-XX:+UseG1GC";

    @Test
    public void launcherSettingsShowTheConfiguredFlagsAndThatInstancesMayDiffer() {
        MCLauncher.HeapPreview noMinimum = MCLauncher.previewHeapArguments(4096, 0, null, RAM, PARAMS);
        assertEquals("-Xmx4096M", HeapFlagsPreview.flagsText(noMinimum));
        assertTrue(HeapFlagsPreview.note(noMinimum, 4096).contains("instance"));

        MCLauncher.HeapPreview withMinimum = MCLauncher.previewHeapArguments(4096, 1024, null, RAM, PARAMS);
        assertEquals("-Xms1024M -Xmx4096M", HeapFlagsPreview.flagsText(withMinimum));
    }

    @Test
    public void initialHeapInTheJavaParametersSuppressesTheGeneratedOne() {
        for (String params : new String[] { "-Xms2G", "-XX:+UseG1GC -XX:InitialHeapSize=1073741824" }) {
            MCLauncher.HeapPreview preview = MCLauncher.previewHeapArguments(4096, 1024, 0, RAM, params);

            assertEquals("-Xmx4096M", HeapFlagsPreview.flagsText(preview), params);
            assertTrue(preview.initialHeapFromParameters);
            assertTrue(HeapFlagsPreview.note(preview, 4096).contains("Java parameters"), params);
        }
    }

    @Test
    public void instancePreviewShowsWhatItLaunchesWithIncludingThePacksRaise() {
        // the pack needs 6 GiB and there's room: -Xmx is raised, and the note says from what
        MCLauncher.HeapPreview raised = MCLauncher.previewHeapArguments(2048, 1024, 6144, RAM, PARAMS);
        assertEquals("-Xms1024M -Xmx6144M", HeapFlagsPreview.flagsText(raised));
        assertTrue(raised.raisedByPack);
        assertTrue(HeapFlagsPreview.note(raised, 2048).contains("2048"));

        // not enough room: it isn't raised, as at launch
        MCLauncher.HeapPreview notRaised = MCLauncher.previewHeapArguments(2048, 0, 6144, 8192, PARAMS);
        assertEquals("-Xmx2048M", HeapFlagsPreview.flagsText(notRaised));
        assertFalse(notRaised.raisedByPack);

        // the settings exactly: nothing to add
        assertNull(HeapFlagsPreview.note(MCLauncher.previewHeapArguments(4096, 1024, 2048, RAM, PARAMS), 4096));
    }

    @Test
    public void inheritedAndOwnInstanceMinimumsPreviewLikeTheyLaunch() {
        // the instance tab shows inherited values in its controls; resolved the same way a launch resolves them
        assertEquals("-Xms2048M -Xmx4096M", HeapFlagsPreview.flagsText(MCLauncher.previewHeapArguments(4096,
                MCLauncher.resolveInitialMemory(null, 2048), 0, RAM, PARAMS)));
        assertEquals("-Xmx4096M", HeapFlagsPreview.flagsText(MCLauncher.previewHeapArguments(4096,
                MCLauncher.resolveInitialMemory(0, 2048), 0, RAM, PARAMS)));
    }

    @Test
    public void savedMinimumAboveTheLaunchedMaximumIsShownAsLaunchedAndExplained() {
        // e.g. an instance with its own lower maximum, inheriting a higher launcher minimum
        MCLauncher.HeapPreview capped = MCLauncher.previewHeapArguments(2048, 4096, 0, RAM, PARAMS);
        assertEquals("-Xms2048M -Xmx2048M", HeapFlagsPreview.flagsText(capped));
        assertTrue(capped.initialCapped);
        assertEquals(4096, capped.savedInitialMemory);
        assertTrue(HeapFlagsPreview.note(capped, 2048).contains("4096M"));

        // the pack's raise makes room for it, so nothing is capped
        MCLauncher.HeapPreview roomy = MCLauncher.previewHeapArguments(2048, 4096, 6144, RAM, PARAMS);
        assertEquals("-Xms4096M -Xmx6144M", HeapFlagsPreview.flagsText(roomy));
        assertFalse(roomy.initialCapped);

        // an initial heap from the Java parameters isn't generated, so isn't capped either
        assertFalse(MCLauncher.previewHeapArguments(2048, 4096, 0, RAM, "-Xms1G").initialCapped);
    }

    @Test
    public void maximumAboveDetectedRamIsLaunchedAsSaved() {
        // no launch-time cap: the flag shows the saved value, and the slider's warning explains it
        assertEquals("-Xmx65536M", HeapFlagsPreview.flagsText(MCLauncher.previewHeapArguments(65536, 0, 0, RAM,
                PARAMS)));
    }

    @Test
    public void flagsGoIntoTheJavaParametersBoxWithoutChangingThem() {
        JavaParametersEditor editor = new JavaParametersEditor();
        editor.setText(PARAMS);
        HeapFlagsPreview component = new HeapFlagsPreview(editor);

        // the launcher's settings: the note says instances can differ
        component.show(MCLauncher.previewHeapArguments(4096, 1024, null, RAM, PARAMS), 4096);
        assertEquals("-Xms1024M -Xmx4096M", editor.getGeneratedFlags());
        assertEquals(PARAMS, editor.getText());
        assertTrue(component.isVisible());
        assertFalse(component.note.isEnabled());

        // nothing to explain: no note at all
        component.show(MCLauncher.previewHeapArguments(4096, 0, 0, RAM, PARAMS), 4096);
        assertEquals("-Xmx4096M", editor.getGeneratedFlags());
        assertEquals(PARAMS, editor.getText());
        assertFalse(component.isVisible());
    }

    @Test
    public void aLinkedMinimumAPackRaisesPastIsExplained() {
        // linked at 4 GiB, the pack needs 6 GiB: only -Xmx is raised at launch
        MCLauncher.HeapPreview raised = MCLauncher.previewHeapArguments(4096, 4096, 6144, RAM, PARAMS);
        assertEquals("-Xms4096M -Xmx6144M", HeapFlagsPreview.flagsText(raised));
        assertTrue(HeapFlagsPreview.linkedMinimumStays(raised));
        assertTrue(HeapFlagsPreview.note(raised, 4096, true).contains("-Xms stays at 4096M"));
        assertFalse(HeapFlagsPreview.note(raised, 4096, false).contains("linked"));

        // a custom initial heap in the parameters comes first, as before: nothing linked about it
        MCLauncher.HeapPreview custom = MCLauncher.previewHeapArguments(4096, 4096, 6144, RAM, "-Xms2G");
        assertEquals("-Xmx6144M", HeapFlagsPreview.flagsText(custom));
        assertFalse(HeapFlagsPreview.linkedMinimumStays(custom));
        assertFalse(HeapFlagsPreview.note(custom, 4096, true).contains("linked"));

        // no raise: linked is just what launches
        MCLauncher.HeapPreview same = MCLauncher.previewHeapArguments(4096, 4096, 2048, RAM, PARAMS);
        assertFalse(HeapFlagsPreview.linkedMinimumStays(same));
        assertNull(HeapFlagsPreview.note(same, 4096, true));
    }
}
