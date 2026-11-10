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
package com.atlauncher.gui.tabs.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JCheckBox;
import javax.swing.JScrollPane;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.atlauncher.App;
import com.atlauncher.data.Settings;
import com.atlauncher.evnt.manager.SettingsManager;
import com.atlauncher.gui.components.HeapFlagsPreview;
import com.atlauncher.gui.components.JavaParametersEditor;
import com.atlauncher.themes.ATLauncherLaf;
import com.atlauncher.themes.Dark;
import com.atlauncher.utils.OS;
import com.atlauncher.viewmodel.impl.settings.JavaSettingsViewModel;

/**
 * Builds the launcher's Java/Minecraft settings, as the Settings tab shows them, from default settings.
 */
public class JavaSettingsTabSmokeTest {
    private LookAndFeel previousLaf;
    private ATLauncherLaf previousTheme;
    private Settings previousSettings;
    private JavaSettingsViewModel viewModel;

    @BeforeEach
    public void setUp() {
        // the screen resolutions are read from the display
        assumeFalse(GraphicsEnvironment.isHeadless(), "A display is required for the screen resolutions");
        previousLaf = UIManager.getLookAndFeel();
        previousTheme = App.THEME;
        previousSettings = App.settings;

        Dark.install();
        App.THEME = Dark.getInstance();
        App.settings = new Settings();
        // as validated settings have it
        App.settings.javaPath = System.getProperty("java.home");
        App.settings.windowWidth = Math.min(App.settings.windowWidth, OS.getMaximumWindowWidth());
        App.settings.windowHeight = Math.min(App.settings.windowHeight, OS.getMaximumWindowHeight());
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (viewModel != null) {
            SettingsManager.removeListener(viewModel);
        }
        if (previousLaf != null) {
            UIManager.setLookAndFeel(previousLaf);
            App.THEME = previousTheme;
            App.settings = previousSettings;
        }
    }

    private static <T extends Component> List<T> findAll(Container container, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof Container) {
                found.addAll(findAll((Container) child, type));
            }
        }
        return found;
    }

    @Test
    public void buildsAndTheDefaultGameOptionsEditTheSettings() {
        viewModel = new JavaSettingsViewModel();
        JavaSettingsTab tab = new JavaSettingsTab(viewModel);
        tab.onShow();

        JScrollPane scrollPane = new JScrollPane(tab);
        scrollPane.setSize(1180, 560);
        scrollPane.doLayout();
        scrollPane.getViewport().doLayout();
        tab.doLayout();

        assertNotNull(findAll(tab, JavaParametersEditor.class).get(0));
        assertNotNull(findAll(tab, HeapFlagsPreview.class).get(0));

        // the six default game options are each ticked to be set; none is at first
        List<JCheckBox> ticks = new ArrayList<>();
        for (JCheckBox box : findAll(tab, JCheckBox.class)) {
            if ("Set this in new instances".equals(box.getToolTipText())) {
                ticks.add(box);
            }
        }
        assertEquals(6, ticks.size());
        assertNull(App.settings.defaultGameOptions.fov);

        ticks.get(0).setSelected(true);
        assertEquals(Integer.valueOf(70), App.settings.defaultGameOptions.fov);
        ticks.get(0).setSelected(false);
        assertNull(App.settings.defaultGameOptions.fov);

        tab.onDestroy();
    }
}
