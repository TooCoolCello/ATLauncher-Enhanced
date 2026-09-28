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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import com.formdev.flatlaf.extras.FlatSVGIcon;

public class MemoryRangeSliderTest {
    private static final int RAM = 32768;

    // as the hints write MB amounts (with the locale's digit grouping, like the spinners)
    private static String mb(int value) {
        return MessageFormat.format("{0}", value);
    }

    // as the Java settings build the memory spinners
    private static JSpinner spinner(int value, int lowest, int totalRam) {
        SpinnerNumberModel model = new SpinnerNumberModel(value, null, null, 512);
        model.setMinimum(lowest);
        model.setMaximum(totalRam);
        return new JSpinner(model);
    }

    private static final class Fixture {
        final JSpinner minimum;
        final JSpinner maximum;
        final MemoryRangeSlider slider;

        Fixture(int minimumValue, int maximumValue, int totalRam, int width) {
            minimum = spinner(minimumValue, 0, totalRam);
            maximum = spinner(maximumValue, 512, totalRam);
            slider = MemoryRangeSlider.forSpinners(minimum, maximum, totalRam);
            layOut(width);
        }

        void layOut(int width) {
            slider.setSize(width, slider.getPreferredSize().height);
            layOut(slider);
        }

        private void layOut(java.awt.Container container) {
            container.doLayout();
            for (java.awt.Component child : container.getComponents()) {
                if (child instanceof java.awt.Container) {
                    layOut((java.awt.Container) child);
                }
            }
        }

        int x(int value) {
            return slider.track.xFor(value);
        }

        int minimum() {
            return (Integer) minimum.getValue();
        }

        int maximum() {
            return (Integer) maximum.getValue();
        }

        void drag(int from, int... to) {
            slider.track.press(from);
            for (int x : to) {
                slider.track.drag(x);
            }
            slider.track.release();
        }

        void key(JComponent thumb, int keyCode) {
            Object name = thumb.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(keyCode, 0));
            thumb.getActionMap().get(name).actionPerformed(null);
        }
    }

    // the minimum is set later when the maximum is typed while linked
    private static void flushEventQueue() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    @Test
    public void runsFromAutoToTheDetectedRamAndFollowsTheSpinners() {
        Fixture f = new Fixture(1024, 4096, RAM, 500);

        assertEquals(24576, f.slider.scale());
        assertEquals(24576, f.slider.usableEnd());
        assertEquals(1024, f.slider.minimum);
        assertEquals(4096, f.slider.maximum);
        assertEquals(f.x(0), f.slider.track.xFor(-1));
        assertTrue(f.x(1024) < f.x(4096));

        f.maximum.setValue(10240);
        f.minimum.setValue(2048);
        assertEquals(10240, f.slider.maximum);
        assertEquals(2048, f.slider.minimum);
        assertTrue(f.slider.maximumEntry.button.getText().contains("10"));
        assertTrue(f.slider.minimumEntry.button.getText().contains("2"));
    }

    @Test
    public void zeroIsAuto() {
        Fixture f = new Fixture(0, 4096, RAM, 500);

        assertEquals(MemoryRangeSlider.amount(0), "Auto");
        assertTrue(f.slider.minimumEntry.button.getText().contains("Auto"));

        // dragging the minimum to the far left sets 0, so no -Xms is added
        f.minimum.setValue(2048);
        f.drag(f.x(2048), f.x(2048) - 1, -50);
        assertEquals(0, f.minimum());
        assertEquals(4096, f.maximum());

        // and Home on its thumb does the same
        f.minimum.setValue(2048);
        f.key(f.slider.minimumThumb, KeyEvent.VK_HOME);
        assertEquals(0, f.minimum());
    }

    @Test
    public void undetectedRamLeavesTheSpinnersOnTheirOwn() {
        JSpinner minimum = spinner(0, 0, RAM);
        JSpinner maximum = spinner(4096, 512, RAM);

        assertNull(MemoryRangeSlider.forSpinners(minimum, maximum, null));
        assertNull(MemoryRangeSlider.forSpinners(minimum, maximum, 0));
        assertNull(MemoryRangeSlider.forSpinners(minimum, maximum, 256));
        assertEquals(4096, maximum.getValue());
        assertEquals(0, minimum.getValue());
    }

    @Test
    public void markersAreFixedOnEachScale() {
        assertEquals(Arrays.asList(4096, 8192, 12288, 16384, 20480, 24576), MemoryRangeSlider.marks(24576));
        assertEquals(Arrays.asList(4096, 8192, 12288, 16384, 20480, 24576, 32768, 49152),
            MemoryRangeSlider.marks(49152));

        // those a thumb can reach stop at the usable end
        assertEquals(Arrays.asList(4096, 8192), MemoryRangeSlider.quickPoints(24576, 12000));
        assertEquals(Collections.emptyList(), MemoryRangeSlider.quickPoints(24576, 3072));
        assertEquals(Arrays.asList(4096, 8192, 12288, 16384, 20480, 24576, 32768),
            MemoryRangeSlider.quickPoints(49152, 40000));
    }

    // how far along the track, from 0 to 1
    private static double along(Fixture f, int value) {
        return (f.x(value) - f.x(0)) / (double) (f.x(f.slider.scale()) - f.x(0));
    }

    @Test
    public void markersSitAtTheSamePlacesWhateverTheRam() {
        for (int ram : new int[] { 8192, 16301, 24576, 32768, 131072 }) {
            Fixture f = new Fixture(0, 4096, ram, 500);
            for (int mark : MemoryRangeSlider.marks(24576)) {
                assertEquals(mark / 24576.0, along(f, mark), 0.01, ram + " MB, " + mark);
            }
        }

        for (int ram : new int[] { 32768, 65536, 131072 }) {
            Fixture f = new Fixture(0, 4096, ram, 500);
            f.slider.expand.doClick(0);
            f.layOut(500);
            for (int mark : MemoryRangeSlider.marks(49152)) {
                assertEquals(mark / 49152.0, along(f, mark), 0.01, ram + " MB, " + mark);
            }
        }
    }

    @Test
    public void positionsSnapToNearbyMarkersAndOtherwiseTo512MbSteps() {
        // on the normal scale a marker pulls from about 600 MB either side
        assertEquals(4096, MemoryRangeSlider.snap(4600, 24576, 24576));
        assertEquals(8192, MemoryRangeSlider.snap(7700, 24576, 24576));
        assertEquals(20480, MemoryRangeSlider.snap(20000, 24576, 24576));
        // away from them, any 512 MB step is still reachable
        assertEquals(6144, MemoryRangeSlider.snap(6000, 24576, 24576));
        assertEquals(3072, MemoryRangeSlider.snap(3000, 24576, 24576));
        assertEquals(0, MemoryRangeSlider.snap(200, 24576, 24576));

        // on the expanded scale, 32 and 48 GiB too, with a longer pull
        assertEquals(32768, MemoryRangeSlider.snap(33500, 49152, 49152));
        assertEquals(28672, MemoryRangeSlider.snap(28700, 49152, 49152));

        // never past the usable end, which is itself a position when it's nearer than the last step
        assertEquals(16301, MemoryRangeSlider.snap(16250, 24576, 16301));
        assertEquals(15872, MemoryRangeSlider.snap(15950, 24576, 16301));
        // and a marker past it doesn't pull
        assertEquals(16301, MemoryRangeSlider.snap(16384, 24576, 16301));
    }

    @Test
    public void staysWithinTheTrackAndTheMinimumNeverPassesTheMaximum() {
        // RAM that isn't a whole step: the track ends at exactly that RAM
        Fixture f = new Fixture(0, 4096, 16301, 500);
        assertEquals(16301, f.slider.usableEnd());

        f.drag(f.x(4096), 10000);
        assertEquals(16301, f.maximum());

        // the maximum stops at the minimum, and never goes below 512
        f.minimum.setValue(6144);
        f.drag(f.x(16301), f.x(16301) - 1, f.x(1024));
        assertEquals(6144, f.maximum());
        f.minimum.setValue(0);
        f.drag(f.x(6144), f.x(6144) - 1, -50);
        assertEquals(512, f.maximum());

        // the minimum stops at the maximum
        f.maximum.setValue(8192);
        f.drag(f.x(0), f.x(0) + 1, 10000);
        assertEquals(8192, f.minimum());
        assertEquals(8192, f.maximum());
    }

    @Test
    public void overlappingThumbsSplitByTheDragsDirection() {
        Fixture f = new Fixture(8192, 8192, RAM, 500);
        int both = f.x(8192);

        // left moves the minimum away
        f.drag(both, both - 1, f.x(4096));
        assertEquals(4096, f.minimum());
        assertEquals(8192, f.maximum());

        // right moves the maximum away
        f.minimum.setValue(8192);
        f.drag(both, both + 1, f.x(12288));
        assertEquals(8192, f.minimum());
        assertEquals(12288, f.maximum());

        // thumbs merely close together split the same way
        f.minimum.setValue(8192);
        f.maximum.setValue(8704);
        f.drag(f.x(8448), f.x(8448) - 1, f.x(4096));
        assertEquals(4096, f.minimum());
        assertEquals(8704, f.maximum());
    }

    @Test
    public void theThumbAPressPicksKeepsMovingUntilRelease() {
        Fixture f = new Fixture(8192, 8192, RAM, 500);
        int both = f.x(8192);

        // picked the minimum by going left, then turned back right past where it started
        f.drag(both, both - 5, both + 80);
        assertEquals(8192, f.minimum());
        assertEquals(8192, f.maximum());

        // no movement picks nothing and changes nothing
        f.drag(both);
        assertEquals(8192, f.minimum());
        assertEquals(8192, f.maximum());
    }

    @Test
    public void aDragOnlySetsTheSpinnersOnRelease() {
        Fixture f = new Fixture(0, 4096, RAM, 500);

        f.slider.track.press(f.x(4096));
        f.slider.track.drag(f.x(20480));
        assertEquals(4096, f.maximum());
        assertEquals(20480, f.slider.maximum);
        assertTrue(f.slider.maximumEntry.button.getText().contains("20"));

        f.slider.track.release();
        assertEquals(20480, f.maximum());
    }

    @Test
    public void pressingTheTrackMovesTheNearerThumbThere() {
        Fixture f = new Fixture(2048, 16384, RAM, 500);

        f.drag(f.x(24576));
        assertEquals(24576, f.maximum());
        assertEquals(2048, f.minimum());

        f.drag(f.x(0));
        assertEquals(0, f.minimum());
    }

    @Test
    public void linkingIsOffByDefaultAndSetsTheMinimumToTheMaximum() {
        Fixture f = new Fixture(1024, 8192, RAM, 500);

        assertFalse(f.slider.link.isSelected());
        assertEquals("Link minimum and maximum", f.slider.link.getToolTipText());
        assertEquals("Link minimum and maximum", f.slider.link.getAccessibleContext().getAccessibleName());

        f.slider.link.doClick(0);
        assertTrue(f.slider.link.isSelected());
        assertEquals(8192, f.minimum());
        assertEquals(8192, f.maximum());
        assertEquals("Unlink minimum and maximum", f.slider.link.getToolTipText());
        assertEquals("Unlink minimum and maximum", f.slider.link.getAccessibleContext().getAccessibleName());
        assertNotEquals(f.slider.link.getIcon(), f.slider.link.getSelectedIcon());
    }

    @Test
    public void whileLinkedEitherThumbOrSpinnerMovesBoth() throws Exception {
        Fixture f = new Fixture(1024, 8192, RAM, 500);
        f.slider.link.doClick(0);

        f.drag(f.x(8192), f.x(8192) + 1, f.x(12288));
        assertEquals(12288, f.minimum());
        assertEquals(12288, f.maximum());

        f.drag(f.x(12288), f.x(12288) - 1, f.x(4096));
        assertEquals(4096, f.minimum());
        assertEquals(4096, f.maximum());

        f.minimum.setValue(6144);
        assertEquals(6144, f.maximum());

        f.maximum.setValue(10240);
        flushEventQueue();
        assertEquals(10240, f.minimum());

        f.key(f.slider.minimumThumb, KeyEvent.VK_RIGHT);
        assertEquals(10752, f.minimum());
        assertEquals(10752, f.maximum());

        // linked, the lowest is the lowest maximum: Auto can't be matched
        f.key(f.slider.maximumThumb, KeyEvent.VK_HOME);
        assertEquals(512, f.minimum());
        assertEquals(512, f.maximum());
    }

    @Test
    public void unlinkingKeepsTheValuesAndSplitsAgain() {
        Fixture f = new Fixture(1024, 8192, RAM, 500);
        f.slider.link.doClick(0);
        f.slider.link.doClick(0);

        assertFalse(f.slider.link.isSelected());
        assertEquals(8192, f.minimum());
        assertEquals(8192, f.maximum());

        f.drag(f.x(8192), f.x(8192) - 1, f.x(4096));
        assertEquals(4096, f.minimum());
        assertEquals(8192, f.maximum());
    }

    @Test
    public void autoInTheMinimumSpinnerEndsTheLink() {
        Fixture f = new Fixture(1024, 8192, RAM, 500);
        f.slider.link.doClick(0);

        f.minimum.setValue(0);
        assertFalse(f.slider.link.isSelected());
        assertEquals(0, f.minimum());
        assertEquals(8192, f.maximum());
    }

    @Test
    public void eachThumbHasItsOwnNameAndKeys() {
        Fixture f = new Fixture(1024, 8192, RAM, 500);

        // reachable by screen readers: every level down to them is Accessible
        assertTrue(f.slider.track instanceof javax.accessibility.Accessible);
        assertEquals(2, f.slider.track.getAccessibleContext().getAccessibleChildrenCount());
        for (JComponent thumb : Arrays.asList(f.slider.minimumThumb, f.slider.maximumThumb)) {
            assertTrue(thumb instanceof javax.accessibility.Accessible);
            assertTrue(thumb.isFocusable());
            assertEquals(AccessibleRole.SLIDER, thumb.getAccessibleContext().getAccessibleRole());
        }
        assertTrue(f.slider.minimumThumb.getAccessibleContext().getAccessibleName().contains("Minimum"));
        assertTrue(f.slider.maximumThumb.getAccessibleContext().getAccessibleName().contains("Maximum"));
    }

    @Test
    public void keysMoveOneThumbAndSetItsSpinnerStraightAway() {
        Fixture f = new Fixture(1024, 8192, RAM, 500);

        f.key(f.slider.maximumThumb, KeyEvent.VK_RIGHT);
        assertEquals(8704, f.maximum());
        f.key(f.slider.maximumThumb, KeyEvent.VK_DOWN);
        assertEquals(8192, f.maximum());
        f.key(f.slider.maximumThumb, KeyEvent.VK_PAGE_UP);
        assertEquals(12288, f.maximum());
        f.key(f.slider.maximumThumb, KeyEvent.VK_PAGE_DOWN);
        assertEquals(8192, f.maximum());
        // the end of the track as shown: 24 GiB here, until it's expanded
        f.key(f.slider.maximumThumb, KeyEvent.VK_END);
        assertEquals(24576, f.maximum());
        assertEquals(1024, f.minimum());

        f.key(f.slider.minimumThumb, KeyEvent.VK_UP);
        assertEquals(1536, f.minimum());
        f.key(f.slider.minimumThumb, KeyEvent.VK_LEFT);
        f.key(f.slider.minimumThumb, KeyEvent.VK_LEFT);
        f.key(f.slider.minimumThumb, KeyEvent.VK_LEFT);
        assertEquals(0, f.minimum());

        // an exact value steps to the next 512 MB step
        f.maximum.setValue(5000);
        f.key(f.slider.maximumThumb, KeyEvent.VK_RIGHT);
        assertEquals(5120, f.maximum());

        // the minimum's keys stop at the maximum
        f.key(f.slider.minimumThumb, KeyEvent.VK_END);
        assertEquals(5120, f.minimum());
        f.key(f.slider.minimumThumb, KeyEvent.VK_UP);
        assertEquals(5120, f.minimum());
    }

    @Test
    public void clickingAValueOpensAnExactEditorThatChangesNothingOnItsOwn() {
        Fixture f = new Fixture(1024, 8192, RAM, 500);
        MemoryRangeSlider.ValueEntry entry = f.slider.maximumEntry;

        assertFalse(entry.isEditing());
        entry.button.doClick(0);
        assertTrue(entry.isEditing());
        assertEquals(8192, entry.exact.getValue());
        assertEquals(8192, f.maximum());

        // Enter closes it
        entry.field.postActionEvent();
        assertFalse(entry.isEditing());
        assertEquals(8192, f.maximum());
        assertEquals(1024, f.minimum());
    }

    @Test
    public void exactValuesMoveTheThumbsAndSpinnersByTheSameRules() {
        Fixture f = new Fixture(2048, 8192, RAM, 500);

        // any exact MB, not just 512 MB steps
        f.slider.maximumEntry.button.doClick(0);
        f.slider.maximumEntry.exact.setValue(6000);
        assertEquals(6000, f.maximum());
        assertEquals(6000, f.slider.maximum);
        assertTrue(f.slider.maximumEntry.button.getText().contains(mb(6000) + " MB"));

        // the maximum stops at the minimum, as its thumb does, and the editor shows where it went
        f.slider.maximumEntry.exact.setValue(1024);
        assertEquals(2048, f.maximum());
        assertEquals(2048, f.slider.maximumEntry.exact.getValue());

        // and the minimum at the maximum
        f.slider.minimumEntry.button.doClick(0);
        f.slider.minimumEntry.exact.setValue(4096);
        assertEquals(2048, f.minimum());

        // bounded like the track
        javax.swing.SpinnerNumberModel minimumModel = (javax.swing.SpinnerNumberModel) f.slider.minimumEntry.exact
            .getModel();
        javax.swing.SpinnerNumberModel maximumModel = (javax.swing.SpinnerNumberModel) f.slider.maximumEntry.exact
            .getModel();
        assertEquals(0, minimumModel.getMinimum());
        assertEquals(512, maximumModel.getMinimum());
        assertEquals(RAM, maximumModel.getMaximum());
    }

    @Test
    public void typedTextIsOnlyTakenOnceItsEntered() throws Exception {
        Fixture f = new Fixture(1024, 8192, RAM, 500);
        MemoryRangeSlider.ValueEntry entry = f.slider.maximumEntry;

        entry.button.doClick(0);
        entry.field.setText("12288");
        assertEquals(8192, f.maximum());

        // Escape drops it
        entry.field.getActionMap().get("cancelExactly").actionPerformed(null);
        assertFalse(entry.isEditing());
        assertEquals(8192, f.maximum());

        // entering it takes it
        entry.button.doClick(0);
        entry.field.setText("12288");
        entry.field.commitEdit();
        entry.field.postActionEvent();
        assertEquals(12288, f.maximum());
        assertFalse(entry.isEditing());

        // out of range isn't taken
        entry.button.doClick(0);
        entry.field.setText("99999");
        entry.commitTyped();
        assertEquals(12288, f.maximum());
    }

    @Test
    public void exactValuesWhileLinkedMoveBoth() {
        Fixture f = new Fixture(1024, 8192, RAM, 500);
        f.slider.link.doClick(0);

        f.slider.maximumEntry.button.doClick(0);
        f.slider.maximumEntry.exact.setValue(10000);
        assertEquals(10000, f.minimum());
        assertEquals(10000, f.maximum());

        f.slider.minimumEntry.button.doClick(0);
        f.slider.minimumEntry.exact.setValue(3000);
        assertEquals(3000, f.minimum());
        assertEquals(3000, f.maximum());
        assertTrue(f.slider.link.isSelected());
    }

    @Test
    public void theMinimumCanAlwaysGoBackToAuto() {
        Fixture f = new Fixture(1024, 8192, RAM, 500);
        f.slider.link.doClick(0);

        // its Auto button, even while linked: that ends the link
        f.slider.minimumEntry.button.doClick(0);
        f.slider.minimumEntry.auto.doClick(0);
        assertEquals(0, f.minimum());
        assertEquals(8192, f.maximum());
        assertFalse(f.slider.link.isSelected());
        assertFalse(f.slider.minimumEntry.isEditing());
        assertTrue(f.slider.minimumEntry.button.getText().contains("Auto"));

        // or typing 0
        f.minimum.setValue(2048);
        f.slider.link.doClick(0);
        f.slider.minimumEntry.button.doClick(0);
        f.slider.minimumEntry.exact.setValue(0);
        assertEquals(0, f.minimum());
        assertEquals(8192, f.maximum());
        assertFalse(f.slider.link.isSelected());

        // the maximum has no Auto
        assertFalse(f.slider.maximumEntry.auto.isDisplayable() || f.slider.maximumEntry.auto.getParent() != null);
    }

    @Test
    public void anExactEditorForASavedValueAboveRamKeepsItUntilChanged() {
        Fixture f = new Fixture(0, 65536, 16384, 500);
        MemoryRangeSlider.ValueEntry entry = f.slider.maximumEntry;

        entry.button.doClick(0);
        assertEquals(65536, entry.exact.getValue());
        entry.commitTyped();
        entry.field.postActionEvent();
        assertEquals(65536, f.maximum());
        assertTrue(f.slider.isWarning());
    }

    @Test
    public void savedValueAboveThisMachinesRamIsKeptAndWarnedAbout() {
        // settings from a machine with more RAM
        Fixture f = new Fixture(0, 65536, 16384, 500);

        assertEquals(65536, f.maximum());
        assertEquals(f.x(24576), f.x(65536));
        assertTrue(f.slider.maximumThumb.isOffScale());
        assertTrue(f.slider.isWarning());
        assertTrue(f.slider.hint.getText().contains(mb(65536)));
        assertTrue(f.slider.hint.getText().contains(mb(16384)));

        // a click on its thumb, or keys that can't lower it, change nothing
        f.drag(f.x(65536));
        f.key(f.slider.maximumThumb, KeyEvent.VK_RIGHT);
        f.key(f.slider.maximumThumb, KeyEvent.VK_END);
        assertEquals(65536, f.maximum());

        // lowering it does
        f.key(f.slider.maximumThumb, KeyEvent.VK_LEFT);
        assertEquals(16384, f.maximum());
        assertFalse(f.slider.isWarning());
    }

    @Test
    public void hintsSayMemoryIsNeededForEverythingElse() {
        String detected = MemoryRangeSlider.hint(4096, 16384);
        assertTrue(detected.contains(mb(16384)));
        assertTrue(detected.contains("system"));

        String little = MemoryRangeSlider.hint(16384, 16384);
        assertTrue(little.contains("little"));
        assertFalse(little.equals(detected));
    }

    @Test
    public void linkIconsExist() {
        MemoryRangeSlider slider = new Fixture(0, 4096, RAM, 500).slider;

        assertTrue(((FlatSVGIcon) slider.link.getIcon()).hasFound());
        assertTrue(((FlatSVGIcon) slider.link.getSelectedIcon()).hasFound());
    }

    private static void assertLabelsFit(Fixture f, int width) {
        FontMetrics metrics = f.slider.track.getFontMetrics(f.slider.track.getFont());
        List<Integer> labelled = f.slider.track.labelledMarks(metrics);

        // Auto and the scale's end always, and every label whole and apart
        assertTrue(labelled.contains(0), width + ": " + labelled);
        assertTrue(labelled.contains(f.slider.scale()), width + ": " + labelled);
        int previousEnd = Integer.MIN_VALUE;
        for (int mark : labelled) {
            int x = f.slider.track.labelX(mark, metrics);
            int labelWidth = metrics.stringWidth(mark == 0 ? "Auto" : (mark / 1024) + " GiB");
            assertTrue(x >= 0 && x + labelWidth <= f.slider.track.getWidth(), width + ": " + mark + " cut off");
            assertTrue(x > previousEnd, width + ": " + mark + " overlaps");
            previousEnd = x + labelWidth;
        }
    }

    @Test
    public void labelsStayWholeAndApartAtAnyWidthAndDroppedOnesKeepTooltips() {
        for (int width : new int[] { 600, 360, 250 }) {
            Fixture normal = new Fixture(0, 4096, 65536, width);
            assertLabelsFit(normal, width);

            Fixture expanded = new Fixture(0, 4096, 65536, width);
            expanded.slider.expand.doClick(0);
            expanded.layOut(width);
            assertLabelsFit(expanded, width);
        }

        Fixture wide = new Fixture(0, 4096, 16384, 600);
        FontMetrics metrics = wide.slider.track.getFontMetrics(wide.slider.track.getFont());
        assertEquals(Arrays.asList(0, 4096, 8192, 12288, 16384, 20480, 24576),
            wide.slider.track.labelledMarks(metrics));

        // narrow and expanded: some are dropped, but their ticks still have tooltips
        Fixture narrow = new Fixture(0, 40960, 65536, 250);
        narrow.slider.expand.doClick(0);
        narrow.layOut(250);
        List<Integer> labelled = narrow.slider.track.labelledMarks(metrics);
        Integer dropped = null;
        for (int mark : MemoryRangeSlider.marks(49152)) {
            if (!labelled.contains(mark) && mark > 4096) {
                dropped = mark;
            }
        }
        assertTrue(dropped != null, labelled.toString());
        MouseEvent atDropped = new MouseEvent(narrow.slider.track, MouseEvent.MOUSE_MOVED, 0, 0, narrow.x(dropped),
            20, 0, false);
        assertEquals((dropped / 1024) + " GiB (" + mb(dropped) + " MB)", narrow.slider.track.getToolTipText(atDropped));
    }

    @Test
    public void asksForAModestWidthAndWrapsItsHintInstead() {
        Fixture f = new Fixture(0, 30000, RAM, 600);

        // it fills whatever its column gives it, but never asks for more than the Java parameters box it sits above
        int javaParametersWidth = new javax.swing.JScrollPane(new JavaParametersEditor()).getPreferredSize().width;
        assertTrue(f.slider.getPreferredSize().width <= javaParametersWidth,
            f.slider.getPreferredSize() + " within " + javaParametersWidth);

        f.slider.hint.setSize(600, 1);
        int wideHeight = f.slider.hint.getPreferredSize().height;
        f.slider.hint.setSize(150, 1);
        int narrowHeight = f.slider.hint.getPreferredSize().height;
        assertTrue(narrowHeight > wideHeight, narrowHeight + " > " + wideHeight);
    }

    @Test
    public void theNormalScaleIs24GiBForEveryone() {
        for (int ram : new int[] { 8192, 16301, 24576, 65536 }) {
            Fixture f = new Fixture(0, 4096, ram, 500);
            assertEquals(24576, f.slider.scale(), ram + " MB");
            assertFalse(f.slider.expanded, ram + " MB");
        }
    }

    @Test
    public void expandShowsOnlyWithRamPast24GiB() {
        assertFalse(new Fixture(0, 4096, 16384, 500).slider.expand.isVisible());
        assertFalse(new Fixture(0, 4096, 24576, 500).slider.expand.isVisible());
        assertTrue(new Fixture(0, 4096, 24577, 500).slider.expand.isVisible());
        assertTrue(new Fixture(0, 4096, 65536, 500).slider.expand.isVisible());
    }

    @Test
    public void theExpandedScaleIs48GiBAndNeverMoreWithoutChangingValues() {
        Fixture f = new Fixture(2048, 8192, 131072, 500);

        f.slider.expand.doClick(0);
        f.layOut(500);
        assertTrue(f.slider.expanded);
        assertEquals(49152, f.slider.scale());
        assertEquals(49152, f.slider.usableEnd());
        assertTrue(f.slider.expand.getText().contains("Normal"));
        assertEquals(2048, f.minimum());
        assertEquals(8192, f.maximum());
        // more RAM than the scale: nothing greyed
        assertEquals(null, f.slider.track.greyedFrom());

        f.drag(f.x(8192), 10000);
        assertEquals(49152, f.maximum());
        assertEquals(49152, ((javax.swing.SpinnerNumberModel) f.slider.maximumEntry.exact.getModel()).getMaximum());

        // back to normal, once nothing's past 24 GiB, still without changing anything
        f.maximum.setValue(8192);
        f.slider.expand.doClick(0);
        assertFalse(f.slider.expanded);
        assertEquals(24576, f.slider.scale());
        assertEquals(2048, f.minimum());
        assertEquals(8192, f.maximum());
    }

    @Test
    public void pastTheRamTheTrackIsGreyedAndNoThumbGoesThere() {
        // 16 GiB on the 24 GiB scale
        Fixture f = new Fixture(0, 4096, 16384, 500);
        assertEquals(24576, f.slider.scale());
        assertEquals(16384, f.slider.usableEnd());
        assertEquals(Integer.valueOf(f.x(16384)), f.slider.track.greyedFrom());
        assertTrue(f.x(16384) < f.x(24576));

        f.drag(f.x(4096), 10000);
        assertEquals(16384, f.maximum());
        // pressed in the greyed part, the nearer thumb only comes to its start
        f.maximum.setValue(8192);
        f.drag(f.x(22528));
        assertEquals(16384, f.maximum());
        f.key(f.slider.maximumThumb, KeyEvent.VK_RIGHT);
        f.key(f.slider.maximumThumb, KeyEvent.VK_END);
        f.key(f.slider.maximumThumb, KeyEvent.VK_PAGE_UP);
        assertEquals(16384, f.maximum());
        // the minimum no more than the maximum can
        f.drag(f.x(0), f.x(0) + 1, 10000);
        assertEquals(16384, f.minimum());
        // nor typed
        assertEquals(16384, ((javax.swing.SpinnerNumberModel) f.slider.maximumEntry.exact.getModel()).getMaximum());

        MouseEvent inGrey = new MouseEvent(f.slider.track, MouseEvent.MOUSE_MOVED, 0, 0, f.x(22528), 5, 0, false);
        assertTrue(f.slider.track.getToolTipText(inGrey).contains("can't be used"));

        // 32 GiB on the 48 GiB scale
        Fixture g = new Fixture(0, 4096, 32768, 500);
        g.slider.expand.doClick(0);
        g.layOut(500);
        assertEquals(Integer.valueOf(g.x(32768)), g.slider.track.greyedFrom());
        g.drag(g.x(4096), 10000);
        assertEquals(32768, g.maximum());

        // with RAM for the whole scale, nothing's greyed
        assertEquals(null, new Fixture(0, 4096, 24576, 500).slider.track.greyedFrom());
    }

    @Test
    public void theExactRamIsTheLastUsablePositionBetweenSteps() {
        Fixture f = new Fixture(0, 8192, 16301, 500);
        assertEquals(16301, f.slider.usableEnd());
        assertEquals(Integer.valueOf(f.x(16301)), f.slider.track.greyedFrom());

        f.drag(f.x(8192), 10000);
        assertEquals(16301, f.maximum());
        f.key(f.slider.maximumThumb, KeyEvent.VK_LEFT);
        assertEquals(15872, f.maximum());
        f.key(f.slider.maximumThumb, KeyEvent.VK_RIGHT);
        assertEquals(16301, f.maximum());
        f.key(f.slider.maximumThumb, KeyEvent.VK_RIGHT);
        assertEquals(16301, f.maximum());

        // expanded too
        Fixture g = new Fixture(0, 8192, 40000, 500);
        g.slider.expand.doClick(0);
        g.layOut(500);
        g.drag(g.x(8192), 10000);
        assertEquals(40000, g.maximum());
        g.key(g.slider.maximumThumb, KeyEvent.VK_LEFT);
        assertEquals(39936, g.maximum());
        assertEquals(40000, ((javax.swing.SpinnerNumberModel) g.slider.maximumEntry.exact.getModel()).getMaximum());
    }

    @Test
    public void savedValuesInTheGreyedPartAreKeptWhereTheyAre() {
        // 20 GiB saved, 16 GiB here: on the scale, but past the RAM
        Fixture f = new Fixture(0, 20480, 16384, 500);

        assertEquals(20480, f.maximum());
        assertTrue(f.x(20480) > f.slider.track.greyedFrom(), "where it is, not at the usable end");
        assertTrue(f.slider.maximumThumb.isOutOfRange());
        assertFalse(f.slider.maximumThumb.isOffScale());
        assertTrue(f.slider.maximumEntry.button.getText().contains("20 GiB"));
        assertTrue(f.slider.maximumEntry.button.getText().contains("RAM"));
        assertTrue(f.slider.maximumThumb.getAccessibleContext().getAccessibleDescription().contains("RAM"));
        assertTrue(f.slider.isWarning());

        // a click on it keeps it; moving it brings it into range
        f.drag(f.x(20480));
        assertEquals(20480, f.maximum());
        f.key(f.slider.maximumThumb, KeyEvent.VK_LEFT);
        assertEquals(16384, f.maximum());
        assertFalse(f.slider.maximumThumb.isOutOfRange());
    }

    @Test
    public void savedValuesPastTheScaleAreKeptAndPointOffIt() {
        // 32 GiB saved, 16 GiB here: there's no 48 GiB scale to show it on
        Fixture f = new Fixture(0, 32768, 16384, 500);
        assertFalse(f.slider.expanded);
        assertEquals(32768, f.maximum());
        assertTrue(f.slider.maximumThumb.isOffScale());
        assertEquals(f.x(24576), f.x(32768));
        assertTrue(f.slider.maximumEntry.button.getText().contains("32 GiB"));

        // 56 GiB saved on a 64 GiB machine: past even the 48 GiB scale
        Fixture g = new Fixture(0, 57344, 65536, 500);
        assertTrue(g.slider.expanded);
        assertFalse(g.slider.expand.isEnabled());
        assertTrue(g.slider.maximumThumb.isOffScale());
        assertTrue(g.slider.maximumEntry.button.getText().contains("56 GiB"));
        assertTrue(g.slider.maximumEntry.button.getText().contains("48"));
        assertTrue(g.slider.hint.getText().contains(mb(57344)));
        assertTrue(g.slider.isWarning());
        g.key(g.slider.maximumThumb, KeyEvent.VK_RIGHT);
        g.key(g.slider.maximumThumb, KeyEvent.VK_END);
        assertEquals(57344, g.maximum());
        g.key(g.slider.maximumThumb, KeyEvent.VK_LEFT);
        assertEquals(49152, g.maximum());

        // an odd value is shown exactly
        Fixture h = new Fixture(0, 70000, 65536, 500);
        assertTrue(h.slider.maximumEntry.button.getText().contains(mb(70000) + " MB"));
        assertEquals(70000, h.maximum());

        // a minimum past the RAM, the same
        Fixture i = new Fixture(20480, 32768, 16384, 500);
        assertTrue(i.slider.minimumThumb.isOutOfRange());
        assertTrue(i.slider.minimumEntry.button.getText().contains("20 GiB"));
        assertEquals(20480, i.minimum());
    }

    @Test
    public void aValuePast24GiBShowsThe48GiBScale() {
        Fixture f = new Fixture(0, 32768, 65536, 500);
        assertTrue(f.slider.expanded);
        assertEquals(49152, f.slider.scale());
        assertFalse(f.slider.maximumThumb.isOutOfRange());
        assertTrue(f.x(32768) > f.x(24576) && f.x(32768) < f.x(49152));
        // it can't go back to normal while that value's past it
        assertFalse(f.slider.expand.isEnabled());
        assertTrue(f.slider.expand.getToolTipText().contains("24"));

        // lowered, it stays expanded so nothing jumps, but can then go back
        f.maximum.setValue(8192);
        assertTrue(f.slider.expanded);
        assertTrue(f.slider.expand.isEnabled());

        // a minimum past it counts too, and exactly 24 GiB doesn't
        assertTrue(new Fixture(30720, 32768, 65536, 500).slider.expanded);
        assertFalse(new Fixture(0, 24576, 65536, 500).slider.expanded);

        // typed past it
        Fixture g = new Fixture(0, 8192, 65536, 500);
        g.slider.maximumEntry.button.doClick(0);
        g.slider.maximumEntry.exact.setValue(40000);
        assertEquals(40000, g.maximum());
        assertTrue(g.slider.expanded);
    }

    @Test
    public void doubleClickingAThumbOpensItsExactEditorWithTheValueSelected() throws Exception {
        Fixture f = new Fixture(1024, 8192, RAM, 500);
        MemoryRangeSlider.ValueEntry maximum = f.slider.maximumEntry;

        f.slider.track.doubleClick(f.x(8192));
        assertTrue(maximum.isEditing());
        assertFalse(f.slider.minimumEntry.isEditing());
        assertEquals(8192, maximum.exact.getValue());
        flushEventQueue();
        assertEquals(maximum.field.getText(), maximum.field.getSelectedText());

        // Escape cancels
        maximum.field.setText("4096");
        maximum.field.getActionMap().get("cancelExactly").actionPerformed(null);
        assertFalse(maximum.isEditing());
        assertEquals(8192, f.maximum());

        f.slider.track.doubleClick(f.x(1024));
        assertTrue(f.slider.minimumEntry.isEditing());
        f.slider.minimumEntry.cancelEditing();

        // nowhere near a thumb: nothing
        f.slider.track.doubleClick(f.x(20480));
        assertFalse(f.slider.minimumEntry.isEditing());
        assertFalse(maximum.isEditing());

        // Enter on a thumb does the same, for the keyboard
        f.key(f.slider.maximumThumb, KeyEvent.VK_ENTER);
        assertTrue(maximum.isEditing());
    }

    @Test
    public void doubleClickingOverlappingThumbsAsksWhichToEdit() {
        Fixture f = new Fixture(8192, 8192, RAM, 500);

        f.slider.track.doubleClick(f.x(8192));
        assertFalse(f.slider.minimumEntry.isEditing());
        assertFalse(f.slider.maximumEntry.isEditing());

        JPopupMenu choice = f.slider.track.choice;
        assertEquals(2, choice.getComponentCount());
        JMenuItem editMinimum = (JMenuItem) choice.getComponent(0);
        JMenuItem editMaximum = (JMenuItem) choice.getComponent(1);
        assertTrue(editMinimum.getText().contains("minimum"));
        assertTrue(editMaximum.getText().contains("maximum"));

        editMaximum.doClick(0);
        assertTrue(f.slider.maximumEntry.isEditing());
        assertFalse(f.slider.minimumEntry.isEditing());
    }

    @Test
    public void doubleClickingLinkedThumbsSaysItsEditorSetsBoth() {
        Fixture f = new Fixture(1024, 8192, RAM, 500);
        f.slider.link.doClick(0);
        MemoryRangeSlider.ValueEntry entry = f.slider.maximumEntry;

        f.slider.track.doubleClick(f.x(8192));
        assertNull(f.slider.track.choice);
        assertTrue(entry.isEditing());
        assertTrue(entry.linkedNote.isVisible());
        assertTrue(entry.field.getAccessibleContext().getAccessibleDescription().contains("both"));

        entry.exact.setValue(6144);
        assertEquals(6144, f.minimum());
        assertEquals(6144, f.maximum());

        // unlinked, no note
        entry.stopEditing();
        f.slider.link.doClick(0);
        f.slider.minimumEntry.button.doClick(0);
        assertFalse(f.slider.minimumEntry.linkedNote.isVisible());
        assertNull(f.slider.minimumEntry.field.getAccessibleContext().getAccessibleDescription());
    }

    private static Rectangle bounds(Component component, MemoryRangeSlider slider) {
        return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), slider);
    }

    private static void assertInOneRow(MemoryRangeSlider slider, int width) {
        Rectangle link = bounds(slider.link, slider);
        Rectangle minimum = bounds(slider.minimumEntry, slider);
        Rectangle maximum = bounds(slider.maximumEntry, slider);

        assertTrue(link.x + link.width <= minimum.x, link + " before " + minimum);
        assertTrue(minimum.x + minimum.width <= maximum.x, minimum + " before " + maximum);
        assertTrue(maximum.x + maximum.width <= width, maximum + " within " + width);
        assertTrue(Math.abs(link.getCenterY() - minimum.getCenterY()) <= 1, link + " level with " + minimum);
        assertTrue(Math.abs(link.getCenterY() - maximum.getCenterY()) <= 1, link + " level with " + maximum);
    }

    @Test
    public void theLinkSitsAtTheStartOfTheValuesRowAtNormalAndNarrowWidths() {
        for (int width : new int[] { 540, 360 }) {
            Fixture f = new Fixture(1024, 12288, RAM, width);
            assertInOneRow(f.slider, width);
            assertEquals(0, bounds(f.slider.link, f.slider).x);
        }

        // while the minimum's edited, the maximum moves along rather than being covered
        Fixture f = new Fixture(1024, 12288, RAM, 540);
        f.slider.minimumEntry.button.doClick(0);
        f.layOut(540);
        assertInOneRow(f.slider, 540);
    }

    @Test
    public void aSavedLinkIsShownWithoutChangingOrReportingAnything() {
        Fixture f = new Fixture(4096, 4096, RAM, 500);
        List<Boolean> reported = new java.util.ArrayList<>();
        f.slider.addLinkListener(reported::add);

        f.slider.showLinked(true);
        assertTrue(f.slider.isLinked());
        assertEquals("Unlink minimum and maximum", f.slider.link.getToolTipText());
        assertEquals(4096, f.minimum());
        assertEquals(4096, f.maximum());
        assertTrue(reported.isEmpty());

        // only while they're the same: that's all linked means
        Fixture g = new Fixture(2048, 4096, RAM, 500);
        g.slider.showLinked(true);
        assertFalse(g.slider.isLinked());
        assertEquals(2048, g.minimum());
    }

    @Test
    public void theUsersLinkChangesAreReported() {
        Fixture f = new Fixture(4096, 4096, RAM, 500);
        f.slider.showLinked(true);
        List<Boolean> reported = new java.util.ArrayList<>();
        f.slider.addLinkListener(reported::add);

        f.slider.link.doClick(0);
        f.slider.link.doClick(0);
        // Auto ends the link
        f.slider.minimumEntry.button.doClick(0);
        f.slider.minimumEntry.auto.doClick(0);
        assertEquals(Arrays.asList(false, true, false), reported);
    }

    @Test
    public void anUnlinkStaysUnlinkedWhenReopenedThoughTheValuesMatch() {
        JSpinner minimum = spinner(4096, 0, RAM);
        JSpinner maximum = spinner(4096, 512, RAM);
        MemoryRangeSlider first = MemoryRangeSlider.forSpinners(minimum, maximum, RAM);
        first.showLinked(true);
        boolean[] saved = { true };
        first.addLinkListener(linked -> saved[0] = linked);

        first.link.doClick(0);
        assertFalse(saved[0]);
        assertEquals(4096, minimum.getValue());
        assertEquals(4096, maximum.getValue());

        // reopened with what was saved
        MemoryRangeSlider reopened = MemoryRangeSlider.forSpinners(minimum, maximum, RAM);
        reopened.showLinked(saved[0]);
        assertFalse(reopened.isLinked());
    }

    @Test
    public void theLinksTooltipCarriesANoteOnlyWhileLinked() {
        Fixture f = new Fixture(4096, 4096, RAM, 500);
        f.slider.showLinked(true);

        f.slider.setLinkNote("At launch, this pack raises only -Xmx.");
        assertTrue(f.slider.link.getToolTipText().contains("raises only -Xmx"));
        assertTrue(f.slider.link.getAccessibleContext().getAccessibleDescription().contains("raises only -Xmx"));
        assertEquals("Unlink minimum and maximum", f.slider.link.getAccessibleContext().getAccessibleName());

        f.slider.link.doClick(0);
        assertEquals("Link minimum and maximum", f.slider.link.getToolTipText());
    }
}
