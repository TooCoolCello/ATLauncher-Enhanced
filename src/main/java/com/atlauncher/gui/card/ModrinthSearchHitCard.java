/*
 * ATLauncher - https://github.com/ATLauncher/ATLauncher
 * Copyright (C) 2013-2022 ATLauncher
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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.data.ModManagement;
import com.atlauncher.data.modrinth.ModrinthSearchHit;
import com.atlauncher.gui.borders.IconTitledBorder;
import com.atlauncher.utils.OS;
import com.atlauncher.utils.Utils;
import com.atlauncher.workers.BackgroundImageWorker;

public final class ModrinthSearchHitCard extends JPanel {
    private final ModrinthSearchHit mod;
    private final ModManagement instanceOrServer;

    private final JButton addButton = new JButton(GetText.tr("Add"));
    private final JButton reinstallButton = new JButton(GetText.tr("Reinstall"));
    private final JButton removeButton = new JButton(GetText.tr("Remove"));
    private final JLabel installedLabel = new JLabel(
            Utils.getIconImage(App.THEME.getResourcePath("image", "tick")));
    private final boolean listView;

    public ModrinthSearchHitCard(final ModrinthSearchHit mod, final ModManagement instanceOrServer,
            ActionListener installAl,
            ActionListener removeAl) {
        this(mod, instanceOrServer, installAl, removeAl, false);
    }

    /**
     * @param listView lay out as a compact full-width list row instead of a card
     */
    public ModrinthSearchHitCard(final ModrinthSearchHit mod, final ModManagement instanceOrServer,
            ActionListener installAl,
            ActionListener removeAl, boolean listView) {
        this.listView = listView;
        this.mod = mod;
        this.instanceOrServer = instanceOrServer;

        JLabel icon = new JLabel(Utils.getIconImage("/assets/image/no-icon.png"));
        icon.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        icon.setVisible(false);

        JButton viewButton = new JButton(GetText.tr("View"));

        addButton.addActionListener(e -> {
            installAl.actionPerformed(e);
            updateInstalledStatus();
        });
        reinstallButton.addActionListener(installAl);
        removeButton.addActionListener(e -> {
            removeAl.actionPerformed(e);
            updateInstalledStatus();
        });
        viewButton.addActionListener(e -> OS.openWebBrowser(String.format("https://modrinth.com/mod/%s", mod.slug)));

        if (listView) {
            CompactResultRow.layout(this, icon, mod.title, mod.author, mod.description, mod.downloads,
                    installedLabel, addButton, reinstallButton, removeButton, viewButton);
        } else {
            layoutAsCard(icon, viewButton);
        }

        if (mod.iconUrl != null && !mod.iconUrl.isEmpty()) {
            int iconSize = listView ? CompactResultRow.ICON_SIZE : 60;
            new BackgroundImageWorker(icon, mod.iconUrl, iconSize, iconSize).execute();
        }

        updateInstalledStatus();
    }

    private void layoutAsCard(JLabel icon, JButton viewButton) {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(250, 180));

        JPanel summaryPanel = new JPanel(new BorderLayout());
        JTextArea summary = new JTextArea();
        summary.setText(mod.description);
        summary.setBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5));
        summary.setEditable(false);
        summary.setHighlighter(null);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        summary.setEditable(false);

        summaryPanel.add(icon, BorderLayout.WEST);
        summaryPanel.add(summary, BorderLayout.CENTER);
        summaryPanel.setBorder(new EmptyBorder(0, 0, 10, 0));

        JPanel buttonsPanel = new JPanel(new FlowLayout());

        buttonsPanel.add(addButton);
        buttonsPanel.add(reinstallButton);
        buttonsPanel.add(removeButton);
        buttonsPanel.add(viewButton);

        add(summaryPanel, BorderLayout.CENTER);
        add(buttonsPanel, BorderLayout.SOUTH);
    }

    @Override
    public Dimension getPreferredSize() {
        return listView ? CompactResultRow.preferredSize(super.getPreferredSize()) : super.getPreferredSize();
    }

    private void updateInstalledStatus() {
        boolean alreadyInstalled = instanceOrServer == null ? false
                : instanceOrServer.getMods().stream()
                        .anyMatch(m -> m.isFromModrinth() && m.modrinthProject.id.equals(mod.projectId));

        addButton.setVisible(!alreadyInstalled);
        reinstallButton.setVisible(alreadyInstalled);
        removeButton.setVisible(alreadyInstalled);

        if (listView) {
            installedLabel.setVisible(alreadyInstalled);
            return;
        }

        setBorder(new IconTitledBorder(mod.title, App.THEME.getBoldFont().deriveFont(12f),
                alreadyInstalled ? Utils.getIconImage(App.THEME.getResourcePath("image", "tick")) : null));
    }
}
