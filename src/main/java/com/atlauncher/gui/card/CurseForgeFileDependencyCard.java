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
import java.util.Optional;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.data.ModManagement;
import com.atlauncher.data.RequiredDependency;
import com.atlauncher.data.curseforge.CurseForgeAttachment;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.gui.dialogs.CurseForgeProjectFileSelectorDialog;
import com.atlauncher.network.Analytics;
import com.atlauncher.network.analytics.AnalyticsEvent;
import com.atlauncher.utils.OS;
import com.atlauncher.utils.Utils;
import com.atlauncher.workers.BackgroundImageWorker;

public final class CurseForgeFileDependencyCard extends JPanel {
    private final CurseForgeProjectFileSelectorDialog parent;
    private final CurseForgeProject mod;
    private final ModManagement instanceOrServer;
    private final RequiredDependency.State state;
    private final String status;
    private final boolean preferConnectorBuilds;

    /**
     * @param mod                   the dependency's project, or null when it couldn't be found
     * @param status                what to tell the user about its installed state, or null
     * @param preferConnectorBuilds whether the file needing it runs through Connector, so its selector suggests a
     *                              Fabric build
     */
    public CurseForgeFileDependencyCard(CurseForgeProjectFileSelectorDialog parent, CurseForgeProject mod,
            ModManagement instanceOrServer, RequiredDependency.State state, String status,
            boolean preferConnectorBuilds) {
        this.parent = parent;
        this.mod = mod;
        this.instanceOrServer = instanceOrServer;
        this.state = state;
        this.status = status;
        this.preferConnectorBuilds = preferConnectorBuilds;

        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(250, 180));

        setupComponents();
    }

    private static JTextArea wrappedText(String text) {
        JTextArea area = new JTextArea(text);
        area.setBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5));
        area.setEditable(false);
        area.setHighlighter(null);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        return area;
    }

    private void setupComponents() {
        JPanel summaryPanel = new JPanel(new BorderLayout());
        JTextArea summary = wrappedText(mod == null ? "" : mod.summary);

        JLabel icon = new JLabel(Utils.getIconImage("/assets/image/no-icon.png"));
        icon.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        icon.setVisible(false);

        if (status != null) {
            JTextArea statusText = wrappedText(status);
            statusText.setForeground(UIManager.getColor("yellow"));
            summaryPanel.add(statusText, BorderLayout.NORTH);
        }

        summaryPanel.add(icon, BorderLayout.WEST);
        summaryPanel.add(summary, BorderLayout.CENTER);
        summaryPanel.setBorder(new EmptyBorder(0, 0, 10, 0));

        add(summaryPanel, BorderLayout.CENTER);

        TitledBorder border = new TitledBorder(null, mod == null ? GetText.tr("Unknown Project") : mod.name,
                TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION,
                App.THEME.getBoldFont().deriveFont(12f));
        setBorder(border);

        if (mod == null) {
            return;
        }

        JPanel buttonsPanel = new JPanel(new FlowLayout());
        JButton viewButton = new JButton(GetText.tr("View"));

        if (RequiredDependency.offersAdd(state)) {
            JButton addButton = new JButton(GetText.tr("Add"));
            buttonsPanel.add(addButton);

            addButton.addActionListener(e -> {
                Analytics.trackEvent(AnalyticsEvent.forAddMod(mod));
                CurseForgeProjectFileSelectorDialog curseForgeProjectFileSelectorDialog = new CurseForgeProjectFileSelectorDialog(
                        parent, mod, instanceOrServer, preferConnectorBuilds);
                curseForgeProjectFileSelectorDialog.setVisible(true);
                parent.reloadDependenciesPanel();
            });
        }

        buttonsPanel.add(viewButton);
        viewButton.addActionListener(e -> OS.openWebBrowser(mod.getWebsiteUrl()));

        add(buttonsPanel, BorderLayout.SOUTH);

        Optional<CurseForgeAttachment> attachment = mod.getLogo();
        attachment.ifPresent(
                curseForgeAttachment -> new BackgroundImageWorker(icon, curseForgeAttachment.thumbnailUrl, 60, 60)
                        .execute());
    }
}
