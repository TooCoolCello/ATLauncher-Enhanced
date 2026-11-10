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
package com.atlauncher.gui.dialogs;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.builders.HTMLBuilder;
import com.atlauncher.constants.Constants;
import com.atlauncher.data.AddModRestriction;
import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.Instance;
import com.atlauncher.data.LoaderIndicators;
import com.atlauncher.data.ModCompatibility;
import com.atlauncher.data.ModCompatibilityChecker;
import com.atlauncher.data.ModManagement;
import com.atlauncher.data.ModPlatform;
import com.atlauncher.data.ResultsView;
import com.atlauncher.data.Settings;
import com.atlauncher.data.curseforge.CurseForgeCategoryForGame;
import com.atlauncher.data.curseforge.CurseForgeCoreApiResponse;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthCategory;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthSearchHit;
import com.atlauncher.data.modrinth.ModrinthSearchResult;
import com.atlauncher.exceptions.InvalidMinecraftVersion;
import com.atlauncher.gui.card.CompatibilityBadge;
import com.atlauncher.gui.card.CurseForgeProjectCard;
import com.atlauncher.gui.card.ModrinthSearchHitCard;
import com.atlauncher.gui.components.ResultsViewPicker;
import com.atlauncher.gui.layouts.WrapLayout;
import com.atlauncher.gui.panels.LoadingPanel;
import com.atlauncher.gui.panels.NoCurseModsPanel;
import com.atlauncher.managers.ConfigManager;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.managers.MinecraftManager;
import com.atlauncher.network.Analytics;
import com.atlauncher.network.analytics.AnalyticsEvent;
import com.atlauncher.utils.ComboItem;
import com.atlauncher.utils.CurseForgeApi;
import com.atlauncher.utils.ModrinthApi;
import com.atlauncher.utils.OS;
import com.atlauncher.utils.Utils;
import com.formdev.flatlaf.icons.FlatSearchIcon;

public final class AddModsDialog extends JDialog {
    private final ModManagement instanceOrServer;

    private boolean updating = false;

    private final JPanel contentPanel = new JPanel(new WrapLayout());
    private final JPanel topPanel = new JPanel(new BorderLayout());
    private final JPanel warningPanel = new JPanel();
    private final JTextField searchField = new JTextField(16);
    // the local collections library, from either provider's tab
    private final JButton collectionsButton = new JButton(GetText.tr("Collections..."));
    private final JButton fromModpackButton = new JButton(GetText.tr("From Modpack..."));
    private final JLabel platformMessageLabel = new JLabel();
    private final JComboBox<ComboItem<ModPlatform>> hostComboBox = new JComboBox<>();
    private final JComboBox<ComboItem<String>> sectionComboBox = new JComboBox<>();
    private final JComboBox<ComboItem<String>> sortComboBox = new JComboBox<>();
    private final JComboBox<ComboItem<String>> categoriesComboBox = new JComboBox<>();
    private final JComboBox<Integer> pageSizeComboBox = new JComboBox<>();
    // bumped for every fetch so only the latest one may update the UI
    private final AtomicInteger fetchGeneration = new AtomicInteger();
    // Cards, Compact List or List: Add Mods' own choice, apart from Collections'
    private final ResultsViewPicker viewPicker = new ResultsViewPicker(App.settings.addModsView(), this::setViewMode);
    // redraws the results currently shown (EDT only); null while loading or before any results
    private Runnable rerenderResults = null;
    // fetches Modrinth results' versions for their compatibility marks; what it fetched lasts while this is open
    private final ModCompatibilityChecker compatibilityChecker = new ModCompatibilityChecker();
    private final JCheckBox hideIncompatibleCheckBox = new JCheckBox(GetText.tr("Hide incompatible"));
    private final JLabel hiddenCountLabel = new JLabel();
    // this page's results with their compatibility marks, for hiding the incompatible ones (EDT only)
    private final Map<JComponent, CompatibilityBadge> pageResults = new LinkedHashMap<>();

    // #. {0} is the loader api (Fabric API/QSL)
    private final JButton installFabricApiButton = new JButton(GetText.tr("Install {0}", "Fabric API"));

    private final JLabel fabricApiWarningLabel = new JLabel(
            "<html><p align=\"center\" style=\"color: "
                    + String.format("#%06x", 0xFFFFFF & UIManager.getColor("yellow").getRGB())
                    // #. {0} is the loader (Fabric/Quilt), {1} is the loader api (Fabric API/QSL)
                    + "\">" + GetText.tr("Before installing {0} mods, you should install {1} first!",
                            "Fabric", "Fabric API")
                    + "</p></html>");

    // #. {0} is the loader api (Fabric API/QSL)
    private final JButton installLegacyFabricApiButton = new JButton(GetText.tr("Install {0}", "Legacy Fabric API"));

    private final JLabel legacyFabricApiWarningLabel = new JLabel(
            "<html><p align=\"center\" style=\"color: "
                    + String.format("#%06x", 0xFFFFFF & UIManager.getColor("yellow").getRGB())
                    // #. {0} is the loader (Fabric/Quilt), {1} is the loader api (Fabric API/QSL)
                    + "\">" + GetText.tr("Before installing {0} mods, you should install {1} first!",
                            "Legacy Fabric", "Legacy Fabric API")
                    + "</p></html>");

    private final JButton installQuiltStandardLibrariesButton = new JButton(
            // #. {0} is the loader api (Fabric API/QSL)
            GetText.tr("Install {0}", "Quilt Standard Libraries"));

    private final JLabel quiltStandardLibrariesWarningLabel = new JLabel(
            "<html><p align=\"center\" style=\"color: "
                    + String.format("#%06x", 0xFFFFFF & UIManager.getColor("yellow").getRGB())
                    + "\">"
                    // #. {0} is the loader (Fabric/Quilt), {1} is the loader api (Fabric API/QSL)
                    + GetText.tr("Before installing {0} mods, you should install {1} first!",
                            "Quilt", "Quilt Standard Libraries")
                    + "</p></html>");

    // #. {0} is the loader api (Fabric API/QSL)
    private final JButton installForgifiedFabricApiButton = new JButton(
            GetText.tr("Install {0}", "Forgified Fabric API"));

    private final JLabel forgifiedFabricApiWarningLabel = new JLabel(
            "<html><p align=\"center\" style=\"color: "
                    + String.format("#%06x", 0xFFFFFF & UIManager.getColor("yellow").getRGB())
                    // #. {0} is the loader (Fabric/Quilt), {1} is the loader api (Fabric API/QSL)
                    + "\">" + GetText.tr("Before installing {0} mods, you should install {1} first!",
                            "Fabric", "Forgified Fabric API")
                    + "</p></html>");

    private JScrollPane jscrollPane;
    private JButton nextButton;
    private JButton prevButton;
    private final JPanel mainPanel = new JPanel(new BorderLayout());
    private int page = 0;

    public AddModsDialog(ModManagement instanceOrServer) {
        this(App.launcher.getParent(), instanceOrServer);
    }

    public AddModsDialog(Window parent, ModManagement instanceOrServer) {
        // #. {0} is the name of the mod we're installing
        super(parent, GetText.tr("Adding Mods For {0}", instanceOrServer.getName()), ModalityType.DOCUMENT_MODAL);
        this.instanceOrServer = instanceOrServer;

        this.setPreferredSize(new Dimension(800, 500));
        this.setMinimumSize(new Dimension(800, 500));
        this.setResizable(true);
        this.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        int selectedHostIndex = 0;
        String platformMessage = null;

        if (ConfigManager.getConfigItem("platforms.curseforge.modsEnabled", true)) {
            hostComboBox.addItem(new ComboItem<>(ModPlatform.CURSEFORGE, "CurseForge"));

            if (App.settings.defaultModPlatform == ModPlatform.CURSEFORGE) {
                selectedHostIndex = hostComboBox.getItemCount() - 1;
                platformMessage = ConfigManager.getConfigItem("platforms.curseforge.message", null);
            }
        }

        if (ConfigManager.getConfigItem("platforms.modrinth.modsEnabled", true)) {
            hostComboBox.addItem(new ComboItem<>(ModPlatform.MODRINTH, "Modrinth"));

            if (App.settings.defaultModPlatform == ModPlatform.MODRINTH) {
                selectedHostIndex = hostComboBox.getItemCount() - 1;
                platformMessage = ConfigManager.getConfigItem("platforms.modrinth.message", null);
            }
        }

        hostComboBox.setSelectedIndex(selectedHostIndex);

        searchField.putClientProperty("JTextField.placeholderText", GetText.tr("Search"));
        searchField.putClientProperty("JTextField.leadingIcon", new FlatSearchIcon());
        searchField.putClientProperty("JTextField.showClearButton", true);
        searchField.putClientProperty("JTextField.clearCallback", (Runnable) () -> {
            searchField.setText("");
            searchForMods();
        });

        if (platformMessage != null) {
            platformMessageLabel.setText(new HTMLBuilder().center().text(platformMessage).build());
        }
        platformMessageLabel.setVisible(platformMessage != null);

        addSectionAndSortOptions(true);

        addCategories();

        setupComponents();

        this.loadDefaultMods();

        this.pack();
        this.setLocationRelativeTo(parent);
    }

    private void setupComponents() {
        Analytics.sendScreenView("Add Mods Dialog");

        this.topPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));

        this.warningPanel.setLayout(new BoxLayout(this.warningPanel, BoxLayout.X_AXIS));

        JPanel searchButtonsPanel = searchRow();
        searchButtonsPanel.add(this.hostComboBox);
        searchButtonsPanel.add(Box.createHorizontalStrut(5));
        searchButtonsPanel.add(this.sectionComboBox);
        searchButtonsPanel.add(Box.createHorizontalStrut(5));
        searchButtonsPanel.add(this.sortComboBox);
        searchButtonsPanel.add(Box.createHorizontalStrut(5));
        searchButtonsPanel.add(this.categoriesComboBox);
        searchButtonsPanel.add(Box.createHorizontalStrut(20));
        searchButtonsPanel.add(this.searchField);
        searchButtonsPanel.add(Box.createHorizontalStrut(5));
        searchButtonsPanel.add(this.collectionsButton);
        this.collectionsButton.setToolTipText(GetText.tr("Your collections of CurseForge and Modrinth projects, and "
                + "Modrinth collections you've opened or saved"));
        this.collectionsButton.addActionListener(
                e -> new CollectionsDialog(this, instanceOrServer).setVisible(true));
        // only where Add Mods has a Mods section
        LoaderVersion packModsLoader = instanceOrServer.getLoaderVersion();
        if (packModsLoader != null && !packModsLoader.isPaper() && !packModsLoader.isPurpur()) {
            searchButtonsPanel.add(Box.createHorizontalStrut(5));
            searchButtonsPanel.add(this.fromModpackButton);
            this.fromModpackButton.setToolTipText(GetText.tr("Add mods from someone else's modpack, or another "
                    + "instance, checked against this one"));
            this.fromModpackButton.addActionListener(e -> {
                new PackModsDialog(this, instanceOrServer).setVisible(true);
                // what was added shows as installed
                rerender();
            });
        }

        this.installFabricApiButton.addActionListener(e -> {
            ModPlatform selectedHost = ((ComboItem<ModPlatform>) hostComboBox.getSelectedItem()).getValue();
            boolean isCurseForge = selectedHost == ModPlatform.CURSEFORGE;
            if (isCurseForge) {
                final ProgressDialog<CurseForgeProject> curseForgeProjectLookupDialog = new ProgressDialog<>(
                        // #. {0} is the loader api were getting info from (Fabric/Quilt)
                        GetText.tr("Getting {0} Information", "Fabric API"), 0,
                        // #. {0} is the loader api were getting info from (Fabric/Quilt)
                        GetText.tr("Getting {0} Information", "Fabric API"),
                        "Aborting Getting Fabric API Information", this);

                curseForgeProjectLookupDialog.addThread(new Thread(() -> {
                    curseForgeProjectLookupDialog
                            .setReturnValue(CurseForgeApi.getProjectById(Constants.CURSEFORGE_FABRIC_MOD_ID));

                    curseForgeProjectLookupDialog.close();
                }));

                curseForgeProjectLookupDialog.start();

                CurseForgeProject mod = curseForgeProjectLookupDialog.getReturnValue();

                if (mod == null) {
                    // #. {0} is the loader api were getting info from (Fabric/Quilt)
                    DialogManager.okDialog().setParent(this).setTitle(GetText.tr("Error Getting {0} Information", "Fabric API"))
                            // #. {0} is the loader (Fabric/Quilt) {1} is the platform (CurseForge/Modrinth)
                            .setContent(new HTMLBuilder().center().text(GetText.tr(
                                    "There was an error getting {0} information from {1}. Please try again later.",
                                    "Fabric API", "CurseForge"))
                                    .build())
                            .setType(DialogManager.ERROR).show();
                    return;
                }

                Analytics.trackEvent(AnalyticsEvent.forAddMod("Fabric API", "CurseForge", "mod"));
                CurseForgeProjectFileSelectorDialog curseForgeProjectFileSelectorDialog = new CurseForgeProjectFileSelectorDialog(
                        this, mod, instanceOrServer);
                curseForgeProjectFileSelectorDialog.setVisible(true);

                if (instanceOrServer.getMods().stream().anyMatch(
                        m -> (m.isFromCurseForge() && m.getCurseForgeModId() == Constants.CURSEFORGE_FABRIC_MOD_ID)
                                || (m.isFromModrinth()
                                        && m.modrinthProject.id.equalsIgnoreCase(Constants.MODRINTH_FABRIC_MOD_ID)))) {
                    fabricApiWarningLabel.setVisible(false);
                    installFabricApiButton.setVisible(false);
                }
            } else {
                final ProgressDialog<ModrinthProject> modrinthProjectLookupDialog = new ProgressDialog<>(
                        // #. {0} is the loader api were getting info from (Fabric/Quilt)
                        GetText.tr("Getting {0} Information", "Fabric API"), 0,
                        // #. {0} is the loader api were getting info from (Fabric/Quilt)
                        GetText.tr("Getting {0} Information", "Fabric API"),
                        "Aborting Getting Fabric API Information", this);

                modrinthProjectLookupDialog.addThread(new Thread(() -> {
                    modrinthProjectLookupDialog
                            .setReturnValue(ModrinthApi.getProject(Constants.MODRINTH_FABRIC_MOD_ID));

                    modrinthProjectLookupDialog.close();
                }));

                modrinthProjectLookupDialog.start();

                ModrinthProject mod = modrinthProjectLookupDialog.getReturnValue();

                if (mod == null) {
                    // #. {0} is the loader api were getting info from (Fabric/Quilt)
                    DialogManager.okDialog().setParent(this).setTitle(GetText.tr("Error Getting {0} Information", "Fabric API"))
                            // #. {0} is the loader (Fabric/Quilt) {1} is the platform (CurseForge/Modrinth)
                            .setContent(new HTMLBuilder().center().text(GetText.tr(
                                    "There was an error getting {0} information from {1}. Please try again later.",
                                    "Fabric API",
                                    "Modrinth"))
                                    .build())
                            .setType(DialogManager.ERROR).show();
                    return;
                }

                Analytics.trackEvent(AnalyticsEvent.forAddMod("Fabric API", "Modrinth", "mod"));
                ModrinthVersionSelectorDialog modrinthVersionSelectorDialog = new ModrinthVersionSelectorDialog(this,
                        mod, instanceOrServer);
                modrinthVersionSelectorDialog.setVisible(true);

                if (instanceOrServer.getMods().stream().anyMatch(
                        m -> (m.isFromCurseForge() && m.getCurseForgeModId() == Constants.CURSEFORGE_FABRIC_MOD_ID)
                                || (m.isFromModrinth()
                                        && m.modrinthProject.id.equalsIgnoreCase(Constants.MODRINTH_FABRIC_MOD_ID)))) {
                    fabricApiWarningLabel.setVisible(false);
                    installFabricApiButton.setVisible(false);
                }
            }

            if (searchField.getText().isEmpty()) {
                loadDefaultMods();
            } else {
                searchForMods();
            }
        });

        this.installLegacyFabricApiButton.addActionListener(e -> {
            boolean isCurseForge = ((ComboItem<ModPlatform>) hostComboBox.getSelectedItem())
                    .getValue() == ModPlatform.CURSEFORGE;
            if (isCurseForge) {
                final ProgressDialog<CurseForgeProject> curseForgeProjectLookupDialog = new ProgressDialog<>(
                        // #. {0} is the loader api were getting info from (Fabric/Quilt)
                        GetText.tr("Getting {0} Information", "Legacy Fabric API"), 0,
                        // #. {0} is the loader api were getting info from (Fabric/Quilt)
                        GetText.tr("Getting {0} Information", "Legacy Fabric API"),
                        "Aborting Getting Legacy Fabric API Information", this);

                curseForgeProjectLookupDialog.addThread(new Thread(() -> {
                    curseForgeProjectLookupDialog
                            .setReturnValue(CurseForgeApi.getProjectById(Constants.CURSEFORGE_LEGACY_FABRIC_MOD_ID));

                    curseForgeProjectLookupDialog.close();
                }));

                curseForgeProjectLookupDialog.start();

                CurseForgeProject mod = curseForgeProjectLookupDialog.getReturnValue();

                if (mod == null) {
                    // #. {0} is the loader api were getting info from (Fabric/Quilt)
                    DialogManager.okDialog().setParent(this)
                            .setTitle(GetText.tr("Error Getting {0} Information", "Legacy Fabric API"))
                            // #. {0} is the loader (Fabric/Quilt) {1} is the platform (CurseForge/Modrinth)
                            .setContent(new HTMLBuilder().center().text(GetText.tr(
                                    "There was an error getting {0} information from {1}. Please try again later.",
                                    "Legacy Fabric API",
                                    "CurseForge"))
                                    .build())
                            .setType(DialogManager.ERROR).show();
                    return;
                }

                Analytics.trackEvent(AnalyticsEvent.forAddMod("Legacy Fabric API", "CurseForge", "mod"));
                CurseForgeProjectFileSelectorDialog curseForgeProjectFileSelectorDialog = new CurseForgeProjectFileSelectorDialog(
                        this, mod, instanceOrServer);
                curseForgeProjectFileSelectorDialog.setVisible(true);

                if (instanceOrServer.getMods().stream().anyMatch(
                        m -> (m.isFromCurseForge()
                                && m.getCurseForgeModId() == Constants.CURSEFORGE_LEGACY_FABRIC_MOD_ID)
                                || (m.isFromModrinth()
                                        && m.modrinthProject.id
                                                .equalsIgnoreCase(Constants.MODRINTH_LEGACY_FABRIC_MOD_ID)))) {
                    legacyFabricApiWarningLabel.setVisible(false);
                    installLegacyFabricApiButton.setVisible(false);
                }
            } else {
                final ProgressDialog<ModrinthProject> modrinthProjectLookupDialog = new ProgressDialog<>(
                        // #. {0} is the loader api were getting info from (Fabric/Quilt)
                        GetText.tr("Getting {0} Information", "Legacy Fabric API"), 0,
                        // #. {0} is the loader api were getting info from (Fabric/Quilt)
                        GetText.tr("Getting {0} Information", "Legacy Fabric API"),
                        "Aborting Getting Legacy Fabric API Information", this);

                modrinthProjectLookupDialog.addThread(new Thread(() -> {
                    modrinthProjectLookupDialog
                            .setReturnValue(ModrinthApi.getProject(Constants.MODRINTH_LEGACY_FABRIC_MOD_ID));

                    modrinthProjectLookupDialog.close();
                }));

                modrinthProjectLookupDialog.start();

                ModrinthProject mod = modrinthProjectLookupDialog.getReturnValue();

                if (mod == null) {
                    // #. {0} is the loader api were getting info from (Fabric/Quilt)
                    DialogManager.okDialog().setParent(this)
                            .setTitle(GetText.tr("Error Getting {0} Information", "Legacy Fabric API"))
                            // #. {0} is the loader (Fabric/Quilt) {1} is the platform (CurseForge/Modrinth)
                            .setContent(new HTMLBuilder().center().text(GetText.tr(
                                    "There was an error getting {0} information from {1}. Please try again later.",
                                    "Legacy Fabric API",
                                    "Modrinth"))
                                    .build())
                            .setType(DialogManager.ERROR).show();
                    return;
                }

                Analytics.trackEvent(AnalyticsEvent.forAddMod("Legacy Fabric API", "Modrinth", "mod"));
                ModrinthVersionSelectorDialog modrinthVersionSelectorDialog = new ModrinthVersionSelectorDialog(this,
                        mod, instanceOrServer);
                modrinthVersionSelectorDialog.setVisible(true);

                if (instanceOrServer.getMods().stream().anyMatch(
                        m -> (m.isFromCurseForge()
                                && m.getCurseForgeModId() == Constants.CURSEFORGE_LEGACY_FABRIC_MOD_ID)
                                || (m.isFromModrinth()
                                        && m.modrinthProject.id
                                                .equalsIgnoreCase(Constants.MODRINTH_LEGACY_FABRIC_MOD_ID)))) {
                    legacyFabricApiWarningLabel.setVisible(false);
                    installLegacyFabricApiButton.setVisible(false);
                }
            }

            if (searchField.getText().isEmpty()) {
                loadDefaultMods();
            } else {
                searchForMods();
            }
        });

        this.installQuiltStandardLibrariesButton.addActionListener(e -> {
            final ProgressDialog<ModrinthProject> modrinthProjectLookupDialog = new ProgressDialog<>(
                    // #. {0} is the loader api were getting info from (Fabric/Quilt)
                    GetText.tr("Getting {0} Information", "Quilt Standard Libaries"), 0,
                    // #. {0} is the loader api were getting info from (Fabric/Quilt)
                    GetText.tr("Getting {0} Information", "Quilt Standard Libaries"),
                    "Aborting Getting Quilt Standard Libaries Information", this);

            modrinthProjectLookupDialog.addThread(new Thread(() -> {
                modrinthProjectLookupDialog
                        .setReturnValue(ModrinthApi.getProject(Constants.MODRINTH_QSL_MOD_ID));

                modrinthProjectLookupDialog.close();
            }));

            modrinthProjectLookupDialog.start();

            ModrinthProject mod = modrinthProjectLookupDialog.getReturnValue();

            if (mod == null) {
                DialogManager.okDialog().setParent(this)
                        // #. {0} is the loader api were getting info from (Fabric/Quilt)
                        .setTitle(GetText.tr("Error Getting {0} Information", "Quilt Standard Libaries"))
                        // #. {0} is the loader (Fabric/Quilt) {1} is the platform (CurseForge/Modrinth)
                        .setContent(new HTMLBuilder().center().text(GetText.tr(
                                "There was an error getting {0} information from {1}. Please try again later.",
                                "Quilt Standard Libaries", "Modrinth"))
                                .build())
                        .setType(DialogManager.ERROR).show();
                return;
            }

            Analytics.trackEvent(AnalyticsEvent.forAddMod("Quilt Standard Libraries", "Modrinth", "mod"));
            ModrinthVersionSelectorDialog modrinthVersionSelectorDialog = new ModrinthVersionSelectorDialog(this, mod,
                    instanceOrServer);
            modrinthVersionSelectorDialog.setVisible(true);

            if (instanceOrServer.getMods().stream().anyMatch(
                    m -> m.isFromModrinth()
                            && m.modrinthProject.id.equalsIgnoreCase(Constants.MODRINTH_QSL_MOD_ID))) {
                quiltStandardLibrariesWarningLabel.setVisible(false);
                installQuiltStandardLibrariesButton.setVisible(false);
            }
        });

        this.installForgifiedFabricApiButton.addActionListener(e -> {
            boolean isCurseForge = ((ComboItem<ModPlatform>) hostComboBox.getSelectedItem())
                    .getValue() == ModPlatform.CURSEFORGE;
            if (isCurseForge) {
                final ProgressDialog<CurseForgeProject> curseForgeProjectLookupDialog = new ProgressDialog<>(
                        // #. {0} is the loader api were getting info from (Fabric/Quilt)
                        GetText.tr("Getting {0} Information", "Forgified Fabric API"), 0,
                        // #. {0} is the loader api were getting info from (Fabric/Quilt)
                        GetText.tr("Getting {0} Information", "Forgified Fabric API"),
                        "Aborting Getting Forgified Fabric API Information", this);

                curseForgeProjectLookupDialog.addThread(new Thread(() -> {
                    curseForgeProjectLookupDialog
                            .setReturnValue(
                                    CurseForgeApi.getProjectById(Constants.CURSEFORGE_FORGIFIED_FABRIC_API_MOD_ID));

                    curseForgeProjectLookupDialog.close();
                }));

                curseForgeProjectLookupDialog.start();

                CurseForgeProject mod = curseForgeProjectLookupDialog.getReturnValue();

                if (mod == null) {
                    // #. {0} is the loader api were getting info from (Fabric/Quilt)
                    DialogManager.okDialog().setParent(this)
                            .setTitle(GetText.tr("Error Getting {0} Information", "Forgified Fabric API"))
                            // #. {0} is the loader (Fabric/Quilt) {1} is the platform (CurseForge/Modrinth)
                            .setContent(new HTMLBuilder().center().text(GetText.tr(
                                    "There was an error getting {0} information from {1}. Please try again later.",
                                    "Forgified Fabric API", "CurseForge"))
                                    .build())
                            .setType(DialogManager.ERROR).show();
                    return;
                }

                Analytics.trackEvent(AnalyticsEvent.forAddMod("Forgified Fabric API", "CurseForge", "mod"));
                CurseForgeProjectFileSelectorDialog curseForgeProjectFileSelectorDialog = new CurseForgeProjectFileSelectorDialog(
                        this, mod, instanceOrServer);
                curseForgeProjectFileSelectorDialog.setVisible(true);

                if (instanceOrServer.getMods().stream().anyMatch(
                        m -> (m.isFromCurseForge()
                                && m.getCurseForgeModId() == Constants.CURSEFORGE_FORGIFIED_FABRIC_API_MOD_ID)
                                || (m.isFromModrinth()
                                        && m.modrinthProject.id
                                                .equalsIgnoreCase(Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID)))) {
                    forgifiedFabricApiWarningLabel.setVisible(false);
                    installForgifiedFabricApiButton.setVisible(false);
                }
            } else {
                final ProgressDialog<ModrinthProject> modrinthProjectLookupDialog = new ProgressDialog<>(
                        // #. {0} is the loader api were getting info from (Fabric/Quilt)
                        GetText.tr("Getting {0} Information", "Fabric API"), 0,
                        // #. {0} is the loader api were getting info from (Fabric/Quilt)
                        GetText.tr("Getting {0} Information", "Fabric API"),
                        "Aborting Getting Fabric API Information", this);

                modrinthProjectLookupDialog.addThread(new Thread(() -> {
                    modrinthProjectLookupDialog
                            .setReturnValue(ModrinthApi.getProject(Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID));

                    modrinthProjectLookupDialog.close();
                }));

                modrinthProjectLookupDialog.start();

                ModrinthProject mod = modrinthProjectLookupDialog.getReturnValue();

                if (mod == null) {
                    // #. {0} is the loader api were getting info from (Fabric/Quilt)
                    DialogManager.okDialog().setParent(this)
                            .setTitle(GetText.tr("Error Getting {0} Information", "Forgified Fabric API"))
                            // #. {0} is the loader (Fabric/Quilt) {1} is the platform (CurseForge/Modrinth)
                            .setContent(new HTMLBuilder().center().text(GetText.tr(
                                    "There was an error getting {0} information from {1}. Please try again later.",
                                    "Forgified Fabric API", "Modrinth"))
                                    .build())
                            .setType(DialogManager.ERROR).show();
                    return;
                }

                Analytics.trackEvent(AnalyticsEvent.forAddMod("Forgified Fabric API", "Modrinth", "mod"));
                ModrinthVersionSelectorDialog modrinthVersionSelectorDialog = new ModrinthVersionSelectorDialog(this,
                        mod, instanceOrServer);
                modrinthVersionSelectorDialog.setVisible(true);

                if (instanceOrServer.getMods().stream().anyMatch(
                        m -> (m.isFromCurseForge()
                                && m.getCurseForgeModId() == Constants.CURSEFORGE_FORGIFIED_FABRIC_API_MOD_ID)
                                || (m.isFromModrinth()
                                        && m.modrinthProject.id
                                                .equalsIgnoreCase(Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID)))) {
                    forgifiedFabricApiWarningLabel.setVisible(false);
                    installForgifiedFabricApiButton.setVisible(false);
                }
            }

            if (searchField.getText().isEmpty()) {
                loadDefaultMods();
            } else {
                searchForMods();
            }
        });

        LoaderVersion loaderVersion = instanceOrServer.getLoaderVersion();

        if (loaderVersion != null && loaderVersion.isFabric() && instanceOrServer.getMods().stream()
                .noneMatch(m -> (m.isFromCurseForge() && m.getCurseForgeModId() == Constants.CURSEFORGE_FABRIC_MOD_ID)
                        || (m.isFromModrinth()
                                && m.modrinthProject.id.equalsIgnoreCase(Constants.MODRINTH_FABRIC_MOD_ID)))) {
            this.warningPanel.add(fabricApiWarningLabel);
            this.warningPanel.add(Box.createHorizontalGlue());
            this.warningPanel.add(installFabricApiButton);
        }

        if (loaderVersion != null && loaderVersion.isLegacyFabric() && instanceOrServer.getMods().stream()
                .noneMatch(m -> (m.isFromCurseForge()
                        && m.getCurseForgeModId() == Constants.CURSEFORGE_LEGACY_FABRIC_MOD_ID)
                        || (m.isFromModrinth()
                                && m.modrinthProject.id.equalsIgnoreCase(Constants.MODRINTH_LEGACY_FABRIC_MOD_ID)))) {
            this.warningPanel.add(legacyFabricApiWarningLabel);
            this.warningPanel.add(Box.createHorizontalGlue());
            this.warningPanel.add(installLegacyFabricApiButton);
        }

        if (loaderVersion != null && loaderVersion.isQuilt() && instanceOrServer.getMods().stream()
                .noneMatch(m -> m.isFromModrinth()
                        && m.modrinthProject.id.equalsIgnoreCase(Constants.MODRINTH_QSL_MOD_ID))) {
            this.warningPanel.add(quiltStandardLibrariesWarningLabel);
            this.warningPanel.add(Box.createHorizontalGlue());
            this.warningPanel.add(installQuiltStandardLibrariesButton);
        }

        // If on Forge/NeoForge and has Sinytra Connector installed, then show Forgified
        // Fabric API things
        if (instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector() && instanceOrServer.getMods().stream()
                .noneMatch(m -> (m.isFromCurseForge()
                        && m.getCurseForgeModId() == Constants.CURSEFORGE_FORGIFIED_FABRIC_API_MOD_ID)
                        || (m.isFromModrinth()
                                && m.modrinthProject.id
                                        .equalsIgnoreCase(Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID)))) {
            this.warningPanel.add(forgifiedFabricApiWarningLabel);
            this.warningPanel.add(Box.createHorizontalGlue());
            this.warningPanel.add(installForgifiedFabricApiButton);
        }

        this.topPanel.add(searchButtonsPanel, BorderLayout.NORTH);
        this.topPanel.add(warningPanel, BorderLayout.CENTER);

        this.jscrollPane = new JScrollPane(this.contentPanel) {
            {
                this.getVerticalScrollBar().setUnitIncrement(16);
            }
        };

        this.jscrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        mainPanel.add(this.topPanel, BorderLayout.NORTH);
        mainPanel.add(this.jscrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        JPanel bottomButtonsPanel = new JPanel(new FlowLayout());

        prevButton = new JButton("<<");
        prevButton.setEnabled(false);
        prevButton.addActionListener(e -> goToPreviousPage());

        nextButton = new JButton(">>");
        nextButton.setEnabled(false);
        nextButton.addActionListener(e -> goToNextPage());

        for (Integer pageSize : Settings.ADD_MODS_PAGE_SIZES) {
            pageSizeComboBox.addItem(pageSize);
        }
        pageSizeComboBox.setSelectedItem(App.settings.addModsPageSize);
        pageSizeComboBox.addActionListener(e -> {
            Integer selected = (Integer) pageSizeComboBox.getSelectedItem();
            if (selected == null || selected == App.settings.addModsPageSize) {
                return;
            }

            App.settings.addModsPageSize = selected;
            App.settings.save();

            page = 0;
            getMods();
        });

        bottomButtonsPanel.add(viewPicker);
        bottomButtonsPanel.add(Box.createHorizontalStrut(10));
        bottomButtonsPanel.add(prevButton);
        bottomButtonsPanel.add(nextButton);
        bottomButtonsPanel.add(Box.createHorizontalStrut(10));
        bottomButtonsPanel.add(new JLabel(GetText.tr("Per page:")));
        bottomButtonsPanel.add(pageSizeComboBox);

        hideIncompatibleCheckBox.setSelected(App.settings.addModsHideIncompatible);
        hideIncompatibleCheckBox.setToolTipText(GetText.tr("Hide results with no file for this instance's Minecraft "
                + "version and loader. Results not checked yet, or that couldn't be checked, stay visible."));
        hideIncompatibleCheckBox.addActionListener(e -> {
            App.settings.addModsHideIncompatible = hideIncompatibleCheckBox.isSelected();
            App.settings.save();
            applyCompatibilityFilter();
        });
        hiddenCountLabel.setVisible(false);
        bottomButtonsPanel.add(Box.createHorizontalStrut(10));
        bottomButtonsPanel.add(hideIncompatibleCheckBox);
        bottomButtonsPanel.add(hiddenCountLabel);

        platformMessageLabel.setForeground(UIManager.getColor("yellow"));
        bottomPanel.add(platformMessageLabel, BorderLayout.NORTH);
        bottomPanel.add(bottomButtonsPanel, BorderLayout.CENTER);

        this.add(mainPanel, BorderLayout.CENTER);
        this.add(bottomPanel, BorderLayout.SOUTH);

        this.hostComboBox.addActionListener(e -> {
            updating = true;
            page = 0;
            ModPlatform selectedModPlatform = ((ComboItem<ModPlatform>) hostComboBox.getSelectedItem()).getValue();

            boolean isCurseForge = selectedModPlatform == ModPlatform.CURSEFORGE;
            boolean isModrinth = selectedModPlatform == ModPlatform.MODRINTH;

            String platformMessage = null;

            sortComboBox.removeAllItems();
            sectionComboBox.removeAllItems();

            addSectionAndSortOptions(false);

            if (isCurseForge) {
                platformMessage = ConfigManager.getConfigItem("platforms.curseforge.message", null);
            } else if (isModrinth) {
                platformMessage = ConfigManager.getConfigItem("platforms.modrinth.message", null);
            }

            addCategories();

            if (platformMessage != null) {
                platformMessageLabel.setText(new HTMLBuilder().center().text(platformMessage).build());
            }
            platformMessageLabel.setVisible(platformMessage != null);

            if (searchField.getText().isEmpty()) {
                loadDefaultMods();
            } else {
                searchForMods();
            }
            updating = false;
        });

        this.sectionComboBox.addActionListener(e -> {
            if (!updating) {
                page = 0;

                addCategories();

                if (searchField.getText().isEmpty()) {
                    loadDefaultMods();
                } else {
                    searchForMods();
                }
            }
        });

        this.sortComboBox.addActionListener(e -> {
            if (!updating) {
                page = 0;

                if (searchField.getText().isEmpty()) {
                    loadDefaultMods();
                } else {
                    searchForMods();
                }
            }
        });

        this.categoriesComboBox.addActionListener(e -> {
            if (!updating) {
                page = 0;

                if (searchField.getText().isEmpty()) {
                    loadDefaultMods();
                } else {
                    searchForMods();
                }
            }
        });

        this.searchField.addActionListener(e -> searchForMods());
    }

    /**
     * The row of search controls: on one line where it fits, wrapping onto a second where it doesn't. With Collections
     * and From Modpack in it, it needs more than the dialog's 800px, and one squeezed line left the search field a
     * sliver and cut off the last button.
     */
    static JPanel searchRow() {
        JPanel row = new JPanel(new WrapLayout(FlowLayout.LEADING, 0, 2));
        // how many lines it takes depends on the width it's given, so lay out again once that's known
        row.addComponentListener(new ComponentAdapter() {
            private int laidOutWidth = -1;

            @Override
            public void componentResized(ComponentEvent e) {
                if (row.getWidth() != laidOutWidth) {
                    laidOutWidth = row.getWidth();
                    row.revalidate();
                }
            }
        });
        return row;
    }

    private void setLoading(boolean loading) {
        if (loading) {
            rerenderResults = null;
            pageResults.clear();
            hiddenCountLabel.setVisible(false);
            contentPanel.removeAll();
            contentPanel.setLayout(new BorderLayout());
            contentPanel.add(new LoadingPanel(), BorderLayout.CENTER);
        }

        revalidate();
        repaint();
    }

    private void goToPreviousPage() {
        if (page > 0) {
            page -= 1;
        }

        ModPlatform selectedModPlatform = ((ComboItem<ModPlatform>) hostComboBox.getSelectedItem()).getValue();
        Analytics.trackEvent(
                AnalyticsEvent.forSearchEventPlatform("add_mods", searchField.getText(), page + 1,
                        selectedModPlatform.toString()));

        getMods();
    }

    private void goToNextPage() {
        if (contentPanel.getComponentCount() != 0) {
            page += 1;
        }

        ModPlatform selectedModPlatform = ((ComboItem<ModPlatform>) hostComboBox.getSelectedItem()).getValue();
        Analytics.trackEvent(
                AnalyticsEvent.forSearchEventPlatform("add_mods", searchField.getText(), page + 1,
                        selectedModPlatform.toString()));

        getMods();
    }

    @SuppressWarnings("unchecked")
    private void getMods() {
        int generation = fetchGeneration.incrementAndGet();

        setLoading(true);
        prevButton.setEnabled(false);
        nextButton.setEnabled(false);

        String query = searchField.getText();
        ModPlatform selectedModPlatform = ((ComboItem<ModPlatform>) hostComboBox.getSelectedItem()).getValue();
        String sectionValue = Optional.ofNullable((ComboItem<String>) sectionComboBox.getSelectedItem())
                .map(ComboItem::getValue)
                .orElse("Mods");
        String sortValue = Optional.ofNullable((ComboItem<String>) sortComboBox.getSelectedItem())
                .map(ComboItem::getValue)
                .orElse(selectedModPlatform == ModPlatform.CURSEFORGE ? "Popularity" : "relevance");
        String categoryValue = categoriesComboBox.getSelectedItem() == null ? null
                : ((ComboItem<String>) categoriesComboBox.getSelectedItem()).getValue();
        int requestPage = page;
        int pageSize = App.settings.addModsPageSize;

        new Thread(() -> {
            if (selectedModPlatform == ModPlatform.CURSEFORGE) {
                String versionToSearchFor = App.settings.addModRestriction == AddModRestriction.STRICT
                        ? instanceOrServer.getMinecraftVersion()
                        : null;

                BiFunction<Integer, Integer, CurseForgeCoreApiResponse<List<CurseForgeProject>>> search;
                if (sectionValue.equals("Data Packs")) {
                    search = (offset, limit) -> CurseForgeApi.searchDataPacks(versionToSearchFor, query, offset,
                            limit, sortValue, categoryValue);
                } else if (sectionValue.equals("Resource Packs")) {
                    search = (offset, limit) -> CurseForgeApi.searchResourcePacks(query, offset, limit, sortValue,
                            categoryValue);
                } else if (sectionValue.equals("Shaders")) {
                    search = (offset, limit) -> CurseForgeApi.searchShaderPacks(query, offset, limit, sortValue,
                            categoryValue);
                } else if (sectionValue.equals("Worlds")) {
                    search = (offset, limit) -> CurseForgeApi.searchWorlds(versionToSearchFor, query, offset, limit,
                            sortValue, categoryValue);
                } else if (sectionValue.equals("Plugins")) {
                    search = (offset, limit) -> CurseForgeApi.searchPlugins(versionToSearchFor, query, offset, limit,
                            sortValue, categoryValue);
                } else {
                    if (instanceOrServer.getLoaderVersion().isFabric()
                            || instanceOrServer.getLoaderVersion().isLegacyFabric()) {
                        search = (offset, limit) -> CurseForgeApi.searchModsForFabric(versionToSearchFor, query,
                                offset, limit, sortValue, categoryValue);
                    } else if (instanceOrServer.getLoaderVersion().isQuilt()) {
                        search = (offset, limit) -> CurseForgeApi.searchModsForQuilt(versionToSearchFor, query,
                                offset, limit, sortValue, categoryValue);
                    } else if (instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector()) {
                        if (instanceOrServer.getLoaderVersion().isForge()) {
                            search = (offset, limit) -> CurseForgeApi.searchModsForForgeOrFabric(versionToSearchFor,
                                    query, offset, limit, sortValue, categoryValue);
                        } else {
                            search = (offset, limit) -> CurseForgeApi.searchModsForNeoForgeOrFabric(
                                    versionToSearchFor, query, offset, limit, sortValue, categoryValue);
                        }
                    } else if (instanceOrServer.getLoaderVersion().isForge()) {
                        search = (offset, limit) -> CurseForgeApi.searchModsForForge(versionToSearchFor, query,
                                offset, limit, sortValue, categoryValue);
                    } else if (instanceOrServer.getLoaderVersion().isNeoForge()) {
                        search = (offset, limit) -> CurseForgeApi.searchModsForNeoForge(versionToSearchFor, query,
                                offset, limit, sortValue, categoryValue);
                    } else {
                        search = (offset, limit) -> CurseForgeApi.searchMods(versionToSearchFor, query, offset,
                                limit, sortValue, categoryValue);
                    }
                }

                AddModsPagination.Page<CurseForgeProject> result = AddModsPagination.fetchPage(
                        AddModsPagination.planRequests(requestPage, pageSize, CurseForgeApi.MAX_SEARCH_PAGE_SIZE,
                                CurseForgeApi.MAX_SEARCH_INDEX),
                        search, response -> response.data,
                        response -> response.pagination == null ? -1 : response.pagination.totalCount,
                        project -> project.id);
                boolean hasNext = result != null && AddModsPagination.curseForgeHasNext(requestPage, pageSize,
                        result.fetched, result.total, CurseForgeApi.MAX_SEARCH_INDEX);

                runIfCurrent(generation, () -> setCurseForgeMods(result == null ? null : result.items, hasNext));
            } else if (selectedModPlatform == ModPlatform.MODRINTH) {
                List<String> versionsToSearchFor = new ArrayList<>();

                if (App.settings.addModRestriction == AddModRestriction.STRICT) {
                    versionsToSearchFor.add(instanceOrServer.getMinecraftVersion());
                } else if (App.settings.addModRestriction == AddModRestriction.LAX) {
                    try {
                        versionsToSearchFor.addAll(MinecraftManager
                                .getMajorMinecraftVersions(instanceOrServer.getMinecraftVersion()).stream()
                                .map(mv -> mv.id).collect(Collectors.toList()));
                    } catch (InvalidMinecraftVersion e) {
                        LogManager.logStackTrace(e);
                        versionsToSearchFor = null;
                    }
                } else if (App.settings.addModRestriction == AddModRestriction.NONE) {
                    versionsToSearchFor = null;
                }

                List<String> versions = versionsToSearchFor;
                BiFunction<Integer, Integer, ModrinthSearchResult> search = null;
                if (sectionValue.equals("Data Packs")) {
                    search = (offset, limit) -> ModrinthApi.searchDataPacks(versions, query, offset, limit,
                            sortValue, categoryValue);
                } else if (sectionValue.equals("Resource Packs")) {
                    search = (offset, limit) -> ModrinthApi.searchResourcePacks(versions, query, offset, limit,
                            sortValue, categoryValue);
                } else if (sectionValue.equals("Shaders")) {
                    search = (offset, limit) -> ModrinthApi.searchShaders(versions, query, offset, limit,
                            sortValue, categoryValue);
                } else if (sectionValue.equals("Plugins")) {
                    if (instanceOrServer.getLoaderVersion().isPaper()) {
                        search = (offset, limit) -> ModrinthApi.searchPluginsForPaper(versions, query, offset, limit,
                                sortValue, categoryValue);
                    } else if (instanceOrServer.getLoaderVersion().isPurpur()) {
                        search = (offset, limit) -> ModrinthApi.searchPluginsForPurpur(versions, query, offset,
                                limit, sortValue, categoryValue);
                    }
                } else {
                    if (instanceOrServer.getLoaderVersion().isFabric()) {
                        search = (offset, limit) -> ModrinthApi.searchModsForFabric(versions, query, offset, limit,
                                sortValue, categoryValue);
                    } else if (instanceOrServer.getLoaderVersion().isLegacyFabric()) {
                        search = (offset, limit) -> ModrinthApi.searchModsForLegacyFabric(versions, query, offset,
                                limit, sortValue, categoryValue);
                    } else if (instanceOrServer.getLoaderVersion().isQuilt()) {
                        search = (offset, limit) -> ModrinthApi.searchModsForQuiltOrFabric(versions, query, offset,
                                limit, sortValue, categoryValue);
                    } else if (instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector()) {
                        if (instanceOrServer.getLoaderVersion().isForge()) {
                            search = (offset, limit) -> ModrinthApi.searchModsForForgeOrFabric(versions, query,
                                    offset, limit, sortValue, categoryValue);
                        } else {
                            search = (offset, limit) -> ModrinthApi.searchModsForNeoForgeOrFabric(versions, query,
                                    offset, limit, sortValue, categoryValue);
                        }
                    } else if (instanceOrServer.getLoaderVersion().isForge()) {
                        search = (offset, limit) -> ModrinthApi.searchModsForForge(versions, query, offset, limit,
                                sortValue, categoryValue);
                    } else if (instanceOrServer.getLoaderVersion().isNeoForge()) {
                        search = (offset, limit) -> ModrinthApi.searchModsForNeoForge(versions, query, offset, limit,
                                sortValue, categoryValue);
                    }
                }

                // no search for this loader/section combination, as before
                if (search != null) {
                    AddModsPagination.Page<ModrinthSearchHit> result = AddModsPagination.fetchPage(
                            AddModsPagination.planRequests(requestPage, pageSize, ModrinthApi.MAX_SEARCH_LIMIT,
                                    Integer.MAX_VALUE),
                            search, response -> response.hits, response -> response.totalHits,
                            hit -> hit.projectId);
                    boolean hasNext = result != null && AddModsPagination.modrinthHasNext(requestPage, pageSize,
                            result.fetched, result.total);

                    runIfCurrent(generation, () -> setModrinthMods(result == null ? null : result.items, hasNext));
                }
            }

            runIfCurrent(generation, () -> setLoading(false));
        }).start();
    }

    /**
     * Schedules a fetch's UI update on the EDT, dropping it if a newer fetch has started so a slow response can't
     * replace newer results.
     */
    private void runIfCurrent(int generation, Runnable update) {
        if (generation != fetchGeneration.get()) {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            if (generation == fetchGeneration.get()) {
                update.run();
            }
        });
    }

    private void loadDefaultMods() {
        getMods();
    }

    private void searchForMods() {
        String query = searchField.getText();

        page = 0;

        ModPlatform selectedModPlatform = ((ComboItem<ModPlatform>) hostComboBox.getSelectedItem()).getValue();
        Analytics.trackEvent(
                AnalyticsEvent.forSearchEventPlatform("add_mods", query, page + 1,
                        selectedModPlatform.toString()));

        getMods();
    }

    /**
     * Switches between cards and list rows, redrawing the current results in place: no fetch, and the page and
     * Next/Previous state stay as they are.
     */
    private void setViewMode(ResultsView view) {
        if (App.settings.addModsView() == view) {
            return;
        }

        viewPicker.select(view);
        App.settings.addModsView = view;
        App.settings.save();

        rerender();
    }

    /**
     * Redraws the results shown, if any. The old cards' compatibility checks are dropped rather than left queued
     * ahead of the new cards' (what they fetched is kept), so the redrawn marks don't wait for checks nobody sees.
     */
    private void rerender() {
        if (rerenderResults != null) {
            fetchGeneration.incrementAndGet();
            rerenderResults.run();
        }
    }

    /**
     * Shows what's installed now on this page's results: an install also adds dependencies, which may be results
     * here, and Collections and Related add through these same cards from their own windows.
     */
    private void refreshInstalled() {
        for (JComponent result : pageResults.keySet()) {
            if (result instanceof CurseForgeProjectCard) {
                ((CurseForgeProjectCard) result).updateInstalledStatus();
            } else if (result instanceof ModrinthSearchHitCard) {
                ((ModrinthSearchHitCard) result).updateInstalledStatus();
            }
        }
    }

    /**
     * List rows fill the width and stack from the top; cards keep using gbc as before (ignored by WrapLayout).
     */
    private static void useListRowConstraints(GridBagConstraints gbc) {
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTH;
        gbc.weighty = 0;
        gbc.insets.set(0, 0, 0, 0);
    }

    /**
     * Takes the space below the last list row so rows stay at the top.
     */
    private static void addListFiller(JPanel panel, GridBagConstraints gbc) {
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(Box.createGlue(), gbc);
    }

    /**
     * Lays out results as Add Mods shows them: wrapped cards, or full-width rows, compact or not, stacked from the
     * top.
     */
    static void layOutResults(JPanel panel, List<? extends JComponent> cards, ResultsView view) {
        panel.removeAll();

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets.set(2, 2, 2, 2);

        if (view.isRow()) {
            panel.setLayout(new GridBagLayout());
            useListRowConstraints(gbc);
        } else {
            panel.setLayout(new WrapLayout());
        }

        for (JComponent card : cards) {
            panel.add(card, gbc);
            gbc.gridy++;
        }

        if (view.isRow()) {
            addListFiller(panel, gbc);
        }
    }

    private void setCurseForgeMods(List<CurseForgeProject> mods, boolean hasNext) {
        rerenderResults = () -> setCurseForgeMods(mods, hasNext);
        ResultsView view = App.settings.addModsView();
        pageResults.clear();

        if (mods == null || mods.isEmpty()) {
            contentPanel.removeAll();
            contentPanel.setLayout(new BorderLayout());
            contentPanel.add(new NoCurseModsPanel(!this.searchField.getText().isEmpty()), BorderLayout.CENTER);
        } else {
            prevButton.setEnabled(page > 0);
            nextButton.setEnabled(hasNext);

            String sectionValue = Optional.ofNullable((ComboItem<String>) sectionComboBox.getSelectedItem())
                    .map(ComboItem::getValue)
                    .orElse("Mods");
            List<JComponent> cards = new ArrayList<>();
            for (CurseForgeProject mod : mods) {
                CurseForgeProjectCard card = curseForgeCard(mod, sectionValue, view, this);
                pageResults.put(card, card.compatibility());
                cards.add(card);
            }
            layOutResults(contentPanel, cards, view);
        }
        applyCompatibilityFilter();

        SwingUtilities.invokeLater(() -> jscrollPane.getVerticalScrollBar().setValue(0));

        revalidate();
        repaint();
    }

    /**
     * A CurseForge result as Add Mods shows it, in the section it's shown for: its card or row, with Add opening the
     * file selector, and Remove removing what's installed. Collections show CurseForge projects with it too.
     *
     * @param parent the window its dialogs belong to
     */
    CurseForgeProjectCard curseForgeCard(CurseForgeProject castMod, String sectionValue, ResultsView view,
            Window parent) {
        CurseForgeProjectCard card = new CurseForgeProjectCard(castMod, instanceOrServer, e -> {
            if (sectionValue.equals("Plugins")) {
                Analytics.trackEvent(AnalyticsEvent.forAddPlugin(castMod));
            } else if (sectionValue.equals("Data Packs")) {
                Analytics.trackEvent(AnalyticsEvent.forAddDataPack(castMod));
            } else if (sectionValue.equals("Resource Packs")) {
                Analytics.trackEvent(AnalyticsEvent.forAddResourcePack(castMod));
            } else if (sectionValue.equals("Shaders")) {
                Analytics.trackEvent(AnalyticsEvent.forAddShaders(castMod));
            } else {
                Analytics.trackEvent(AnalyticsEvent.forAddMod(castMod));
            }

            CurseForgeProjectFileSelectorDialog curseForgeProjectFileSelectorDialog = new CurseForgeProjectFileSelectorDialog(
                    parent, castMod, instanceOrServer);
            curseForgeProjectFileSelectorDialog.setVisible(true);
            refreshInstalled();
        }, e -> {
            if (sectionValue.equals("Plugins")) {
                Analytics.trackEvent(AnalyticsEvent.forRemovePlugin(castMod));
            } else if (sectionValue.equals("Data Packs")) {
                Analytics.trackEvent(AnalyticsEvent.forRemoveDataPack(castMod));
            } else if (sectionValue.equals("Resource Packs")) {
                Analytics.trackEvent(AnalyticsEvent.forRemoveResourcePack(castMod));
            } else if (sectionValue.equals("Shaders")) {
                Analytics.trackEvent(AnalyticsEvent.forRemoveShaders(castMod));
            } else {
                Analytics.trackEvent(AnalyticsEvent.forRemoveMod(castMod));
            }

            Optional<DisableableMod> foundMod = instanceOrServer.getMods().stream()
                    .filter(dm -> dm.isFromCurseForge() && dm.curseForgeProjectId == castMod.id)
                    .findFirst();

            if (foundMod.isPresent()) {
                instanceOrServer.removeMod(foundMod.get());

                if (castMod.id == Constants.CURSEFORGE_FABRIC_MOD_ID) {
                    fabricApiWarningLabel.setVisible(true);
                    installFabricApiButton.setVisible(true);
                }

                if (castMod.id == Constants.CURSEFORGE_LEGACY_FABRIC_MOD_ID) {
                    legacyFabricApiWarningLabel.setVisible(true);
                    installLegacyFabricApiButton.setVisible(true);
                }

                if (castMod.id == Constants.CURSEFORGE_FORGIFIED_FABRIC_API_MOD_ID) {
                    forgifiedFabricApiWarningLabel.setVisible(true);
                    installForgifiedFabricApiButton.setVisible(true);
                }
                refreshInstalled();
            }
        }, view, LoaderIndicators.forSection(LoaderIndicators.fromCurseForge(castMod), sectionValue));

        // settled by the search result's latest-file index, with no request
        card.compatibility().setVerdict(ModCompatibility.forCurseForgeProject(
                ModCompatibility.Target.of(instanceOrServer), sectionValue, castMod));
        if (sectionValue.equals("Mods")) {
            RelatedModsDialog.offer(card, () -> RelatedModsDialog.open(parent, this, instanceOrServer, castMod));
        }
        return card;
    }

    private void setModrinthMods(List<ModrinthSearchHit> hits, boolean hasNext) {
        rerenderResults = () -> setModrinthMods(hits, hasNext);
        ResultsView view = App.settings.addModsView();
        pageResults.clear();

        if (hits == null || hits.isEmpty()) {
            contentPanel.removeAll();
            contentPanel.setLayout(new BorderLayout());
            contentPanel.add(new NoCurseModsPanel(!this.searchField.getText().isEmpty()), BorderLayout.CENTER);
        } else {
            prevButton.setEnabled(page > 0);
            nextButton.setEnabled(hasNext);

            String sectionValue = Optional.ofNullable((ComboItem<String>) sectionComboBox.getSelectedItem())
                    .map(ComboItem::getValue)
                    .orElse("Mods");
            List<JComponent> cards = new ArrayList<>();
            for (ModrinthSearchHit hit : hits) {
                ModrinthSearchHitCard card = modrinthCard(hit, sectionValue, view, this);
                pageResults.put(card, card.compatibility());
                cards.add(card);
            }
            layOutResults(contentPanel, cards, view);
        }
        applyCompatibilityFilter();

        SwingUtilities.invokeLater(() -> jscrollPane.getVerticalScrollBar().setValue(0));

        revalidate();
        repaint();
    }

    /**
     * A Modrinth result as Add Mods shows it, in the section it's shown for: its card or list row, with Add opening
     * the version selector, and Remove removing what's installed. Collections show their projects with it too.
     *
     * @param parent the window its dialogs belong to
     */
    ModrinthSearchHitCard modrinthCard(ModrinthSearchHit castMod, String sectionValue, ResultsView view,
            Window parent) {
        // another window's (Related) checks stop when it closes; this one's own stop on a new fetch or on closing
        return modrinthCard(castMod, sectionValue, view, parent, parent == this ? () -> true : parent::isDisplayable);
    }

    /**
     * @param shown whether the window it's in still shows it; its compatibility check is dropped once it doesn't
     */
    ModrinthSearchHitCard modrinthCard(ModrinthSearchHit castMod, String sectionValue, ResultsView view,
            Window parent, BooleanSupplier shown) {
        ModrinthSearchHitCard card = new ModrinthSearchHitCard(castMod, instanceOrServer, e -> {
            final ProgressDialog<ModrinthProject> modrinthProjectLookupDialog = new ProgressDialog<>(
                    GetText.tr("Getting Mod Information"), 0, GetText.tr("Getting Mod Information"),
                    "Aborting Getting Mod Information", parent);

            modrinthProjectLookupDialog.addThread(new Thread(() -> {
                modrinthProjectLookupDialog.setReturnValue(ModrinthApi.getProject(castMod.projectId));

                modrinthProjectLookupDialog.close();
            }));

            modrinthProjectLookupDialog.start();

            ModrinthProject modrinthMod = modrinthProjectLookupDialog.getReturnValue();

            if (modrinthMod == null) {
                DialogManager.okDialog().setParent(parent).setTitle(GetText.tr("Error Getting Mod Information"))
                        .setContent(new HTMLBuilder().center().text(GetText.tr(
                                "There was an error getting mod information from Modrinth. Please try again later."))
                                .build())
                        .setType(DialogManager.ERROR).show();
                return;
            }

            if (sectionValue.equals("Plugins")) {
                Analytics.trackEvent(AnalyticsEvent.forAddPlugin(castMod));
            } else if (sectionValue.equals("Data Packs")) {
                Analytics.trackEvent(AnalyticsEvent.forAddDataPack(castMod));
            } else if (sectionValue.equals("Resource Packs")) {
                Analytics.trackEvent(AnalyticsEvent.forAddResourcePack(castMod));
            } else if (sectionValue.equals("Shaders")) {
                Analytics.trackEvent(AnalyticsEvent.forAddShaders(castMod));
            } else {
                Analytics.trackEvent(AnalyticsEvent.forAddMod(castMod));
            }

            ModrinthVersionSelectorDialog modrinthVersionSelectorDialog = sectionValue.equals("Data Packs")
                    ? new ModrinthVersionSelectorDialog(parent, modrinthMod, instanceOrServer,
                            com.atlauncher.data.Type.datapack)
                    : new ModrinthVersionSelectorDialog(parent, modrinthMod, instanceOrServer);
            modrinthVersionSelectorDialog.setVisible(true);
            refreshInstalled();
        }, e -> {
            if (sectionValue.equals("Plugins")) {
                Analytics.trackEvent(AnalyticsEvent.forRemovePlugin(castMod));
            } else if (sectionValue.equals("Data Packs")) {
                Analytics.trackEvent(AnalyticsEvent.forRemoveDataPack(castMod));
            } else if (sectionValue.equals("Resource Packs")) {
                Analytics.trackEvent(AnalyticsEvent.forRemoveResourcePack(castMod));
            } else if (sectionValue.equals("Shaders")) {
                Analytics.trackEvent(AnalyticsEvent.forRemoveShaders(castMod));
            } else {
                Analytics.trackEvent(AnalyticsEvent.forRemoveMod(castMod));
            }

            Optional<DisableableMod> foundMod = instanceOrServer.getMods().stream()
                    .filter(dm -> dm.isFromModrinth() && dm.modrinthProject.id.equals(castMod.projectId))
                    .findFirst();

            if (foundMod.isPresent()) {
                instanceOrServer.removeMod(foundMod.get());

                if (castMod.projectId.equals(Constants.MODRINTH_FABRIC_MOD_ID)) {
                    fabricApiWarningLabel.setVisible(true);
                    installFabricApiButton.setVisible(true);
                }

                if (castMod.projectId.equals(Constants.MODRINTH_LEGACY_FABRIC_MOD_ID)) {
                    legacyFabricApiWarningLabel.setVisible(true);
                    installLegacyFabricApiButton.setVisible(true);
                }

                if (castMod.projectId.equals(Constants.MODRINTH_QSL_MOD_ID)) {
                    quiltStandardLibrariesWarningLabel.setVisible(true);
                    installQuiltStandardLibrariesButton.setVisible(true);
                }

                if (castMod.projectId.equals(Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID)) {
                    forgifiedFabricApiWarningLabel.setVisible(true);
                    installForgifiedFabricApiButton.setVisible(true);
                }
                refreshInstalled();
            }
        }, view, LoaderIndicators.forSection(LoaderIndicators.fromModrinth(castMod), sectionValue));

        // checked in the background unless the search result settles it; dropped once another search replaces it
        int generation = fetchGeneration.get();
        BooleanSupplier stillShown = () -> generation == fetchGeneration.get() && shown.getAsBoolean();
        card.compatibility().setVerdict(compatibilityChecker.modrinth(ModCompatibility.Target.of(instanceOrServer),
                sectionValue, castMod, stillShown, verdict -> SwingUtilities.invokeLater(() -> {
                    if (stillShown.getAsBoolean()) {
                        card.compatibility().setVerdict(verdict);
                        applyCompatibilityFilter();
                    }
                })));
        if (sectionValue.equals("Mods")) {
            RelatedModsDialog.offer(card, () -> RelatedModsDialog.open(parent, this, instanceOrServer, castMod));
        }
        return card;
    }

    /**
     * Hides this page's results that have no file for the instance while "Hide incompatible" is on, and says how
     * many, so filtering is never silent. Results still being checked, or that couldn't be, stay.
     */
    private void applyCompatibilityFilter() {
        int hidden = 0;
        for (Map.Entry<JComponent, CompatibilityBadge> result : pageResults.entrySet()) {
            boolean hide = ModCompatibility.hiddenWhenFiltering(App.settings.addModsHideIncompatible,
                    result.getValue().state());
            result.getKey().setVisible(!hide);
            hidden += hide ? 1 : 0;
        }

        // #. {0} results hidden as incompatible, out of {1} on this page
        hiddenCountLabel.setText(GetText.tr("{0} of {1} hidden", hidden, pageResults.size()));
        hiddenCountLabel.setVisible(hidden > 0);
        contentPanel.revalidate();
        contentPanel.repaint();
    }

    @Override
    public void dispose() {
        // a search still loading isn't shown, and nothing more is checked, once this has closed
        fetchGeneration.incrementAndGet();
        compatibilityChecker.shutdown();
        super.dispose();
    }

    private void addSectionAndSortOptions(boolean firstTime) {
        if (instanceOrServer.supportsPlugins()) {
            sectionComboBox.addItem(new ComboItem<>("Plugins", GetText.tr("Plugins")));
        }
        if (instanceOrServer.getLoaderVersion() != null && !instanceOrServer.getLoaderVersion().isPaper()
                && !instanceOrServer.getLoaderVersion().isPurpur()) {
            sectionComboBox.addItem(new ComboItem<>("Mods", GetText.tr("Mods")));
        }
        if (instanceOrServer instanceof Instance) {
            sectionComboBox.addItem(new ComboItem<>("Data Packs", GetText.tr("Data Packs")));
            if (!firstTime) {
                boolean dataPacksSelected = ((ComboItem<String>) sectionComboBox.getSelectedItem()).getValue()
                        .equals("Data Packs");

                if (dataPacksSelected) {
                    sectionComboBox.setSelectedIndex(sectionComboBox.getItemCount() - 1);
                }
            }

            sectionComboBox.addItem(new ComboItem<>("Resource Packs", GetText.tr("Resource Packs")));
            if (!firstTime) {
                boolean resourcePacksSelected = ((ComboItem<String>) sectionComboBox.getSelectedItem()).getValue()
                        .equals("Resource Packs");

                if (resourcePacksSelected) {
                    sectionComboBox.setSelectedIndex(sectionComboBox.getItemCount() - 1);
                }
            }

            sectionComboBox.addItem(new ComboItem<>("Shaders", GetText.tr("Shaders")));
            if (!firstTime) {
                boolean shadersSelected = ((ComboItem<String>) sectionComboBox.getSelectedItem()).getValue()
                        .equals("Shaders");

                if (shadersSelected) {
                    sectionComboBox.setSelectedIndex(sectionComboBox.getItemCount() - 1);
                }
            }
        }

        boolean isCurseForgeSelected = (firstTime
                && (instanceOrServer.getLoaderVersion() == null || (!instanceOrServer.getLoaderVersion().isPaper()
                        && !instanceOrServer.getLoaderVersion().isPurpur())))
                                ? App.settings.defaultModPlatform == ModPlatform.CURSEFORGE
                                : ((ComboItem<ModPlatform>) hostComboBox.getSelectedItem())
                                        .getValue() == ModPlatform.CURSEFORGE;

        if (isCurseForgeSelected) {
            if (instanceOrServer instanceof Instance) {
                sectionComboBox.addItem(new ComboItem<>("Worlds", GetText.tr("Worlds")));
            }

            sortComboBox.addItem(new ComboItem<>("Popularity", GetText.tr("Popularity")));
            sortComboBox.addItem(new ComboItem<>("Last Updated", GetText.tr("Last Updated")));
            sortComboBox.addItem(new ComboItem<>("Total Downloads", GetText.tr("Total Downloads")));
        } else {
            sortComboBox.addItem(new ComboItem<>("relevance", GetText.tr("Relevance")));
            sortComboBox.addItem(new ComboItem<>("newest", GetText.tr("Newest")));
            sortComboBox.addItem(new ComboItem<>("updated", GetText.tr("Last Updated")));
            sortComboBox.addItem(new ComboItem<>("downloads", GetText.tr("Total Downloads")));
        }

    }

    // which category fetch is current; one finishing after the platform or section changed again is dropped
    private int categoriesRequest = 0;

    /**
     * Shows "All Categories" now and fills in the rest once fetched in the background, so a cold cache or slow
     * network doesn't freeze the dialog.
     */
    private void addCategories() {
        boolean wasUpdating = updating;
        updating = true;
        categoriesComboBox.removeAllItems();
        categoriesComboBox.addItem(new ComboItem<>(null, GetText.tr("All Categories")));
        updating = wasUpdating;

        boolean isCurseForge = ((ComboItem<ModPlatform>) hostComboBox.getSelectedItem())
                .getValue() == ModPlatform.CURSEFORGE;
        String section = ((ComboItem<String>) sectionComboBox.getSelectedItem()).getValue();
        int request = ++categoriesRequest;

        new Thread(() -> {
            List<ComboItem<String>> items;
            try {
                items = isCurseForge ? curseForgeCategories(section) : modrinthCategories(section);
            } catch (RuntimeException e) {
                LogManager.logStackTrace("Couldn't load the categories for " + section, e);
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (request != categoriesRequest) {
                    return;
                }
                boolean was = updating;
                updating = true;
                items.forEach(categoriesComboBox::addItem);
                updating = was;
            });
        }, "Add Mods categories").start();
    }

    private static List<ComboItem<String>> curseForgeCategories(String section) {
        List<CurseForgeCategoryForGame> categories = new ArrayList<>();
        if (section.equals("Data Packs")) {
            categories.addAll(CurseForgeApi.getCategoriesForDataPacks());
        } else if (section.equals("Resource Packs")) {
            categories.addAll(CurseForgeApi.getCategoriesForResourcePacks());
        } else if (section.equals("Shaders")) {
            categories.addAll(CurseForgeApi.getCategoriesForShaderPacks());
        } else if (section.equals("Worlds")) {
            categories.addAll(CurseForgeApi.getCategoriesForWorlds());
        } else if (section.equals("Plugins")) {
            categories.addAll(CurseForgeApi.getCategoriesForPlugins());
        } else {
            categories.addAll(CurseForgeApi.getCategoriesForMods());
        }

        List<ComboItem<String>> items = new ArrayList<>();
        categories.forEach(c -> items.add(new ComboItem<>(String.valueOf(c.id), c.name)));
        return items;
    }

    private static List<ComboItem<String>> modrinthCategories(String section) {
        List<ModrinthCategory> categories = new ArrayList<>();
        if (section.equals("Data Packs")) {
            categories.addAll(ModrinthApi.getCategoriesForDataPacks());
        } else if (section.equals("Resource Packs")) {
            categories.addAll(ModrinthApi.getCategoriesForResourcePacks());
        } else if (section.equals("Shaders")) {
            categories.addAll(ModrinthApi.getCategoriesForShaders());
        } else if (section.equals("Plugins")) {
            categories.addAll(ModrinthApi.getCategoriesForPlugins());
        } else {
            categories.addAll(ModrinthApi.getCategoriesForMods());
        }

        List<ComboItem<String>> items = new ArrayList<>();
        categories.forEach(c -> items.add(new ComboItem<>(c.name, Utils.capitalize(c.name))));
        return items;
    }
}
