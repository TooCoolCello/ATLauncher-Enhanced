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
package com.atlauncher.gui.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.FontMetrics;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.atlauncher.App;
import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.Instance;
import com.atlauncher.data.InstanceLauncher;
import com.atlauncher.data.ResultsView;
import com.atlauncher.data.Settings;
import com.atlauncher.data.curseforge.CurseForgeAuthor;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.minecraft.MinecraftVersion;
import com.atlauncher.data.minecraft.loaders.LoaderType;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthSearchHit;
import com.atlauncher.data.modrinth.ModrinthVersion;
import com.atlauncher.themes.ATLauncherLaf;
import com.atlauncher.themes.Dark;

/**
 * The Compact List and List rows Add Mods and Collections share for both providers: one height per view whatever the
 * metadata or installed state, the first line's columns and actions lined up from row to row, unknown metadata left
 * out, and everything inside the row down to the width Collections gives its results at its minimum size.
 */
public class ResultRowsTest {
    // narrower than the results in Collections at its minimum size
    private static final int NARROW = 440;
    private static final ResultsView[] ROWS = { ResultsView.COMPACT_LIST, ResultsView.LIST };
    private static final String DESCRIPTION = "A modern rendering engine that greatly improves frame rates and "
        + "stuttering, while fixing many graphical issues in Minecraft, and then goes on for a good while longer.";

    private LookAndFeel previousLaf;
    private ATLauncherLaf previousTheme;
    private Settings previousSettings;

    @BeforeEach
    public void setUp() throws Exception {
        previousLaf = UIManager.getLookAndFeel();
        previousTheme = App.THEME;
        previousSettings = App.settings;
        // in memory only, for the theme's fonts
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

    private static void layOut(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                layOut((Container) child);
            }
        }
    }

    private static <T extends Component> List<T> all(Container container, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof Container) {
                found.addAll(all((Container) child, type));
            }
        }
        return found;
    }

    private static JLabel find(Container container, Predicate<JLabel> test) {
        return all(container, JLabel.class).stream().filter(test).findFirst().orElse(null);
    }

    private static JLabel withText(Container container, String text) {
        return find(container, label -> text.equals(label.getText()));
    }

    private static JLabel withTip(Container container, String prefix) {
        return find(container, label -> label.getToolTipText() != null && label.getToolTipText().startsWith(prefix));
    }

    private static Rectangle bounds(Component component, Container within) {
        return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), within);
    }

    private static Instance instance(boolean installed) {
        MinecraftVersion version = new MinecraftVersion();
        version.id = "1.20.1";
        Instance instance = new Instance(version);
        instance.launcher = new InstanceLauncher();
        instance.launcher.mods = new ArrayList<>();
        if (installed) {
            DisableableMod modrinth = new DisableableMod();
            modrinth.modrinthProject = new ModrinthProject();
            modrinth.modrinthProject.id = "P1";
            modrinth.modrinthVersion = new ModrinthVersion();
            instance.launcher.mods.add(modrinth);
            DisableableMod curseForge = new DisableableMod();
            curseForge.curseForgeProjectId = 238222;
            curseForge.curseForgeFileId = 1;
            instance.launcher.mods.add(curseForge);
        }
        return instance;
    }

    private static <T extends JPanel> T sized(T card, int width) {
        card.setSize(width, card.getPreferredSize().height);
        layOut(card);
        return card;
    }

    private static ModrinthSearchHitCard modrinth(boolean installed, ResultsView view, int width, String title,
            String author, int downloads, String description, List<String> clicks) {
        ModrinthSearchHit hit = new ModrinthSearchHit();
        hit.projectId = "P1";
        hit.slug = "sodium";
        hit.title = title;
        hit.author = author;
        hit.description = description;
        hit.downloads = downloads;
        return sized(new ModrinthSearchHitCard(hit, instance(installed), e -> clicks.add("install"),
            e -> clicks.add("remove"), view, Collections.emptySet()), width);
    }

    private static ModrinthSearchHitCard modrinth(boolean installed, ResultsView view, int width) {
        return modrinth(installed, view, width, "Sodium", "jellysquid3", 91234567, DESCRIPTION, new ArrayList<>());
    }

    private static CurseForgeProjectCard curseForge(boolean installed, ResultsView view, int width) {
        CurseForgeProject project = new CurseForgeProject();
        project.id = 238222;
        project.name = "Just Enough Items (JEI) with a rather long name that goes on and on";
        project.summary = "View Items and Recipes";
        project.downloadCount = 412345678;
        project.categories = new ArrayList<>();
        project.links = new HashMap<>();
        CurseForgeAuthor author = new CurseForgeAuthor();
        author.name = "mezz";
        project.authors = Collections.singletonList(author);
        return sized(new CurseForgeProjectCard(project, instance(installed), e -> {
        }, e -> {
        }, view, Collections.emptySet()), width);
    }

    private static final List<String> NAMES = Arrays.asList("Sodium", "No Metadata", "Short",
        "Just Enough Items (JEI) with a rather long name that goes on and on");

    private static List<JPanel> variety(ResultsView view, int width) {
        return Arrays.asList(modrinth(false, view, width), modrinth(true, view, width),
            curseForge(false, view, width), curseForge(true, view, width),
            modrinth(false, view, width, "No Metadata", null, 0, null, new ArrayList<>()),
            modrinth(true, view, width, "Short", "a", 5, "Short.", new ArrayList<>()));
    }

    private static List<JButton> buttons(Container container) {
        return all(container, JButton.class);
    }

    private static List<ResultRow.WrappedText> descriptions(Container card) {
        return all(card, ResultRow.WrappedText.class).stream().filter(text -> text.lines() > 1)
            .collect(Collectors.toList());
    }

    private static List<String> texts(List<JButton> buttons) {
        return buttons.stream().map(JButton::getText).collect(Collectors.toList());
    }

    @Test
    public void everyRowInAViewIsOneHeightWhateverItsStateOrMetadata() {
        for (ResultsView view : ROWS) {
            for (int width : new int[] { 900, NARROW }) {
                List<JPanel> rows = variety(view, width);
                for (JPanel row : rows) {
                    assertEquals(rows.get(0).getPreferredSize().height, row.getPreferredSize().height,
                        view + " " + width);
                }
            }
        }
        assertTrue(modrinth(false, ResultsView.LIST, 900).getPreferredSize().height
            > modrinth(false, ResultsView.COMPACT_LIST, 900).getPreferredSize().height);
    }

    @Test
    public void theFirstLineIsNameAuthorDownloadsThenActionsAndListAddsTheDescriptionBelow() {
        for (ResultsView view : ROWS) {
            ModrinthSearchHitCard card = modrinth(false, view, 900);
            Rectangle name = bounds(withText(card, "Sodium"), card);
            Rectangle author = bounds(withText(card, "jellysquid3"), card);
            Rectangle downloads = bounds(withText(card, "91.2M"), card);
            Rectangle add = bounds(buttons(card).get(0), card);
            Rectangle viewButton = bounds(buttons(card).get(1), card);

            // left to right, on one line
            assertTrue(name.x + name.width <= author.x, view.toString());
            assertTrue(author.x + author.width <= downloads.x, view.toString());
            assertTrue(downloads.x + downloads.width <= add.x, view.toString());
            for (Rectangle part : Arrays.asList(author, downloads, add)) {
                assertTrue(part.y < name.y + name.height && name.y < part.y + part.height, view.toString());
            }
            // the actions at the far right
            assertTrue(card.getWidth() - (viewButton.x + viewButton.width) <= 16, view.toString());

            List<ResultRow.WrappedText> description = descriptions(card);
            if (view == ResultsView.COMPACT_LIST) {
                assertTrue(description.isEmpty());
                continue;
            }
            Rectangle below = bounds(description.get(0), card);
            assertTrue(below.y >= name.y + name.height);
            assertEquals(name.x, below.x);
            FontMetrics metrics = description.get(0).getFontMetrics(description.get(0).getFont());
            assertEquals(ResultRow.DESCRIPTION_LINES * metrics.getHeight(), below.height);
        }
    }

    @Test
    public void fullAuthorDownloadsAndDescriptionAreInTooltips() {
        ModrinthSearchHitCard card = modrinth(false, ResultsView.LIST, NARROW);

        // the author too, as there's no room to show it at this width
        assertEquals("Sodium by jellysquid3", withText(card, "Sodium").getToolTipText());
        assertEquals(0, withText(card, "jellysquid3").getWidth());
        assertEquals("Author: jellysquid3", withText(card, "jellysquid3").getToolTipText());
        assertEquals("91,234,567 downloads", withText(card, "91.2M").getToolTipText());
        assertEquals("91,234,567 downloads", withText(card, "91.2M").getAccessibleContext().getAccessibleName());
        assertEquals(DESCRIPTION, descriptions(card).get(0).getToolTipText());
    }

    @Test
    public void unknownAuthorAndDownloadsAreLeftOutNotShownEmpty() {
        for (ResultsView view : ROWS) {
            ModrinthSearchHitCard card = modrinth(false, view, 900, "No Metadata", null, 0, null, new ArrayList<>());
            ModrinthSearchHitCard full = modrinth(false, view, 900);

            assertNull(withTip(card, "Author"), view.toString());
            assertNull(withTip(card, "0 downloads"), view.toString());
            // no blank label says anything, and no person or download icon is shown on its own
            for (JLabel label : all(card, JLabel.class)) {
                if (label.getText() == null || label.getText().isEmpty()) {
                    assertNull(label.getToolTipText(), view.toString());
                }
            }
            assertEquals(all(full, JLabel.class).stream().filter(label -> label.getIcon() != null).count() - 2,
                all(card, JLabel.class).stream().filter(label -> label.getIcon() != null).count(), view.toString());
            assertTrue(descriptions(card).stream().allMatch(text -> text.getToolTipText() == null));

            // the name gets the author's share, and the actions stay where every other row has them
            assertTrue(withText(card, "No Metadata").getParent().getWidth()
                > withText(full, "Sodium").getParent().getWidth());
            assertEquals(bounds(buttons(full).get(0), full), bounds(buttons(card).get(0), card));
        }
    }

    @Test
    public void columnsAndActionsLineUpFromRowToRow() {
        for (ResultsView view : ROWS) {
            for (int width : new int[] { 900, NARROW }) {
                ModrinthSearchHitCard added = modrinth(false, view, width);
                ModrinthSearchHitCard installed = modrinth(true, view, width);
                ModrinthSearchHitCard other = modrinth(false, view, width,
                    "A Far Longer Project Name Than Sodium", "someone_else_entirely", 12, DESCRIPTION,
                    new ArrayList<>());
                CurseForgeProjectCard curseForge = curseForge(true, view, width);
                String at = view + " " + width;

                int authorX = bounds(withText(added, "jellysquid3"), added).x;
                assertEquals(authorX, bounds(withText(installed, "jellysquid3"), installed).x, at);
                assertEquals(authorX, bounds(withText(other, "someone_else_entirely"), other).x, at);
                assertEquals(authorX, bounds(withText(curseForge, "mezz"), curseForge).x, at);

                int downloadsX = bounds(withText(added, "91.2M"), added).x;
                assertEquals(downloadsX, bounds(withText(installed, "91.2M"), installed).x, at);
                assertEquals(downloadsX, bounds(withText(other, "12"), other).x, at);
                assertEquals(downloadsX, bounds(withText(curseForge, "412M"), curseForge).x, at);

                Rectangle view1 = bounds(buttons(added).get(1), added);
                assertEquals(view1, bounds(buttons(installed).get(2), installed), at);
                assertEquals(view1, bounds(buttons(curseForge).get(2), curseForge), at);
            }
        }
    }

    @Test
    public void everythingStaysInsideTheRowWithoutOverlapAtNormalAndNarrowWidths() {
        for (ResultsView view : ROWS) {
            for (int width : new int[] { 900, NARROW }) {
                for (JPanel card : variety(view, width)) {
                    assertEquals(0, card.getPreferredSize().width, "a row never asks to be wider than the list");
                    List<Rectangle> parts = new ArrayList<>();
                    for (JComponent part : all(card, JComponent.class)) {
                        if (!(part instanceof JButton || part instanceof JLabel)) {
                            continue;
                        }
                        Rectangle bounds = bounds(part, card);
                        String what = view + " " + width + " " + part;
                        assertTrue(bounds.x >= 0 && bounds.x + bounds.width <= width, what);
                        assertTrue(bounds.y >= 0 && bounds.y + bounds.height <= card.getHeight(), what);
                        if (!bounds.isEmpty()) {
                            parts.add(bounds);
                        }
                    }
                    for (int i = 0; i < parts.size(); i++) {
                        for (int j = i + 1; j < parts.size(); j++) {
                            assertFalse(parts.get(i).intersects(parts.get(j)),
                                view + " " + width + " " + parts.get(i) + " " + parts.get(j));
                        }
                    }
                    // the name is never squeezed away entirely
                    JLabel name = find(card, label -> NAMES.contains(label.getText()));
                    assertTrue(name.getWidth() >= Math.min(40, name.getPreferredSize().width), view + " " + width);
                }
            }
        }
    }

    @Test
    public void theActionsAreUnchanged() {
        for (ResultsView view : ROWS) {
            List<String> clicks = new ArrayList<>();
            ModrinthSearchHitCard added = modrinth(false, view, NARROW, "Sodium", "jellysquid3", 5, DESCRIPTION,
                clicks);
            assertEquals(Arrays.asList("Add", "View"), texts(buttons(added)));
            buttons(added).get(0).doClick(0);
            assertEquals(Collections.singletonList("install"), clicks);

            ModrinthSearchHitCard installed = modrinth(true, view, NARROW);
            assertEquals(Arrays.asList("Reinstall", "Remove", "View"), texts(buttons(installed)));
            assertEquals(Arrays.asList("Add", "View"), texts(buttons(curseForge(false, view, NARROW))));
            assertEquals(Arrays.asList("Reinstall", "Remove", "View"), texts(buttons(curseForge(true, view, NARROW))));
            for (JButton action : buttons(installed)) {
                assertTrue(action.isFocusable());
            }
        }
    }

    @Test
    public void theInstalledMarkIsASmallIconJustAfterTheName() {
        for (ResultsView view : ROWS) {
            ModrinthSearchHitCard card = modrinth(true, view, 900);
            JLabel name = withText(card, "Sodium");
            JLabel mark = withTip(card, "Installed");

            assertSame(name.getParent(), mark.getParent());
            Rectangle nameBounds = bounds(name, card);
            Rectangle markBounds = bounds(mark, card);
            assertEquals(nameBounds.x + nameBounds.width, markBounds.x, view.toString());
            assertTrue(markBounds.height <= nameBounds.height);
            assertEquals("Installed", mark.getAccessibleContext().getAccessibleName());

            // and hidden when it isn't installed
            assertNull(withTip(modrinth(false, view, 900), "Installed"));
        }
    }

    @Test
    public void aLargeInstalledIconNeverMakesARowTaller() {
        // the tick image is 50px; a mark of that size would make installed rows taller
        for (boolean compact : new boolean[] { false, true }) {
            JPanel shown = new JPanel();
            JPanel hidden = new JPanel();
            JLabel shownMark = new JLabel(new ImageIcon(new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB)));
            JLabel hiddenMark = new JLabel(new ImageIcon(new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB)));
            hiddenMark.setVisible(false);

            ResultRow.layout(shown, compact, new JLabel(), "Name", "Author", "Summary", 5, null, shownMark,
                new JButton("Add"));
            ResultRow.layout(hidden, compact, new JLabel(), "Name", "Author", "Summary", 5, null, hiddenMark,
                new JButton("Add"));

            assertEquals(hidden.getPreferredSize().height, shown.getPreferredSize().height);
            assertTrue(shownMark.getIcon().getIconHeight() < 50);
        }
    }

    @Test
    public void downloadCountsAreShortEnoughToScan() {
        assertEquals("999", ResultRow.shortCount(999));
        assertEquals("1K", ResultRow.shortCount(1000));
        assertEquals("1.2K", ResultRow.shortCount(1299));
        assertEquals("91.2M", ResultRow.shortCount(91234567));
        assertEquals("412M", ResultRow.shortCount(412999999));
        assertEquals("1B", ResultRow.shortCount(1000000000));
    }

    @Test
    public void descriptionsKeepToTwoLinesEndingInAnEllipsisWhenThereIsMore() {
        ResultRow.WrappedText text = new ResultRow.WrappedText(
            "one two three four five six seven eight nine ten eleven twelve", 2);
        FontMetrics metrics = text.getFontMetrics(text.getFont());
        int width = metrics.stringWidth("one two three four");

        List<String> lines = text.wrap(metrics, width);
        assertEquals(2, lines.size());
        assertEquals("one two three four", lines.get(0));
        assertTrue(lines.get(1).endsWith("..."), lines.get(1));
        assertTrue(metrics.stringWidth(lines.get(1)) <= width);

        // a word too long for its line is shortened, not left to spill over
        String word = new ResultRow.WrappedText("Supercalifragilisticexpialidocious", 2).wrap(metrics, 40).get(0);
        assertTrue(metrics.stringWidth(word) <= 40, word);

        // short text is shown whole, and none is none
        assertEquals(Collections.singletonList("one two"),
            new ResultRow.WrappedText("one two", 2).wrap(metrics, width));
        assertTrue(new ResultRow.WrappedText(null, 2).wrap(metrics, width).isEmpty());
    }

    @Test
    void everyViewShowsTheReportedLoaders() {
        ModrinthSearchHit hit = new ModrinthSearchHit();
        hit.projectId = "P1";
        hit.slug = "sodium";
        hit.title = "Sodium";
        hit.description = DESCRIPTION;
        for (ResultsView view : ResultsView.values()) {
            ModrinthSearchHitCard card = sized(new ModrinthSearchHitCard(hit, instance(false), e -> {
            }, e -> {
            }, view, EnumSet.of(LoaderType.FABRIC, LoaderType.NEOFORGE)), NARROW);
            List<String> names = all(card, JLabel.class).stream()
                .map(label -> label.getAccessibleContext().getAccessibleName()).collect(Collectors.toList());
            assertTrue(names.contains(LoaderType.FABRIC.toString()), view + ": " + names);
            assertTrue(names.contains(LoaderType.NEOFORGE.toString()), view + ": " + names);
        }
    }
}
