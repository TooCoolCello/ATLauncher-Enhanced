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
package com.atlauncher.gui.card;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.text.NumberFormat;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;

/**
 * Lays out an Add Mods result card as a compact full-width list row: icon, name/author and a one-line summary,
 * then downloads, the installed marker and the card's existing action buttons.
 */
final class CompactResultRow {
    static final int ICON_SIZE = 32;

    private CompactResultRow() {
    }

    static void layout(JPanel row, JLabel icon, String name, String author, String summary, int downloads,
            JLabel installedLabel, JButton... actions) {
        row.setLayout(new BorderLayout(8, 0));

        Color separator = UIManager.getColor("Separator.foreground");
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, separator == null ? Color.GRAY : separator),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));

        // fixed slot so text lines up whether or not the icon has loaded yet
        icon.setBorder(null);
        icon.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel iconSlot = new JPanel(new BorderLayout());
        iconSlot.setOpaque(false);
        iconSlot.setPreferredSize(new Dimension(ICON_SIZE, ICON_SIZE));
        iconSlot.add(icon, BorderLayout.CENTER);
        row.add(iconSlot, BorderLayout.WEST);

        JLabel nameLabel = new JLabel(name);
        nameLabel.setFont(App.THEME.getBoldFont().deriveFont(12f));
        nameLabel.setToolTipText(name);

        JPanel titleLine = new JPanel();
        titleLine.setLayout(new BoxLayout(titleLine, BoxLayout.X_AXIS));
        titleLine.setOpaque(false);
        titleLine.add(nameLabel);
        if (author != null && !author.isEmpty()) {
            JLabel authorLabel = new JLabel(GetText.tr("by {0}", author));
            authorLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            titleLine.add(Box.createHorizontalStrut(6));
            titleLine.add(authorLabel);
        }

        JLabel summaryLabel = new JLabel(summary);
        summaryLabel.setToolTipText(summary);

        // grid cells are sized to the space left between the icon and actions; overflowing text is clipped there
        JPanel text = new JPanel(new GridLayout(2, 1));
        text.setOpaque(false);
        text.add(titleLine);
        text.add(summaryLabel);
        row.add(text, BorderLayout.CENTER);

        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actionsPanel.setOpaque(false);
        actionsPanel.add(new JLabel(GetText.tr("{0} downloads", NumberFormat.getIntegerInstance().format(downloads))));
        installedLabel.setToolTipText(GetText.tr("Installed"));
        actionsPanel.add(installedLabel);
        for (JButton action : actions) {
            actionsPanel.add(action);
        }
        row.add(actionsPanel, BorderLayout.EAST);
    }

    /**
     * A list row only asks for its height; its width comes from the list, so long names or summaries never widen
     * the results past the viewport.
     */
    static Dimension preferredSize(Dimension natural) {
        return new Dimension(0, natural.height);
    }
}
