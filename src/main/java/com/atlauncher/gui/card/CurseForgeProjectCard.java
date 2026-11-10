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
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.data.ModManagement;
import com.atlauncher.data.ResultsView;
import com.atlauncher.data.curseforge.CurseForgeAttachment;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.minecraft.loaders.LoaderType;
import com.atlauncher.gui.borders.IconTitledBorder;
import com.atlauncher.utils.OS;
import com.atlauncher.utils.Utils;
import com.atlauncher.workers.BackgroundImageWorker;

public final class CurseForgeProjectCard extends JPanel {
    private final CurseForgeProject mod;
    private final ModManagement instanceOrServer;

    private final JButton addButton = new JButton(GetText.tr("Add"));
    private final JButton reinstallButton = new JButton(GetText.tr("Reinstall"));
    private final JButton removeButton = new JButton(GetText.tr("Remove"));
    private final JLabel installedLabel = new JLabel(
            Utils.getIconImage(App.THEME.getResourcePath("image", "tick")));
    private final ResultsView view;

    public CurseForgeProjectCard(final CurseForgeProject mod, final ModManagement instanceOrServer,
            ActionListener installAl,
            ActionListener removeAl) {
        this(mod, instanceOrServer, installAl, removeAl, ResultsView.CARDS, Collections.emptySet());
    }

    /**
     * @param view    as a card, a compact list row or a comfortable list row
     * @param loaders provider-reported loaders to show as icons; empty for none
     */
    public CurseForgeProjectCard(final CurseForgeProject mod, final ModManagement instanceOrServer,
            ActionListener installAl,
            ActionListener removeAl, ResultsView view, Set<LoaderType> loaders) {
        this.view = view;
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
        viewButton.addActionListener(e -> OS.openWebBrowser(mod.getWebsiteUrl()));

        String author = mod.authors == null || mod.authors.isEmpty() ? null : mod.authors.get(0).name;
        JPanel loaderStrip = LoaderIndicatorStrip.create(loaders, "CurseForge");

        if (view.isRow()) {
            ResultRow.layout(this, view == ResultsView.COMPACT_LIST, icon, mod.name, author, mod.summary,
                    mod.downloadCount, loaderStrip, installedLabel, addButton, reinstallButton, removeButton,
                    viewButton);
        } else {
            layoutAsCard(icon, viewButton, loaderStrip);
        }

        int iconSize = view.isRow() ? ResultRow.iconSize(view == ResultsView.COMPACT_LIST) : 60;
        Optional<CurseForgeAttachment> attachment = mod.getLogo();
        attachment.ifPresent(
                curseForgeAttachment -> new BackgroundImageWorker(icon, curseForgeAttachment.thumbnailUrl, iconSize,
                        iconSize).execute());

        updateInstalledStatus();
    }

    private void layoutAsCard(JLabel icon, JButton viewButton, JPanel loaderStrip) {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(250, 180));

        JPanel summaryPanel = new JPanel(new BorderLayout());
        JTextArea summary = new JTextArea();
        summary.setText(mod.summary);
        summary.setBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5));
        summary.setEditable(false);
        summary.setHighlighter(null);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        summary.setEditable(false);

        summaryPanel.add(icon, BorderLayout.WEST);
        summaryPanel.add(summary, BorderLayout.CENTER);
        if (loaderStrip != null) {
            // lines up with the project icon's 5px inset
            loaderStrip.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 0));
            summaryPanel.add(loaderStrip, BorderLayout.SOUTH);
        }
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
        return view.isRow() ? ResultRow.preferredSize(super.getPreferredSize()) : super.getPreferredSize();
    }

    private void updateInstalledStatus() {
        boolean alreadyInstalled = instanceOrServer == null ? false : instanceOrServer.getMods().stream()
                .anyMatch(m -> m.isFromCurseForge() && m.curseForgeProjectId == mod.id);

        addButton.setVisible(!alreadyInstalled);
        reinstallButton.setVisible(alreadyInstalled);
        removeButton.setVisible(alreadyInstalled);

        if (view.isRow()) {
            installedLabel.setVisible(alreadyInstalled);
            return;
        }

        setBorder(new IconTitledBorder(mod.name, App.THEME.getBoldFont().deriveFont(12f),
                alreadyInstalled ? Utils.getIconImage(App.THEME.getResourcePath("image", "tick")) : null));
    }
}
