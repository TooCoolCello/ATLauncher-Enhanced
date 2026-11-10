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
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.ItemEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JLayer;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.builders.HTMLBuilder;
import com.atlauncher.data.BulkUpdateCheck;
import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.InstalledDependencies;
import com.atlauncher.data.Instance;
import com.atlauncher.data.ModManagement;
import com.atlauncher.data.Server;
import com.atlauncher.data.curseforge.CurseForgeAuthor;
import com.atlauncher.data.curseforge.CurseForgeFingerprint;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.minecraft.FabricMod;
import com.atlauncher.data.minecraft.MCMod;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthVersion;
import com.atlauncher.gui.WheelScrollLayerUI;
import com.atlauncher.gui.components.ModsJCheckBox;
import com.atlauncher.gui.handlers.ModsJCheckBoxTransferHandler;
import com.atlauncher.gui.layouts.WrapLayout;
import com.atlauncher.managers.ConfigManager;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.network.Analytics;
import com.atlauncher.utils.CurseForgeApi;
import com.atlauncher.utils.FileUtils;
import com.atlauncher.utils.Hashing;
import com.atlauncher.utils.ModrinthApi;
import com.atlauncher.utils.Utils;
import com.formdev.flatlaf.icons.FlatSearchIcon;

public class EditModsDialog extends JDialog {
    private static final long serialVersionUID = 7004414192679481818L;

    enum SortOrder {
        NAME, DATE_ADDED, UPDATE_AVAILABLE;

        @Override
        public String toString() {
            switch (this) {
                case DATE_ADDED:
                    return GetText.tr("Date Added");
                case UPDATE_AVAILABLE:
                    return GetText.tr("Update Available");
                default:
                    return GetText.tr("Name");
            }
        }
    }

    // ponytail: remembered until the launcher closes, not saved to settings
    private static SortOrder lastSortOrder = SortOrder.NAME;

    public final ModManagement instanceOrServer;

    private JPanel disabledModsPanel, enabledModsPanel;
    private JButton checkForUpdatesButton;
    private JButton reinstallButton;
    private JButton enableButton;
    private JButton disableButton;
    private JButton removeButton;
    private JButton refreshMetadataButton;
    private JCheckBox selectAllEnabledModsCheckbox, selectAllDisabledModsCheckbox;
    private ArrayList<ModsJCheckBox> enabledMods, disabledMods;
    private final JTextField searchField = new JTextField(20);
    private final JComboBox<SortOrder> sortBox = new JComboBox<>();
    private final JLabel updateCheckLabel = new JLabel();
    private final Map<DisableableMod, DisableableMod.UpdateStatus> updateStatuses = new ConcurrentHashMap<>();
    private ExecutorService updateChecker;

    public EditModsDialog(Instance instance) {
        super(App.launcher.getParent(),
            // #. {0} is the name of the instance
            GetText.tr("Editing Mods For {0}", instance.launcher.name), ModalityType.DOCUMENT_MODAL);
        this.instanceOrServer = instance;

        setup();
    }

    public EditModsDialog(Server server) {
        super(App.launcher.getParent(),
            // #. {0} is the name of the instance
            GetText.tr("Editing Mods For {0}", server.name), ModalityType.DOCUMENT_MODAL);
        this.instanceOrServer = server;

        setup();
    }

    private void setup() {
        // bigger by default, but never larger than the screen's usable area (HiDPI scaling shrinks it)
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1000, screen.width), Math.min(700, screen.height));
        setMinimumSize(new Dimension(550, 450));
        setLocationRelativeTo(App.launcher.getParent());
        setLayout(new BorderLayout());
        setResizable(true);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent arg0) {
                dispose();
            }
        });

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "focusSearch");
        getRootPane().getActionMap().put("focusSearch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                searchField.requestFocusInWindow();
                searchField.selectAll();
            }
        });

        setupComponents();

        instanceOrServer.scanMissingMods(this);

        loadMods();
    }

    private void setupComponents() {
        Analytics.sendScreenView("Edit Mods Dialog");

        JPanel searchPanel = new JPanel(new WrapLayout());
        add(searchPanel, BorderLayout.NORTH);
        searchField.putClientProperty("JTextField.placeholderText", GetText.tr("Search"));
        searchField.putClientProperty("JTextField.leadingIcon", new FlatSearchIcon());
        searchField.putClientProperty("JTextField.showClearButton", true);
        searchField.putClientProperty("JTextField.clearCallback", (Runnable) () -> searchField.setText(""));
        // document events (not key events) so paste, the clear button and setText all refilter
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });
        searchPanel.add(searchField);

        sortBox.addItem(SortOrder.NAME);
        sortBox.addItem(SortOrder.DATE_ADDED);
        if (canCheckForUpdates()) {
            sortBox.addItem(SortOrder.UPDATE_AVAILABLE);
        }
        sortBox.setSelectedItem(lastSortOrder);
        sortBox.addActionListener(e -> {
            lastSortOrder = (SortOrder) sortBox.getSelectedItem();
            applySort();
        });
        searchPanel.add(new JLabel(GetText.tr("Sort By")));
        searchPanel.add(sortBox);

        JButton refreshButton = new JButton(GetText.tr("Refresh"));
        refreshButton.setToolTipText(GetText.tr("Show mods added or removed outside the launcher"));
        refreshButton.addActionListener(e -> refresh());
        searchPanel.add(refreshButton);

        searchPanel.add(updateCheckLabel);

        JPanel topLeftPanel = new JPanel(new FlowLayout());

        JLabel topLabelLeft = new JLabel(GetText.tr("Enabled Mods"));
        topLabelLeft.setHorizontalAlignment(SwingConstants.CENTER);
        topLeftPanel.add(topLabelLeft);

        selectAllEnabledModsCheckbox = new JCheckBox();
        selectAllEnabledModsCheckbox.addActionListener(e -> {
            boolean selected = selectAllEnabledModsCheckbox.isSelected();

            enabledMods.stream().filter(Component::isVisible).forEach(em -> em.setSelected(selected));
        });
        topLeftPanel.add(selectAllEnabledModsCheckbox);

        JPanel topRightPanel = new JPanel(new FlowLayout());

        JLabel topLabelRight = new JLabel(GetText.tr("Disabled Mods"));
        topLabelRight.setHorizontalAlignment(SwingConstants.CENTER);
        topRightPanel.add(topLabelRight);

        selectAllDisabledModsCheckbox = new JCheckBox();
        selectAllDisabledModsCheckbox.addActionListener(e -> {
            boolean selected = selectAllDisabledModsCheckbox.isSelected();

            disabledMods.stream().filter(Component::isVisible).forEach(dm -> dm.setSelected(selected));
        });
        topRightPanel.add(selectAllDisabledModsCheckbox);

        disabledModsPanel = new JPanel();
        disabledModsPanel.setLayout(new BoxLayout(disabledModsPanel, BoxLayout.Y_AXIS));
        disabledModsPanel.setBackground(UIManager.getColor("Mods.modSelectionColor"));
        disabledModsPanel.setTransferHandler(new ModsJCheckBoxTransferHandler(this, true));

        JScrollPane scroller1 = new JScrollPane(disabledModsPanel,
            JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroller1.getVerticalScrollBar().setUnitIncrement(16);

        JPanel disabledColumn = new JPanel(new BorderLayout());
        disabledColumn.add(topRightPanel, BorderLayout.NORTH);
        disabledColumn.add(new JLayer<>(scroller1, new WheelScrollLayerUI()), BorderLayout.CENTER);

        enabledModsPanel = new JPanel();
        enabledModsPanel.setLayout(new BoxLayout(enabledModsPanel, BoxLayout.Y_AXIS));
        enabledModsPanel.setBackground(UIManager.getColor("Mods.modSelectionColor"));
        enabledModsPanel.setTransferHandler(new ModsJCheckBoxTransferHandler(this, true));;

        JScrollPane scroller2 = new JScrollPane(enabledModsPanel,
            JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroller2.getVerticalScrollBar().setUnitIncrement(16);

        JPanel enabledColumn = new JPanel(new BorderLayout());
        enabledColumn.add(topLeftPanel, BorderLayout.NORTH);
        enabledColumn.add(new JLayer<>(scroller2, new WheelScrollLayerUI()), BorderLayout.CENTER);

        // drag the divider to resize the two lists; resizing the window grows both equally
        JSplitPane modsInPack = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, enabledColumn, disabledColumn);
        modsInPack.setResizeWeight(0.5);
        modsInPack.setContinuousLayout(true);
        modsInPack.setBorder(null);
        add(modsInPack, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new WrapLayout());
        add(bottomPanel, BorderLayout.SOUTH);

        JButton addButton = new JButton(GetText.tr("Add Mod"));
        addButton.addActionListener(e -> {
            String[] modTypes;

            if (instanceOrServer instanceof Instance) {
                modTypes = new String[] { "Mods Folder", "Data Pack", "Resource Pack", "Shader Pack",
                    "Inside Minecraft.jar" };
            } else if (instanceOrServer.getLoaderVersion() != null && (instanceOrServer.getLoaderVersion().isPaper()
                || instanceOrServer.getLoaderVersion().isPurpur())) {
                modTypes = new String[] { "Plugins Folder" };
            } else {
                modTypes = new String[] { "Mods Folder" };
            }

            FileChooserDialog fcd = new FileChooserDialog(this, GetText.tr("Add Mod"), GetText.tr("Mod"),
                GetText.tr("Add"), GetText.tr("Type of Mod"), modTypes);
            fcd.setVisible(true);

            if (fcd.wasClosed()) {
                return;
            }

            final ProgressDialog<Object> progressDialog = new ProgressDialog<>(GetText.tr("Copying Mods"), 0,
                GetText.tr("Copying Mods"), this);

            progressDialog.addThread(new Thread(() -> {
                List<File> files = fcd.getChosenFiles();
                if (files != null && !files.isEmpty()) {
                    boolean reload = false;
                    for (File file : files) {
                        String typeTemp = fcd.getSelectorValue();
                        com.atlauncher.data.Type type = null;
                        if (typeTemp.equalsIgnoreCase("Mods Folder")) {
                            type = com.atlauncher.data.Type.mods;
                        } else if (typeTemp.equalsIgnoreCase("Inside Minecraft.jar")) {
                            int ret = DialogManager.yesNoDialog().setTitle(GetText.tr("Add As Mod?"))
                                .setContent(new HTMLBuilder().text(GetText.tr(
                                        "Adding as Inside Minecraft.jar is usually not what you want and will likely cause issues.<br/><br/>If you're adding mods this is usually not correct. Do you want to add this as a mod instead?"))
                                    .build())
                                .setType(DialogManager.WARNING).show();

                            if (ret != 0) {
                                type = com.atlauncher.data.Type.jar;
                            } else {
                                type = com.atlauncher.data.Type.mods;
                            }
                        } else if (typeTemp.equalsIgnoreCase("CoreMods Mod")) {
                            type = com.atlauncher.data.Type.coremods;
                        } else if (typeTemp.equalsIgnoreCase("Texture Pack")) {
                            type = com.atlauncher.data.Type.texturepack;
                        } else if (typeTemp.equalsIgnoreCase("Data Pack")) {
                            type = com.atlauncher.data.Type.datapack;
                        } else if (typeTemp.equalsIgnoreCase("Resource Pack")) {
                            type = com.atlauncher.data.Type.resourcepack;
                        } else if (typeTemp.equalsIgnoreCase("Shader Pack")) {
                            type = com.atlauncher.data.Type.shaderpack;
                        } else if (typeTemp.equalsIgnoreCase("Plugins Folder")) {
                            type = com.atlauncher.data.Type.plugins;
                        }
                        if (type != null) {
                            DisableableMod mod = DisableableMod.generateMod(file, type,
                                App.settings.enableAddedModsByDefault);
                            File copyTo = App.settings.enableAddedModsByDefault ? mod.getFile(instanceOrServer)
                                : mod.getDisabledFile(instanceOrServer);

                            if (copyTo.exists()) {
                                LogManager.warn("The file " + file.getName() + " already exists. Not adding!");
                                continue;
                            }

                            if (!copyTo.getParentFile().exists()) {
                                copyTo.getParentFile().mkdirs();
                            }

                            if (Utils.copyFile(file, copyTo, true)) {
                                instanceOrServer.addMod(mod);
                                reload = true;
                            }
                        }
                    }
                    if (reload) {
                        SwingUtilities.invokeLater(this::reloadPanels);
                    }
                }
                progressDialog.close();
            }));

            progressDialog.start();
        });
        bottomPanel.add(addButton);

        if (canCheckForUpdates()) {
            if (ConfigManager.getConfigItem("platforms.curseforge.modsEnabled", true)
                || (ConfigManager.getConfigItem("platforms.modrinth.modsEnabled", true)
                && (instanceOrServer.getLoaderVersion() != null
                || instanceOrServer instanceof Instance))) {
                JButton browseMods = new JButton(GetText.tr("Browse Mods"));
                browseMods.addActionListener(e -> {
                    AddModsDialog addModsDialog = new AddModsDialog(this, instanceOrServer);
                    addModsDialog.setVisible(true);

                    loadMods();

                    reloadPanels();
                });
                bottomPanel.add(browseMods);
            }

            checkForUpdatesButton = new JButton(GetText.tr("Check For Updates"));
            checkForUpdatesButton.addActionListener(e -> checkForUpdates());
            checkForUpdatesButton.setEnabled(false);
            bottomPanel.add(checkForUpdatesButton);

            reinstallButton = new JButton(GetText.tr("Reinstall"));
            reinstallButton.addActionListener(e -> reinstall());
            reinstallButton.setEnabled(false);
            bottomPanel.add(reinstallButton);
        }

        enableButton = new JButton(GetText.tr("Enable Selected"));
        enableButton.addActionListener(e -> enableMods());
        enableButton.setEnabled(false);
        bottomPanel.add(enableButton);

        disableButton = new JButton(GetText.tr("Disable Selected"));
        disableButton.addActionListener(e -> disableMods());
        disableButton.setEnabled(false);
        bottomPanel.add(disableButton);

        removeButton = new JButton(GetText.tr("Remove Selected"));
        removeButton.addActionListener(e -> removeMods());
        removeButton.setEnabled(false);
        bottomPanel.add(removeButton);

        refreshMetadataButton = new JButton(GetText.tr("Refresh Metadata"));
        refreshMetadataButton.addActionListener(e -> refreshMetadata());
        refreshMetadataButton.setEnabled(false);
        bottomPanel.add(refreshMetadataButton);
    }

    private void loadMods() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::loadMods);
            return;
        }

        List<DisableableMod> mods = instanceOrServer.getMods().stream().filter(DisableableMod::wasSelected)
            .filter(m -> !m.skipped && m.type != com.atlauncher.data.Type.worlds).collect(Collectors.toList());
        enabledMods = new ArrayList<>();
        disabledMods = new ArrayList<>();
        boolean canCheckForUpdates = canCheckForUpdates();
        for (DisableableMod mod : mods) {
            ModsJCheckBox checkBox = new ModsJCheckBox(mod, this);
            if (canCheckForUpdates) {
                checkBox.showUpdateSlot();

                DisableableMod.UpdateStatus status = updateStatuses.get(mod);
                if (status != null) {
                    checkBox.setUpdateStatus(status);
                }
            }

            (mod.isDisabled() ? disabledMods : enabledMods).add(checkBox);
        }

        rows().forEach(checkBox -> checkBox.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED || e.getStateChange() == ItemEvent.DESELECTED) {
                checkBoxesChanged();
            }
        }));

        if (canCheckForUpdates()) {
            queueUpdateChecks();
        }

        // puts the rows in their panels in the chosen order
        applySort();

        // keeps the current search across refreshes and sets the list heights
        applyFilter();
    }

    private Stream<ModsJCheckBox> rows() {
        return Stream.concat(enabledMods.stream(), disabledMods.stream());
    }

    /**
     * The rows a row's right-click menu acts on: every selected row in both lists when the clicked row is selected,
     * else just that row.
     */
    public List<ModsJCheckBox> rowsForMenu(ModsJCheckBox clicked) {
        if (!clicked.isSelected()) {
            return Collections.singletonList(clicked);
        }

        return rows().filter(AbstractButton::isSelected).collect(Collectors.toList());
    }

    /**
     * Orders the rows and refills the panels with them. The rows themselves are kept, so a re-sort doesn't lose the
     * selection.
     */
    private void applySort() {
        if (enabledMods == null || disabledMods == null) {
            return;
        }

        SortOrder order = (SortOrder) sortBox.getSelectedItem();
        Map<DisableableMod, Long> addedTimes = new HashMap<>();
        if (order == SortOrder.DATE_ADDED) {
            rows().forEach(row -> addedTimes.put(row.getDisableableMod(), addedTime(row.getDisableableMod())));
        }

        Comparator<ModsJCheckBox> rowOrder = Comparator.comparing(ModsJCheckBox::getDisableableMod,
            sortComparator(order, addedTimes, updateStatuses));
        enabledMods.sort(rowOrder);
        disabledMods.sort(rowOrder);

        refill(enabledModsPanel, enabledMods);
        refill(disabledModsPanel, disabledMods);
    }

    private static void refill(JPanel panel, List<ModsJCheckBox> rows) {
        panel.removeAll();
        rows.forEach(panel::add);
        panel.revalidate();
        panel.repaint();
    }

    /**
     * Name A-Z; Date Added newest first; Update Available puts updates first, then mods still being checked. Ties go
     * by name.
     */
    static Comparator<DisableableMod> sortComparator(SortOrder order, Map<DisableableMod, Long> addedTimes,
        Map<DisableableMod, DisableableMod.UpdateStatus> updateStatuses) {
        Comparator<DisableableMod> byName = Comparator.comparing(m -> m.name, String.CASE_INSENSITIVE_ORDER);

        if (order == SortOrder.DATE_ADDED) {
            return Comparator.<DisableableMod>comparingLong(m -> addedTimes.getOrDefault(m, 0L)).reversed()
                .thenComparing(byName);
        }

        if (order == SortOrder.UPDATE_AVAILABLE) {
            return Comparator.<DisableableMod>comparingInt(m -> updateRank(updateStatuses.get(m)))
                .thenComparing(byName);
        }

        return byName;
    }

    private static int updateRank(DisableableMod.UpdateStatus status) {
        if (status == DisableableMod.UpdateStatus.AVAILABLE) {
            return 0;
        }

        return status == DisableableMod.UpdateStatus.CHECKING ? 1 : 2;
    }

    /**
     * When the mod's file arrived in the folder: its creation time where the file system keeps one (Windows, macOS),
     * else its last modified time. Enabling or disabling renames the file, which keeps it; updating replaces the file,
     * so an updated mod counts as newly added.
     */
    private long addedTime(DisableableMod mod) {
        File file = mod.isDisabled() ? mod.getDisabledFile(instanceOrServer) : mod.getFile(instanceOrServer);
        if (file == null) {
            return 0;
        }

        try {
            return Files.readAttributes(file.toPath(), BasicFileAttributes.class).creationTime().toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Checks every CurseForge/Modrinth mod not checked yet, in the background, for the update icon: a few batch
     * requests settle most of them, then the rest get the full per-mod check, a few at a time. A mod updated since is
     * a new object, so it gets checked again.
     */
    private void queueUpdateChecks() {
        List<DisableableMod> unchecked = rows().map(ModsJCheckBox::getDisableableMod)
            .filter(mod -> mod.isUpdatable() && !updateStatuses.containsKey(mod)).collect(Collectors.toList());
        if (unchecked.isEmpty()) {
            showUpdateCheckProgress();
            return;
        }

        if (updateChecker == null) {
            // ponytail: 4 threads for the per-mod leftovers, usually only mods with an update; reopening the dialog
            // checks again. Share results between dialogs if that ever trips rate limits.
            updateChecker = Executors.newFixedThreadPool(4, r -> {
                Thread thread = new Thread(r, "Edit Mods update checks");
                thread.setDaemon(true);
                return thread;
            });
        }

        unchecked.forEach(mod -> updateStatuses.put(mod, DisableableMod.UpdateStatus.CHECKING));
        showUpdateCheckProgress();

        updateChecker.execute(() -> {
            Map<DisableableMod, DisableableMod.UpdateStatus> settled;
            try {
                settled = BulkUpdateCheck.settle(instanceOrServer, unchecked);
            } catch (RuntimeException e) {
                LogManager.logStackTrace("Error checking mods for updates in bulk", e, false);
                settled = Collections.emptyMap();
            }

            updateStatuses.putAll(settled);
            Set<DisableableMod> settledMods = settled.keySet();
            SwingUtilities.invokeLater(() -> updateChecked(settledMods));

            for (DisableableMod mod : unchecked) {
                if (settledMods.contains(mod)) {
                    continue;
                }

                try {
                    updateChecker.execute(() -> {
                        DisableableMod.UpdateStatus status;
                        try {
                            status = mod.fetchUpdateStatus(instanceOrServer);
                        } catch (RuntimeException e) {
                            LogManager.logStackTrace("Error checking " + mod.name + " for updates", e, false);
                            status = DisableableMod.UpdateStatus.UNKNOWN;
                        }

                        updateStatuses.put(mod, status);
                        SwingUtilities.invokeLater(() -> updateChecked(Collections.singleton(mod)));
                    });
                } catch (RejectedExecutionException e) {
                    return; // the dialog closed
                }
            }
        });
    }

    private void updateChecked(Collection<DisableableMod> mods) {
        if (enabledMods == null || disabledMods == null) {
            return;
        }

        rows().filter(row -> mods.contains(row.getDisableableMod()))
            .forEach(row -> row.setUpdateStatus(updateStatuses.get(row.getDisableableMod())));

        // re-sort once, when the last result is in, rather than moving rows around while they're being picked
        if (showUpdateCheckProgress() == 0 && (SortOrder) sortBox.getSelectedItem() == SortOrder.UPDATE_AVAILABLE) {
            applySort();
        }
    }

    // returns how many shown mods are still being checked
    private long showUpdateCheckProgress() {
        long checking = rows().filter(
            row -> updateStatuses.get(row.getDisableableMod()) == DisableableMod.UpdateStatus.CHECKING).count();
        long available = rows().filter(
            row -> updateStatuses.get(row.getDisableableMod()) == DisableableMod.UpdateStatus.AVAILABLE).count();

        if (checking > 0) {
            updateCheckLabel.setText(GetText.tr("Checking for updates: {0} left", checking));
        } else if (available > 0) {
            updateCheckLabel.setText(GetText.tr("Updates available: {0}", available));
        } else {
            updateCheckLabel.setText("");
        }

        return checking;
    }

    @Override
    public void dispose() {
        if (updateChecker != null) {
            updateChecker.shutdownNow();
        }

        super.dispose();
    }

    /**
     * Hides rows that don't match the search. Hidden rows are deselected so bulk actions (enable, disable, remove,
     * update, reinstall, refresh metadata) can never act on mods the user can't see.
     */
    private void applyFilter() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::applyFilter);
            return;
        }

        if (enabledMods == null || disabledMods == null) {
            return;
        }

        String query = searchField.getText();
        filterRows(enabledMods, enabledModsPanel, query);
        filterRows(disabledMods, disabledModsPanel, query);
        checkBoxesChanged();
    }

    private static void filterRows(List<ModsJCheckBox> rows, JPanel panel, String query) {
        int height = 0;
        for (ModsJCheckBox row : rows) {
            boolean visible = matchesSearch(row.getDisableableMod(), row.getText(), query);
            if (!visible && row.isSelected()) {
                row.setSelected(false);
            }
            row.setVisible(visible);
            if (visible) {
                height += row.getPreferredSize().height;
            }
        }

        panel.setPreferredSize(new Dimension(0, height));
        panel.revalidate();
        panel.repaint();
    }

    /**
     * Case-insensitive match against the name, row label (e.g. "[Plugin] " prefix), filename, CurseForge project
     * id/slug/authors and Modrinth project id/slug. Any of these may be missing. Modrinth only stores a team id, so
     * Modrinth authors aren't searchable.
     */
    static boolean matchesSearch(DisableableMod mod, String displayText, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return true;
        }

        List<String> fields = new ArrayList<>();
        fields.add(mod.getName());
        fields.add(displayText);
        fields.add(mod.getFilename());

        if (mod.curseForgeProjectId != null) {
            fields.add(String.valueOf(mod.curseForgeProjectId));
        }
        if (mod.curseForgeProject != null) {
            fields.add(String.valueOf(mod.curseForgeProject.id));
            fields.add(mod.curseForgeProject.slug);
            if (mod.curseForgeProject.authors != null) {
                for (CurseForgeAuthor author : mod.curseForgeProject.authors) {
                    if (author != null) {
                        fields.add(author.name);
                    }
                }
            }
        }
        if (mod.modrinthProject != null) {
            fields.add(mod.modrinthProject.id);
            fields.add(mod.modrinthProject.slug);
        }

        for (String field : fields) {
            if (field != null && field.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    // the Check For Updates and Reinstall buttons, and the update icons, need CurseForge/Modrinth integration
    private boolean canCheckForUpdates() {
        return instanceOrServer instanceof Server || (instanceOrServer instanceof Instance
            && ((Instance) instanceOrServer).launcher.enableCurseForgeIntegration);
    }

    private void checkBoxesChanged() {
        if (canCheckForUpdates()) {
            boolean hasSelectedACurseForgeOrModrinthMod = (enabledMods.stream().anyMatch(AbstractButton::isSelected)
                && enabledMods.stream().filter(AbstractButton::isSelected)
                .anyMatch(cb -> cb.getDisableableMod().isUpdatable()))
                || (disabledMods.stream().anyMatch(AbstractButton::isSelected) && disabledMods.stream()
                .filter(AbstractButton::isSelected).anyMatch(cb -> cb.getDisableableMod().isUpdatable()));

            checkForUpdatesButton.setEnabled(hasSelectedACurseForgeOrModrinthMod);
            reinstallButton.setEnabled(hasSelectedACurseForgeOrModrinthMod);
        }

        removeButton.setEnabled((!disabledMods.isEmpty() && disabledMods.stream().anyMatch(AbstractButton::isSelected))
            || (!enabledMods.isEmpty() && enabledMods.stream().anyMatch(AbstractButton::isSelected)));
        enableButton.setEnabled(!disabledMods.isEmpty() && disabledMods.stream().anyMatch(AbstractButton::isSelected));
        disableButton.setEnabled(!enabledMods.isEmpty() && enabledMods.stream().anyMatch(AbstractButton::isSelected));
        refreshMetadataButton
            .setEnabled(!enabledMods.isEmpty() && enabledMods.stream().anyMatch(AbstractButton::isSelected));

        updateSelectAll(selectAllEnabledModsCheckbox, enabledMods);
        updateSelectAll(selectAllDisabledModsCheckbox, disabledMods);
    }

    private static void updateSelectAll(JCheckBox selectAll, List<ModsJCheckBox> rows) {
        List<ModsJCheckBox> visible = rows.stream().filter(Component::isVisible).collect(Collectors.toList());

        selectAll.setEnabled(!visible.isEmpty());
        selectAll.setSelected(!visible.isEmpty() && visible.stream().allMatch(AbstractButton::isSelected));
    }

    private void checkForUpdates() {
        ArrayList<ModsJCheckBox> mods = new ArrayList<>();
        mods.addAll(enabledMods);
        mods.addAll(disabledMods);

        ProgressDialog<Void> progressDialog = new ProgressDialog<>(GetText.tr("Checking For Updates"), mods.size(),
            GetText.tr("Checking For Updates"), this);
        progressDialog.addThread(new Thread(() -> {
            for (ModsJCheckBox mod : mods) {
                if (mod.isSelected() && mod.getDisableableMod().isUpdatable()) {
                    mod.getDisableableMod().checkForUpdate(progressDialog, instanceOrServer);
                }
                progressDialog.doneTask();
            }

            progressDialog.close();
        }));
        progressDialog.start();

        DialogManager.okDialog().setTitle(GetText.tr("Checking For Updates Complete"))
            .setContent(GetText.tr("The selected mods have been checked for updates.")).show();

        reloadPanels();
    }

    private void reinstall() {
        ArrayList<ModsJCheckBox> mods = new ArrayList<>();
        mods.addAll(enabledMods);
        mods.addAll(disabledMods);

        for (ModsJCheckBox mod : mods) {
            if (mod.isSelected() && mod.getDisableableMod().isUpdatable()) {
                mod.getDisableableMod().reinstall(this, instanceOrServer);
            }
        }
        reloadPanels();
    }

    private void enableMods() {
        ArrayList<ModsJCheckBox> mods = new ArrayList<>(disabledMods);
        for (ModsJCheckBox mod : mods) {
            if (mod.isSelected()) {
                mod.getDisableableMod().enable(instanceOrServer);
            }
        }
        reloadPanels();
    }

    private void disableMods() {
        ArrayList<ModsJCheckBox> mods = new ArrayList<>(enabledMods);
        for (ModsJCheckBox mod : mods) {
            if (mod.isSelected()) {
                mod.getDisableableMod().disable(instanceOrServer);
            }
        }
        reloadPanels();
    }

    private void removeMods() {
        removeMods(rows().filter(AbstractButton::isSelected).map(ModsJCheckBox::getDisableableMod)
            .collect(Collectors.toList()), true);
    }

    /**
     * Removes mods. When other installed mods need them, or they leave dependencies nothing else needs, it first asks
     * what to do (see {@link ModRemovalDialog}); otherwise it asks only when {@code confirm} is set, as the row menu's
     * Remove never asked.
     */
    public void removeMods(List<DisableableMod> selected, boolean confirm) {
        InstalledDependencies deps = InstalledDependencies.of(instanceOrServer);
        List<DisableableMod> affected = deps.brokenByAll(selected);
        List<DisableableMod> withoutData = deps.withoutData(selected);
        InstalledDependencies.Plan plan;

        if (!affected.isEmpty() || !deps.unneededAfter(selected, selected).isEmpty()) {
            plan = ModRemovalDialog.ask(this, deps, selected, affected, withoutData);
            if (plan == null) {
                return;
            }
        } else {
            if (confirm) {
                String notes = ModRemovalDialog.dataNotes(withoutData, selected.stream()
                    .filter(m -> !InstalledDependencies.hasData(m)).collect(Collectors.toList()));
                int ret = DialogManager.yesNoDialog(false)
                    .setTitle(GetText.tr("Delete Selected Mods?"))
                    .setContent(new HTMLBuilder().center().text(GetText.tr(
                            "This will delete the selected mods from the instance.<br/><br/>Are you sure you want to do this?")
                            + (notes == null ? "" : "<br/><br/>" + escape(notes).replace("\n", "<br/>")))
                        .split(100).build())
                    .setType(DialogManager.WARNING).show();
                if (ret != 0) {
                    return;
                }
            }
            plan = deps.plan(selected, InstalledDependencies.DependentsChoice.REMOVE_ANYWAY,
                Collections.emptyList());
        }

        plan.disable.forEach(mod -> mod.disable(instanceOrServer));
        for (DisableableMod mod : plan.remove) {
            instanceOrServer.getMods().remove(mod);
            FileUtils.delete((mod.isDisabled() ? mod.getDisabledFile(instanceOrServer)
                : mod.getFile(instanceOrServer)).toPath(), true);
        }
        reloadPanels();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void refreshMetadata() {
        final ProgressDialog<Boolean> dialog = new ProgressDialog<>(GetText.tr("Refreshing Metadata"), 0,
            GetText.tr("Refreshing Metadata"),
            "Aborting refreshing metadata");
        dialog.addThread(new Thread(() -> {

            List<ModsJCheckBox> modsToRefresh = new ArrayList<>();
            modsToRefresh
                .addAll(enabledMods.parallelStream().filter(ModsJCheckBox::isSelected)
                    .collect(Collectors.toList()));
            modsToRefresh
                .addAll(disabledMods.parallelStream().filter(ModsJCheckBox::isSelected)
                    .collect(Collectors.toList()));

            // TODO: Generalise this, cause fuck me I've copy pasted this like 10 times now
            if (!App.settings.dontCheckModsOnCurseForge) {
                Map<Long, ModsJCheckBox> murmurHashes = new HashMap<>();

                modsToRefresh.stream()
                    .filter(mjc -> mjc.getDisableableMod().getFile(instanceOrServer.getRoot(),
                        instanceOrServer.getMinecraftVersion()) != null)
                    .forEach(mjc -> {
                        try {
                            long hash = Hashing
                                .murmur(mjc.getDisableableMod().getFile(instanceOrServer.getRoot(),
                                    instanceOrServer.getMinecraftVersion()).toPath());
                            murmurHashes.put(hash, mjc);
                        } catch (IOException t) {
                            LogManager.logStackTrace(t);
                        }
                    });

                if (!murmurHashes.isEmpty()) {
                    CurseForgeFingerprint fingerprintResponse = CurseForgeApi
                        .checkFingerprints(murmurHashes.keySet().stream().toArray(Long[]::new));

                    if (fingerprintResponse != null && fingerprintResponse.exactMatches != null) {
                        int[] projectIdsFound = fingerprintResponse.exactMatches.stream().mapToInt(em -> em.id)
                            .toArray();

                        if (projectIdsFound.length != 0) {
                            Map<Integer, CurseForgeProject> foundProjects = CurseForgeApi
                                .getProjectsAsMap(projectIdsFound);

                            if (foundProjects != null) {
                                fingerprintResponse.exactMatches.stream().filter(em -> em != null && em.file != null
                                    && murmurHashes.containsKey(em.file.packageFingerprint)).forEach(foundMod -> {
                                    DisableableMod dm = murmurHashes.get(foundMod.file.packageFingerprint)
                                        .getDisableableMod();

                                    CurseForgeProject curseForgeProject = foundProjects.get(foundMod.id);

                                    if (curseForgeProject != null && curseForgeProject.status == 4) {
                                        dm.curseForgeProjectId = foundMod.id;
                                        dm.curseForgeFile = foundMod.file;
                                        dm.curseForgeFileId = foundMod.file.id;
                                        dm.curseForgeProject = curseForgeProject;
                                        dm.name = curseForgeProject.name;
                                        dm.description = curseForgeProject.summary;

                                        LogManager.debug("Found matching mod from CurseForge called "
                                            + dm.curseForgeFile.displayName);
                                    }

                                    // reset if the file is not approved
                                    if (curseForgeProject != null && curseForgeProject.status != 4) {
                                        dm.curseForgeProjectId = null;
                                        dm.curseForgeFile = null;
                                        dm.curseForgeFileId = null;
                                        dm.curseForgeProject = null;

                                        File path = dm.getFile(instanceOrServer);
                                        MCMod mcMod = Utils.getMCModForFile(path);
                                        if (mcMod != null) {
                                            dm.name = Optional.ofNullable(mcMod.name)
                                                .orElse(path.getName());
                                            dm.description = mcMod.description;
                                        } else {
                                            FabricMod fabricMod = Utils.getFabricModForFile(path);
                                            if (fabricMod != null) {
                                                dm.name = Optional.ofNullable(fabricMod.name)
                                                    .orElse(path.getName());
                                                dm.description = fabricMod.description;
                                            }
                                        }
                                    }
                                });
                            }
                        }
                    }
                }
            }

            if (!App.settings.dontCheckModsOnModrinth) {
                Map<String, ModsJCheckBox> sha1Hashes = new HashMap<>();

                modsToRefresh.stream()
                    .filter(mjc -> mjc.getDisableableMod().getFile(instanceOrServer.getRoot(),
                        instanceOrServer.getMinecraftVersion()) != null)
                    .forEach(mjc -> {
                        try {
                            sha1Hashes.put(
                                Hashing.sha1(
                                        mjc.getDisableableMod()
                                            .getFile(instanceOrServer.getRoot(),
                                                instanceOrServer.getMinecraftVersion())
                                            .toPath())
                                    .toString(),
                                mjc);
                        } catch (Throwable t) {
                            LogManager.logStackTrace(t);
                        }
                    });

                if (!sha1Hashes.isEmpty()) {
                    Set<String> keys = sha1Hashes.keySet();
                    Map<String, ModrinthVersion> modrinthVersions = ModrinthApi
                        .getVersionsFromSha1Hashes(keys.toArray(new String[0]));

                    if (modrinthVersions != null && !modrinthVersions.isEmpty()) {
                        String[] projectIdsFound = modrinthVersions.values().stream().map(mv -> mv.projectId)
                            .toArray(String[]::new);

                        if (projectIdsFound.length != 0) {
                            Map<String, ModrinthProject> foundProjects = ModrinthApi.getProjectsAsMap(projectIdsFound);

                            if (foundProjects != null) {
                                for (Map.Entry<String, ModrinthVersion> entry : modrinthVersions.entrySet()) {
                                    ModrinthVersion version = entry.getValue();
                                    ModrinthProject project = foundProjects.get(version.projectId);

                                    if (project != null) {
                                        DisableableMod dm = sha1Hashes.get(entry.getKey()).getDisableableMod();

                                        // add Modrinth information
                                        dm.modrinthProject = project;
                                        dm.modrinthVersion = version;
                                        dm.name = project.title;
                                        dm.description = project.description;

                                        LogManager
                                            .debug(String.format(
                                                "Found matching mod from Modrinth called %s with file %s",
                                                project.title, version.name));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            instanceOrServer.save();

            dialog.close();
        }));
        dialog.start();

        reloadPanels();
    }

    // picks up mod files added or removed outside the launcher; mods already checked keep their update status
    private void refresh() {
        instanceOrServer.scanMissingMods(this);
        reloadPanels();
    }

    public void reloadPanels() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::reloadPanels);
            return;
        }

        instanceOrServer.save();

        enabledModsPanel.removeAll();
        disabledModsPanel.removeAll();
        loadMods();
        checkBoxesChanged();
        enabledModsPanel.revalidate();
        enabledModsPanel.repaint();
        disabledModsPanel.revalidate();
        disabledModsPanel.repaint();
    }

}