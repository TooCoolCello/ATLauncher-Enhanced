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
import java.awt.Window;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.UIManager;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.builders.HTMLBuilder;
import com.atlauncher.constants.Constants;
import com.atlauncher.data.AddModRestriction;
import com.atlauncher.data.DependencyChoice;
import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.LoaderChoice;
import com.atlauncher.data.ModCompatibility;
import com.atlauncher.data.ModManagement;
import com.atlauncher.data.RequiredDependency;
import com.atlauncher.data.SinytraConnector;
import com.atlauncher.data.curseforge.CurseForgeFile;
import com.atlauncher.data.curseforge.CurseForgeFileDependency;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.exceptions.InvalidMinecraftVersion;
import com.atlauncher.gui.card.CompatibilityBadge;
import com.atlauncher.gui.components.DependencyChoicesPanel;
import com.atlauncher.managers.ConfigManager;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.managers.MinecraftManager;
import com.atlauncher.network.Analytics;
import com.atlauncher.network.analytics.AnalyticsEvent;
import com.atlauncher.utils.ComboItem;
import com.atlauncher.utils.CurseForgeApi;
import com.atlauncher.utils.OS;

public class CurseForgeProjectFileSelectorDialog extends JDialog {
    private int filesLength = 0;
    private final CurseForgeProject mod;
    private final ModManagement instanceOrServer;
    private Integer installedFileId = null;
    private boolean selectNewest = true;
    private Predicate<CurseForgeFile> updateCandidate = null;
    private boolean reinstall = false;

    private final DependencyChoicesPanel<CurseForgeFile> dependenciesPanel = new DependencyChoicesPanel<>();
    private JScrollPane scrollPane;
    private JButton addButton;
    private JButton viewModButton;
    private JButton viewFileButton;
    private JLabel versionsLabel;
    private JLabel installedJLabel;
    private JComboBox<CurseForgeFile> filesDropdown;
    private final List<CurseForgeFile> files = new ArrayList<>();
    private final JLabel connectorNoteLabel = new JLabel(
        GetText.tr("Uses Sinytra Connector; compatibility is not guaranteed."), JLabel.CENTER);
    private volatile boolean fabricFallbackAllowed = false;
    private volatile boolean forgeCompatible = false;
    // a new install's loader path, native first; per dialog and never saved, shown only when there's a choice
    private final JComboBox<ComboItem<LoaderChoice>> loaderDropdown = new JComboBox<>();
    private final JPanel loaderPanel = new JPanel(new FlowLayout());
    private volatile boolean loaderChoicesReady = false;
    private final List<CurseForgeFile> allFiles = new ArrayList<>();
    private final List<CurseForgeFile> usualFiles = new ArrayList<>();
    // opened for a dependency of a Fabric build running through Connector: suggest a Fabric build
    private boolean preferConnectorBuilds = false;
    private final JLabel overrideNoteLabel = new JLabel("", JLabel.CENTER);

    public CurseForgeProjectFileSelectorDialog(CurseForgeProject mod, ModManagement instanceOrServer) {
        this(App.launcher.getParent(), mod, instanceOrServer);
    }

    public CurseForgeProjectFileSelectorDialog(Window parent, CurseForgeProject mod, ModManagement instanceOrServer) {
        this(parent, mod, instanceOrServer, false);
    }

    /**
     * @param preferConnectorBuilds opened for a dependency of a Fabric build running through Connector: a Fabric
     *                              build is suggested, and choosing another is flagged
     */
    public CurseForgeProjectFileSelectorDialog(Window parent, CurseForgeProject mod, ModManagement instanceOrServer,
        boolean preferConnectorBuilds) {
        super(parent, ModalityType.DOCUMENT_MODAL);

        this.mod = mod;
        this.instanceOrServer = instanceOrServer;
        this.preferConnectorBuilds = preferConnectorBuilds;

        setupComponents();
    }

    /**
     * For updating an installed file: only files passing {@code updateCandidate} are offered, the same filter the
     * update check used, so a pick can't switch the installed file's loader path.
     */
    public CurseForgeProjectFileSelectorDialog(Window parent, CurseForgeProject mod, ModManagement instanceOrServer,
        int installedFileId, Predicate<CurseForgeFile> updateCandidate) {
        this(parent, mod, instanceOrServer, installedFileId, updateCandidate, false);
    }

    /**
     * @param reinstall preselect the exact installed file, and stop with a message instead of defaulting to another
     *                  file when it isn't offered
     */
    public CurseForgeProjectFileSelectorDialog(Window parent, CurseForgeProject mod, ModManagement instanceOrServer,
        int installedFileId, Predicate<CurseForgeFile> updateCandidate, boolean reinstall) {
        super(parent, ModalityType.DOCUMENT_MODAL);

        this.mod = mod;
        this.instanceOrServer = instanceOrServer;
        this.installedFileId = installedFileId;
        this.updateCandidate = updateCandidate;
        this.reinstall = reinstall;
        this.selectNewest = !reinstall;

        setupComponents();
    }

    /**
     * The offered file with the installed file's exact ID, or null. Reinstall never falls back to another file.
     */
    public static CurseForgeFile installedFileToReinstall(List<CurseForgeFile> offered, Integer installedFileId) {
        return installedFileId == null ? null
            : offered.stream().filter(f -> f != null && f.id == installedFileId).findFirst().orElse(null);
    }

    private void setupComponents() {
        // #. {0} is the name of the mod we're installing
        setTitle(GetText.tr("Installing {0}", mod.name));

        setSize(550, 200);
        setMinimumSize(new Dimension(550, 200));
        setLocationRelativeTo(App.launcher.getParent());
        setLayout(new BorderLayout());
        setResizable(true);
        this.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        addButton = new JButton(GetText.tr("Add"));
        addButton.setEnabled(false);

        viewModButton = new JButton(GetText.tr("View Mod"));
        viewModButton.setEnabled(false);

        viewFileButton = new JButton(GetText.tr("View File"));
        viewFileButton.setEnabled(false);

        dependenciesPanel.setVisible(false);
        dependenciesPanel.setBorder(BorderFactory.createTitledBorder(GetText.tr("Dependencies")));

        // Top Panel Stuff
        JPanel top = new JPanel(new BorderLayout());
        // #. {0} is the name of the mod we're installing
        top.add(new JLabel(GetText.tr("Installing {0}", mod.name), JLabel.CENTER), BorderLayout.NORTH);

        installedJLabel = new JLabel("", JLabel.CENTER);
        top.add(installedJLabel, BorderLayout.SOUTH);

        // Middle Panel Stuff
        JPanel middle = new JPanel(new BorderLayout());

        // Middle Panel Stuff
        JPanel filesPanel = new JPanel(new FlowLayout());
        filesPanel.setBorder(BorderFactory.createEmptyBorder(20, 0, 20, 0));

        versionsLabel = new JLabel(GetText.tr("Version To Install") + ": ");
        filesPanel.add(versionsLabel);

        filesDropdown = new JComboBox<>();
        CurseForgeFile loadingProject = new CurseForgeFile();
        loadingProject.displayName = GetText.tr("Loading");
        filesDropdown.addItem(loadingProject);
        filesDropdown.setEnabled(false);
        filesPanel.add(filesDropdown);

        scrollPane = new JScrollPane(dependenciesPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setPreferredSize(new Dimension(550, 250));

        connectorNoteLabel.setForeground(UIManager.getColor("yellow"));
        // the same Sinytra mark Add Mods shows on results that only run through Connector
        connectorNoteLabel.setIcon(CompatibilityBadge.connectorIcon());
        connectorNoteLabel.setVisible(false);

        loaderPanel.add(new JLabel(GetText.tr("Mod loader") + ": "));
        loaderPanel.add(loaderDropdown);
        loaderPanel.setVisible(false);
        loaderDropdown.addActionListener(e -> {
            if (loaderChoicesReady) {
                relistFiles();
            }
        });

        overrideNoteLabel.setForeground(UIManager.getColor("yellow"));
        overrideNoteLabel.setAlignmentX(CENTER_ALIGNMENT);
        overrideNoteLabel.setVisible(false);

        middle.add(ModrinthVersionSelectorDialog.selectorWithConnectorNote(connectorNoteLabel, loaderPanel,
            filesPanel, overrideNoteLabel), BorderLayout.NORTH);
        middle.add(scrollPane, BorderLayout.SOUTH);

        this.getFiles();

        // Bottom Panel Stuff
        JPanel bottom = new JPanel();
        bottom.setLayout(new FlowLayout());

        addButton.addActionListener(e -> {
            CurseForgeFile file = (CurseForgeFile) filesDropdown.getSelectedItem();

            // recheck now: the list or Connector may have changed since the file was selected
            boolean fileNeedsConnector = file != null && needsConnector(file);
            boolean connectorActiveNow = instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector();
            if (!SinytraConnector.mayInstall(listedFiles(), file, fileNeedsConnector, connectorActiveNow)) {
                if (fileNeedsConnector && !connectorActiveNow) {
                    showConnectorUnavailable(this, mod.name);
                }
                return;
            }

            // checked again now: choices made against a list that predates a change to the installed mods aren't
            // carried out
            if (!dependenciesPanel.matches(checkDependencies(file))) {
                reloadDependenciesPanel();
                showDependenciesChanged(this);
                return;
            }

            List<String> warnings = new ArrayList<>();
            String override = overrideWarning(file);
            if (override != null) {
                warnings.add(override);
            }
            warnings.addAll(dependenciesPanel.warnings());
            if (!confirmDespiteWarnings(this, file.displayName, warnings)) {
                return;
            }

            List<String> problems = new ArrayList<>();
            ProgressDialog<Void> progressDialog = new ProgressDialog<>(
                // #. {0} is the name of the mod we're installing
                GetText.tr("Installing {0}", file.displayName), false, this);
            progressDialog.addThread(new Thread(() -> {
                Analytics.trackEvent(mod.getAnalyticsEventForAdded(file));
                instanceOrServer.addFileFromCurseForge(mod, file, progressDialog);
                problems.addAll(dependenciesPanel.apply(instanceOrServer, progressDialog,
                    (item, picked) -> installDependency(item, picked, progressDialog)));

                progressDialog.close();
            }));
            progressDialog.start();
            DependencyChoicesPanel.showProblems(this, problems);
            dispose();
        });

        viewModButton.addActionListener(e -> OS.openWebBrowser(mod.getWebsiteUrl()));

        viewFileButton.addActionListener(e -> {
            CurseForgeFile file = (CurseForgeFile) filesDropdown.getSelectedItem();

            OS.openWebBrowser(String.format(Locale.ENGLISH, "%s/files/%d", mod.getWebsiteUrl(), file.id));
        });

        filesDropdown.addActionListener(e -> reloadDependenciesPanel());

        JButton cancel = new JButton(GetText.tr("Cancel"));
        cancel.addActionListener(e -> dispose());
        bottom.add(addButton);
        bottom.add(viewModButton);
        bottom.add(viewFileButton);
        bottom.add(cancel);

        add(top, BorderLayout.NORTH);
        add(middle, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
    }

    /**
     * A file with no game version list is treated as untagged (the filters' existing rule for files without loader
     * tags) instead of failing the background load and leaving the dialog stuck on "Loading". It can never be a
     * Connector fallback, since that needs a Fabric tag.
     */
    public static void markMissingTagsAsUntagged(List<CurseForgeFile> files) {
        files.stream().filter(f -> f != null && f.gameVersions == null).forEach(f -> f.gameVersions = new ArrayList<>());
    }

    // room for the notes and the loader choice when they're showing
    private int compactHeight() {
        return 200 + (connectorNoteLabel.isVisible() ? 20 : 0) + (loaderPanel.isVisible() ? 30 : 0)
            + (overrideNoteLabel.isVisible() ? 20 : 0);
    }

    /**
     * Fills a loader dropdown with the offered paths and selects the initial one. It's only shown when there's more
     * than one, so a lone native loader adds nothing to the dialog.
     */
    static void showLoaderChoices(JComboBox<ComboItem<LoaderChoice>> dropdown, JPanel panel,
        List<LoaderChoice> choices, LoaderChoice initial, LoaderVersion loader) {
        dropdown.removeAllItems();
        ComboItem<LoaderChoice> selected = null;
        for (LoaderChoice choice : choices) {
            ComboItem<LoaderChoice> item = new ComboItem<>(choice, LoaderChoice.label(choice, loader));
            dropdown.addItem(item);
            if (choice == initial) {
                selected = item;
            }
        }
        dropdown.setSelectedItem(selected);
        panel.setVisible(choices.size() > 1);
    }

    private LoaderChoice selectedLoader() {
        Object item = loaderDropdown.getSelectedItem();
        return item == null ? LoaderChoice.NATIVE
            : ((ComboItem<LoaderChoice>) item).getValue();
    }

    private LoaderChoice loaderChoiceOf(CurseForgeFile file) {
        return LoaderChoice.of(file.gameVersions, instanceOrServer.getLoaderVersion(), forgeCompatible);
    }

    private List<CurseForgeFile> filesForLoader(LoaderChoice choice) {
        return LoaderChoice.filesFor(choice, allFiles, usualFiles, this::loaderChoiceOf, this::isConnectorBuild);
    }

    // for a Connector parent's dependency: a file that isn't a Fabric build may not satisfy it
    private String overrideWarning(CurseForgeFile selected) {
        return RequiredDependency.isOverride(selected, null, preferConnectorBuilds, this::isConnectorBuild)
            ? GetText.tr("Not a Fabric build, so it may not satisfy the Fabric mod that needs it.")
            : null;
    }

    /**
     * Asks before adding a file whose dependencies can't be vouched for. Nothing about them is verified or installed
     * here; the user decides with what the launcher knows. True to go ahead.
     */
    static boolean confirmDespiteWarnings(Window parent, String name, List<String> warnings) {
        if (warnings.isEmpty()) {
            return true;
        }

        StringBuilder content = new StringBuilder(GetText.tr("Before adding {0}:", escape(name))).append("<br/>");
        warnings.forEach(w -> content.append("<br/>- ").append(escape(w)));
        content.append("<br/><br/>").append(GetText.tr("The launcher can't verify these dependencies. Add it anyway?"));

        return DialogManager.yesNoDialog().setParent(parent).setTitle(GetText.tr("Dependency Warnings"))
            .setContent(new HTMLBuilder().text(content.toString()).build()).setType(DialogManager.WARNING)
            .show() == DialogManager.YES_OPTION;
    }

    /**
     * Shown when Add finds the dependencies no longer as listed, so the user checks the refreshed choices first.
     */
    static void showDependenciesChanged(Window parent) {
        DialogManager.okDialog().setParent(parent).setTitle(GetText.tr("Dependencies Changed"))
            .setContent(GetText.tr("The installed mods changed since the dependencies were listed. Check the "
                + "choices again, then press Add."))
            .setType(DialogManager.WARNING).show();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // lists files and selects one, or none, leaving Add usable only with a selection
    private void showFiles(List<CurseForgeFile> listed, CurseForgeFile select) {
        filesDropdown.removeAllItems();
        listed.forEach(filesDropdown::addItem);
        if (select == null) {
            filesDropdown.setSelectedIndex(-1);
        } else {
            filesDropdown.setSelectedItem(select);
        }
        addButton.setEnabled(select != null);
    }

    private boolean needsConnector(CurseForgeFile file) {
        return SinytraConnector.isFabricFallbackFile(file.gameVersions, instanceOrServer.getLoaderVersion(),
            forgeCompatible);
    }

    private boolean isConnectorBuild(CurseForgeFile file) {
        return SinytraConnector.isConnectorBuild(file.gameVersions, file.gameVersions,
            instanceOrServer.getMinecraftVersion(), instanceOrServer.getLoaderVersion(), forgeCompatible);
    }

    private List<CurseForgeFile> listedFiles() {
        List<CurseForgeFile> listed = new ArrayList<>();
        for (int i = 0; i < filesDropdown.getItemCount(); i++) {
            listed.add(filesDropdown.getItemAt(i));
        }
        return listed;
    }

    // lists the chosen loader's files, never leaving a file it doesn't show selected
    private void relistFiles() {
        CurseForgeFile selected = (CurseForgeFile) filesDropdown.getSelectedItem();
        List<CurseForgeFile> listed = filesForLoader(selectedLoader());
        showFiles(listed, LoaderChoice.selectionAfterSwitch(listed, selected));
    }

    /**
     * Shown when Add is pressed for a file that needs Connector after Connector was disabled or removed.
     */
    static void showConnectorUnavailable(Window parent, String name) {
        DialogManager.okDialog().setParent(parent).setTitle(GetText.tr("Can't Add"))
            .setContent(GetText.tr("This file of {0} needs Sinytra Connector, which is no longer installed and "
                + "enabled. Nothing was installed.", name))
            .setType(DialogManager.ERROR).show();
    }

    public void reloadDependenciesPanel() {
        if (filesDropdown.getSelectedItem() == null) {
            overrideNoteLabel.setVisible(false);
            return;
        }

        CurseForgeFile selectedFile = (CurseForgeFile) filesDropdown.getSelectedItem();

        // only listed while Connector is in play: the automatic fallback, the opt-in or a Connector install's update
        connectorNoteLabel.setVisible(needsConnector(selectedFile));
        String override = overrideWarning(selectedFile);
        overrideNoteLabel.setText(override == null ? "" : override);
        overrideNoteLabel.setVisible(override != null);

        dependenciesPanel.setVisible(false);
        dependenciesPanel.show(checkDependencies(selectedFile));

        if (!dependenciesPanel.isEmpty()) {
            setSize(650, 480);
            setLocationRelativeTo(App.launcher.getParent());

            dependenciesPanel.setVisible(true);

            scrollPane.repaint();
            scrollPane.validate();
        } else {
            setSize(550, compactHeight());
        }
    }

    /**
     * The dependencies of this exact file, checked against the installed mods as they are now: required and optional
     * ones whether or not they're met, and installed mods it declares incompatible. Checked again when Add is
     * pressed, so choices made against an older list aren't carried out.
     */
    private List<DependencyChoicesPanel.Item<CurseForgeFile>> checkDependencies(CurseForgeFile file) {
        List<DependencyChoicesPanel.Item<CurseForgeFile>> items = new ArrayList<>();
        if (file.dependencies == null) {
            return items;
        }

        boolean parentNeedsConnector = needsConnector(file);
        boolean connectorActive = instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector();
        List<String> neoForgeForgeCompatabilityVersions = ConfigManager
            .getConfigItem("loaders.neoforge.forgeCompatibleMinecraftVersions", new ArrayList<>());
        List<DisableableMod> mods = instanceOrServer.getMods();
        ModCompatibility.Target target = ModCompatibility.Target.of(instanceOrServer);

        // looked up together; Forgified Fabric API stands in for Fabric API under Connector
        int[] projectIds = file.dependencies.stream()
            .filter(d -> DependencyChoice.kindOf(d) != null && d.modId != 0)
            .mapToInt(d -> connectorActive && d.modId == Constants.CURSEFORGE_FABRIC_MOD_ID
                ? Constants.CURSEFORGE_FORGIFIED_FABRIC_API_MOD_ID
                : d.modId)
            .distinct().toArray();
        Map<Integer, CurseForgeProject> projects = CurseForgeApi.getProjectsAsMap(projectIds);
        if (projects == null) {
            projects = new HashMap<>();
        }

        for (CurseForgeFileDependency dependency : file.dependencies) {
            DependencyChoice.Kind kind = DependencyChoice.kindOf(dependency);
            if (kind == null) {
                continue;
            }

            if (kind == DependencyChoice.Kind.INCOMPATIBLE) {
                mods.stream().filter(m -> m != null && activity(m) == null && m.isFromCurseForge()
                    && m.getCurseForgeModId() == dependency.modId).forEach(m -> items.add(
                        new DependencyChoicesPanel.Item<>("incompatible:" + m.file, m.name, kind, null,
                            // #. {0} is the mod being added
                            GetText.tr("{0} declares it incompatible.", mod.name), m, false, null)));
                continue;
            }

            RequiredDependency.State state;
            DisableableMod installed;
            boolean viaForgified = connectorActive && dependency.modId == Constants.CURSEFORGE_FABRIC_MOD_ID;

            if (viaForgified) {
                // Connector gets Fabric API from Forgified Fabric API, never both
                installed = preferUsable(mods, RequiredDependency::isForgifiedFabricApi);
                state = RequiredDependency.fabricApiUnderConnector(
                    RequiredDependency.anyInstalled(mods,
                        m -> RequiredDependency.isFabricApi(m) && RequiredDependency.isPresent(m)),
                    installed != null, activity(installed));
                if (state == RequiredDependency.State.FABRIC_API_CONFLICT) {
                    // the row is about the ordinary Fabric API, which Replace takes out
                    installed = mods.stream().filter(m -> m != null && RequiredDependency.isFabricApi(m)
                        && RequiredDependency.isPresent(m)).findFirst().orElse(null);
                }
            } else {
                // the known equivalents: Fabric API from QSL on Quilt, or the same library from Modrinth
                DisableableMod equivalent = preferUsable(mods, m -> isKnownEquivalent(dependency, m));
                if (equivalent != null) {
                    installed = equivalent;
                    RequiredDependency.State activity = activity(equivalent);
                    state = activity == null ? RequiredDependency.State.SATISFIED : activity;
                } else {
                    installed = preferUsable(mods,
                        m -> m.isFromCurseForge() && m.getCurseForgeModId() == dependency.modId);
                    state = RequiredDependency.state(installed == null ? null
                            : installed.curseForgeInstalledPath(instanceOrServer, new ArrayList<>(),
                                neoForgeForgeCompatabilityVersions),
                        activity(installed), parentNeedsConnector, null, null);
                }
            }

            CurseForgeProject project = projects.get(
                viaForgified ? Constants.CURSEFORGE_FORGIFIED_FABRIC_API_MOD_ID : dependency.modId);
            if (installed == null) {
                // nothing installed to fall back on, so a project that can't be found can't be installed either
                state = RequiredDependency.withProject(state, project);
            }
            String version = installed == null ? null : installed.version;
            // another project standing in, such as Quilted Fabric API, or Forgified Fabric API under Connector
            boolean equivalent = installed != null && (viaForgified || !installed.isFromCurseForge()
                || installed.getCurseForgeModId() != dependency.modId);

            String status = state == RequiredDependency.State.SATISFIED && installed != null
                ? DependencyChoice.satisfiedStatus(installed.name, version, equivalent)
                : RequiredDependency.status(state, parentNeedsConnector, version);
            String title = project != null ? project.name
                : installed != null ? installed.name : GetText.tr("Unknown Project");
            // installing the dependency's own project replaces its entry anyway; anything else has to come out
            boolean replacesInstalled = installed != null && !(project != null && installed.isFromCurseForge()
                && installed.getCurseForgeModId() == project.id);

            DependencyChoicesPanel.Item<CurseForgeFile> item = new DependencyChoicesPanel.Item<>(
                kind + ":" + dependency.modId + ":" + state + ":" + (installed == null ? "" : installed.file),
                title, kind, state, status, installed, replacesInstalled, project);

            if (project != null) {
                boolean preferConnector = parentNeedsConnector && !viaForgified;
                item.picker = () -> pickFile(project, preferConnector, target);
                item.view = () -> OS.openWebBrowser(project.getWebsiteUrl());

                if (DependencyChoice.actions(kind, state).stream().anyMatch(DependencyChoice.Action::installs)) {
                    boolean nestedPreferConnector = parentNeedsConnector
                        && state == RequiredDependency.State.MISSING;
                    item.choose = () -> {
                        Analytics.trackEvent(AnalyticsEvent.forAddMod(project));
                        new CurseForgeProjectFileSelectorDialog(this, project, instanceOrServer, nestedPreferConnector)
                            .setVisible(true);
                        reloadDependenciesPanel();
                    };
                }
            }

            items.add(item);
        }

        return items;
    }

    /**
     * The file a dependency installs by default (see {@link DependencyChoice#pickFile}), or null when none of its
     * files runs here or CurseForge can't be reached.
     */
    private DependencyChoicesPanel.Pick<CurseForgeFile> pickFile(CurseForgeProject dependency,
        boolean preferConnector, ModCompatibility.Target target) {
        List<CurseForgeFile> projectFiles = CurseForgeApi.getFilesForProject(dependency.id);
        if (projectFiles == null) {
            return null;
        }

        markMissingTagsAsUntagged(projectFiles);
        List<CurseForgeFile> newestFirst = projectFiles.stream().filter(f -> f != null)
            .sorted(Comparator.comparingInt((CurseForgeFile f) -> f.id).reversed()).collect(Collectors.toList());
        // CurseForge lists a file's loaders among its game versions
        CurseForgeFile picked = DependencyChoice.pickFile(newestFirst, f -> f.gameVersions, f -> f.gameVersions,
            f -> f.releaseType == null || f.releaseType == 1, null, target, preferConnector);

        return picked == null ? null : new DependencyChoicesPanel.Pick<>(picked, picked.displayName, null, null);
    }

    // true when the dependency's chosen file is now installed
    private boolean installDependency(DependencyChoicesPanel.Item<CurseForgeFile> item, CurseForgeFile picked,
        ProgressDialog<Void> progressDialog) {
        CurseForgeProject dependency = (CurseForgeProject) item.project;
        instanceOrServer.addFileFromCurseForge(dependency, picked, progressDialog);

        return instanceOrServer.getMods().stream().anyMatch(m -> m != null && m.isFromCurseForge()
            && m.getCurseForgeModId() == dependency.id && m.getCurseForgeFileId() == picked.id);
    }

    private boolean isKnownEquivalent(CurseForgeFileDependency dependency, DisableableMod m) {
        return RequiredDependency.providesCurseForgeAsEquivalent(m, dependency.modId,
            instanceOrServer.getLoaderVersion());
    }

    // the matching installed entry, a usable one if there is one
    private DisableableMod preferUsable(List<DisableableMod> mods, Predicate<DisableableMod> matches) {
        List<DisableableMod> matching = mods.stream().filter(m -> m != null && matches.test(m))
            .collect(Collectors.toList());
        return matching.stream().filter(m -> activity(m) == null).findFirst()
            .orElse(matching.isEmpty() ? null : matching.get(0));
    }

    // why an installed entry can't satisfy a dependency right now, or null (see RequiredDependency.activity)
    private RequiredDependency.State activity(DisableableMod installed) {
        return installed == null ? null : RequiredDependency.activity(installed, fileExists(instanceOrServer,
            installed));
    }

    /**
     * Whether an enabled entry's file is on disk. An entry whose location can't be worked out isn't counted as
     * missing, since that can't be checked.
     */
    static boolean fileExists(ModManagement instanceOrServer, DisableableMod installed) {
        if (installed.path == null && installed.type == null) {
            return true;
        }

        java.io.File file = installed.getFile(instanceOrServer);
        return file == null || file.exists();
    }

    protected void getFiles() {
        versionsLabel.setVisible(true);
        filesDropdown.setVisible(true);

        Runnable r = () -> {
            LoaderVersion loaderVersion = instanceOrServer.getLoaderVersion();

            if (installedFileId == null) {
                String blocker = RequiredDependency.fabricApiInstallBlocker(
                    mod.id == Constants.CURSEFORGE_FABRIC_MOD_ID,
                    mod.id == Constants.CURSEFORGE_FORGIFIED_FABRIC_API_MOD_ID,
                    loaderVersion != null && (loaderVersion.isForge() || loaderVersion.isNeoForge()),
                    instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector(),
                    RequiredDependency.anyInstalled(instanceOrServer.getMods(),
                        m -> RequiredDependency.isFabricApi(m) && RequiredDependency.isPresent(m)));
                if (blocker != null) {
                    DialogManager.okDialog().setParent(CurseForgeProjectFileSelectorDialog.this)
                        .setTitle(GetText.tr("Can't Add")).setContent(blocker).setType(DialogManager.ERROR).show();
                    dispose();
                    return;
                }
            }

            List<CurseForgeFile> projectFiles = CurseForgeApi.getFilesForProject(mod.id);

            if (projectFiles == null || projectFiles.isEmpty()) {
                DialogManager.okDialog().setParent(CurseForgeProjectFileSelectorDialog.this)
                    .setTitle(GetText.tr("No files found"))
                    .setContent(new HTMLBuilder().text(GetText.tr(
                            "No files found for this mod. CurseForge may be down or having issues.<br/>Please wait and try again in a few minutes."))
                        .center().build()).setType(DialogManager.ERROR).show();
                dispose();
                return;
            }

            markMissingTagsAsUntagged(projectFiles);

            List<String> neoForgeForgeCompatabilityVersions = ConfigManager
                .getConfigItem("loaders.neoforge.forgeCompatibleMinecraftVersions", new ArrayList<>());
            boolean modsSection = mod.getRootCategoryId() == Constants.CURSEFORGE_MODS_SECTION_ID;

            // a project that's already installed keeps its installed loader here, as its update would; switching
            // loaders would need its own migration action
            if (modsSection && installedFileId == null && updateCandidate == null) {
                instanceOrServer.getMods().stream()
                    .filter(m -> m.isFromCurseForge() && m.getCurseForgeModId() == mod.id).findFirst()
                    .ifPresent(installed -> {
                        updateCandidate = installed.curseForgeUpdateCandidate(instanceOrServer, projectFiles,
                            neoForgeForgeCompatabilityVersions);
                        // #. {0} is the installed mod, {1} its installed version
                        installedJLabel.setText(GetText.tr("{0} is already installed ({1}); adding a file replaces it.",
                            installed.name, installed.version));
                        installedJLabel.setVisible(true);
                    });
            }

            Stream<CurseForgeFile> curseForgeFilesStream = projectFiles.stream()
                .sorted(Comparator.comparingInt((CurseForgeFile file) -> file.id).reversed());

            if (App.settings.addModRestriction == AddModRestriction.STRICT) {
                curseForgeFilesStream = curseForgeFilesStream.filter(
                    file -> mod.getRootCategoryId() == Constants.CURSEFORGE_RESOURCE_PACKS_SECTION_ID
                        || mod.getRootCategoryId() == Constants.CURSEFORGE_PLUGINS_SECTION_ID
                        || file.gameVersions.contains(instanceOrServer.getMinecraftVersion()));
            } else if (App.settings.addModRestriction == AddModRestriction.LAX) {
                try {
                    List<String> minecraftVersionsToSearch = MinecraftManager
                        .getMajorMinecraftVersions(instanceOrServer.getMinecraftVersion()).stream().map(mv -> mv.id)
                        .collect(Collectors.toList());

                    curseForgeFilesStream = curseForgeFilesStream
                        .filter(v -> v.gameVersions.stream()
                            .anyMatch(minecraftVersionsToSearch::contains));
                } catch (InvalidMinecraftVersion e) {
                    LogManager.logStackTrace(e);
                }
            }

            forgeCompatible = neoForgeForgeCompatabilityVersions.contains(instanceOrServer.getMinecraftVersion());
            boolean connectorActive = instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector();

            // only native files that fit this Minecraft version rule out a Connector fallback
            List<CurseForgeFile> versionMatchedFiles = curseForgeFilesStream.collect(Collectors.toList());
            // an update's filter already decided which Connector files may be offered
            fabricFallbackAllowed = modsSection
                && (updateCandidate != null ? connectorActive
                : SinytraConnector.allowsFabricFallback(connectorActive, loaderVersion, forgeCompatible,
                versionMatchedFiles.stream().map(f -> f.gameVersions).collect(Collectors.toList())));
            curseForgeFilesStream = versionMatchedFiles.stream();

            // filter out files not for our loader (if browsing mods)
            if (modsSection) {
                curseForgeFilesStream = curseForgeFilesStream.filter(cf -> {
                    if (updateCandidate != null) {
                        return updateCandidate.test(cf);
                    }

                    if (cf.gameVersions.contains("Fabric") && loaderVersion != null
                        && (loaderVersion.isFabric() || loaderVersion.isLegacyFabric()
                        || loaderVersion.isQuilt()
                        || (fabricFallbackAllowed && isConnectorBuild(cf)))) {
                        return true;
                    }

                    if (cf.gameVersions.contains("NeoForge") && loaderVersion != null
                        && loaderVersion.isNeoForge()) {
                        return true;
                    }

                    if (cf.gameVersions.contains("Forge") && loaderVersion != null
                        && (loaderVersion.isForge()
                        || (loaderVersion.isNeoForge()
                        && neoForgeForgeCompatabilityVersions
                        .contains(instanceOrServer.getMinecraftVersion())))) {
                        return true;
                    }

                    if (cf.gameVersions.contains("Quilt") && loaderVersion != null
                        && loaderVersion.isQuilt()) {
                        return true;
                    }

                    // if there's no loaders, assume the mod is untagged so we should show it
                    return !cf.gameVersions.contains("Fabric") && !cf.gameVersions.contains("NeoForge")
                        && !cf.gameVersions.contains("Forge") && !cf.gameVersions.contains("Quilt");
                });
            }

            files.addAll(curseForgeFilesStream.collect(Collectors.toList()));

            allFiles.addAll(projectFiles.stream()
                .sorted(Comparator.comparingInt((CurseForgeFile file) -> file.id).reversed())
                .collect(Collectors.toList()));

            // ensures that font width is taken into account
            for (CurseForgeFile file : allFiles) {
                filesLength = Math.max(filesLength,
                    getFontMetrics(App.THEME.getNormalFont()).stringWidth(file.displayName) + 100);
            }

            filesDropdown.removeAllItems();

            // try to filter out non compatible mods (Forge on Fabric and vice versa) if no
            // loader gameVersions are set
            if (App.settings.addModRestriction == AddModRestriction.NONE) {
                files.forEach(version -> filesDropdown.addItem(version));
            } else {
                files.stream().filter(version -> {
                    if (!version.gameVersions.contains("Forge") && !version.gameVersions.contains("Fabric")) {
                        String fileName = version.fileName.toLowerCase(Locale.ENGLISH);
                        String displayName = version.displayName.toLowerCase(Locale.ENGLISH);

                        if (loaderVersion != null && loaderVersion.isFabric()) {
                            return !displayName.contains("-forge-") && !displayName.contains("(forge)")
                                && !displayName.contains("[forge") && !fileName.contains("forgemod");
                        }

                        if (loaderVersion != null && !loaderVersion.isFabric()) {
                            // if it's Forge, and the gameVersion has "Fabric" then exclude it
                            return version.gameVersions.contains("Fabric")
                                || (!displayName.toLowerCase(Locale.ENGLISH).contains("-fabric-")
                                && !displayName.contains("(fabric)")
                                && !displayName.contains("[fabric") && !fileName.contains("fabricmod"));
                        }
                    }

                    return true;
                }).forEach(version -> filesDropdown.addItem(version));
            }

            usualFiles.addAll(listedFiles());

            // a new install picks its loader path, native first: the paths split the files the loader already
            // accepted (Forge ones on NeoForge, Fabric ones on Quilt), plus Fabric via Connector where that's
            // verified; a project that's already installed, an update and a reinstall keep their installed
            // loader's list
            if (modsSection && installedFileId == null && updateCandidate == null) {
                List<LoaderChoice> choices = LoaderChoice.choices(usualFiles, this::loaderChoiceOf,
                    connectorActive && allFiles.stream().anyMatch(this::isConnectorBuild));
                // Fabric via Connector first for the automatic fallback, or a Connector build's dependency
                LoaderChoice initial = LoaderChoice.defaultChoice(choices,
                    preferConnectorBuilds || fabricFallbackAllowed);

                // before listing, so the first selection sizes the dialog with room for it
                showLoaderChoices(loaderDropdown, loaderPanel, choices, initial, loaderVersion);
                loaderChoicesReady = true;

                if (initial != null) {
                    List<CurseForgeFile> listed = filesForLoader(initial);
                    showFiles(listed, RequiredDependency.initialSelection(listed, null, preferConnectorBuilds,
                        this::isConnectorBuild));
                }
            }

            if (filesDropdown.getItemCount() == 0) {
                String minecraftVersion = instanceOrServer.getMinecraftVersion();
                DialogManager.okDialog().setParent(CurseForgeProjectFileSelectorDialog.this)
                    .setTitle(GetText.tr("No compatible files found"))
                    .setContent(new HTMLBuilder().text(
                            loaderVersion != null && mod.getRootCategoryId() == Constants.CURSEFORGE_MODS_SECTION_ID
                                ? GetText.tr("No compatible files were found for {0} on Minecraft {1}.",
                                    loaderVersion.getLoaderType(), minecraftVersion)
                                : GetText.tr("No compatible files were found for Minecraft {0}.", minecraftVersion))
                        .center().build()).setType(DialogManager.ERROR).show();
                dispose();
            }

            if (reinstall && filesDropdown.getItemCount() != 0) {
                List<CurseForgeFile> offered = new ArrayList<>();
                for (int i = 0; i < filesDropdown.getItemCount(); i++) {
                    offered.add(filesDropdown.getItemAt(i));
                }

                // a combo box ignores selecting a file it doesn't list, which left the newest file selected
                if (installedFileToReinstall(offered, this.installedFileId) == null) {
                    DialogManager.okDialog().setParent(CurseForgeProjectFileSelectorDialog.this)
                        .setTitle(GetText.tr("Can't Reinstall"))
                        .setContent(GetText.tr("The installed file for {0} isn't available to reinstall here. It may "
                            + "have been removed, or it doesn't match this loader or Minecraft version. Nothing was "
                            + "changed.", mod.name))
                        .setType(DialogManager.ERROR).show();
                    dispose();
                    return;
                }
            }

            if (this.installedFileId != null) {
                CurseForgeFile installedFile = files.stream().filter(f -> f.id == this.installedFileId).findFirst()
                    .orElse(null);

                if (installedFile != null) {
                    if (!selectNewest) {
                        filesDropdown.setSelectedItem(installedFile);
                    }

                    // #. {0} is the name of the file that the user already has installed
                    installedJLabel.setText(GetText.tr("The version currently installed is {0}", installedFile));
                    installedJLabel.setVisible(true);
                }
            }

            // ensures that the dropdown is at least 200 px wide and has a maximum width of
            // 350 px to prevent overflow
            filesDropdown.setPreferredSize(new Dimension(Math.min(350, Math.max(200, filesLength)), 25));

            filesDropdown.setEnabled(true);
            versionsLabel.setVisible(true);
            filesDropdown.setVisible(true);
            addButton.setEnabled(filesDropdown.getSelectedItem() != null);
            viewModButton.setEnabled(true);
            viewFileButton.setEnabled(true);
        };

        new Thread(r).start();
    }
}
