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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.FileSystem;
import com.atlauncher.data.ModManagement;
import com.atlauncher.data.ResultsView;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.modrinth.ModrinthCollection;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthSearchHit;
import com.atlauncher.gui.components.ResultsViewPicker;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.utils.CollectionsLibrary;
import com.atlauncher.utils.CollectionsLibrary.LocalCollection;
import com.atlauncher.utils.CollectionsLibrary.Resolution;
import com.atlauncher.utils.CollectionsLibrary.Resolved;
import com.atlauncher.utils.ModrinthCollections;
import com.atlauncher.utils.ModrinthCollections.CreatorLookup;
import com.atlauncher.utils.ModrinthCollections.Handoff;
import com.atlauncher.utils.ModrinthCollections.Lookup;
import com.atlauncher.utils.ModrinthCollections.Saved;
import com.atlauncher.utils.ModrinthCollections.State;

/**
 * The launcher's collections library, from Add Mods on either provider: Favorites and the user's own collections of
 * CurseForge and Modrinth projects, and Modrinth collections opened by link, from a creator, or saved. Projects are
 * shown as Add Mods shows its results, in this window's own view, and each is added through its own provider's
 * existing selector. Saved collections are only kept in this launcher; nothing on either provider is changed, and
 * nothing is installed without the selector.
 */
public final class CollectionsDialog extends JDialog {
    private static final String PROJECTS = "projects";
    private static final String MESSAGE = "message";
    // the results' side of the window; rows are laid out to fit in this, less the scroll bar
    static final int RESULTS_MIN_WIDTH = 460;

    /**
     * Makes a Modrinth project's card as Add Mods does, for the section it's shown in.
     */
    interface ModrinthCards {
        JComponent card(ModrinthSearchHit hit, String section, ResultsView view);
    }

    /**
     * Makes a CurseForge project's card as Add Mods does, for the section it's shown in.
     */
    interface CurseForgeCards {
        JComponent card(CurseForgeProject project, String section, ResultsView view);
    }

    /**
     * An entry in the library: Favorites, a collection of the user's own, or a saved Modrinth collection.
     */
    static final class Entry {
        final LocalCollection local;
        final Saved modrinth;

        Entry(LocalCollection local, Saved modrinth) {
            this.local = local;
            this.modrinth = modrinth;
        }
    }

    private final AddModsDialog addMods;
    private final ModManagement instanceOrServer;
    private final CollectionsLibrary library = new CollectionsLibrary(FileSystem.COLLECTIONS,
        FileSystem.MODRINTH_COLLECTIONS);

    private final JTextField input = new JTextField(28);
    private final JButton open = new JButton(GetText.tr("Open"));

    private final DefaultListModel<Entry> libraryModel = new DefaultListModel<>();
    private final JList<Entry> libraryList = new JList<>(libraryModel);
    private final JButton removeSaved = new JButton(GetText.tr("Remove..."));

    private final JTextField creatorInput = new JTextField(14);
    private final JButton find = new JButton(GetText.tr("Find"));
    private final DefaultListModel<ModrinthCollection> creatorModel = new DefaultListModel<>();
    private final JList<ModrinthCollection> creatorList = new JList<>(creatorModel);
    private final JLabel creatorMessage = new JLabel("", SwingConstants.CENTER);
    private final CardLayout creatorCards = new CardLayout();
    private final JPanel creatorArea = new JPanel(creatorCards);

    private final JLabel title = new JLabel();
    private final JLabel description = new JLabel();
    private final JPanel results = new JPanel();
    private final JLabel notAddable = new JLabel();
    private final JLabel message = new JLabel("", SwingConstants.CENTER);
    private final CardLayout cards = new CardLayout();
    private final JPanel projectArea = new JPanel(cards);
    private final JScrollPane resultsScroll;

    private final ResultsViewPicker viewPicker;
    private final JButton save = new JButton(GetText.tr("Save To Library"));
    private final JButton close = new JButton(GetText.tr("Close"));

    // what's shown: a Modrinth collection looked up, or a local one resolved
    private Lookup shown;
    private Resolution shownLocal;
    // each lookup's number, so only the latest is shown
    private int generation = 0;
    private int creatorGeneration = 0;

    public CollectionsDialog(AddModsDialog addMods, ModManagement instanceOrServer) {
        super(addMods, GetText.tr("Collections"), ModalityType.DOCUMENT_MODAL);
        this.addMods = addMods;
        this.instanceOrServer = instanceOrServer;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(760, 460));
        setSize(1000, 640);
        setLocationRelativeTo(addMods);

        input.putClientProperty("JTextField.placeholderText", GetText.tr("Modrinth collection link or ID"));
        input.getAccessibleContext().setAccessibleName(GetText.tr("Modrinth collection link or ID"));
        input.addActionListener(e -> openInput());
        open.setToolTipText(GetText.tr("Open a Modrinth collection by its link or ID. Only Modrinth has collections "
            + "of its own."));
        open.addActionListener(e -> openInput());
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(new JLabel(GetText.tr("Modrinth collection:")), BorderLayout.WEST);
        top.add(input, BorderLayout.CENTER);
        top.add(open, BorderLayout.EAST);

        JTabbedPane sources = new JTabbedPane();
        sources.addTab(GetText.tr("Library"), libraryPanel());
        sources.addTab(GetText.tr("Modrinth: By Creator"), creatorPanel());
        sources.setMinimumSize(new Dimension(220, 100));

        results.setLayout(new BorderLayout());
        resultsScroll = new JScrollPane(results);
        resultsScroll.getVerticalScrollBar().setUnitIncrement(16);
        resultsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        notAddable.setEnabled(false);
        JPanel projectsPanel = new JPanel(new BorderLayout(0, 4));
        projectsPanel.add(resultsScroll, BorderLayout.CENTER);
        projectsPanel.add(notAddable, BorderLayout.SOUTH);
        message.setEnabled(false);
        projectArea.add(projectsPanel, PROJECTS);
        projectArea.add(message, MESSAGE);

        description.setEnabled(false);
        JPanel heading = new JPanel(new BorderLayout(0, 2));
        heading.add(title, BorderLayout.NORTH);
        heading.add(description, BorderLayout.CENTER);
        JPanel collectionPanel = new JPanel(new BorderLayout(0, 6));
        collectionPanel.add(heading, BorderLayout.NORTH);
        collectionPanel.add(projectArea, BorderLayout.CENTER);
        // never so narrow that a row's name, count and actions can't all be seen
        collectionPanel.setMinimumSize(new Dimension(RESULTS_MIN_WIDTH, 0));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sources, collectionPanel);
        split.setDividerLocation(260);
        split.setBorder(BorderFactory.createEmptyBorder());

        // this window's own view, apart from Add Mods'
        viewPicker = new ResultsViewPicker(App.settings.collectionsView(), this::setViewMode);

        save.setToolTipText(GetText.tr("Keep this Modrinth collection in this launcher to open again. Modrinth isn't "
            + "changed."));
        save.addActionListener(e -> saveShown());
        close.addActionListener(e -> dispose());
        JPanel bottom = new JPanel(new BorderLayout());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        actions.add(viewPicker);
        actions.add(save);
        JPanel window = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        window.add(close);
        bottom.add(actions, BorderLayout.CENTER);
        bottom.add(window, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(top, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);

        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);

        fillLibrary();
        showNothing();
        if (!library.isReadable()) {
            showMessage(GetText.tr("The collections library couldn't be read from {0}, so it isn't shown and nothing "
                + "can be saved until it's fixed.", FileSystem.COLLECTIONS.toString()));
        }
        updateButtons();
    }

    private void fillLibrary() {
        libraryModel.clear();
        libraryModel.addElement(new Entry(library.favorites(), null));
        library.localCollections().forEach(collection -> libraryModel.addElement(new Entry(collection, null)));
        library.modrinthCollections().forEach(collection -> libraryModel.addElement(new Entry(null, collection)));
    }

    private JPanel libraryPanel() {
        libraryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        libraryList.getAccessibleContext().setAccessibleName(GetText.tr("Library"));
        libraryList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
                Entry entry = (Entry) value;
                // #. {0} is a collection's name, {1} how many projects it has
                String text = entry.local != null
                    ? GetText.tr("{0} ({1})", entry.local.name, entry.local.items.size())
                    : GetText.tr("{0} ({1} projects) - Modrinth", entry.modrinth.toString(),
                        entry.modrinth.projectCount);
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
            }
        });
        libraryList.addListSelectionListener(e -> {
            Entry selected = libraryList.getSelectedValue();
            if (!e.getValueIsAdjusting() && selected != null) {
                creatorList.clearSelection();
                if (selected.local != null) {
                    openLocal(selected.local);
                } else if (shown == null || !shown.id.equals(selected.modrinth.id)) {
                    lookUp(selected.modrinth.id);
                }
            }
            updateButtons();
        });
        removeSaved.setToolTipText(GetText.tr("Remove the selected Modrinth collection from this launcher. It isn't "
            + "changed on Modrinth."));
        removeSaved.addActionListener(e -> removeSaved());

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        panel.add(new JScrollPane(libraryList), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        buttons.add(removeSaved);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel creatorPanel() {
        creatorInput.putClientProperty("JTextField.placeholderText", GetText.tr("Username or profile link"));
        creatorInput.getAccessibleContext().setAccessibleName(GetText.tr("Modrinth username or profile link"));
        creatorInput.addActionListener(e -> findCreator());
        find.addActionListener(e -> findCreator());
        JPanel search = new JPanel(new BorderLayout(4, 0));
        search.add(creatorInput, BorderLayout.CENTER);
        search.add(find, BorderLayout.EAST);

        creatorList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        creatorList.getAccessibleContext().setAccessibleName(GetText.tr("The creator's collections"));
        creatorList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
                ModrinthCollection collection = (ModrinthCollection) value;
                int count = collection.projects == null ? 0 : collection.projects.size();
                String name = collection.name == null ? collection.id : collection.name;
                // #. {0} is a collection's name, {1} how many projects it has
                JLabel label = (JLabel) super.getListCellRendererComponent(list,
                    GetText.tr("{0} ({1} projects)", name, count), index, isSelected, cellHasFocus);
                label.setToolTipText(collection.description);
                return label;
            }
        });
        creatorList.addListSelectionListener(e -> {
            ModrinthCollection selected = creatorList.getSelectedValue();
            if (!e.getValueIsAdjusting() && selected != null && (shown == null || !shown.id.equals(selected.id))) {
                libraryList.clearSelection();
                lookUp(selected.id);
            }
        });
        creatorMessage.setEnabled(false);
        creatorArea.add(new JScrollPane(creatorList), PROJECTS);
        creatorArea.add(creatorMessage, MESSAGE);
        showCreatorMessage(GetText.tr("Find a Modrinth creator to see the collections of theirs you can see."));

        JLabel scope = new JLabel("<html><div style='width:210px'>" + escape(GetText.tr("Shows the collections of "
            + "this Modrinth creator that Modrinth lets you see: their listed collections with projects in them. With "
            + "a Modrinth API key set in the launcher's settings, your own unlisted and private collections are "
            + "included when you look yourself up; they aren't publicly listed. Modrinth has no way to search all "
            + "collections, and CurseForge has no collections of its own.")) + "</div></html>");
        scope.setEnabled(false);

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        panel.add(search, BorderLayout.NORTH);
        panel.add(creatorArea, BorderLayout.CENTER);
        panel.add(scope, BorderLayout.SOUTH);
        return panel;
    }

    private void openInput() {
        Optional<String> id = ModrinthCollections.parseCollectionId(input.getText());
        if (!id.isPresent()) {
            DialogManager.okDialog().setParent(this).setTitle(GetText.tr("Collections"))
                .setContent(GetText.tr("That isn't a Modrinth collection link or ID. Links look like "
                    + "https://modrinth.com/collection/ID."))
                .setType(DialogManager.ERROR).show();
            return;
        }

        libraryList.clearSelection();
        creatorList.clearSelection();
        lookUp(id.get());
    }

    private void findCreator() {
        Optional<String> creator = ModrinthCollections.parseCreator(creatorInput.getText());
        if (!creator.isPresent()) {
            showCreatorMessage(GetText.tr("That isn't a Modrinth username, user ID or profile link."));
            return;
        }

        int lookup = ++creatorGeneration;
        creatorModel.clear();
        showCreatorMessage(GetText.tr("Loading..."));
        find.setEnabled(false);

        Thread thread = new Thread(() -> {
            CreatorLookup result = ModrinthCollections.lookUpCreator(creator.get(), ModrinthCollections.MODRINTH);
            SwingUtilities.invokeLater(() -> {
                if (lookup != creatorGeneration) {
                    return;
                }
                find.setEnabled(true);
                if (result.state == State.LOADED) {
                    result.collections.forEach(creatorModel::addElement);
                    creatorCards.show(creatorArea, PROJECTS);
                } else if (result.state == State.EMPTY) {
                    showCreatorMessage(GetText.tr("None of {0}'s collections are visible to you.", result.creator));
                } else {
                    showCreatorMessage(result.problem);
                }
            });
        }, "ModrinthCreatorCollections");
        thread.setDaemon(true);
        thread.start();
    }

    // a Modrinth collection, off the event dispatch thread; only the latest lookup is shown
    private void lookUp(String id) {
        int lookup = ++generation;
        shown = null;
        shownLocal = null;
        title.setText(GetText.tr("Modrinth collection {0}", id));
        description.setText("");
        showMessage(GetText.tr("Loading..."));
        updateButtons();

        Thread thread = new Thread(() -> {
            Lookup result = ModrinthCollections.lookUp(id, ModrinthCollections.MODRINTH);
            SwingUtilities.invokeLater(() -> {
                if (lookup == generation) {
                    show(result);
                }
            });
        }, "ModrinthCollection");
        thread.setDaemon(true);
        thread.start();
    }

    // one of the launcher's own collections, each project from its own provider
    private void openLocal(LocalCollection collection) {
        int lookup = ++generation;
        shown = null;
        shownLocal = null;
        title.setText(collection.name);
        description.setText("");
        updateButtons();

        if (collection.items.isEmpty()) {
            showMessage(GetText.tr("There are no projects in {0} yet.", collection.name));
            return;
        }

        showMessage(GetText.tr("Loading..."));
        List<CollectionsLibrary.Item> items = new ArrayList<>(collection.items);
        Thread thread = new Thread(() -> {
            Resolution result = CollectionsLibrary.resolve(items, CollectionsLibrary.PROVIDERS);
            SwingUtilities.invokeLater(() -> {
                if (lookup == generation) {
                    showLocal(result);
                }
            });
        }, "LocalCollection");
        thread.setDaemon(true);
        thread.start();
    }

    private void show(Lookup lookup) {
        shown = lookup;

        if (!lookup.canBeSaved()) {
            title.setText(GetText.tr("Modrinth collection {0}", lookup.id));
            description.setText("");
            showMessage(lookup.problem);
            updateButtons();
            return;
        }

        title.setText(lookup.collection.name == null ? lookup.id : lookup.collection.name);
        String about = lookup.collection.description == null ? "" : lookup.collection.description;
        if (lookup.missing > 0) {
            about = about + (about.isEmpty() ? "" : " ") + GetText.tr("{0} project(s) in it couldn't be found, such as "
                + "private or deleted ones.", lookup.missing);
        }
        description.setText(about.isEmpty() ? "" : "<html><div style='width:640px'>" + escape(about) + "</div></html>");

        if (lookup.state == State.EMPTY) {
            showMessage(GetText.tr("This collection has no projects."));
        } else if (lookup.projects.isEmpty()) {
            showMessage(GetText.tr("None of the projects in this collection could be found. They may be private or "
                + "deleted."));
        } else {
            renderShown();
            cards.show(projectArea, PROJECTS);
        }

        // what's shown for a saved one is brought up to date; its reference is all that's kept
        if (library.findModrinth(lookup.id) != null) {
            try {
                library.refreshModrinth(lookup);
                libraryList.repaint();
            } catch (IOException e) {
                LogManager.logStackTrace("Couldn't update a saved Modrinth collection", e);
            }
        }
        updateButtons();
    }

    private void showLocal(Resolution resolution) {
        shownLocal = resolution;
        if (resolution.state == State.ERROR) {
            showMessage(resolution.problem);
            return;
        }

        description.setText(resolution.missing == 0 ? ""
            : GetText.tr("{0} project(s) in it couldn't be found, such as deleted ones.", resolution.missing));
        if (resolution.found.isEmpty()) {
            showMessage(GetText.tr("None of the projects in this collection could be found."));
        } else {
            renderShown();
            cards.show(projectArea, PROJECTS);
        }
    }

    private void renderShown() {
        ResultsView view = App.settings.collectionsView();
        ModrinthCards modrinthCards = (hit, section, rowView) -> addMods.modrinthCard(hit, section, rowView, this);
        CurseForgeCards curseForgeCards = (project, section, rowView) -> addMods.curseForgeCard(project, section,
            rowView, this);

        List<String> left;
        if (shown != null && shown.canBeSaved()) {
            left = render(results, shown.projects, instanceOrServer, view, modrinthCards);
        } else if (shownLocal != null && shownLocal.state != State.ERROR) {
            left = renderLocal(results, shownLocal.found, instanceOrServer, view, modrinthCards, curseForgeCards);
        } else {
            return;
        }

        notAddable.setText(left.isEmpty() ? "" : "<html><div style='width:640px'>"
            + escape(GetText.tr("Can't be added here: {0}", String.join(", ", left))) + "</div></html>");
        results.revalidate();
        results.repaint();
        SwingUtilities.invokeLater(() -> resultsScroll.getVerticalScrollBar().setValue(0));
    }

    /**
     * A Modrinth collection's projects that can be added here, as Add Mods shows its results, each in the section of
     * its type. The rest are only named, with their type.
     *
     * @return the projects that can't be added here
     */
    static List<String> render(JPanel results, List<ModrinthProject> projects, ModManagement instanceOrServer,
        ResultsView view, ModrinthCards factory) {
        List<JComponent> shownCards = new ArrayList<>();
        List<String> left = new ArrayList<>();

        for (ModrinthProject project : projects) {
            Handoff handoff = ModrinthCollections.handoff(project, instanceOrServer);
            if (handoff == Handoff.MODPACK || handoff == Handoff.UNSUPPORTED) {
                left.add(project.title + " (" + ModrinthCollections.typeName(project.projectType) + ")");
            } else {
                shownCards.add(factory.card(ModrinthCollections.asSearchHit(project),
                    ModrinthCollections.sectionFor(project.projectType), view));
            }
        }

        AddModsDialog.layOutResults(results, shownCards, view);
        return left;
    }

    /**
     * A local collection's projects, each made by its own provider's card as Add Mods makes it, so adding one opens
     * that provider's selector whichever Add Mods tab is showing. Those Add Mods doesn't offer here are only named.
     *
     * @return the projects that can't be added here
     */
    static List<String> renderLocal(JPanel results, List<Resolved> found, ModManagement instanceOrServer,
        ResultsView view, ModrinthCards modrinthCards, CurseForgeCards curseForgeCards) {
        List<JComponent> shownCards = new ArrayList<>();
        List<String> left = new ArrayList<>();

        for (Resolved resolved : found) {
            if (resolved.modrinth != null) {
                ModrinthProject project = resolved.modrinth;
                Handoff handoff = ModrinthCollections.handoff(project, instanceOrServer);
                if (handoff == Handoff.MODPACK || handoff == Handoff.UNSUPPORTED) {
                    left.add(project.title + " (Modrinth)");
                } else {
                    shownCards.add(modrinthCards.card(ModrinthCollections.asSearchHit(project),
                        ModrinthCollections.sectionFor(project.projectType), view));
                }
            } else if (resolved.curseForge != null) {
                CurseForgeProject project = resolved.curseForge;
                String section = CollectionsLibrary.curseForgeSection(project);
                if (!CollectionsLibrary.curseForgeOffers(section, instanceOrServer)) {
                    left.add(project.name + " (CurseForge)");
                } else {
                    shownCards.add(curseForgeCards.card(project, section, view));
                }
            }
        }

        AddModsDialog.layOutResults(results, shownCards, view);
        return left;
    }

    private void setViewMode(ResultsView view) {
        if (App.settings.collectionsView() == view) {
            return;
        }

        // this window's own choice; Add Mods keeps its own
        viewPicker.select(view);
        App.settings.collectionsView = view;
        App.settings.save();
        renderShown();
    }

    private void saveShown() {
        if (shown == null || !shown.canBeSaved()) {
            return;
        }

        try {
            library.saveModrinth(shown);
            fillLibrary();
        } catch (IOException e) {
            LogManager.logStackTrace("Couldn't save a Modrinth collection", e);
            DialogManager.okDialog().setParent(this).setTitle(GetText.tr("Collections"))
                .setContent(GetText.tr("The collection couldn't be saved: {0}", e.getMessage()))
                .setType(DialogManager.ERROR).show();
        }
        updateButtons();
    }

    private void removeSaved() {
        Entry selected = libraryList.getSelectedValue();
        if (selected == null || selected.modrinth == null) {
            return;
        }

        int option = DialogManager.confirmDialog().setParent(this).setTitle(GetText.tr("Remove Saved Collection"))
            .setContent(GetText.tr("Remove {0} from your saved collections?\n\nThis only removes it from this "
                + "launcher. The collection on Modrinth isn't changed, and nothing installed is removed.",
                selected.modrinth.toString()))
            .addOption(GetText.tr("Remove")).addOption(GetText.tr("Cancel"), true).show();
        if (option != 0) {
            return;
        }

        try {
            library.removeModrinth(selected.modrinth.id);
            fillLibrary();
        } catch (IOException e) {
            LogManager.logStackTrace("Couldn't remove a saved Modrinth collection", e);
            DialogManager.okDialog().setParent(this).setTitle(GetText.tr("Collections"))
                .setContent(GetText.tr("The collection couldn't be removed: {0}", e.getMessage()))
                .setType(DialogManager.ERROR).show();
        }
        updateButtons();
    }

    private void showNothing() {
        title.setText(GetText.tr("No collection open"));
        description.setText("");
        showMessage(GetText.tr("Choose Favorites or a collection from your library, or open a Modrinth collection by "
            + "its link or from a creator."));
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void showMessage(String text) {
        message.setText("<html><div style='text-align:center;width:420px'>" + escape(text) + "</div></html>");
        cards.show(projectArea, MESSAGE);
    }

    private void showCreatorMessage(String text) {
        creatorMessage.setText("<html><div style='text-align:center;width:200px'>" + escape(text) + "</div></html>");
        creatorCards.show(creatorArea, MESSAGE);
    }

    private void updateButtons() {
        boolean found = shown != null && shown.canBeSaved();
        boolean saved = found && library.findModrinth(shown.id) != null;
        save.setEnabled(found && library.isReadable() && !saved);
        save.setText(saved ? GetText.tr("Saved") : GetText.tr("Save To Library"));
        Entry selected = libraryList.getSelectedValue();
        removeSaved.setEnabled(library.isReadable() && selected != null && selected.modrinth != null);
    }
}
