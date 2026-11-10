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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;

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
}
