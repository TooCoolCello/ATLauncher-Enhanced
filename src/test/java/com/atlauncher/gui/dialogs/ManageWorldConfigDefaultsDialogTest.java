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
package com.atlauncher.gui.dialogs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.atlauncher.data.Instance;
import com.atlauncher.data.InstanceLauncher;
import com.atlauncher.data.minecraft.MinecraftVersion;
import com.atlauncher.themes.Dark;

/**
 * Builds Manage World Config Defaults on an instance with default configs, as the instance card opens it, without
 * showing it.
 */
public class ManageWorldConfigDefaultsDialogTest {
    @TempDir
    Path temp;
    private LookAndFeel previousLaf;
    private ManageWorldConfigDefaultsDialog dialog;

    @BeforeEach
    public void setUp() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A display is required to build a dialog");
        previousLaf = UIManager.getLookAndFeel();
        Dark.install();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (dialog != null) {
            dialog.dispose();
        }
        if (previousLaf != null) {
            UIManager.setLookAndFeel(previousLaf);
        }
    }

    private static <T extends Component> T find(Container container, Class<T> type) {
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container) {
                T found = find((Container) child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // the text at the top saying what the list is
    private static JLabel explanation(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel && ((JLabel) child).getText().contains("New worlds created")) {
                return (JLabel) child;
            }
            if (child instanceof Container) {
                JLabel found = explanation((Container) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean busy() throws Exception {
        Field busy = ManageWorldConfigDefaultsDialog.class.getDeclaredField("busy");
        busy.setAccessible(true);
        return busy.getBoolean(dialog);
    }

    @Test
    public void listsTheDefaultConfigsAndItsExplanationFitsAtTheMinimumSize() throws Exception {
        Path root = Files.createDirectories(temp.resolve("instances/Test Pack"));
        Files.createDirectories(root.resolve("defaultconfigs/sub"));
        Files.write(root.resolve("defaultconfigs/create-server.toml"), "a".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("defaultconfigs/sub/tweaks.json"), "b".getBytes(StandardCharsets.UTF_8));

        MinecraftVersion version = new MinecraftVersion();
        version.id = "1.20.1";
        Instance instance = new Instance(version);
        instance.launcher = new InstanceLauncher();
        instance.launcher.name = "Test Pack";
        instance.ROOT = root;

        Constructor<ManageWorldConfigDefaultsDialog> constructor = ManageWorldConfigDefaultsDialog.class
            .getDeclaredConstructor(Window.class, Instance.class);
        constructor.setAccessible(true);
        dialog = constructor.newInstance(null, instance);

        // the list loads in the background
        long until = System.currentTimeMillis() + 10000;
        do {
            SwingUtilities.invokeAndWait(() -> {
            });
        } while (busy() && System.currentTimeMillis() < until);

        JList<?> list = find(dialog.getContentPane(), JList.class);
        assertNotNull(list);
        assertEquals(2, list.getModel().getSize());
        assertEquals("create-server.toml", list.getModel().getElementAt(0).toString());
        assertEquals("sub/tweaks.json", list.getModel().getElementAt(1).toString());

        // as narrow as it goes, the explanation wraps rather than being cut off on the right
        dialog.pack();
        dialog.setSize(dialog.getMinimumSize());
        dialog.validate();
        SwingUtilities.invokeAndWait(() -> {
        });
        dialog.validate();

        JLabel explanation = explanation(dialog.getContentPane());
        assertNotNull(explanation);
        assertTrue(explanation.getPreferredSize().width <= explanation.getWidth(),
            explanation.getPreferredSize() + " in " + explanation.getSize());
        assertTrue(explanation.getHeight() >= explanation.getPreferredSize().height);
    }
}
