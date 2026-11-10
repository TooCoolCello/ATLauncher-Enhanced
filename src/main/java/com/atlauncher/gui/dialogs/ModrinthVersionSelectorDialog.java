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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.UIManager;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.constants.Constants;
import com.atlauncher.data.AddModRestriction;
import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.LoaderChoice;
import com.atlauncher.data.ModCompatibility;
import com.atlauncher.data.ModManagement;
import com.atlauncher.data.RequiredDependency;
import com.atlauncher.data.SinytraConnector;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthDependency;
import com.atlauncher.data.modrinth.ModrinthDependencyType;
import com.atlauncher.data.modrinth.ModrinthDownloadMetadata;
import com.atlauncher.data.modrinth.ModrinthFile;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthProjectType;
import com.atlauncher.data.modrinth.ModrinthVersion;
import com.atlauncher.exceptions.InvalidMinecraftVersion;
import com.atlauncher.gui.card.ModrinthProjectDependencyCard;
import com.atlauncher.managers.ConfigManager;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.managers.MinecraftManager;
import com.atlauncher.network.Analytics;
import com.atlauncher.utils.ComboItem;
import com.atlauncher.utils.ModrinthApi;
import com.atlauncher.utils.OS;

public class ModrinthVersionSelectorDialog extends JDialog {
    private int versionsLength = 0;
    private int filesLength = 0;
    private final ModrinthProject project;
    private final ModManagement instanceOrServer;
    private final com.atlauncher.data.Type installType;
    private ModrinthDownloadMetadata.Reason downloadReason = ModrinthDownloadMetadata.Reason.STANDALONE;
    private String installedVersionId = null;
    private String dependentVersionId = null;
    private boolean selectNewest = true;
    private boolean reinstall = false;

    private final JPanel dependenciesPanel = new JPanel(new FlowLayout());
    private JScrollPane scrollPane;
    private JButton addButton;
    private JButton viewModButton;
    private JButton viewFileButton;
    private JLabel versionsLabel;
    private JLabel installedJLabel;
    private JComboBox<ModrinthVersion> versionsDropdown;
    private final List<ModrinthVersion> versions = new ArrayList<>();
    private List<ModrinthVersion> versionsData;
    private final JLabel connectorNoteLabel = new JLabel(
        GetText.tr("Uses Sinytra Connector; compatibility is not guaranteed."), JLabel.CENTER);
    private volatile boolean fabricFallbackAllowed = false;
    private volatile boolean forgeCompatible = false;
    // a new install's loader path, native first; per dialog and never saved, shown only when there's a choice
    private final JComboBox<ComboItem<LoaderChoice>> loaderDropdown = new JComboBox<>();
    private final JPanel loaderPanel = new JPanel(new FlowLayout());
    private volatile boolean loaderChoicesReady = false;
    private final List<ModrinthVersion> allVersions = new ArrayList<>();
    // opened for a dependency: suggest a Fabric build for a Connector parent, and select its declared version
    private boolean preferConnectorBuilds = false;
    private String requiredVersionId = null;
    private final JLabel overrideNoteLabel = new JLabel("", JLabel.CENTER);

    private JLabel filesLabel;
    private JComboBox<ComboItem<ModrinthFile>> filesDropdown;

    public ModrinthVersionSelectorDialog(ModrinthProject mod, ModManagement instanceOrServer) {
        this(App.launcher.getParent(), mod, instanceOrServer, (com.atlauncher.data.Type) null);
    }

    public ModrinthVersionSelectorDialog(Window parent, ModrinthProject mod, ModManagement instanceOrServer) {
        this(parent, mod, instanceOrServer, (com.atlauncher.data.Type) null);
    }

    public ModrinthVersionSelectorDialog(Window parent, ModrinthProject mod, ModManagement instanceOrServer,
        com.atlauncher.data.Type installType) {
        super(parent, ModalityType.DOCUMENT_MODAL);

        this.project = mod;
        this.instanceOrServer = instanceOrServer;
        this.installType = installType;

        setupComponents();
    }

    public ModrinthVersionSelectorDialog(Window parent, ModrinthProject mod, ModManagement instanceOrServer,
        ModrinthDownloadMetadata.Reason downloadReason) {
        this(parent, mod, instanceOrServer, downloadReason, null);
    }

    public ModrinthVersionSelectorDialog(Window parent, ModrinthProject mod, ModManagement instanceOrServer,
        ModrinthDownloadMetadata.Reason downloadReason, String dependentVersionId) {
        this(parent, mod, instanceOrServer, downloadReason, dependentVersionId, false, null);
    }

    /**
     * For adding a dependency.
     *
     * @param preferConnectorBuilds the version needing it runs through Connector: a Fabric build is suggested, and
     *                              choosing another is flagged
     * @param requiredVersionId     the exact version the dependency declares: it's preselected, and when it isn't
     *                              available nothing is, so the user sees that; choosing another is flagged
     */
    public ModrinthVersionSelectorDialog(Window parent, ModrinthProject mod, ModManagement instanceOrServer,
        ModrinthDownloadMetadata.Reason downloadReason, String dependentVersionId, boolean preferConnectorBuilds,
        String requiredVersionId) {
        super(parent, ModalityType.DOCUMENT_MODAL);

        this.project = mod;
        this.instanceOrServer = instanceOrServer;
        this.installType = null;
        this.downloadReason = downloadReason;
        this.dependentVersionId = dependentVersionId;
        this.preferConnectorBuilds = preferConnectorBuilds;
        this.requiredVersionId = requiredVersionId;

        setupComponents();
    }

    public ModrinthVersionSelectorDialog(Window parent, ModrinthProject mod, List<ModrinthVersion> versions,
        ModManagement instanceOrServer, String installedVersionId) {
        this(parent, mod, versions, instanceOrServer, installedVersionId, null);
    }

    public ModrinthVersionSelectorDialog(Window parent, ModrinthProject mod, List<ModrinthVersion> versions,
        ModManagement instanceOrServer, String installedVersionId, com.atlauncher.data.Type installType) {
        super(parent, ModalityType.DOCUMENT_MODAL);

        this.project = mod;
        this.versionsData = versions;
        this.instanceOrServer = instanceOrServer;
        this.installedVersionId = installedVersionId;
        this.installType = installType;

        setupComponents();
    }

    /**
     * For Reinstall: preselects the exact installed version among {@code versions}, and stops with a message instead of
     * defaulting to another version when it isn't offered.
     */
    public ModrinthVersionSelectorDialog(Window parent, ModrinthProject mod, List<ModrinthVersion> versions,
        ModManagement instanceOrServer, String installedVersionId, com.atlauncher.data.Type installType,
        boolean reinstall) {
        super(parent, ModalityType.DOCUMENT_MODAL);

        this.project = mod;
        this.versionsData = versions;
        this.instanceOrServer = instanceOrServer;
        this.installedVersionId = installedVersionId;
        this.installType = installType;
        this.reinstall = reinstall;
        this.selectNewest = !reinstall;

        setupComponents();
    }

    /**
     * The offered version with the installed version's exact ID, or null. Reinstall never falls back to another
     * version.
     */
    public static ModrinthVersion installedVersionToReinstall(List<ModrinthVersion> offered,
        String installedVersionId) {
        return installedVersionId == null ? null
            : offered.stream().filter(v -> v != null && installedVersionId.equalsIgnoreCase(v.id)).findFirst()
            .orElse(null);
    }

    public ModrinthVersionSelectorDialog(Window parent, ModrinthProject mod, List<ModrinthVersion> versions,
        ModManagement instanceOrServer, String installedVersionId, com.atlauncher.data.Type installType,
        ModrinthDownloadMetadata.Reason downloadReason) {
        this(parent, mod, versions, instanceOrServer, installedVersionId, installType);

        this.downloadReason = downloadReason;
    }

    private com.atlauncher.data.Type getEffectiveInstallType(ModrinthVersion version) {
        if (installType != null) {
            return installType;
        }

        if (project.projectType == ModrinthProjectType.DATAPACK
            || (version != null && version.loaders != null && version.loaders.contains("datapack"))) {
            return com.atlauncher.data.Type.datapack;
        }

        if (project.projectType == ModrinthProjectType.SHADER) {
            return com.atlauncher.data.Type.shaderpack;
        }

        if (project.projectType == ModrinthProjectType.RESOURCEPACK) {
            return com.atlauncher.data.Type.resourcepack;
        }

        if (project.projectType == ModrinthProjectType.PLUGIN) {
            return com.atlauncher.data.Type.plugins;
        }

        return com.atlauncher.data.Type.mods;
    }

    private String getProjectUrlPath() {
        com.atlauncher.data.Type effectiveType =
            getEffectiveInstallType(versionsDropdown.getSelectedItem() instanceof ModrinthVersion
                ? (ModrinthVersion) versionsDropdown.getSelectedItem()
                : null);

        if (effectiveType == com.atlauncher.data.Type.datapack) {
            return "datapack";
        }

        if (project.projectType == ModrinthProjectType.RESOURCEPACK) {
            return "resourcepack";
        }

        if (project.projectType == ModrinthProjectType.SHADER) {
            return "shader";
        }

        if (project.projectType == ModrinthProjectType.PLUGIN) {
            return "plugin";
        }

        return "mod";
    }

    public String getSelectedVersionId() {
        Object selected = versionsDropdown.getSelectedItem();
        return selected instanceof ModrinthVersion ? ((ModrinthVersion) selected).id : null;
    }

    public void reloadDependenciesPanel() {
        ModrinthVersion selectedFile = (ModrinthVersion) versionsDropdown.getSelectedItem();

        String override = overrideWarning(selectedFile);
        overrideNoteLabel.setText(override == null ? "" : override);
        overrideNoteLabel.setVisible(override != null);

        if (selectedFile == null) {
            return;
        }

        // only listed while Connector is in play: the automatic fallback, the opt-in or a Connector install's update
        connectorNoteLabel.setVisible(needsConnector(selectedFile));

        dependenciesPanel.setVisible(false);

        List<ModrinthProjectDependencyCard> cards = checkDependencies(selectedFile).stream()
            .map(check -> new ModrinthProjectDependencyCard(this, check.project, instanceOrServer, check.state,
                check.status, check.preferConnectorBuilds, check.requiredVersionId))
            .collect(Collectors.toList());

        if (!cards.isEmpty()) {
            dependenciesPanel.removeAll();
            cards.forEach(dependenciesPanel::add);

            dependenciesPanel.setLayout(new GridLayout(cards.size() < 2 ? 1 : cards.size() / 2,
                (cards.size() / 2) + 1));

            setSize(550, 450);
            setLocationRelativeTo(App.launcher.getParent());

            dependenciesPanel.setVisible(true);

            scrollPane.repaint();
            scrollPane.validate();
        } else {
            setSize(550, compactHeight());
        }
    }

    // one unsatisfied required dependency, as checked against what's installed right now
    private static final class DependencyCheck {
        private final RequiredDependency.State state;
        private final ModrinthProject project;
        private final String status;
        private final String warning;
        private final boolean preferConnectorBuilds;
        private final String requiredVersionId;

        private DependencyCheck(RequiredDependency.State state, ModrinthProject project, String status,
            String warning, boolean preferConnectorBuilds, String requiredVersionId) {
            this.state = state;
            this.project = project;
            this.status = status;
            this.warning = warning;
            this.preferConnectorBuilds = preferConnectorBuilds;
            this.requiredVersionId = requiredVersionId;
        }
    }

    /**
     * The required dependencies of this exact version that aren't satisfied, checked against the installed mods as
     * they are now. Used for the cards and again when Add is pressed, so an earlier card can't vouch for a
     * dependency that has since been disabled or removed.
     */
    private List<DependencyCheck> checkDependencies(ModrinthVersion version) {
        boolean parentNeedsConnector = needsConnector(version);
        boolean connectorActive = instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector();
        List<String> neoForgeForgeCompatabilityVersions = ConfigManager
            .getConfigItem("loaders.neoforge.forgeCompatibleMinecraftVersions", new ArrayList<>());
        List<DisableableMod> mods = instanceOrServer.getMods();
        List<DependencyCheck> checks = new ArrayList<>();

        for (ModrinthDependency dependency : version.dependencies) {
            if (dependency.dependencyType != ModrinthDependencyType.REQUIRED) {
                continue;
            }

            RequiredDependency.State state;
            DisableableMod installed = null;
            boolean viaForgified = connectorActive && Constants.MODRINTH_FABRIC_MOD_ID.equals(dependency.projectId);

            if (dependency.projectId == null) {
                // a dependency naming only a version can't be looked up here
                state = RequiredDependency.State.UNRESOLVED;
            } else if (viaForgified) {
                // Connector gets Fabric API from Forgified Fabric API, never both
                installed = preferUsable(mods, RequiredDependency::isForgifiedFabricApi);
                state = RequiredDependency.fabricApiUnderConnector(
                    RequiredDependency.anyInstalled(mods,
                        m -> RequiredDependency.isFabricApi(m) && RequiredDependency.isPresent(m)),
                    installed != null, activity(installed));
            } else {
                // the known equivalents: the same library from CurseForge, or QSL for Fabric API
                DisableableMod equivalent = preferUsable(mods, m -> providesDependency(m, dependency.projectId)
                    && !(m.isFromModrinth() && dependency.projectId.equals(m.modrinthProject.id)));
                if (equivalent != null) {
                    installed = equivalent;
                    RequiredDependency.State activity = activity(equivalent);
                    state = activity == null ? RequiredDependency.State.SATISFIED : activity;
                } else {
                    installed = preferUsable(mods,
                        m -> m.isFromModrinth() && dependency.projectId.equals(m.modrinthProject.id));
                    state = RequiredDependency.state(installed == null ? null
                            : installed.modrinthInstalledPath(instanceOrServer, null,
                                neoForgeForgeCompatabilityVersions),
                        activity(installed), parentNeedsConnector, dependency.versionId,
                        installed == null ? null : installed.modrinthVersion.id);
                }
            }

            if (state == RequiredDependency.State.SATISFIED) {
                continue;
            }

            ModrinthProject dependencyProject = state == RequiredDependency.State.UNRESOLVED ? null
                : ModrinthApi.getProject(viaForgified ? Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID
                    : dependency.projectId);
            state = RequiredDependency.withProject(state, dependencyProject);
            String installedVersion = installed == null ? null
                : installed.modrinthVersion != null && installed.modrinthVersion.name != null
                    ? installed.modrinthVersion.name
                    : installed.version;
            boolean forDependencyItself = state == RequiredDependency.State.MISSING
                || state == RequiredDependency.State.OTHER_VERSION;

            checks.add(new DependencyCheck(state, dependencyProject,
                RequiredDependency.status(state, parentNeedsConnector, installedVersion),
                (dependencyProject == null ? GetText.tr("Unknown Project") : dependencyProject.title) + ": "
                    + RequiredDependency.warning(state, parentNeedsConnector, installedVersion),
                parentNeedsConnector && forDependencyItself, forDependencyItself ? dependency.versionId : null));
        }

        return checks;
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
        return installed == null ? null : RequiredDependency.activity(installed,
            CurseForgeProjectFileSelectorDialog.fileExists(instanceOrServer, installed));
    }

    // room for the notes and the loader choice when they're showing
    private int compactHeight() {
        return 200 + (connectorNoteLabel.isVisible() ? 20 : 0) + (loaderPanel.isVisible() ? 30 : 0)
            + (overrideNoteLabel.isVisible() ? 20 : 0);
    }

    private LoaderChoice selectedLoader() {
        Object item = loaderDropdown.getSelectedItem();
        return item == null ? LoaderChoice.NATIVE
            : ((ComboItem<LoaderChoice>) item).getValue();
    }

    private LoaderChoice loaderChoiceOf(ModrinthVersion version) {
        return LoaderChoice.of(version.loaders, instanceOrServer.getLoaderVersion(), forgeCompatible);
    }

    private List<ModrinthVersion> versionsForLoader(LoaderChoice choice) {
        return LoaderChoice.filesFor(choice, allVersions, versions, this::loaderChoiceOf, this::isConnectorBuild);
    }

    private Predicate<ModrinthVersion> requiredVersion() {
        return requiredVersionId == null ? null : v -> v != null && requiredVersionId.equalsIgnoreCase(v.id);
    }

    // for a dependency: a missing or departed-from declared version, or a non-Fabric build for a Connector parent
    private String overrideWarning(ModrinthVersion selected) {
        Predicate<ModrinthVersion> required = requiredVersion();

        if (required != null && listedVersions().stream().noneMatch(required)) {
            return GetText.tr("The declared version isn't available here; another may not meet the requirement.");
        }

        if (required != null && selected != null && !required.test(selected)) {
            return GetText.tr("Not the declared version, so it may not meet the requirement.");
        }

        return RequiredDependency.isOverride(selected, null, preferConnectorBuilds, this::isConnectorBuild)
            ? GetText.tr("Not a Fabric build, so it may not satisfy the Fabric mod that needs it.")
            : null;
    }

    private boolean needsConnector(ModrinthVersion version) {
        return SinytraConnector.isFabricFallbackFile(version.loaders, instanceOrServer.getLoaderVersion(),
            forgeCompatible);
    }

    private boolean isConnectorBuild(ModrinthVersion version) {
        return SinytraConnector.isConnectorBuild(version.loaders, version.gameVersions,
            instanceOrServer.getMinecraftVersion(), instanceOrServer.getLoaderVersion(), forgeCompatible);
    }

    private List<ModrinthVersion> listedVersions() {
        List<ModrinthVersion> listed = new ArrayList<>();
        for (int i = 0; i < versionsDropdown.getItemCount(); i++) {
            listed.add(versionsDropdown.getItemAt(i));
        }
        return listed;
    }

    // lists versions and selects one, or none, leaving Add usable only with a selection
    private void showVersions(List<ModrinthVersion> listed, ModrinthVersion select) {
        versionsDropdown.removeAllItems();
        listed.forEach(versionsDropdown::addItem);
        if (select == null) {
            versionsDropdown.setSelectedIndex(-1);
        } else {
            versionsDropdown.setSelectedItem(select);
        }
        addButton.setEnabled(select != null);
    }

    // lists the chosen loader's versions, never leaving a version it doesn't show selected
    private void relistVersions() {
        ModrinthVersion selected = (ModrinthVersion) versionsDropdown.getSelectedItem();
        List<ModrinthVersion> listed = versionsForLoader(selectedLoader());
        showVersions(listed, LoaderChoice.selectionAfterSwitch(listed, selected));
    }

    /**
     * Whether an installed mod already satisfies a Modrinth dependency, including the same library installed from
     * CurseForge (matched by its CurseForge mod ID).
     */
    static boolean providesDependency(DisableableMod installedMod, String dependencyProjectId) {
        // don't show Modrinth dependency when grabbed from CurseForge
        if (dependencyProjectId.equals(Constants.MODRINTH_FABRIC_MOD_ID)
            && installedMod.isFromCurseForge()
            && installedMod.getCurseForgeModId() == Constants.CURSEFORGE_FABRIC_MOD_ID) {
            return true;
        }

        // don't show Modrinth dependency when grabbed from CurseForge
        if (dependencyProjectId.equals(Constants.MODRINTH_LEGACY_FABRIC_MOD_ID)
            && installedMod.isFromCurseForge()
            && installedMod.getCurseForgeModId() == Constants.CURSEFORGE_LEGACY_FABRIC_MOD_ID) {
            return true;
        }

        // don't show Fabric dependency when QSL is installed
        if (dependencyProjectId.equals(Constants.MODRINTH_FABRIC_MOD_ID)
            && installedMod.isFromModrinth()
            && installedMod.modrinthProject.id.equals(Constants.MODRINTH_QSL_MOD_ID)) {
            return true;
        }

        // don't show Modrinth dependency when grabbed from CurseForge
        if (dependencyProjectId.equals(Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID)
            && installedMod.isFromCurseForge()
            && installedMod.getCurseForgeModId() == Constants.CURSEFORGE_FORGIFIED_FABRIC_API_MOD_ID) {
            return true;
        }

        return installedMod.isFromModrinth() && installedMod.modrinthProject.id.equals(dependencyProjectId);
    }

    /**
     * Whether a mod version's loaders run on this loader. Fabric-only versions on Forge/NeoForge pass only as a
     * Connector fallback, and versions with no loader metadata never pass.
     */
    static boolean acceptsLoaders(List<String> loaders, LoaderVersion loaderVersion, boolean forgeCompatible,
        boolean fabricFallbackAllowed) {
        if (loaders == null) {
            return false;
        }

        // the same native rule Add Mods' compatibility marks use
        return ModCompatibility.nativeTags(loaderVersion, forgeCompatible).stream().anyMatch(loaders::contains)
            || (fabricFallbackAllowed && loaders.contains("fabric"));
    }

    /**
     * Stacks the selector rows with the Connector note under them, for the middle panel's NORTH slot. BorderLayout
     * gives the always-present 250px dependencies scroll pane in SOUTH its full height, so anything in CENTER gets no
     * room at the compact dialog size.
     */
    static JPanel selectorWithConnectorNote(JLabel connectorNote, JComponent... selectorRows) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        for (JComponent row : selectorRows) {
            panel.add(row);
        }

        connectorNote.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(connectorNote);

        return panel;
    }

    private void setupComponents() {
        // #. {0} is the name of the mod we're installing
        setTitle(GetText.tr("Installing {0}", project.title));

        setSize(550, 200);
        setMinimumSize(new Dimension(550, 200));
        setLocationRelativeTo(App.launcher.getParent());
        setLayout(new BorderLayout());
        setResizable(true);
        this.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        addButton = new JButton(GetText.tr("Add"));
        addButton.setEnabled(false);

        viewModButton = new JButton(GetText.tr("View Mod"));
        if (instanceOrServer.getLoaderVersion() != null && ((instanceOrServer.getLoaderVersion().isPaper()
            && (project.projectType == ModrinthProjectType.PLUGIN || project.loaders.contains("paper")
            || project.loaders.contains("bukkit") || project.loaders.contains("spigot")))
            || (instanceOrServer.getLoaderVersion().isPurpur()
            && (project.projectType == ModrinthProjectType.PLUGIN || project.loaders.contains("purpur")
            || project.loaders.contains("paper")
            || project.loaders.contains("bukkit") || project.loaders.contains("spigot"))))) {
            viewModButton.setText(GetText.tr("View Plugin"));
        }
        viewModButton.setEnabled(false);

        viewFileButton = new JButton(GetText.tr("View File"));
        viewFileButton.setEnabled(false);

        dependenciesPanel.setVisible(false);
        dependenciesPanel
            .setBorder(BorderFactory.createTitledBorder(GetText.tr("The below mods need to be installed")));

        // Top Panel Stuff
        JPanel top = new JPanel(new BorderLayout());
        // #. {0} is the name of the mod we're installing
        top.add(new JLabel(GetText.tr("Installing {0}", project.title), JLabel.CENTER), BorderLayout.NORTH);

        installedJLabel = new JLabel("", JLabel.CENTER);
        top.add(installedJLabel, BorderLayout.SOUTH);

        // Middle Panel Stuff
        JPanel middle = new JPanel(new BorderLayout());

        // Middle Panel Stuff
        JPanel versionsPanel = new JPanel(new FlowLayout());
        versionsPanel.setBorder(BorderFactory.createEmptyBorder(20, 0, 0, 0));

        versionsLabel = new JLabel(GetText.tr("Version To Install") + ": ");
        versionsPanel.add(versionsLabel);

        versionsDropdown = new JComboBox<>();
        ModrinthVersion loadingProject = new ModrinthVersion();
        loadingProject.name = GetText.tr("Loading");
        versionsDropdown.addItem(loadingProject);
        versionsDropdown.setEnabled(false);
        versionsPanel.add(versionsDropdown);

        JPanel filesPanel = new JPanel(new FlowLayout());
        filesPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 20, 0));
        filesLabel = new JLabel(GetText.tr("File To Install") + ": ");
        filesPanel.add(filesLabel);
        filesDropdown = new JComboBox<>();
        filesPanel.add(filesDropdown);
        filesPanel.setVisible(false);

        scrollPane = new JScrollPane(dependenciesPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setPreferredSize(new Dimension(550, 250));

        connectorNoteLabel.setForeground(UIManager.getColor("yellow"));
        connectorNoteLabel.setVisible(false);

        loaderPanel.add(new JLabel(GetText.tr("Mod loader") + ": "));
        loaderPanel.add(loaderDropdown);
        loaderPanel.setVisible(false);
        loaderDropdown.addActionListener(e -> {
            if (loaderChoicesReady) {
                relistVersions();
            }
        });

        overrideNoteLabel.setForeground(UIManager.getColor("yellow"));
        overrideNoteLabel.setAlignmentX(CENTER_ALIGNMENT);
        overrideNoteLabel.setVisible(false);

        middle.add(selectorWithConnectorNote(connectorNoteLabel, loaderPanel, versionsPanel, filesPanel,
            overrideNoteLabel), BorderLayout.NORTH);
        middle.add(scrollPane, BorderLayout.SOUTH);

        this.getFiles();

        // Bottom Panel Stuff
        JPanel bottom = new JPanel();
        bottom.setLayout(new FlowLayout());

        addButton.addActionListener(e -> {
            ModrinthVersion version = (ModrinthVersion) versionsDropdown.getSelectedItem();

            // recheck now: the list or Connector may have changed since the version was selected
            boolean versionNeedsConnector = version != null && needsConnector(version);
            boolean connectorActiveNow = instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector();
            if (!SinytraConnector.mayInstall(listedVersions(), version, versionNeedsConnector, connectorActiveNow)) {
                if (versionNeedsConnector && !connectorActiveNow) {
                    CurseForgeProjectFileSelectorDialog.showConnectorUnavailable(this, project.title);
                }
                return;
            }

            List<String> warnings = new ArrayList<>();
            String override = overrideWarning(version);
            if (override != null) {
                warnings.add(override);
            }
            // checked again now, not taken from the cards, which may predate a change to the installed mods
            checkDependencies(version).forEach(check -> warnings.add(check.warning));
            if (!CurseForgeProjectFileSelectorDialog.confirmDespiteWarnings(this, version.name, warnings)) {
                return;
            }

            ModrinthFile file = filesDropdown.getSelectedItem() == null ? null
                : ((ComboItem<ModrinthFile>) filesDropdown.getSelectedItem()).getValue();

            ProgressDialog<Void> progressDialog = new ProgressDialog<>(
                // #. {0} is the name of the mod we're installing
                GetText.tr("Installing {0}", version.name), true, this);
            progressDialog.addThread(new Thread(() -> {
                com.atlauncher.data.Type effectiveInstallType = getEffectiveInstallType(version);

                Analytics.trackEvent(project.getAnalyticsEventForAdded(version, effectiveInstallType));
                instanceOrServer.addFileFromModrinth(project, version, file, effectiveInstallType, downloadReason,
                    dependentVersionId, progressDialog);
                progressDialog.close();
            }));
            progressDialog.start();
            dispose();
        });

        viewModButton
            .addActionListener(e -> OS.openWebBrowser(String.format("https://modrinth.com/%s/%s",
                getProjectUrlPath(), project.slug)));

        viewFileButton.addActionListener(e -> {
            ModrinthVersion version = (ModrinthVersion) versionsDropdown.getSelectedItem();

            OS.openWebBrowser(String.format("https://modrinth.com/mod/%s/version/%s", project.slug, version.id));
        });

        versionsDropdown.addActionListener(e -> {
            ModrinthVersion version = (ModrinthVersion) versionsDropdown.getSelectedItem();

            // also with nothing selected, so a missing declared version is still explained
            reloadDependenciesPanel();

            if (version != null) {

                filesDropdown.removeAllItems();
                if (version.files.size() > 1) {
                    int selectedIndex = 0;

                    for (ModrinthFile file : version.files) {
                        filesDropdown.addItem(new ComboItem<>(file, file.toString()));

                        if (file.primary) {
                            selectedIndex = filesDropdown.getItemCount() - 1;
                        }

                        // ensures that font width is taken into account
                        filesLength = Math.max(filesLength,
                            getFontMetrics(App.THEME.getNormalFont()).stringWidth(file.toString()) + 100);
                    }

                    // ensures that the dropdown is at least 200 px wide and has a maximum width of
                    // 350 px to prevent overflow
                    filesDropdown.setPreferredSize(new Dimension(Math.min(350, Math.max(200, filesLength)), 25));
                    filesDropdown.setSelectedIndex(selectedIndex);
                }

                filesPanel.setVisible(version.files.size() > 1);
            }
        });

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

    protected void getFiles() {
        versionsLabel.setVisible(true);
        versionsDropdown.setVisible(true);

        Runnable r = () -> {
            if (this.installedVersionId == null) {
                LoaderVersion loader = instanceOrServer.getLoaderVersion();
                String blocker = RequiredDependency.fabricApiInstallBlocker(
                    Constants.MODRINTH_FABRIC_MOD_ID.equals(project.id),
                    Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID.equals(project.id),
                    loader != null && (loader.isForge() || loader.isNeoForge()),
                    instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector(),
                    RequiredDependency.anyInstalled(instanceOrServer.getMods(),
                        m -> RequiredDependency.isFabricApi(m) && RequiredDependency.isPresent(m)));
                if (blocker != null) {
                    DialogManager.okDialog().setParent(ModrinthVersionSelectorDialog.this)
                        .setTitle(GetText.tr("Can't Add")).setContent(blocker).setType(DialogManager.ERROR).show();
                    dispose();
                    return;
                }
            }

            if (this.versionsData == null) {
                this.versionsData = ModrinthApi.getVersions(project.id);
            }

            if (this.versionsData == null || this.versionsData.isEmpty()) {
                DialogManager.okDialog().setParent(ModrinthVersionSelectorDialog.this).setTitle("No versions found")
                    .setContent("No versions found for this mod").setType(DialogManager.ERROR).show();
                dispose();
                return;
            }

            LoaderVersion loaderVersion = instanceOrServer.getLoaderVersion();
            String minecraftVersion = instanceOrServer.getMinecraftVersion();
            List<String> neoForgeForgeCompatabilityVersions = ConfigManager
                .getConfigItem("loaders.neoforge.forgeCompatibleMinecraftVersions", new ArrayList<>());
            boolean modVersions = loaderVersion != null
                && project.projectType == ModrinthProjectType.MOD
                && this.installType != com.atlauncher.data.Type.datapack;

            // a project that's already installed keeps its installed loader here, as its update would; switching
            // loaders would need its own migration action
            DisableableMod installedMod = this.installedVersionId != null ? null
                : instanceOrServer.getMods().stream()
                .filter(m -> m.isFromModrinth() && project.id.equalsIgnoreCase(m.modrinthProject.id))
                .findFirst().orElse(null);
            if (installedMod != null && modVersions) {
                this.versionsData = installedMod.modrinthUpdateCandidates(instanceOrServer, this.versionsData,
                    installedMod.modrinthInstalledPath(instanceOrServer, this.versionsData,
                        neoForgeForgeCompatabilityVersions), neoForgeForgeCompatabilityVersions);
                // #. {0} is the installed mod, {1} its installed version
                installedJLabel.setText(GetText.tr("{0} is already installed ({1}); adding a version replaces it.",
                    installedMod.name, Optional.ofNullable(installedMod.modrinthVersion.name)
                        .orElse(installedMod.version)));
                installedJLabel.setVisible(true);
            }
            boolean newInstall = this.installedVersionId == null && installedMod == null;

            Stream<ModrinthVersion> modrinthVersionsStream = this.versionsData.stream()
                .sorted(Comparator.comparing((ModrinthVersion version) -> version.datePublished).reversed());

            if (App.settings.addModRestriction == AddModRestriction.STRICT) {
                modrinthVersionsStream = modrinthVersionsStream.filter(v -> v.gameVersions.contains(minecraftVersion));
            } else if (App.settings.addModRestriction == AddModRestriction.LAX) {
                try {
                    List<String> minecraftVersionsToSearch = MinecraftManager
                        .getMajorMinecraftVersions(minecraftVersion).stream().map(mv -> mv.id)
                        .collect(Collectors.toList());

                    modrinthVersionsStream = modrinthVersionsStream.filter(
                        v -> v.gameVersions.stream().anyMatch(minecraftVersionsToSearch::contains));
                } catch (InvalidMinecraftVersion e) {
                    LogManager.logStackTrace(e);
                }
            }

            if (modVersions) {
                forgeCompatible = neoForgeForgeCompatabilityVersions.contains(minecraftVersion);
                boolean connectorActive = instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector();

                // only native versions that fit this Minecraft version rule out a Connector fallback
                List<ModrinthVersion> versionMatched = modrinthVersionsStream.collect(Collectors.toList());
                fabricFallbackAllowed = SinytraConnector.allowsFabricFallback(connectorActive, loaderVersion,
                    forgeCompatible, versionMatched.stream().map(v -> v.loaders).collect(Collectors.toList()));
                modrinthVersionsStream = versionMatched.stream();

                // Connector versions only ever for this exact Minecraft version, whatever the restriction
                modrinthVersionsStream = modrinthVersionsStream
                    .filter(v -> acceptsLoaders(v.loaders, loaderVersion, forgeCompatible, fabricFallbackAllowed)
                        && (!needsConnector(v) || isConnectorBuild(v)));

                allVersions.addAll(this.versionsData.stream()
                    .sorted(Comparator.comparing((ModrinthVersion version) -> version.datePublished).reversed())
                    .collect(Collectors.toList()));
            }

            if (this.installType == com.atlauncher.data.Type.datapack) {
                modrinthVersionsStream = modrinthVersionsStream
                    .filter(v -> v.loaders != null && v.loaders.contains("datapack"));
            }

            versions.addAll(modrinthVersionsStream.collect(Collectors.toList()));

            // ensures that font width is taken into account
            for (ModrinthVersion version : this.versionsData) {
                versionsLength = Math.max(versionsLength,
                    getFontMetrics(App.THEME.getNormalFont()).stringWidth(version.name) + 100);
            }

            versionsDropdown.removeAllItems();

            versions.forEach(version -> versionsDropdown.addItem(version));

            // a new install picks its loader path, native first: the paths split the versions the loader already
            // accepted (Forge ones on NeoForge, Fabric ones on Quilt), plus Fabric via Connector where that's
            // verified; a project that's already installed, an update and a reinstall keep their installed
            // loader's list
            Predicate<ModrinthVersion> required = requiredVersion();
            if (modVersions && newInstall) {
                List<LoaderChoice> choices = LoaderChoice.choices(versions, this::loaderChoiceOf,
                    instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector()
                        && allVersions.stream().anyMatch(this::isConnectorBuild));

                // a dependency's declared version decides the loader path; otherwise Fabric via Connector first for
                // the automatic fallback or a Connector build's dependency
                ModrinthVersion declared = required == null ? null
                    : allVersions.stream().filter(required).findFirst().orElse(null);
                LoaderChoice declaredChoice = declared == null ? null : loaderChoiceOf(declared);
                LoaderChoice initial = declaredChoice != null && choices.contains(declaredChoice)
                    ? declaredChoice
                    : LoaderChoice.defaultChoice(choices, preferConnectorBuilds || fabricFallbackAllowed);

                // before listing, so the first selection sizes the dialog with room for it
                CurseForgeProjectFileSelectorDialog.showLoaderChoices(loaderDropdown, loaderPanel, choices, initial,
                    loaderVersion);
                loaderChoicesReady = true;

                if (initial != null) {
                    List<ModrinthVersion> listed = versionsForLoader(initial);
                    showVersions(listed, RequiredDependency.initialSelection(listed, required, preferConnectorBuilds,
                        this::isConnectorBuild));
                }
            } else if (required != null && versionsDropdown.getItemCount() != 0) {
                // a declared version in a list kept to the installed loader: select it or show it's missing
                showVersions(new ArrayList<>(versions), RequiredDependency.initialSelection(versions, required, false,
                    this::isConnectorBuild));
            }

            if (versionsDropdown.getItemCount() == 0) {
                DialogManager.okDialog().setParent(ModrinthVersionSelectorDialog.this)
                    .setTitle(GetText.tr("No compatible files found"))
                    .setContent(loaderVersion != null && project.projectType == ModrinthProjectType.MOD
                        && this.installType != com.atlauncher.data.Type.datapack
                        ? GetText.tr("No compatible files were found for {0} on Minecraft {1}.",
                            loaderVersion.getLoaderType(), minecraftVersion)
                        : GetText.tr("No compatible files were found for Minecraft {0}.", minecraftVersion))
                    .setType(DialogManager.ERROR).show();
                dispose();
            }

            if (reinstall && !versions.isEmpty()
                && installedVersionToReinstall(versions, this.installedVersionId) == null) {
                DialogManager.okDialog().setParent(ModrinthVersionSelectorDialog.this)
                    .setTitle(GetText.tr("Can't Reinstall"))
                    .setContent(GetText.tr("The installed version of {0} isn't available to reinstall here. It may "
                        + "have been removed, or it doesn't match this loader or Minecraft version. Nothing was "
                        + "changed.", project.title))
                    .setType(DialogManager.ERROR).show();
                dispose();
                return;
            }

            if (this.installedVersionId != null) {
                ModrinthVersion installedFile = versions.stream()
                    .filter(f -> f.id.equalsIgnoreCase(this.installedVersionId)).findFirst().orElse(null);

                if (installedFile != null) {
                    if (!selectNewest) {
                        versionsDropdown.setSelectedItem(installedFile);
                    }

                    // #. {0} is the name of the mod that the user already has installed
                    installedJLabel.setText(GetText.tr("The version currently installed is {0}", installedFile.name));
                    installedJLabel.setVisible(true);
                }
            }

            // ensures that the dropdown is at least 200 px wide and has a maximum width of
            // 350 px to prevent overflow
            versionsDropdown.setPreferredSize(new Dimension(Math.min(350, Math.max(200, versionsLength)), 25));

            versionsDropdown.setEnabled(true);
            versionsLabel.setVisible(true);
            versionsDropdown.setVisible(true);
            addButton.setEnabled(versionsDropdown.getSelectedItem() != null);
            viewModButton.setEnabled(true);
            viewFileButton.setEnabled(true);
        };

        new Thread(r).start();
    }
}
