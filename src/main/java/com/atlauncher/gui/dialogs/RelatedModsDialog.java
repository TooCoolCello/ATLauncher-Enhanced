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
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.constants.Constants;
import com.atlauncher.data.ModCompatibility;
import com.atlauncher.data.ModCompatibilityChecker;
import com.atlauncher.data.ModManagement;
import com.atlauncher.data.PackMods;
import com.atlauncher.data.RelatedMods;
import com.atlauncher.data.ResultsView;
import com.atlauncher.data.curseforge.CurseForgeAuthor;
import com.atlauncher.data.curseforge.CurseForgeCoreApiResponse;
import com.atlauncher.data.curseforge.CurseForgeFile;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthProjectType;
import com.atlauncher.data.modrinth.ModrinthSearchHit;
import com.atlauncher.data.modrinth.ModrinthSearchResult;
import com.atlauncher.gui.components.WrappingLabel;
import com.atlauncher.managers.LogManager;
import com.atlauncher.utils.CollectionsLibrary;
import com.atlauncher.utils.CurseForgeApi;
import com.atlauncher.utils.ModrinthApi;

/**
 * Projects related to one Add Mods result, in clearly labelled tabs, since neither site lists similar projects: more
 * from the same author, a search for its addons among mods for this instance, and what the file Add Mods would offer
 * here depends on. Each is shown by Add Mods' own cards, so the compatibility marks, installed state and adding work
 * as there.
 */
public final class RelatedModsDialog extends JDialog {
    private static final String RESULTS = "results";
    private static final String MESSAGE = "message";
    // how many of each are shown
    private static final int LIMIT = 30;

    private final AddModsDialog addMods;
    private final ModManagement instanceOrServer;
    private final ModCompatibility.Target target;
    private final JTabbedPane tabs = new JTabbedPane();

    /**
     * Has a result's right-click menu open wherever on it the click lands, such as its buttons or description.
     */
    static void inheritPopup(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JComponent) {
                ((JComponent) child).setInheritsPopupMenu(true);
            }
            if (child instanceof Container) {
                inheritPopup((Container) child);
            }
        }
    }

    static void open(Window parent, AddModsDialog addMods, ModManagement instanceOrServer, ModrinthSearchHit hit) {
        RelatedModsDialog dialog = new RelatedModsDialog(parent, addMods, instanceOrServer, hit.title);
        dialog.modrinth(hit);
        dialog.setVisible(true);
    }

    static void open(Window parent, AddModsDialog addMods, ModManagement instanceOrServer,
            CurseForgeProject project) {
        RelatedModsDialog dialog = new RelatedModsDialog(parent, addMods, instanceOrServer, project.name);
        dialog.curseForge(project);
        dialog.setVisible(true);
    }

    private RelatedModsDialog(Window parent, AddModsDialog addMods, ModManagement instanceOrServer, String name) {
        // #. {0} is the name of the mod whose related mods are shown
        super(parent, GetText.tr("Related to {0}", name), ModalityType.DOCUMENT_MODAL);
        this.addMods = addMods;
        this.instanceOrServer = instanceOrServer;
        this.target = ModCompatibility.Target.of(instanceOrServer);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(640, 420));
        setSize(900, 600);
        setLocationRelativeTo(parent);

        WrappingLabel about = new WrappingLabel();
        about.setWrappedText(escape(GetText.tr("Neither Modrinth nor CurseForge lists similar projects, so these are "
                + "found from what they do say. Each tab says how its list was made.")));
        about.setEnabled(false);

        JButton close = new JButton(GetText.tr("Close"));
        close.addActionListener(e -> dispose());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        bottom.add(close);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(about, BorderLayout.NORTH);
        content.add(tabs, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);

        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    /**
     * One tab: a note on how its list was made, then the results, or a message while loading or when there are
     * none.
     */
    private final class Section {
        final int index;
        final String title;
        // wraps to the tab's width: a fixed 760px one was cut off at the dialog's 640px minimum
        final WrappingLabel note = new WrappingLabel();
        final JPanel results = new JPanel();
        final JLabel message = new JLabel("", SwingConstants.CENTER);
        final CardLayout cards = new CardLayout();
        final JPanel area = new JPanel(cards);

        Section(String title) {
            this.title = title;
            note.setEnabled(false);
            message.setEnabled(false);
            JScrollPane scroll = new JScrollPane(results);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            area.add(scroll, RESULTS);
            area.add(message, MESSAGE);

            JPanel panel = new JPanel(new BorderLayout(0, 6));
            panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            panel.add(note, BorderLayout.NORTH);
            panel.add(area, BorderLayout.CENTER);
            index = tabs.getTabCount();
            tabs.addTab(title, panel);
            say(GetText.tr("Loading..."));
        }

        void note(String text) {
            note.setWrappedText(escape(text));
        }

        void say(String text) {
            message.setText("<html><div style='text-align:center;width:420px'>" + escape(text) + "</div></html>");
            cards.show(area, MESSAGE);
        }

        void show(List<JComponent> shown) {
            tabs.setTitleAt(index, GetText.tr("{0} ({1})", title, shown.size()));
            if (shown.isEmpty()) {
                say(GetText.tr("Nothing found."));
                return;
            }
            AddModsDialog.layOutResults(results, shown, App.settings.addModsView());
            results.revalidate();
            cards.show(area, RESULTS);
        }

        /**
         * Loads off the event dispatch thread, then shows the cards it makes back on it.
         */
        void load(Supplier<Runnable> work) {
            Thread thread = new Thread(() -> {
                Runnable show;
                try {
                    show = work.get();
                } catch (RuntimeException e) {
                    LogManager.logStackTrace("Couldn't load related mods", e);
                    show = () -> say(GetText.tr("This couldn't be loaded. Try again later."));
                }
                Runnable then = show;
                SwingUtilities.invokeLater(() -> {
                    if (isDisplayable()) {
                        then.run();
                    }
                });
            }, "RelatedMods");
            thread.setDaemon(true);
            thread.start();
        }
    }

    // --- Modrinth

    private void modrinth(ModrinthSearchHit hit) {
        Section author = new Section(GetText.tr("Same Author"));
        Section addons = new Section(GetText.tr("Addons (Search)"));
        Section needs = new Section(GetText.tr("Dependencies"));

        author.load(() -> {
            String user = RelatedMods.modrinthUser(hit.author);
            if (user == null) {
                user = RelatedMods.modrinthUser(paced(() -> ModrinthApi.getProjectOwner(hit.projectId)));
            }
            if (user == null) {
                return () -> author.say(GetText.tr("Modrinth didn't say who made this."));
            }
            String name = user;
            List<ModrinthProject> projects = paced(() -> ModrinthApi.getUserProjects(name));
            if (projects == null) {
                return () -> author.say(GetText.tr("Modrinth couldn't be reached. Try again later."));
            }
            List<ModrinthProject> others = projects.stream()
                    .filter(p -> p != null && !hit.projectId.equals(p.id))
                    .sorted(Comparator.comparingInt((ModrinthProject p) -> p.downloads).reversed()).limit(LIMIT)
                    .collect(Collectors.toList());
            return () -> {
                // #. {0} is a Modrinth username
                author.note(GetText.tr("{0}'s other projects on Modrinth, most downloaded first. Projects owned by "
                        + "an organization they're in aren't included.", name));
                showModrinthProjects(author, others);
            };
        });

        String query = RelatedMods.addonQuery(hit.title);
        addons.load(() -> {
            ModrinthSearchResult result = paced(() -> ModrinthApi.searchModrinth(
                    Collections.singletonList(RelatedMods.minecraftVersion(target)), query, 0, LIMIT, "relevance",
                    Collections.singletonList(RelatedMods.modrinthSearchLoaders(target)), ModrinthProjectType.MOD));
            if (result == null || result.hits == null) {
                return () -> addons.say(GetText.tr("Modrinth couldn't be reached. Try again later."));
            }
            List<ModrinthSearchHit> hits = result.hits.stream()
                    .filter(h -> h != null && !hit.projectId.equals(h.projectId)).collect(Collectors.toList());
            return () -> {
                addons.note(addonNote("Modrinth", query));
                ResultsView view = App.settings.addModsView();
                addons.show(hits.stream().map(h -> (JComponent) addMods.modrinthCard(h, "Mods", view, this))
                        .collect(Collectors.toList()));
            };
        });

        needs.load(() -> {
            List<com.atlauncher.data.modrinth.ModrinthVersion> versions = paced(() -> ModrinthApi
                    .getVersionsForLoaders(hit.projectId, RelatedMods.minecraftVersion(target),
                            RelatedMods.versionLoaders(target)));
            // a failed request isn't proof there's no file here
            if (versions == null) {
                return () -> needs.say(GetText.tr("Modrinth couldn't be reached. Try again later."));
            }
            com.atlauncher.data.modrinth.ModrinthVersion version = RelatedMods.versionForDependencies(versions,
                    target);
            if (version == null) {
                return () -> needs.say(noFileMessage());
            }
            RelatedMods.Needs<String> ids = RelatedMods.modrinthNeeds(version, hit.projectId);
            if (ids.all().isEmpty()) {
                return () -> needs.say(GetText.tr("{0} depends on nothing else.", version.name));
            }
            List<ModrinthProject> found = paced(
                    () -> ModrinthApi.getProjects(ids.all().toArray(new String[0])));
            if (found == null) {
                return () -> needs.say(GetText.tr("Modrinth couldn't be reached. Try again later."));
            }
            Map<String, ModrinthProject> byId = new HashMap<>();
            found.stream().filter(p -> p != null && p.id != null).forEach(p -> byId.put(p.id, p));
            List<ModrinthProject> ordered = ids.all().stream().map(byId::get).filter(Objects::nonNull)
                    .collect(Collectors.toList());
            return () -> {
                needs.note(needsNote(version.name, names(ids.required, id -> byId.containsKey(id)
                        ? byId.get(id).title
                        : id), names(ids.optional, id -> byId.containsKey(id) ? byId.get(id).title : id)));
                showModrinthProjects(needs, ordered);
            };
        });
    }

    private void showModrinthProjects(Section section, List<ModrinthProject> projects) {
        List<JComponent> shown = new ArrayList<>();
        CollectionsDialog.render(section.results, projects, instanceOrServer, App.settings.addModsView(),
                (hit, sectionValue, view) -> {
                    JComponent card = addMods.modrinthCard(hit, sectionValue, view, this);
                    shown.add(card);
                    return card;
                });
        section.show(shown);
    }

    // --- CurseForge

    private void curseForge(CurseForgeProject project) {
        Section author = new Section(GetText.tr("Same Author"));
        Section addons = new Section(GetText.tr("Addons (Search)"));
        Section needs = new Section(GetText.tr("Dependencies"));

        CurseForgeAuthor who = project.authors == null || project.authors.isEmpty() ? null : project.authors.get(0);
        author.load(() -> {
            if (who == null) {
                return () -> author.say(GetText.tr("CurseForge didn't say who made this."));
            }
            List<CurseForgeProject> projects = CurseForgeApi.searchByAuthor(who.id,
                    Constants.CURSEFORGE_MODS_SECTION_ID, LIMIT + 1);
            if (projects == null) {
                return () -> author.say(GetText.tr("CurseForge couldn't be reached. Try again later."));
            }
            List<CurseForgeProject> others = without(RelatedMods.byCurseForgeAuthor(projects, who.id), project.id);
            return () -> {
                // #. {0} is a CurseForge author's name
                author.note(GetText.tr("{0}'s most popular mods on CurseForge.", who.name));
                showCurseForgeProjects(author, others);
            };
        });

        String query = RelatedMods.addonQuery(project.name);
        addons.load(() -> {
            CurseForgeCoreApiResponse<List<CurseForgeProject>> result = CurseForgeApi.searchCurseForge(
                    RelatedMods.minecraftVersion(target), Constants.CURSEFORGE_MODS_SECTION_ID, query, 0, LIMIT,
                    RelatedMods.curseForgeLoaderTypes(target), "Popularity", true, null);
            if (result == null || result.data == null) {
                return () -> addons.say(GetText.tr("CurseForge couldn't be reached. Try again later."));
            }
            List<CurseForgeProject> found = without(result.data, project.id);
            return () -> {
                addons.note(addonNote("CurseForge", query));
                showCurseForgeProjects(addons, found);
            };
        });

        needs.load(() -> {
            Integer fileId = PackMods.bestCurseForgeFileId(target, project.latestFilesIndexes);
            CurseForgeFile file = fileId == null ? null : CurseForgeApi.getFileForProject(project.id, fileId);
            if (file == null) {
                return () -> needs.say(fileId == null ? noFileMessage()
                        : GetText.tr("CurseForge couldn't be reached. Try again later."));
            }
            RelatedMods.Needs<Integer> ids = RelatedMods.curseForgeNeeds(file, project.id);
            if (ids.all().isEmpty()) {
                return () -> needs.say(GetText.tr("{0} depends on nothing else.", file.displayName));
            }
            List<CurseForgeProject> found = CurseForgeApi
                    .getProjects(ids.all().stream().mapToInt(Integer::intValue).toArray());
            if (found == null) {
                return () -> needs.say(GetText.tr("CurseForge couldn't be reached. Try again later."));
            }
            Map<Integer, CurseForgeProject> byId = new HashMap<>();
            found.stream().filter(Objects::nonNull).forEach(p -> byId.put(p.id, p));
            List<CurseForgeProject> ordered = ids.all().stream().map(byId::get).filter(Objects::nonNull)
                    .collect(Collectors.toList());
            return () -> {
                needs.note(needsNote(file.displayName,
                        names(ids.required, id -> byId.containsKey(id) ? byId.get(id).name : "#" + id),
                        names(ids.optional, id -> byId.containsKey(id) ? byId.get(id).name : "#" + id)));
                showCurseForgeProjects(needs, ordered);
            };
        });
    }

    private void showCurseForgeProjects(Section section, List<CurseForgeProject> projects) {
        ResultsView view = App.settings.addModsView();
        List<JComponent> shown = new ArrayList<>();
        for (CurseForgeProject found : projects) {
            String sectionValue = CollectionsLibrary.curseForgeSection(found);
            // only what Add Mods would offer here, as Collections shows them
            if (CollectionsLibrary.offers(sectionValue, instanceOrServer)) {
                shown.add(addMods.curseForgeCard(found, sectionValue, view, this));
            }
        }
        section.show(shown);
    }

    private static List<CurseForgeProject> without(List<CurseForgeProject> projects, int id) {
        return projects.stream().filter(p -> p != null && p.id != id).limit(LIMIT).collect(Collectors.toList());
    }

    // --- notes

    private String addonNote(String site, String query) {
        // #. {0} is the site (Modrinth/CurseForge), {1} the search text, {2} like "Minecraft 1.20.1 NeoForge"
        return GetText.tr("A search of {0} for \"{1}\" among mods for {2}. Neither site keeps a list of a mod's "
                + "addons, so these are only as good as the search.", site, query, RelatedMods.describe(target));
    }

    private String noFileMessage() {
        // #. {0} is like "Minecraft 1.20.1 NeoForge"
        return GetText.tr("There's no file of this for {0}, so there are no dependencies to show for it.",
                RelatedMods.describe(target));
    }

    private String needsNote(String file, String required, String optional) {
        // #. {0} is a file's name, {1} like "Minecraft 1.20.1 NeoForge"
        String note = GetText.tr("What {0} depends on: the file the selector would offer for {1}.", file,
                RelatedMods.describe(target));
        if (!required.isEmpty()) {
            note += " " + GetText.tr("Required: {0}.", required);
        }
        if (!optional.isEmpty()) {
            note += " " + GetText.tr("Optional: {0}.", optional);
        }
        return note;
    }

    private static <T> String names(List<T> ids, java.util.function.Function<T, String> name) {
        return ids.stream().map(name).collect(Collectors.joining(", "));
    }

    // Modrinth requests share Add Mods' pacing
    private static <T> T paced(Supplier<T> request) {
        try {
            ModCompatibilityChecker.waitForTurn();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return request.get();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
