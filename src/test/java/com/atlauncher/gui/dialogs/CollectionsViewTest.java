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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.atlauncher.constants.Constants;
import com.atlauncher.data.Instance;
import com.atlauncher.data.InstanceLauncher;
import com.atlauncher.data.ModPlatform;
import com.atlauncher.data.ResultsView;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.minecraft.MinecraftVersion;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthProjectType;
import com.atlauncher.gui.layouts.WrapLayout;
import com.atlauncher.utils.CollectionsLibrary;
import com.atlauncher.utils.CollectionsLibrary.Item;
import com.atlauncher.utils.CollectionsLibrary.LocalCollection;
import com.atlauncher.utils.CollectionsLibrary.Resolved;

/**
 * A collection's projects are shown as Add Mods shows its results: the same cards from the same factories, in the same
 * layouts, each project through its own provider's card whichever Add Mods tab is showing.
 */
public class CollectionsViewTest {
    private static List<JComponent> cards(int count) {
        List<JComponent> cards = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            cards.add(new JLabel("card " + i));
        }
        return cards;
    }

    @Test
    public void theResultsAreNeverNarrowerThanRowsAreCheckedAt() {
        // ResultRowsTest lays rows out at 440; the results lose up to 20 to the scroll pane's bar and border
        assertTrue(CollectionsDialog.RESULTS_MIN_WIDTH - 20 >= 440);
    }

    @Test
    public void cardsWrapAsAddModsShowsThem() {
        JPanel panel = new JPanel();
        panel.add(new JLabel("old"));
        List<JComponent> cards = cards(3);

        AddModsDialog.layOutResults(panel, cards, ResultsView.CARDS);
        assertTrue(panel.getLayout() instanceof WrapLayout);
        assertEquals(3, panel.getComponentCount());
        for (int i = 0; i < 3; i++) {
            assertSame(cards.get(i), panel.getComponent(i));
        }
    }

    @Test
    public void bothListsFillTheWidthStackedFromTheTop() {
        for (ResultsView view : new ResultsView[] { ResultsView.COMPACT_LIST, ResultsView.LIST }) {
            listRowsFillTheWidthStackedFromTheTop(view);
        }
    }

    private void listRowsFillTheWidthStackedFromTheTop(ResultsView view) {
        JPanel panel = new JPanel();
        List<JComponent> cards = cards(3);

        AddModsDialog.layOutResults(panel, cards, view);
        assertTrue(panel.getLayout() instanceof GridBagLayout);
        GridBagLayout layout = (GridBagLayout) panel.getLayout();
        // the rows, then the filler below them
        assertEquals(4, panel.getComponentCount());
        for (int i = 0; i < 3; i++) {
            GridBagConstraints row = layout.getConstraints(panel.getComponent(i));
            assertEquals(i, row.gridy);
            assertEquals(GridBagConstraints.HORIZONTAL, row.fill);
            assertEquals(GridBagConstraints.NORTH, row.anchor);
            assertEquals(1.0, row.weightx);
            assertEquals(0.0, row.weighty);
        }
        assertEquals(1.0, layout.getConstraints(panel.getComponent(3)).weighty);
    }

    private static ModrinthProject project(String id, ModrinthProjectType type, String... loaders) {
        ModrinthProject project = new ModrinthProject();
        project.id = id;
        project.title = "Title " + id;
        project.projectType = type;
        project.categories = new ArrayList<>(Arrays.asList("optimization"));
        project.loaders = new ArrayList<>(Arrays.asList(loaders));
        return project;
    }

    private static Instance fabric() {
        MinecraftVersion version = new MinecraftVersion();
        version.id = "1.20.1";
        Instance instance = new Instance(version);
        instance.launcher = new InstanceLauncher();
        instance.launcher.loaderVersion = new LoaderVersion("0.16.9", false, "Fabric");
        return instance;
    }

    @Test
    public void aCollectionsProjectsComeFromAddModsCardsInTheViewChosen() {
        List<ModrinthProject> projects = Arrays.asList(project("A", ModrinthProjectType.MOD, "fabric"),
            project("B", ModrinthProjectType.MODPACK), project("C", ModrinthProjectType.DATAPACK, "datapack"),
            project("D", ModrinthProjectType.PLUGIN, "paper"), project("E", ModrinthProjectType.SHADER, "iris"));

        for (ResultsView listView : ResultsView.values()) {
            List<String> made = new ArrayList<>();
            JPanel results = new JPanel();
            List<String> left = CollectionsDialog.render(results, projects, fabric(), listView,
                (hit, section, list) -> {
                    made.add(hit.projectId + " " + section + " " + list);
                    // the loaders go with the categories, as in search results, for the loader icons
                    if (hit.projectId.equals("A")) {
                        assertTrue(hit.categories.contains("fabric"));
                    }
                    return new JLabel(hit.title);
                });

            // in the collection's order, each in the section of its type, in the same view
            assertEquals(Arrays.asList("A Mods " + listView, "C Data Packs " + listView, "E Shaders " + listView),
                made);
            assertEquals(Arrays.asList("Title B (Modpack)", "Title D (Plugin)"), left);
            assertTrue(listView.isRow() ? results.getLayout() instanceof GridBagLayout
                : results.getLayout() instanceof WrapLayout);
            Component first = results.getComponent(0);
            assertEquals("Title A", ((JLabel) first).getText());
        }
    }

    private static CurseForgeProject curseForgeProject(int id, int classId) {
        CurseForgeProject project = new CurseForgeProject();
        project.id = id;
        project.name = "CurseForge " + id;
        project.classId = classId;
        project.categories = new ArrayList<>();
        return project;
    }

    @Test
    public void aLocalCollectionsProjectsGoToTheirOwnProvidersCards() {
        Item jei = new Item(ModPlatform.CURSEFORGE, "238222", "Mods", "JEI");
        Item sodium = new Item(ModPlatform.MODRINTH, "AANobbMI", "Mods", "Sodium");
        CollectionsLibrary.Providers providers = new CollectionsLibrary.Providers() {
            @Override
            public List<ModrinthProject> modrinth(List<String> ids) {
                return Arrays.asList(project("AANobbMI", ModrinthProjectType.MOD, "fabric"));
            }

            @Override
            public List<CurseForgeProject> curseForge(List<Integer> ids) {
                return Arrays.asList(curseForgeProject(238222, Constants.CURSEFORGE_MODS_SECTION_ID),
                    curseForgeProject(1, Constants.CURSEFORGE_MODPACKS_SECTION_ID),
                    curseForgeProject(2, Constants.CURSEFORGE_PLUGINS_SECTION_ID));
            }
        };
        List<Resolved> found = CollectionsLibrary.resolve(Arrays.asList(jei, sodium,
            new Item(ModPlatform.CURSEFORGE, "1", null, null), new Item(ModPlatform.CURSEFORGE, "2", null, null)),
            providers).found;

        List<String> made = new ArrayList<>();
        JPanel results = new JPanel();
        List<String> left = CollectionsDialog.renderLocal(results, found, fabric(), ResultsView.LIST,
            (hit, section, view) -> {
                made.add("Modrinth " + hit.projectId + " " + section + " " + view);
                return new JLabel(hit.title);
            }, (project, section, view) -> {
                made.add("CurseForge " + project.id + " " + section + " " + view);
                return new JLabel(project.name);
            });

        // by the provider each was recorded with, in the collection's order
        assertEquals(Arrays.asList("CurseForge 238222 Mods LIST", "Modrinth AANobbMI Mods LIST"), made);
        assertEquals(Arrays.asList("CurseForge 1 (CurseForge)", "CurseForge 2 (CurseForge)"), left);
        assertEquals(3, results.getComponentCount());
    }

    @Test
    public void aLocalCollectionsProjectIsShownInTheSectionItWasAddedFrom() {
        CollectionsLibrary.Providers providers = new CollectionsLibrary.Providers() {
            @Override
            public List<ModrinthProject> modrinth(List<String> ids) {
                return Arrays.asList(project("DataPck1", ModrinthProjectType.MOD, "fabric", "datapack"),
                    project("Plugin01", ModrinthProjectType.MOD, "fabric", "paper"));
            }

            @Override
            public List<CurseForgeProject> curseForge(List<Integer> ids) {
                return Arrays.asList(curseForgeProject(5, Constants.CURSEFORGE_RESOURCE_PACKS_SECTION_ID),
                    curseForgeProject(1, Constants.CURSEFORGE_MODPACKS_SECTION_ID));
            }
        };
        // a Modrinth mod added as a data pack; one added as a plugin, which this instance has no section for; and a
        // modpack, which is never offered whatever it was recorded as
        List<Resolved> found = CollectionsLibrary.resolve(Arrays.asList(
            new Item(ModPlatform.MODRINTH, "DataPck1", "Data Packs", null),
            new Item(ModPlatform.MODRINTH, "Plugin01", "Plugins", null),
            new Item(ModPlatform.CURSEFORGE, "5", "Resource Packs", null),
            new Item(ModPlatform.CURSEFORGE, "1", "Mods", null)), providers).found;

        List<String> made = new ArrayList<>();
        List<String> left = CollectionsDialog.renderLocal(new JPanel(), found, fabric(), ResultsView.CARDS,
            (hit, section, view) -> {
                made.add(hit.projectId + " " + section);
                return new JLabel(hit.title);
            }, (project, section, view) -> {
                made.add(project.id + " " + section);
                return new JLabel(project.name);
            });

        assertEquals(Arrays.asList("DataPck1 Data Packs", "Plugin01 Mods", "5 Resource Packs"), made);
        assertEquals(Collections.singletonList("CurseForge 1 (CurseForge)"), left);
    }

    // --- a result's right-click menu

    @TempDir
    Path temp;

    private static List<String> labels(JPopupMenu menu) {
        List<String> labels = new ArrayList<>();
        for (Component component : menu.getComponents()) {
            labels.add(component instanceof JMenuItem ? ((JMenuItem) component).getText() : "-");
        }
        return labels;
    }

    private static JPopupMenu open(JComponent card) {
        JPopupMenu menu = card.getComponentPopupMenu();
        for (PopupMenuListener listener : menu.getPopupMenuListeners()) {
            listener.popupMenuWillBecomeVisible(new PopupMenuEvent(menu));
        }
        return menu;
    }

    @Test
    public void aResultsMenuKeepsRelatedAndAddsItToFavoritesAndCollections() throws IOException {
        CollectionsLibrary library = new CollectionsLibrary(temp.resolve("collections.json"), null);
        LocalCollection building = library.createCollection("Building");
        JPanel card = new JPanel();
        JButton add = new JButton("Add");
        card.add(add);
        AtomicInteger related = new AtomicInteger();
        AtomicInteger changed = new AtomicInteger();
        Item sodium = new Item(ModPlatform.MODRINTH, "AANobbMI", "Mods", "Sodium");

        CollectionsDialog.offerMenu(card, () -> library, sodium, null, related::incrementAndGet,
            changed::incrementAndGet);
        // wherever on it the click lands
        assertTrue(add.getInheritsPopupMenu());
        JPopupMenu menu = open(card);
        assertEquals(Arrays.asList("Related...", "-", "Add to Favorites", "Add to Collection"), labels(menu));
        JMenu addTo = (JMenu) menu.getComponent(3);
        assertEquals(Arrays.asList("Building", "-", "New Collection..."), labels(addTo.getPopupMenu()));

        ((JMenuItem) menu.getComponent(0)).doClick(0);
        assertEquals(1, related.get());
        ((JMenuItem) menu.getComponent(2)).doClick(0);
        ((JMenuItem) addTo.getMenuComponent(0)).doClick(0);
        assertTrue(library.isFavorite(ModPlatform.MODRINTH, "AANobbMI"));
        assertTrue(library.contains(building.id, ModPlatform.MODRINTH, "AANobbMI"));
        assertEquals("Mods", building.items.get(0).section);
        assertEquals(2, changed.get());

        // made afresh each time it opens
        assertEquals("Remove from Favorites", labels(open(card)).get(2));
        ((JMenuItem) card.getComponentPopupMenu().getComponent(2)).doClick(0);
        assertFalse(library.isFavorite(ModPlatform.MODRINTH, "AANobbMI"));

        // no Related where it isn't offered
        JPanel other = new JPanel();
        CollectionsDialog.offerMenu(other, () -> library, sodium, null, null, () -> {
        });
        assertEquals(Arrays.asList("Add to Favorites", "Add to Collection"), labels(open(other)));
    }

    @Test
    public void aResultInTheCollectionOpenCanBeRemovedFromIt() throws IOException {
        CollectionsLibrary library = new CollectionsLibrary(temp.resolve("collections.json"), null);
        LocalCollection building = library.createCollection("Building");
        Item jei = new Item(ModPlatform.CURSEFORGE, "238222", "Mods", "JEI");
        library.addTo(building.id, jei);
        library.addFavorite(jei);
        AtomicInteger changed = new AtomicInteger();

        JPopupMenu menu = new JPopupMenu();
        CollectionsDialog.fillMenu(menu, library, jei, null, building, changed::incrementAndGet);
        assertEquals(Arrays.asList("Remove from Favorites", "Add to Collection", "-", "Remove from this collection"),
            labels(menu));
        ((JMenuItem) menu.getComponent(3)).doClick(0);
        assertFalse(library.contains(building.id, ModPlatform.CURSEFORGE, "238222"));
        assertEquals(1, changed.get());

        // in Favorites, removing it from this collection is removing it from Favorites, offered once
        menu = new JPopupMenu();
        CollectionsDialog.fillMenu(menu, library, jei, null, library.favorites(), () -> {
        });
        assertEquals(Arrays.asList("Add to Collection", "-", "Remove from this collection"), labels(menu));
        ((JMenuItem) menu.getComponent(2)).doClick(0);
        assertFalse(library.isFavorite(ModPlatform.CURSEFORGE, "238222"));
    }
}
