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
package com.atlauncher.gui.dialogs.instancesettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.accessibility.AccessibleRole;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.JSpinner;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.atlauncher.App;
import com.atlauncher.Data;
import com.atlauncher.data.Instance;
import com.atlauncher.data.InstanceLauncher;
import com.atlauncher.data.Settings;
import com.atlauncher.data.minecraft.JavaRuntimes;
import com.atlauncher.data.minecraft.MinecraftVersion;
import com.atlauncher.gui.components.HeapFlagsPreview;
import com.atlauncher.gui.components.JavaParametersEditor;
import com.atlauncher.gui.components.MemoryRangeSlider;
import com.atlauncher.themes.ATLauncherLaf;
import com.atlauncher.themes.Dark;
import com.atlauncher.utils.OS;

/**
 * The instance's Java tab, laid out as its dialog shows it: in a scroll pane the size of the dialog's tab. It's taller
 * than that, and must scroll rather than squeeze the Java parameters down to their minimum size.
 */
public class JavaInstanceSettingsTabLayoutTest {
    private LookAndFeel previousLaf;
    private ATLauncherLaf previousTheme;
    private Settings previousSettings;
    private JavaRuntimes previousRuntimes;
    private Instance instance;
    private JavaInstanceSettingsTab tab;

    @BeforeEach
    public void setUp() throws Exception {
        previousLaf = UIManager.getLookAndFeel();
        previousTheme = App.THEME;
        previousSettings = App.settings;
        previousRuntimes = Data.JAVA_RUNTIMES;

        Dark.install();
        App.THEME = Dark.getInstance();
        App.settings = new Settings();
        App.settings.maximumMemory = 4096;
        App.settings.initialMemory = 0;
        Data.JAVA_RUNTIMES = new JavaRuntimes();
        for (Field field : JavaRuntimes.class.getFields()) {
            if (field.getType() == Map.class) {
                field.set(Data.JAVA_RUNTIMES, new HashMap<>());
            }
        }

        MinecraftVersion version = new MinecraftVersion();
        version.id = "1.20.1";
        instance = new Instance(version);
        instance.launcher = new InstanceLauncher();
        tab = new JavaInstanceSettingsTab(instance);
    }

    @AfterEach
    public void tearDown() throws Exception {
        UIManager.setLookAndFeel(previousLaf);
        App.THEME = previousTheme;
        App.settings = previousSettings;
        Data.JAVA_RUNTIMES = previousRuntimes;
    }

    @Test
    public void javaParametersKeepTheirSizeWithTheirNoteBelow() {
        // the 800x600 dialog, less its title, tabs and buttons
        JScrollPane scrollPane = new JScrollPane(tab);
        scrollPane.setSize(780, 470);
        layOut(scrollPane);
        // the notes wrap to the width they were given, so once more for their heights
        layOut(scrollPane);

        JavaParametersEditor javaParameters = find(tab, JavaParametersEditor.class);
        JScrollPane javaParametersScrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class,
            javaParameters);
        assertEquals(javaParametersScrollPane.getPreferredSize(), javaParametersScrollPane.getSize());
        assertTrue(javaParametersScrollPane.getHeight() >= 6
            * javaParameters.getFontMetrics(javaParameters.getFont()).getHeight());

        Rectangle parameters = bounds(javaParametersScrollPane.getParent(), tab);
        HeapFlagsPreview note = find(tab, HeapFlagsPreview.class);
        if (note.isVisible()) {
            Rectangle noteBounds = bounds(note, tab);
            assertTrue(noteBounds.y >= parameters.y + parameters.height, noteBounds + " below " + parameters);
            assertEquals(parameters.x, noteBounds.x);
        }

        // the slider only shows when this machine's RAM is known
        MemoryRangeSlider slider = find(tab, MemoryRangeSlider.class);
        if (slider != null) {
            Rectangle sliderBounds = bounds(slider, tab);
            assertTrue(sliderBounds.y + sliderBounds.height <= parameters.y);
            assertEquals(parameters.x, sliderBounds.x);
            assertTrue(slider.getHeight() >= slider.getPreferredSize().height);
        }
    }

    @Test
    public void theSliderReplacesTheSpinnersWhenRamIsKnown() {
        List<JSpinner> spinners = findAll(tab, JSpinner.class);
        MemoryRangeSlider slider = find(tab, MemoryRangeSlider.class);

        // maximum, minimum and PermGen; the slider's own exact editors are inside it
        List<JSpinner> memory = new ArrayList<>();
        for (JSpinner spinner : spinners) {
            if (slider == null || !SwingUtilities.isDescendingFrom(spinner, slider)) {
                memory.add(spinner);
            }
        }
        assertEquals(3, memory.size());
        assertEquals(slider == null, memory.get(0).isVisible());
        assertEquals(slider == null, memory.get(1).isVisible());
        assertTrue(memory.get(2).isVisible());
    }

    @Test
    public void theFlagsInTheJavaParametersBoxFollowTheSliderAndCustomXms() throws Exception {
        MemoryRangeSlider slider = find(tab, MemoryRangeSlider.class);
        Integer ram = OS.getDetectedSystemRam();
        assumeTrue(slider != null && ram >= 8192, "needs a slider, so RAM that's detected, and room for 6 GiB");
        JavaParametersEditor javaParameters = find(tab, JavaParametersEditor.class);
        String custom = javaParameters.getText();
        assertEquals("-Xmx4096M", javaParameters.getGeneratedFlags());

        // an exact maximum, typed after clicking its value
        button(slider, "Maximum:").doClick(0);
        findAll(slider, JSpinner.class).get(1).setValue(6144);
        // and a minimum by its thumb's keys
        JComponent minimumThumb = null;
        for (JComponent component : findAll(slider, JComponent.class)) {
            if (component.getAccessibleContext().getAccessibleRole() == AccessibleRole.SLIDER
                && component.getAccessibleContext().getAccessibleName().startsWith("Minimum")) {
                minimumThumb = component;
            }
        }
        Object right = minimumThumb.getInputMap(JComponent.WHEN_FOCUSED)
            .get(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0));
        minimumThumb.getActionMap().get(right).actionPerformed(null);

        assertEquals("-Xms512M -Xmx6144M", javaParameters.getGeneratedFlags());
        assertEquals(custom, javaParameters.getText());

        // a custom initial heap in the parameters takes the generated one's place
        javaParameters.getDocument().insertString(0, "-Xms2G ", null);
        assertEquals("-Xmx6144M", javaParameters.getGeneratedFlags());
        assertEquals("-Xms2G " + custom, javaParameters.getText());

        // Auto: no -Xms either way
        javaParameters.setText(custom);
        assertEquals("-Xms512M -Xmx6144M", javaParameters.getGeneratedFlags());
        button(slider, "Minimum:").doClick(0);
        button(slider, "Auto").doClick(0);
        assertEquals("-Xmx6144M", javaParameters.getGeneratedFlags());
    }

    // the instance's settings opened again, as they'd be after saving and reopening the dialog
    private void reopen() {
        tab = new JavaInstanceSettingsTab(instance);
    }

    private JToggleButton link() {
        return find(find(tab, MemoryRangeSlider.class), JToggleButton.class);
    }

    @Test
    public void aNewInstanceOnANewInstallInheritsTheLinked4GiB() {
        App.settings = Settings.forNewInstall(65536, true);
        reopen();

        assertEquals("-Xms4096M -Xmx4096M", find(tab, JavaParametersEditor.class).getGeneratedFlags());
        MemoryRangeSlider slider = find(tab, MemoryRangeSlider.class);
        if (slider != null) {
            assertTrue(slider.isLinked());
        }

        // saved unchanged, it stores nothing of its own
        tab.saveSettings();
        assertNull(instance.launcher.maximumMemory);
        assertNull(instance.launcher.initialMemory);
        assertNull(instance.launcher.memoryLinked);
    }

    @Test
    public void anInstanceUnlinkedStaysUnlinkedWhenReopened() {
        App.settings = Settings.forNewInstall(65536, true);
        reopen();
        assumeTrue(find(tab, MemoryRangeSlider.class) != null, "this machine's RAM isn't detected, so no link");

        link().doClick(0);
        tab.saveSettings();
        assertEquals(Boolean.FALSE, instance.launcher.memoryLinked);
        assertNull(instance.launcher.maximumMemory);
        assertNull(instance.launcher.initialMemory);

        // equal values don't relink it
        reopen();
        assertFalse(find(tab, MemoryRangeSlider.class).isLinked());
        assertEquals("-Xms4096M -Xmx4096M", find(tab, JavaParametersEditor.class).getGeneratedFlags());

        // linked again, as the launcher is, it goes back to inheriting
        link().doClick(0);
        tab.saveSettings();
        assertNull(instance.launcher.memoryLinked);
    }

    @Test
    public void anOlderInstallStaysUnlinkedWithNoMinimum() {
        // set up as read from a settings file saved before the minimum or the link existed
        assertNull(App.settings.memoryLinked);
        MemoryRangeSlider slider = find(tab, MemoryRangeSlider.class);
        if (slider != null) {
            assertFalse(slider.isLinked());
        }
        assertEquals("-Xmx4096M", find(tab, JavaParametersEditor.class).getGeneratedFlags());

        tab.saveSettings();
        assertNull(instance.launcher.maximumMemory);
        assertNull(instance.launcher.initialMemory);
        assertNull(instance.launcher.memoryLinked);
    }

    @Test
    public void aPackRaisingXmxShowsWhatLaunchesWhileLinked() {
        assumeTrue(OS.getMaximumRam() / 2 >= 6144, "needs room for the pack's 6 GiB, as a launch does");
        App.settings = Settings.forNewInstall(65536, true);
        instance.launcher.requiredMemory = 6144;
        reopen();
        assumeTrue(find(tab, MemoryRangeSlider.class) != null, "this machine's RAM isn't detected, so no link");

        JavaParametersEditor javaParameters = find(tab, JavaParametersEditor.class);
        assertEquals("-Xms4096M -Xmx6144M", javaParameters.getGeneratedFlags());
        HeapFlagsPreview note = find(tab, HeapFlagsPreview.class);
        assertTrue(note.isVisible());
        assertTrue(find(note, JLabel.class).getText().contains("-Xms stays at 4096M"));
        assertTrue(link().getToolTipText().contains("raises only -Xmx"));

        // a custom initial heap in the parameters still comes first
        javaParameters.setText("-Xms2G " + javaParameters.getText());
        assertEquals("-Xmx6144M", javaParameters.getGeneratedFlags());
        assertFalse(link().getToolTipText().contains("raises only -Xmx"));
    }

    private static JButton button(Container container, String text) {
        for (JButton button : findAll(container, JButton.class)) {
            if (button.getText().startsWith(text)) {
                return button;
            }
        }
        throw new AssertionError("no " + text + " button");
    }

    private static void layOut(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                layOut((Container) child);
            }
        }
    }

    private static Rectangle bounds(Component component, Container within) {
        assertNotNull(component);
        return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), within);
    }

    private static <T> T find(Container container, Class<T> type) {
        List<T> all = findAll(container, type);
        return all.isEmpty() ? null : all.get(0);
    }

    // in layout order
    private static <T> List<T> findAll(Container container, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof Container) {
                found.addAll(findAll((Container) child, type));
            }
        }
        assertFalse(found.contains(null));
        return found;
    }
}
