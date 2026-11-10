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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicHTML;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.atlauncher.App;
import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.Instance;
import com.atlauncher.data.InstanceLauncher;
import com.atlauncher.data.LoaderIndicators;
import com.atlauncher.data.ResultsView;
import com.atlauncher.data.Settings;
import com.atlauncher.data.curseforge.CurseForgeAuthor;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.minecraft.MinecraftVersion;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthCollection;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthProjectType;
import com.atlauncher.data.modrinth.ModrinthSearchHit;
import com.atlauncher.data.modrinth.ModrinthVersion;
import com.atlauncher.gui.card.CurseForgeProjectCard;
import com.atlauncher.gui.card.ModrinthSearchHitCard;
import com.atlauncher.themes.ATLauncherLaf;
import com.atlauncher.themes.Dark;
import com.atlauncher.utils.ModrinthCollections;

/**
 * Builds a full 200-result Add Mods page of both providers' cards in every view, as Add Mods and Collections lay them
 * out, from fixture data with no network. AddModsDialog and CollectionsDialog themselves aren't built: their
 * constructors fetch results, categories and analytics straight away.
 */
public class AddModsResultsSmokeTest {
    private static final int PAGE = 200;
    // the results' width in Add Mods at its 800px minimum, less the scroll bar
    private static final int WIDTH = 770;

    private LookAndFeel previousLaf;
    private ATLauncherLaf previousTheme;
    private Settings previousSettings;

    @BeforeEach
    public void setUp() throws Exception {
        previousLaf = UIManager.getLookAndFeel();
        previousTheme = App.THEME;
        previousSettings = App.settings;
        App.settings = new Settings();
        Dark.install();
        App.THEME = Dark.getInstance();
    }

    @AfterEach
    public void tearDown() throws Exception {
        UIManager.setLookAndFeel(previousLaf);
        App.THEME = previousTheme;
        App.settings = previousSettings;
    }

    private static Instance forge() {
        MinecraftVersion version = new MinecraftVersion();
        version.id = "1.20.1";
        Instance instance = new Instance(version);
        instance.launcher = new InstanceLauncher();
        instance.launcher.loaderVersion = new LoaderVersion("47.3.0", false, "Forge");
        instance.launcher.mods = new ArrayList<>();
        return instance;
    }

    private static ModrinthSearchHit hit(int i) {
        ModrinthSearchHit hit = new ModrinthSearchHit();
        hit.projectId = "P" + (1000 + i);
        hit.slug = "project-" + i;
        hit.title = "Modrinth Project " + i;
        hit.author = "author" + i;
        hit.description = "A description long enough to wrap over two lines in the comfortable list view, number " + i;
        hit.downloads = 1000 * i;
        hit.categories = Arrays.asList("optimization", i % 2 == 0 ? "forge" : "fabric", "neoforge");
        hit.versions = Collections.singletonList("1.20.1");
        return hit;
    }

    private static CurseForgeProject project(int i) {
        CurseForgeProject project = new CurseForgeProject();
        project.id = 100000 + i;
        project.name = "CurseForge Project " + i;
        project.summary = "Summary " + i;
        project.downloadCount = 5000 * i;
        project.categories = new ArrayList<>();
        project.links = new HashMap<>();
        CurseForgeAuthor author = new CurseForgeAuthor();
        author.name = "author" + i;
        project.authors = Collections.singletonList(author);
        return project;
    }

    private static void layOut(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                layOut((Container) child);
            }
        }
    }

    private static JPanel page(List<? extends JComponent> cards, ResultsView view) {
        JPanel results = new JPanel();
        AddModsDialog.layOutResults(results, cards, view);
        results.setSize(WIDTH, results.getPreferredSize().height);
        layOut(results);
        return results;
    }

    @Test
    public void aFullPageOfEitherProviderBuildsAndLaysOutInEveryView() {
        Instance instance = forge();
        for (ResultsView view : ResultsView.values()) {
            long start = System.nanoTime();
            List<JComponent> modrinth = new ArrayList<>();
            List<JComponent> curseForge = new ArrayList<>();
            for (int i = 0; i < PAGE; i++) {
                ModrinthSearchHit hit = hit(i);
                modrinth.add(new ModrinthSearchHitCard(hit, instance, e -> {
                }, e -> {
                }, view, LoaderIndicators.forSection(LoaderIndicators.fromModrinth(hit), "Mods")));
                curseForge.add(new CurseForgeProjectCard(project(i), instance, e -> {
                }, e -> {
                }, view, Collections.emptySet()));
            }
            JPanel modrinthPage = page(modrinth, view);
            JPanel curseForgePage = page(curseForge, view);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            System.out.println("Built and laid out " + 2 * PAGE + " " + view + " results in " + elapsedMs + " ms");

            // rows end with the filler that keeps them at the top
            int expected = view.isRow() ? PAGE + 1 : PAGE;
            assertEquals(expected, modrinthPage.getComponentCount(), view.toString());
            assertEquals(expected, curseForgePage.getComponentCount(), view.toString());
            assertTrue(modrinthPage.getComponent(0).getWidth() > 0, view.toString());
        }
    }

    @Test
    public void aCollectionShowsBothProvidersCardsInEveryView() {
        Instance instance = forge();
        List<ModrinthProject> projects = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ModrinthProject project = new ModrinthProject();
            project.id = "P" + (1000 + i);
            project.slug = "project-" + i;
            project.title = "Project " + i;
            project.projectType = i % 5 == 0 ? ModrinthProjectType.MODPACK : ModrinthProjectType.MOD;
            project.loaders = Arrays.asList("forge");
            projects.add(project);
        }

        for (ResultsView view : ResultsView.values()) {
            JPanel results = new JPanel();
            List<String> left = CollectionsDialog.render(results, projects, instance, view,
                (hit, section, rowView) -> new ModrinthSearchHitCard(hit, instance, e -> {
                }, e -> {
                }, rowView, LoaderIndicators.forSection(LoaderIndicators.fromModrinth(hit), section)));
            results.setSize(CollectionsDialog.RESULTS_MIN_WIDTH, results.getPreferredSize().height);
            layOut(results);

            assertEquals(4, left.size(), view.toString());
            assertEquals(view.isRow() ? 17 : 16, results.getComponentCount(), view.toString());
        }
    }

    // the search row's controls as Add Mods has them, with CurseForge's longest mod category loaded
    private static List<Component> searchControls() {
        JComboBox<String> categories = new JComboBox<>(new String[] { "All Categories", "Armor, Tools, and Weapons" });
        return Arrays.asList(new JComboBox<>(new String[] { "CurseForge", "Modrinth" }), Box.createHorizontalStrut(5),
            new JComboBox<>(new String[] { "Mods", "Data Packs", "Resource Packs", "Shaders", "Worlds" }),
            Box.createHorizontalStrut(5),
            new JComboBox<>(new String[] { "Popularity", "Last Updated", "Total Downloads" }),
            Box.createHorizontalStrut(5), categories, Box.createHorizontalStrut(20), new JTextField(16),
            Box.createHorizontalStrut(5), new JButton("Collections..."), Box.createHorizontalStrut(5),
            new JButton("From Modpack..."));
    }

    @Test
    public void theSearchRowKeepsEveryControlWholeAtTheDialogsDefaultWidth() {
        // the dialog opens at 800, less its insets and the row's 10px sides
        int width = 764;

        // as it was: one squeezed line
        JPanel squeezed = new JPanel();
        squeezed.setLayout(new BoxLayout(squeezed, BoxLayout.X_AXIS));
        searchControls().forEach(squeezed::add);
        squeezed.setSize(width, squeezed.getPreferredSize().height);
        squeezed.doLayout();
        Component lastButton = squeezed.getComponent(squeezed.getComponentCount() - 1);
        assertTrue(lastButton.getX() + lastButton.getWidth() > width, "the old row fitted after all");

        JPanel row = AddModsDialog.searchRow();
        List<Component> controls = searchControls();
        controls.forEach(row::add);
        row.setSize(width, 1);
        row.setSize(width, row.getPreferredSize().height);
        row.doLayout();
        for (Component control : controls) {
            assertTrue(control.getX() >= 0 && control.getX() + control.getWidth() <= width, control.toString());
            assertTrue(control.getY() + control.getHeight() <= row.getHeight(), control.toString());
            assertEquals(control.getPreferredSize().width, control.getWidth(), control.toString());
        }
        // the buttons go to a second line rather than squeezing the search field
        assertTrue(controls.get(12).getY() > controls.get(0).getY());
    }

    @Test
    public void aCreatorsCollectionNamesAndDescriptionsAreNeverTakenAsHtml() {
        ModrinthCollection collection = new ModrinthCollection();
        collection.id = "AbCd1234";
        collection.name = "<html><b>Mine</b>";
        collection.description = "<html><b>About</b>";

        JComponent shown = (JComponent) CollectionsDialog.creatorRenderer()
            .getListCellRendererComponent(new JList<>(), collection, 0, false, false);
        assertNull(shown.getClientProperty(BasicHTML.propertyKey));
        assertFalse(shown.getToolTipText().contains("<b>"), shown.getToolTipText());

        ModrinthCollections.Saved saved = new ModrinthCollections.Saved();
        saved.id = "AbCd1234";
        saved.name = "<html><b>Saved</b>";
        JComponent entry = (JComponent) CollectionsDialog.libraryRenderer()
            .getListCellRendererComponent(new JList<>(), new CollectionsDialog.Entry(null, saved), 0, false, false);
        assertNull(entry.getClientProperty(BasicHTML.propertyKey));
    }

    @Test
    public void aResultShowsAnInstallMadeElsewhere() {
        // such as a dependency of another result, or one added from Collections or Related
        Instance instance = forge();
        ModrinthSearchHitCard modrinth = new ModrinthSearchHitCard(hit(1), instance, e -> {
        }, e -> {
        }, ResultsView.LIST, Collections.emptySet());
        CurseForgeProjectCard curseForge = new CurseForgeProjectCard(project(1), instance, e -> {
        }, e -> {
        }, ResultsView.CARDS, Collections.emptySet());
        assertTrue(button(modrinth, "Add").isVisible());
        assertTrue(button(curseForge, "Add").isVisible());

        DisableableMod fromModrinth = new DisableableMod();
        fromModrinth.modrinthProject = new ModrinthProject();
        fromModrinth.modrinthProject.id = hit(1).projectId;
        fromModrinth.modrinthVersion = new ModrinthVersion();
        instance.launcher.mods.add(fromModrinth);
        DisableableMod fromCurseForge = new DisableableMod();
        fromCurseForge.curseForgeProjectId = project(1).id;
        fromCurseForge.curseForgeFileId = 1;
        instance.launcher.mods.add(fromCurseForge);

        modrinth.updateInstalledStatus();
        curseForge.updateInstalledStatus();
        for (JComponent card : Arrays.asList(modrinth, curseForge)) {
            assertFalse(button(card, "Add").isVisible());
            assertTrue(button(card, "Remove").isVisible());
        }
    }

    private static JButton button(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton && text.equals(((JButton) child).getText())) {
                return (JButton) child;
            }
            if (child instanceof Container) {
                JButton found = button((Container) child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
