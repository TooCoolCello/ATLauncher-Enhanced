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

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.FileSystem;
import com.atlauncher.Gsons;
import com.atlauncher.constants.Constants;
import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.Instance;
import com.atlauncher.data.ModCompatibility;
import com.atlauncher.data.ModCompatibilityChecker;
import com.atlauncher.data.ModManagement;
import com.atlauncher.data.ModPlatform;
import com.atlauncher.data.ModSide;
import com.atlauncher.data.PackMods;
import com.atlauncher.data.PackMods.Row;
import com.atlauncher.data.PackMods.Status;
import com.atlauncher.data.Server;
import com.atlauncher.data.curseforge.CurseForgeCoreApiResponse;
import com.atlauncher.data.curseforge.CurseForgeFile;
import com.atlauncher.data.curseforge.CurseForgeFileHash;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.curseforge.pack.CurseForgeManifest;
import com.atlauncher.data.modrinth.ModrinthFile;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthProjectType;
import com.atlauncher.data.modrinth.ModrinthSearchHit;
import com.atlauncher.data.modrinth.ModrinthSearchResult;
import com.atlauncher.data.modrinth.ModrinthVersion;
import com.atlauncher.data.modrinth.pack.ModrinthModpackManifest;
import com.atlauncher.gui.card.CompatibilityBadge;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.managers.InstanceManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.network.Download;
import com.atlauncher.utils.ArchiveUtils;
import com.atlauncher.utils.ComboItem;
import com.atlauncher.utils.CurseForgeApi;
import com.atlauncher.utils.FileUtils;
import com.atlauncher.utils.Hashing;
import com.atlauncher.utils.ModrinthApi;
import com.atlauncher.utils.ModrinthCollections;
import com.formdev.flatlaf.icons.FlatSearchIcon;

/**
 * Adds mods from someone else's modpack, or another instance, to this one: pick a pack (a Modrinth or CurseForge
 * search, a link, or an instance), see each of its mods checked against this instance, tick those wanted and add them
 * in one go, each with the best file for here, through the same install path as the selectors. Required
 * dependencies the pack has are added with them; those it doesn't are named afterwards.
 */
public final class PackModsDialog extends JDialog {
    private enum Source {
        MODRINTH, CURSEFORGE, INSTANCES
    }

    private static final String LIST = "list";
    private static final String MESSAGE = "message";
    // only the pack's mod list is read, so a big download is checked with the user first
    private static final long LARGE_PACK_BYTES = 50L * 1024 * 1024;
    private static final int SEARCH_LIMIT = 50;

    /**
     * A pack to read: a Modrinth or CurseForge modpack, or one of the user's instances.
     */
    static final class Pack {
        final ModrinthSearchHit modrinth;
        final CurseForgeProject curseForge;
        final Instance instance;
        // a version or file named by a link, chosen when it's listed
        final String preferredVersion;

        Pack(ModrinthSearchHit modrinth, CurseForgeProject curseForge, Instance instance, String preferredVersion) {
            this.modrinth = modrinth;
            this.curseForge = curseForge;
            this.instance = instance;
            this.preferredVersion = preferredVersion;
        }

        String name() {
            return modrinth != null ? modrinth.title : curseForge != null ? curseForge.name : instance.getName();
        }

        String detail() {
            if (instance != null) {
                return GetText.tr("Instance: Minecraft {0} {1}", instance.getMinecraftVersion(),
                        instance.getLoaderVersion() == null ? "" : instance.getLoaderVersion().getLoaderType());
            }
            String author = modrinth != null ? modrinth.author
                    : curseForge.authors == null || curseForge.authors.isEmpty() ? null
                            : curseForge.authors.get(0).name;
            String site = modrinth != null ? "Modrinth" : "CurseForge";
            // #. {0} is the site, {1} the pack's author
            return author == null ? site : GetText.tr("{0}, by {1}", site, author);
        }
    }

    private final AddModsDialog addMods;
    private final ModManagement instanceOrServer;
    private final ModCompatibility.Target target;
    private final boolean server;
    private PackMods.Installed installed;

    private final JComboBox<ComboItem<Source>> sourceBox = new JComboBox<>();
    private final JTextField searchField = new JTextField(14);
    private final JButton searchButton = new JButton(GetText.tr("Search"));
    private final JCheckBox sameVersion;
    private final JTextField linkField = new JTextField(14);
    private final JButton openLink = new JButton(GetText.tr("Open"));
    private final DefaultListModel<Pack> packModel = new DefaultListModel<>();
    private final JList<Pack> packList = new JList<>(packModel);
    private final JLabel packMessage = new JLabel("", SwingConstants.CENTER);
    private final CardLayout packCards = new CardLayout();
    private final JPanel packArea = new JPanel(packCards);

    private final JLabel title = new JLabel(GetText.tr("No pack chosen"));
    private final JLabel versionLabel = new JLabel(GetText.tr("Version:"));
    private final JComboBox<ComboItem<Object>> versionBox = new JComboBox<>();
    private final JTextField filterField = new JTextField(14);
    private final JButton selectAll = new JButton(GetText.tr("Select All"));
    private final JButton selectCompatible = new JButton(GetText.tr("Select Compatible"));
    private final JButton selectNone = new JButton(GetText.tr("Select None"));
    private final RowsModel model = new RowsModel();
    private final JTable table = new JTable(model);
    private final JLabel summary = new JLabel();
    private final JLabel modsMessage = new JLabel("", SwingConstants.CENTER);
    private final CardLayout modsCards = new CardLayout();
    private final JPanel modsArea = new JPanel(modsCards);
    private final JButton addSelected = new JButton();

    private List<Row> rows = new ArrayList<>();
    // ticked rows, by key, kept while the filter changes
    private final Set<String> selected = new HashSet<>();
    // only the latest search and load are shown
    private int searchGeneration = 0;
    private int loadGeneration = 0;
    private boolean fillingVersions = false;

    public PackModsDialog(AddModsDialog addMods, ModManagement instanceOrServer) {
        // #. {0} is the name of the instance or server mods are added to
        super(addMods, GetText.tr("Add Mods From A Modpack To {0}", instanceOrServer.getName()),
                ModalityType.DOCUMENT_MODAL);
        this.addMods = addMods;
        this.instanceOrServer = instanceOrServer;
        this.target = ModCompatibility.Target.of(instanceOrServer);
        this.server = instanceOrServer instanceof Server;
        this.installed = PackMods.Installed.of(new ArrayList<>(instanceOrServer.getMods()));
        // #. {0} is a Minecraft version
        this.sameVersion = new JCheckBox(GetText.tr("Minecraft {0} only", instanceOrServer.getMinecraftVersion()),
                true);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(820, 480));
        setSize(1080, 680);
        setLocationRelativeTo(addMods);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sourcePanel(), modsPanel());
        split.setDividerLocation(300);
        split.setBorder(BorderFactory.createEmptyBorder());

        addSelected.addActionListener(e -> addSelected());
        JButton close = new JButton(GetText.tr("Close"));
        close.addActionListener(e -> dispose());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        bottom.add(addSelected);
        bottom.add(close);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(split, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);

        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        showModsMessage(GetText.tr("Choose a modpack, or one of your instances, to see which of its mods can be "
                + "added here."));
        updateButtons();
        search();
    }

    // --- choosing a pack

    private JPanel sourcePanel() {
        sourceBox.addItem(new ComboItem<>(Source.MODRINTH, GetText.tr("Modrinth modpacks")));
        sourceBox.addItem(new ComboItem<>(Source.CURSEFORGE, GetText.tr("CurseForge modpacks")));
        sourceBox.addItem(new ComboItem<>(Source.INSTANCES, GetText.tr("My instances")));
        sourceBox.setSelectedIndex(App.settings.defaultModPlatform == ModPlatform.CURSEFORGE ? 1 : 0);
        sourceBox.addActionListener(e -> search());
        sourceBox.getAccessibleContext().setAccessibleName(GetText.tr("Where to find packs"));

        searchField.putClientProperty("JTextField.placeholderText", GetText.tr("Search"));
        searchField.putClientProperty("JTextField.leadingIcon", new FlatSearchIcon());
        searchField.getAccessibleContext().setAccessibleName(GetText.tr("Search packs"));
        searchField.addActionListener(e -> search());
        searchButton.addActionListener(e -> search());
        sameVersion.setToolTipText(GetText.tr("Only packs for this instance's Minecraft version, whose mods are the "
                + "likeliest to have files for it"));
        sameVersion.addActionListener(e -> search());

        linkField.putClientProperty("JTextField.placeholderText", GetText.tr("Modrinth or CurseForge modpack link"));
        linkField.getAccessibleContext().setAccessibleName(GetText.tr("Modrinth or CurseForge modpack link"));
        linkField.addActionListener(e -> openLink());
        openLink.addActionListener(e -> openLink());

        packList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        packList.getAccessibleContext().setAccessibleName(GetText.tr("Packs"));
        packList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                Pack pack = (Pack) value;
                return super.getListCellRendererComponent(list, "<html><b>" + escape(pack.name()) + "</b><br>"
                        + escape(pack.detail()) + "</html>", index, isSelected, cellHasFocus);
            }
        });
        packList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && packList.getSelectedValue() != null) {
                loadPack(packList.getSelectedValue());
            }
        });
        packMessage.setEnabled(false);
        packArea.add(new JScrollPane(packList), LIST);
        packArea.add(packMessage, MESSAGE);

        JPanel searchRow = new JPanel(new BorderLayout(4, 0));
        searchRow.add(searchField, BorderLayout.CENTER);
        searchRow.add(searchButton, BorderLayout.EAST);
        JPanel linkRow = new JPanel(new BorderLayout(4, 0));
        linkRow.add(linkField, BorderLayout.CENTER);
        linkRow.add(openLink, BorderLayout.EAST);

        JPanel top = new JPanel();
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));
        for (JComponent row : new JComponent[] { sourceBox, searchRow, sameVersion, linkRow }) {
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            top.add(row);
            top.add(javax.swing.Box.createVerticalStrut(4));
        }

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        panel.add(top, BorderLayout.NORTH);
        panel.add(packArea, BorderLayout.CENTER);
        panel.setMinimumSize(new Dimension(220, 100));
        return panel;
    }

    private Source source() {
        @SuppressWarnings("unchecked")
        ComboItem<Source> item = (ComboItem<Source>) sourceBox.getSelectedItem();
        return item == null ? Source.MODRINTH : item.getValue();
    }

    private void search() {
        int search = ++searchGeneration;
        String query = searchField.getText().trim();
        String minecraft = sameVersion.isSelected() ? instanceOrServer.getMinecraftVersion() : null;
        Source source = source();

        if (source == Source.INSTANCES) {
            String wanted = query.toLowerCase(Locale.ENGLISH);
            showPacks(search, InstanceManager.getInstances().stream()
                    .filter(i -> i != instanceOrServer && i.getLoaderVersion() != null)
                    .filter(i -> minecraft == null || minecraft.equals(i.getMinecraftVersion()))
                    .filter(i -> i.getName().toLowerCase(Locale.ENGLISH).contains(wanted))
                    .sorted(Comparator.comparing(i -> i.getName().toLowerCase(Locale.ENGLISH)))
                    .map(i -> new Pack(null, null, i, null)).collect(Collectors.toList()),
                    GetText.tr("You have no other instances with a loader that match."));
            return;
        }

        showPackMessage(GetText.tr("Searching..."));
        background("PackModsSearch", () -> {
            List<Pack> found;
            if (source == Source.MODRINTH) {
                ModrinthSearchResult result = paced(() -> ModrinthApi.searchModrinth(
                        minecraft == null ? null : Collections.singletonList(minecraft), query, 0, SEARCH_LIMIT,
                        "relevance", null, ModrinthProjectType.MODPACK));
                found = result == null || result.hits == null ? null
                        : result.hits.stream().filter(Objects::nonNull).map(h -> new Pack(h, null, null, null))
                                .collect(Collectors.toList());
            } else {
                CurseForgeCoreApiResponse<List<CurseForgeProject>> result = CurseForgeApi.searchCurseForge(minecraft,
                        Constants.CURSEFORGE_MODPACKS_SECTION_ID, query, 0, SEARCH_LIMIT, null, "Popularity", true,
                        null);
                found = result == null || result.data == null ? null
                        : result.data.stream().filter(Objects::nonNull).map(p -> new Pack(null, p, null, null))
                                .collect(Collectors.toList());
            }
            SwingUtilities.invokeLater(() -> {
                if (found == null) {
                    if (search == searchGeneration) {
                        showPackMessage(GetText.tr("The search couldn't be done. Try again later."));
                    }
                    return;
                }
                showPacks(search, found, GetText.tr("No modpacks found."));
            });
        });
    }

    private void showPacks(int search, List<Pack> packs, String none) {
        if (search != searchGeneration) {
            return;
        }
        packModel.clear();
        packs.forEach(packModel::addElement);
        if (packs.isEmpty()) {
            showPackMessage(none);
        } else {
            packCards.show(packArea, LIST);
        }
    }

    private void openLink() {
        PackMods.Link link = PackMods.parseLink(linkField.getText());
        if (link == null) {
            DialogManager.okDialog().setParent(this).setTitle(GetText.tr("Add Mods From A Modpack"))
                    .setContent(GetText.tr("That isn't a Modrinth or CurseForge modpack link. Links look like "
                            + "https://modrinth.com/modpack/NAME or https://www.curseforge.com/minecraft/modpacks/NAME."))
                    .setType(DialogManager.ERROR).show();
            return;
        }

        int search = ++searchGeneration;
        showPackMessage(GetText.tr("Loading..."));
        background("PackModsLink", () -> {
            Pack pack = null;
            if (link.platform == ModPlatform.MODRINTH) {
                ModrinthProject project = paced(() -> ModrinthApi.getProject(link.slug));
                if (project != null && project.projectType == ModrinthProjectType.MODPACK) {
                    pack = new Pack(ModrinthCollections.asSearchHit(project), null, null, link.version);
                }
            } else {
                CurseForgeProject project = CurseForgeApi.getModPackBySlug(link.slug);
                if (project != null) {
                    pack = new Pack(null, project, null, link.version);
                }
            }
            Pack found = pack;
            SwingUtilities.invokeLater(() -> {
                if (search != searchGeneration) {
                    return;
                }
                if (found == null) {
                    showPackMessage(GetText.tr("That modpack couldn't be found, or the site couldn't be reached."));
                    return;
                }
                showPacks(search, Collections.singletonList(found), "");
                packList.setSelectedIndex(0);
            });
        });
    }

    private void showPackMessage(String text) {
        packMessage.setText("<html><div style='text-align:center;width:200px'>" + escape(text) + "</div></html>");
        packCards.show(packArea, MESSAGE);
    }

    // --- reading the pack's mods

    private void loadPack(Pack pack) {
        int load = startLoad(pack.name());
        fillingVersions = true;
        versionBox.removeAllItems();
        fillingVersions = false;
        versionBox.setVisible(false);
        versionLabel.setVisible(false);

        if (pack.instance != null) {
            List<DisableableMod> mods = new ArrayList<>(pack.instance.getMods());
            Instance from = pack.instance;
            showModsMessage(GetText.tr("Checking its mods..."));
            background("PackModsInstance", () -> resolveAndShow(load,
                    PackMods.fromInstanceMods(mods, mod -> sha1Of(mod, from))));
            return;
        }

        showModsMessage(GetText.tr("Getting the pack's versions..."));
        background("PackModsVersions", () -> {
            List<ComboItem<Object>> items = new ArrayList<>();
            Object best;
            if (pack.modrinth != null) {
                List<ModrinthVersion> versions = paced(() -> ModrinthApi.getVersions(pack.modrinth.projectId));
                if (versions == null) {
                    versions = Collections.emptyList();
                }
                versions.stream().filter(Objects::nonNull)
                        .forEach(v -> items.add(new ComboItem<>(v, versionName(v.name, v.gameVersions))));
                best = versions.stream().filter(v -> v != null && pack.preferredVersion != null
                        && (pack.preferredVersion.equals(v.id) || pack.preferredVersion.equals(v.versionNumber)))
                        .findFirst().map(v -> (Object) v).orElse(PackMods.bestPackVersion(versions, target));
            } else {
                List<CurseForgeFile> files = PackMods
                        .packFiles(CurseForgeApi.getFilesForProject(pack.curseForge.id));
                files.forEach(f -> items.add(new ComboItem<>(f, versionName(f.getDisplayName(), f.gameVersions))));
                best = files.stream().filter(f -> String.valueOf(f.id).equals(pack.preferredVersion)).findFirst()
                        .map(f -> (Object) f)
                        .orElse(PackMods.bestPackFile(files, instanceOrServer.getMinecraftVersion()));
            }

            SwingUtilities.invokeLater(() -> {
                if (load != loadGeneration) {
                    return;
                }
                if (items.isEmpty()) {
                    showModsMessage(GetText.tr("No versions of this pack could be found, or the site couldn't be "
                            + "reached."));
                    return;
                }
                fillingVersions = true;
                items.forEach(versionBox::addItem);
                items.stream().filter(i -> i.getValue() == best).findFirst().ifPresent(versionBox::setSelectedItem);
                fillingVersions = false;
                versionBox.setVisible(true);
                versionLabel.setVisible(true);
                loadVersion();
            });
        });
    }

    // a version's name with the first few game versions it's for, short enough for the dropdown
    private static String versionName(String name, List<String> gameVersions) {
        if (gameVersions == null || gameVersions.isEmpty()) {
            return name;
        }
        String shown = gameVersions.stream().limit(3).collect(Collectors.joining(", "));
        return name + " (" + shown + (gameVersions.size() > 3 ? ", ..." : "") + ")";
    }

    private void loadVersion() {
        @SuppressWarnings("unchecked")
        ComboItem<Object> item = (ComboItem<Object>) versionBox.getSelectedItem();
        if (item == null) {
            return;
        }
        int load = startLoad(title.getText());

        String url;
        long size;
        String hash;
        String entry;
        if (item.getValue() instanceof ModrinthVersion) {
            ModrinthVersion version = (ModrinthVersion) item.getValue();
            if (version.files == null || version.files.isEmpty()) {
                showModsMessage(GetText.tr("This version has no file to read."));
                return;
            }
            ModrinthFile file = version.getPrimaryFile();
            url = file.url;
            size = file.size == null ? 0 : file.size;
            hash = file.hashes == null ? null : file.hashes.get("sha1");
            entry = "modrinth.index.json";
        } else {
            CurseForgeFile file = (CurseForgeFile) item.getValue();
            if (file.downloadUrl == null) {
                showModsMessage(GetText.tr("This pack's author doesn't let other launchers download it, so its mod "
                        + "list can't be read here. Install it from the Packs tab instead, then choose it under My "
                        + "instances."));
                return;
            }
            url = file.downloadUrl;
            size = file.fileLength;
            hash = file.hashes == null ? null
                    : file.hashes.stream().filter(CurseForgeFileHash::isSha1).map(h -> h.value).findFirst()
                            .orElse(null);
            entry = "manifest.json";
        }

        if (size > LARGE_PACK_BYTES && DialogManager.confirmDialog().setParent(this)
                .setTitle(GetText.tr("Large Pack"))
                // #. {0} is a size in MB
                .setContent(GetText.tr("This pack's file is {0} MB. It's downloaded only to read its mod list, then "
                        + "deleted. Download it?", size / 1024 / 1024))
                .addOption(GetText.tr("Download")).addOption(GetText.tr("Cancel"), true).show() != 0) {
            showModsMessage(GetText.tr("Not downloaded."));
            return;
        }

        showModsMessage(size > 0 ? GetText.tr("Downloading the pack ({0} MB) to read its mod list...",
                Math.max(1, size / 1024 / 1024)) : GetText.tr("Downloading the pack to read its mod list..."));
        background("PackModsRead", () -> {
            List<PackMods.PackFile> files = null;
            try {
                String json = readFromArchive(url, size, hash, entry);
                if (json != null && entry.equals("manifest.json")) {
                    files = PackMods.fromCurseForgeManifest(Gsons.DEFAULT.fromJson(json, CurseForgeManifest.class));
                } else if (json != null) {
                    files = PackMods.fromModrinthIndex(Gsons.DEFAULT.fromJson(json, ModrinthModpackManifest.class));
                }
            } catch (IOException | RuntimeException e) {
                LogManager.logStackTrace("Couldn't read a pack's mod list", e);
            }

            if (files == null) {
                SwingUtilities.invokeLater(() -> {
                    if (load == loadGeneration) {
                        showModsMessage(GetText.tr("The pack's mod list couldn't be read. Try again later."));
                    }
                });
                return;
            }
            resolveAndShow(load, files);
        });
    }

    // downloads the pack's file to the launcher's temp folder, reads one entry and deletes it again
    private static String readFromArchive(String url, long size, String hash, String entry) throws IOException {
        Path path = FileSystem.TEMP.resolve("pack-mods-" + UUID.randomUUID() + ".zip");
        try {
            Download download = new Download().setUrl(url).downloadTo(path);
            if (size > 0) {
                download = download.size(size);
            }
            if (hash != null) {
                download = download.hash(hash);
            }
            download.downloadFile();
            return ArchiveUtils.getFile(path, entry);
        } finally {
            FileUtils.delete(path);
        }
    }

    // a mod's file in another instance, else the hash Modrinth gave when it was added
    private static String sha1Of(DisableableMod mod, Instance instance) {
        File file = mod.isDisabled() ? mod.getDisabledFile(instance) : mod.getFile(instance);
        if (file != null && file.isFile()) {
            return Hashing.sha1(file.toPath()).toString();
        }
        if (mod.modrinthVersion != null && mod.modrinthVersion.files != null && !mod.modrinthVersion.files.isEmpty()
                && mod.modrinthVersion.getPrimaryFile().hashes != null) {
            return mod.modrinthVersion.getPrimaryFile().hashes.get("sha1");
        }
        return null;
    }

    // off the event dispatch thread
    private void resolveAndShow(int load, List<PackMods.PackFile> files) {
        PackMods.Installed now = installed;
        PackMods.Result result = PackMods.resolve(files, target, now, PackMods.SITES);
        SwingUtilities.invokeLater(() -> {
            if (load != loadGeneration) {
                return;
            }
            rows = result.rows;
            selected.clear();
            if (rows.isEmpty()) {
                showModsMessage(result.problem != null ? result.problem
                        : GetText.tr("This pack has no mods that can be added here."));
            } else {
                modsCards.show(modsArea, LIST);
            }
            refilter();
            describe(result);
        });
    }

    private int startLoad(String name) {
        int load = ++loadGeneration;
        title.setText(name);
        rows = new ArrayList<>();
        selected.clear();
        summary.setText("");
        refilter();
        return load;
    }

    // --- the mod list

    private JPanel modsPanel() {
        title.setFont(App.THEME.getBoldFont().deriveFont(14f));
        versionBox.getAccessibleContext().setAccessibleName(GetText.tr("Pack version"));
        // a fixed width, so a long version name can't push the list aside
        versionBox.setPrototypeDisplayValue(new ComboItem<>(null, "Some Pack 1.2.3 (1.20.1, NeoForge, ...)"));
        versionBox.addActionListener(e -> {
            if (!fillingVersions) {
                loadVersion();
            }
        });
        JPanel heading = new JPanel(new BorderLayout(8, 0));
        heading.add(title, BorderLayout.CENTER);
        JPanel version = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        version.add(versionLabel);
        version.add(versionBox);
        heading.add(version, BorderLayout.EAST);

        filterField.putClientProperty("JTextField.placeholderText", GetText.tr("Filter mods"));
        filterField.putClientProperty("JTextField.leadingIcon", new FlatSearchIcon());
        filterField.putClientProperty("JTextField.showClearButton", true);
        filterField.getAccessibleContext().setAccessibleName(GetText.tr("Filter mods"));
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refilter();
            }
        });
        selectAll.setToolTipText(GetText.tr("Tick every shown mod that has a file that might run here"));
        selectAll.addActionListener(e -> select(Row::installable));
        selectCompatible.setToolTipText(server
                ? GetText.tr("Tick the shown mods with a native file for this server, leaving out client-only mods, "
                        + "Connector files and files that don't say their loader")
                : GetText.tr("Tick the shown mods with a native file for this instance, leaving out Connector files "
                        + "and files that don't say their loader"));
        selectCompatible.addActionListener(e -> select(row -> row.compatible(server)));
        selectNone.addActionListener(e -> {
            model.shown.forEach(row -> selected.remove(row.key()));
            model.fireTableDataChanged();
            updateButtons();
        });
        JPanel tools = new JPanel(new BorderLayout(6, 0));
        tools.add(filterField, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        buttons.add(selectAll);
        buttons.add(selectCompatible);
        buttons.add(selectNone);
        tools.add(buttons, BorderLayout.EAST);

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.add(heading, BorderLayout.NORTH);
        top.add(tools, BorderLayout.SOUTH);

        setUpTable();
        modsMessage.setEnabled(false);
        modsArea.add(new JScrollPane(table), LIST);
        modsArea.add(modsMessage, MESSAGE);

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
        panel.add(top, BorderLayout.NORTH);
        panel.add(modsArea, BorderLayout.CENTER);
        panel.add(summary, BorderLayout.SOUTH);
        return panel;
    }

    private void setUpTable() {
        table.setFillsViewportHeight(true);
        table.setRowHeight(Math.max(table.getRowHeight(), 22));
        table.getTableHeader().setReorderingAllowed(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getAccessibleContext().setAccessibleName(GetText.tr("The pack's mods"));

        int[] widths = { 30, 320, 160, 110, 90 };
        for (int column = 0; column < widths.length; column++) {
            table.getColumnModel().getColumn(column).setPreferredWidth(widths[column]);
        }
        table.getColumnModel().getColumn(0).setMaxWidth(40);

        // a mod that can't be added has its box disabled
        TableCellRenderer box = table.getDefaultRenderer(Boolean.class);
        table.getColumnModel().getColumn(0).setCellRenderer((t, value, isSelected, hasFocus, row, column) -> {
            Component component = box.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
            component.setEnabled(model.shown.get(row).installable());
            return component;
        });
        RowRenderer text = new RowRenderer();
        for (int column = 1; column < widths.length; column++) {
            table.getColumnModel().getColumn(column).setCellRenderer(text);
        }

        // Space ticks the selected row, as a click on its box does
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggleRow");
        table.getActionMap().put("toggleRow", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int row = table.getSelectedRow();
                if (row >= 0 && model.isCellEditable(row, 0)) {
                    model.setValueAt(!(Boolean) model.getValueAt(row, 0), row, 0);
                }
            }
        });

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showRowMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showRowMenu(e);
            }
        });
    }

    // right-click a mod for its related mods, as on Add Mods' results
    private void showRowMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int index = table.rowAtPoint(e.getPoint());
        if (index < 0) {
            return;
        }
        table.setRowSelectionInterval(index, index);
        Row row = model.shown.get(index);
        if (row.modrinthProject == null && row.curseForgeProject == null) {
            return;
        }

        JPopupMenu menu = new JPopupMenu();
        JMenuItem related = new JMenuItem(GetText.tr("Related..."));
        related.addActionListener(a -> {
            if (row.modrinthProject != null) {
                RelatedModsDialog.open(this, addMods, instanceOrServer,
                        ModrinthCollections.asSearchHit(row.modrinthProject));
            } else {
                RelatedModsDialog.open(this, addMods, instanceOrServer, row.curseForgeProject);
            }
            refreshInstalled();
        });
        menu.add(related);
        menu.show(table, e.getX(), e.getY());
    }

    private final class RowsModel extends AbstractTableModel {
        List<Row> shown = new ArrayList<>();

        @Override
        public int getRowCount() {
            return shown.size();
        }

        @Override
        public int getColumnCount() {
            return 5;
        }

        @Override
        public String getColumnName(int column) {
            switch (column) {
                case 1:
                    return GetText.tr("Mod");
                case 2:
                    return GetText.tr("Here");
                case 3:
                    return GetText.tr("Side");
                case 4:
                    return GetText.tr("From");
                default:
                    return "";
            }
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : Object.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            return column == 0 ? selected.contains(shown.get(row).key()) : shown.get(row);
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0 && shown.get(row).installable();
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column != 0) {
                return;
            }
            String key = shown.get(row).key();
            if (Boolean.TRUE.equals(value)) {
                selected.add(key);
            } else {
                selected.remove(key);
            }
            fireTableCellUpdated(row, column);
            updateButtons();
        }
    }

    /**
     * A row's name, its state here with Add Mods' marks, its side and its site. Mods that can't be added are greyed
     * out, and a client-only mod is flagged on a server.
     */
    private final class RowRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus,
                int index, int column) {
            Row row = (Row) value;
            String text;
            String tip = null;
            javax.swing.Icon icon = null;
            Color warning = null;
            switch (column) {
                case 1:
                    text = row.name;
                    tip = row.name;
                    break;
                case 2:
                    text = statusLabel(row.status);
                    tip = row.reason;
                    icon = row.status == Status.INSTALLED ? null : CompatibilityBadge.icon(stateOf(row.status));
                    break;
                case 3:
                    text = row.side == ModSide.CLIENT ? GetText.tr("Client only")
                            : row.side == ModSide.SERVER ? GetText.tr("Server only") : "";
                    if (row.clientOnlyOnServer(server)) {
                        tip = GetText.tr("Only players need this mod. A server has no use for it, and it may stop the "
                                + "server starting, so Select Compatible leaves it out.");
                        warning = UIManager.getColor("yellow");
                    }
                    break;
                default:
                    text = row.platform == null ? "" : row.platform.toString();
                    break;
            }

            super.getTableCellRendererComponent(t, text, isSelected, hasFocus, index, column);
            setIcon(icon);
            setToolTipText(tip);
            if (!isSelected) {
                Color disabled = UIManager.getColor("Label.disabledForeground");
                setForeground(warning != null ? warning
                        : !row.installable() && disabled != null ? disabled : t.getForeground());
            }
            return this;
        }
    }

    static String statusLabel(Status status) {
        return status == Status.INSTALLED ? GetText.tr("Already installed")
                : CompatibilityBadge.label(stateOf(status));
    }

    private static ModCompatibility.State stateOf(Status status) {
        switch (status) {
            case COMPATIBLE:
                return ModCompatibility.State.COMPATIBLE;
            case VIA_CONNECTOR:
                return ModCompatibility.State.VIA_CONNECTOR;
            case NOT_AVAILABLE:
                return ModCompatibility.State.NOT_AVAILABLE;
            default:
                return ModCompatibility.State.UNKNOWN;
        }
    }

    private void refilter() {
        model.shown = PackMods.filter(rows, filterField.getText());
        model.fireTableDataChanged();
        updateButtons();
    }

    private void select(java.util.function.Predicate<Row> which) {
        model.shown.stream().filter(which).forEach(row -> selected.add(row.key()));
        model.fireTableDataChanged();
        updateButtons();
    }

    private void describe(PackMods.Result result) {
        Map<Status, Integer> counts = PackMods.counts(result.rows);
        // #. counts of a pack's mods: {0} in all, then by how they'd run here
        String text = GetText.tr("{0} mods: {1} compatible, {2} via Connector, {3} unknown, {4} not available, {5} "
                + "already installed.", result.rows.size(), counts.get(Status.COMPATIBLE),
                counts.get(Status.VIA_CONNECTOR), counts.get(Status.UNKNOWN), counts.get(Status.NOT_AVAILABLE),
                counts.get(Status.INSTALLED));
        if (result.notMods > 0) {
            text += " " + GetText.tr("{0} resource packs, shaders and other non-mods left out.", result.notMods);
        }
        if (result.problem != null) {
            text += " " + result.problem;
        }
        // a set width, so the text wraps instead of widening the window
        summary.setText("<html><div style='width:600px'>" + escape(text) + "</div></html>");
    }

    private void updateButtons() {
        long count = rows.stream().filter(r -> selected.contains(r.key()) && r.installable()).count();
        addSelected.setText(GetText.tr("Add Selected ({0})", count));
        addSelected.setEnabled(count > 0);
        boolean any = !model.shown.isEmpty();
        selectAll.setEnabled(any);
        selectCompatible.setEnabled(any);
        selectNone.setEnabled(any);
    }

    private void showModsMessage(String text) {
        modsMessage.setText("<html><div style='text-align:center;width:420px'>" + escape(text) + "</div></html>");
        modsCards.show(modsArea, MESSAGE);
    }

    // --- adding

    /**
     * What will be added, worked out before asking: the selected mods, the required dependencies the pack has, the
     * CurseForge files for them, and the names of dependencies that can't be added from here.
     */
    private static final class Plan {
        List<Row> toAdd = new ArrayList<>();
        List<Row> alsoAdd = new ArrayList<>();
        Map<Integer, CurseForgeFile> files = new HashMap<>();
        List<String> missing = new ArrayList<>();
    }

    // read for the report while a stopped install may still be finishing its current mod
    private static final class Outcome {
        final List<String> added = new CopyOnWriteArrayList<>();
        final List<String> skipped = new CopyOnWriteArrayList<>();
        final List<String> failed = new CopyOnWriteArrayList<>();
    }

    private void addSelected() {
        List<Row> chosen = rows.stream().filter(r -> selected.contains(r.key()) && r.installable())
                .collect(Collectors.toList());
        if (chosen.isEmpty()) {
            return;
        }
        List<Row> all = new ArrayList<>(rows);
        PackMods.Installed now = installed;

        ProgressDialog<Plan> planning = new ProgressDialog<>(GetText.tr("Add Mods From A Modpack"), 0,
                GetText.tr("Checking what they need..."), null, this);
        planning.addThread(new Thread(() -> {
            Plan plan = new Plan();
            plan.toAdd.addAll(chosen);
            PackMods.Dependencies needs = null;
            // CurseForge dependencies need the files, including those of dependencies added along the way
            for (int round = 0; round < 3; round++) {
                List<Row> known = new ArrayList<>(chosen);
                if (needs != null) {
                    known.addAll(needs.alsoAdd);
                }
                fetchFiles(known, plan.files);
                needs = PackMods.dependencies(chosen, all, now, plan.files);
                if (needs.alsoAdd.stream().allMatch(r -> r.platform != ModPlatform.CURSEFORGE
                        || plan.files.containsKey(r.curseForgeFileId))) {
                    break;
                }
            }
            fetchFiles(needs.alsoAdd, plan.files);
            plan.alsoAdd.addAll(needs.alsoAdd);
            plan.toAdd.addAll(needs.alsoAdd);
            plan.missing.addAll(missingNames(needs));
            planning.setReturnValue(plan);
            planning.close();
        }));
        planning.start();
        Plan plan = planning.getReturnValue();
        if (plan == null) {
            return;
        }

        if (!confirm(plan)) {
            return;
        }

        // the install paths take a ProgressDialog<Void> for their download progress
        ProgressDialog<Void> adding = new ProgressDialog<>(GetText.tr("Adding Mods"), 0,
                GetText.tr("Adding Mods"), null, this);
        Outcome outcome = new Outcome();
        adding.addThread(new Thread(() -> {
            for (int i = 0; i < plan.toAdd.size(); i++) {
                Row row = plan.toAdd.get(i);
                if (adding.wasClosed || Thread.currentThread().isInterrupted()) {
                    outcome.skipped.add(GetText.tr("{0}: stopped before it was added", row.name));
                    continue;
                }
                // #. {0} is a mod's name, {1} and {2} how far through the list it is
                adding.setLabel(GetText.tr("Adding {0} ({1} of {2})", row.name, i + 1, plan.toAdd.size()));
                add(row, plan.files, adding, outcome);
            }
            adding.close();
        }));
        adding.start();

        refreshInstalled();
        report(outcome, plan.missing);
    }

    // the CurseForge files to add for these rows that aren't fetched yet, in one request
    private static void fetchFiles(List<Row> rows, Map<Integer, CurseForgeFile> files) {
        int[] ids = rows.stream().filter(r -> r.platform == ModPlatform.CURSEFORGE && r.curseForgeFileId != null
                && !files.containsKey(r.curseForgeFileId)).mapToInt(r -> r.curseForgeFileId).distinct().toArray();
        if (ids.length == 0) {
            return;
        }
        List<CurseForgeFile> found = CurseForgeApi.getFiles(ids);
        if (found != null) {
            found.stream().filter(Objects::nonNull).forEach(f -> files.put(f.id, f));
        }
    }

    private static List<String> missingNames(PackMods.Dependencies needs) {
        List<String> names = new ArrayList<>();
        if (!needs.missingModrinth.isEmpty()) {
            List<ModrinthProject> found = paced(
                    () -> ModrinthApi.getProjects(needs.missingModrinth.toArray(new String[0])));
            Map<String, String> byId = new HashMap<>();
            if (found != null) {
                found.stream().filter(p -> p != null && p.id != null).forEach(p -> byId.put(p.id, p.title));
            }
            needs.missingModrinth.forEach(id -> names.add(byId.getOrDefault(id, id) + " (Modrinth)"));
        }
        if (!needs.missingCurseForge.isEmpty()) {
            List<CurseForgeProject> found = CurseForgeApi
                    .getProjects(needs.missingCurseForge.stream().mapToInt(Integer::intValue).toArray());
            Map<Integer, String> byId = new HashMap<>();
            if (found != null) {
                found.stream().filter(Objects::nonNull).forEach(p -> byId.put(p.id, p.name));
            }
            needs.missingCurseForge.forEach(id -> names.add(byId.getOrDefault(id, "#" + id) + " (CurseForge)"));
        }
        return names;
    }

    private boolean confirm(Plan plan) {
        List<String> lines = new ArrayList<>();
        lines.add(GetText.tr("Add {0} mods to {1}? Each gets the file shown as compatible here.",
                plan.toAdd.size(), instanceOrServer.getName()));
        if (!plan.alsoAdd.isEmpty()) {
            lines.add(GetText.tr("This includes mods they require that weren't ticked: {0}.",
                    plan.alsoAdd.stream().map(r -> r.name).collect(Collectors.joining(", "))));
        }
        if (!plan.missing.isEmpty()) {
            lines.add(GetText.tr("They also require mods this pack can't add here, so add these yourself from Add "
                    + "Mods: {0}.", String.join(", ", plan.missing)));
        }
        long connector = plan.toAdd.stream().filter(r -> r.status == Status.VIA_CONNECTOR).count();
        if (connector > 0) {
            lines.add(GetText.tr("{0} of them are Fabric mods that use Sinytra Connector; compatibility is not "
                    + "guaranteed.", connector));
        }
        long clientOnly = plan.toAdd.stream().filter(r -> r.clientOnlyOnServer(server)).count();
        if (clientOnly > 0) {
            lines.add(GetText.tr("{0} of them are client-only mods, which a server has no use for and may not start "
                    + "with.", clientOnly));
        }

        return DialogManager.confirmDialog().setParent(this).setTitle(GetText.tr("Add Mods From A Modpack"))
                .setContent("<html><div style='width:420px'>" + lines.stream().map(PackModsDialog::escape)
                        .collect(Collectors.joining("<br><br>")) + "</div></html>")
                .addOption(GetText.tr("Add")).addOption(GetText.tr("Cancel"), true).show() == 0;
    }

    // one mod, through the same install path as the selectors; a failed download shows its own error there
    private void add(Row row, Map<Integer, CurseForgeFile> files, ProgressDialog<Void> dialog, Outcome outcome) {
        CurseForgeFile file = row.platform == ModPlatform.CURSEFORGE ? files.get(row.curseForgeFileId) : null;
        if (row.platform == ModPlatform.CURSEFORGE && file == null) {
            outcome.failed.add(GetText.tr("{0}: CurseForge didn't give its file", row.name));
            return;
        }
        if (file != null && file.downloadUrl == null) {
            // the install path would stop to have it downloaded in the browser, one mod at a time
            outcome.skipped.add(GetText.tr("{0}: its author doesn't let other launchers download it; add it from "
                    + "Add Mods to download it in your browser", row.name));
            return;
        }

        try {
            if (file == null) {
                instanceOrServer.addFileFromModrinth(row.modrinthProject, row.modrinthVersion, null,
                        com.atlauncher.data.Type.mods, dialog);
            } else {
                instanceOrServer.addFileFromCurseForge(row.curseForgeProject, file, dialog);
            }
        } catch (RuntimeException e) {
            LogManager.logStackTrace("Couldn't add " + row.name + " from a pack", e);
        } finally {
            dialog.clearDownloadedBytes();
        }

        // the install path only says it failed in its own dialog, so check the instance has it now
        if (PackMods.Installed.of(new ArrayList<>(instanceOrServer.getMods())).has(row)) {
            outcome.added.add(row.name);
        } else {
            outcome.failed.add(row.name);
        }
    }

    private void report(Outcome outcome, List<String> missing) {
        List<String> lines = new ArrayList<>();
        lines.add(GetText.tr("Added {0}: {1}", outcome.added.size(),
                outcome.added.isEmpty() ? GetText.tr("none") : String.join(", ", outcome.added)));
        if (!outcome.skipped.isEmpty()) {
            lines.add(GetText.tr("Skipped {0}: {1}", outcome.skipped.size(), String.join("; ", outcome.skipped)));
        }
        if (!outcome.failed.isEmpty()) {
            lines.add(GetText.tr("Failed {0}: {1}", outcome.failed.size(), String.join("; ", outcome.failed)));
        }
        if (!missing.isEmpty()) {
            lines.add(GetText.tr("Still needed, from Add Mods: {0}", String.join(", ", missing)));
        }

        DialogManager.okDialog().setParent(this).setTitle(GetText.tr("Add Mods From A Modpack"))
                .setContent("<html><div style='width:420px'>" + lines.stream().map(PackModsDialog::escape)
                        .collect(Collectors.joining("<br><br>")) + "</div></html>")
                .setType(outcome.failed.isEmpty() ? DialogManager.INFO : DialogManager.WARNING).show();
    }

    // what's installed may have changed: mark it, and keep ticks only on what can still be added
    private void refreshInstalled() {
        installed = PackMods.Installed.of(new ArrayList<>(instanceOrServer.getMods()));
        rows = PackMods.markInstalled(rows, installed);
        selected.removeIf(key -> rows.stream().noneMatch(r -> r.key().equals(key) && r.installable()));
        refilter();
    }

    // --- helpers

    private static void background(String name, Runnable work) {
        Thread thread = new Thread(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                LogManager.logStackTrace("Pack mods: " + name + " failed", e);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    // Modrinth requests share Add Mods' pacing
    private static <T> T paced(java.util.function.Supplier<T> request) {
        try {
            ModCompatibilityChecker.waitForTurn();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return request.get();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
