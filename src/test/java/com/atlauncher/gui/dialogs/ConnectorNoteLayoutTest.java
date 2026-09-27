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
package com.atlauncher.gui.dialogs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import org.junit.jupiter.api.Test;

public class ConnectorNoteLayoutTest {
    private static void layoutAll(Container container) {
        container.doLayout();

        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                layoutAll((Container) child);
            }
        }
    }

    @Test
    public void noteKeepsItsHeightAtTheCompactDialogSize() {
        JPanel versionsPanel = new JPanel(new FlowLayout());
        versionsPanel.setBorder(BorderFactory.createEmptyBorder(20, 0, 20, 0));
        JComboBox<String> dropdown = new JComboBox<>(new String[] { "1.0.0+fabric" });
        dropdown.setPreferredSize(new Dimension(200, 25));
        versionsPanel.add(dropdown);

        JLabel note = new JLabel("Uses Sinytra Connector; compatibility is not guaranteed.", JLabel.CENTER);

        // same middle panel as both selectors: an always-present 250px dependencies scroll pane in SOUTH
        JScrollPane scrollPane = new JScrollPane(new JPanel());
        scrollPane.setPreferredSize(new Dimension(550, 250));

        JPanel middle = new JPanel(new BorderLayout());
        middle.add(ModrinthVersionSelectorDialog.selectorWithConnectorNote(note, versionsPanel), BorderLayout.NORTH);
        middle.add(scrollPane, BorderLayout.SOUTH);

        // roughly the middle panel's share of a 550x220 dialog
        middle.setSize(534, 130);
        layoutAll(middle);

        int noteBottom = note.getParent().getY() + note.getY() + note.getHeight();
        assertTrue(note.getHeight() >= note.getPreferredSize().height, "note height " + note.getHeight());
        assertTrue(noteBottom <= middle.getHeight(), "note ends at " + noteBottom);
    }
}
