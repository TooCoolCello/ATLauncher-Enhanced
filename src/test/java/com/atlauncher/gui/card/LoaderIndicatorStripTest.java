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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Scanner;
import java.util.Set;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;

import com.atlauncher.data.minecraft.loaders.LoaderType;

public class LoaderIndicatorStripTest {
    private static JLabel label(JPanel strip, int index) {
        return (JLabel) strip.getComponent(index);
    }

    @Test
    void nothingToShowGivesNoStrip() {
        assertNull(LoaderIndicatorStrip.create(null, "Modrinth"));
        assertNull(LoaderIndicatorStrip.create(EnumSet.noneOf(LoaderType.class), "Modrinth"));
    }

    @Test
    void eachLoaderGetsAnIconTooltipAndAccessibleName() {
        JPanel strip = LoaderIndicatorStrip.create(EnumSet.of(LoaderType.NEOFORGE, LoaderType.FABRIC), "Modrinth");

        assertEquals(2, strip.getComponentCount());
        assertEquals("Fabric", label(strip, 0).getAccessibleContext().getAccessibleName());
        assertEquals("NeoForge", label(strip, 1).getAccessibleContext().getAccessibleName());
        assertEquals("Fabric: reported by Modrinth for this project, not checked against your Minecraft version",
                label(strip, 0).getToolTipText());
        assertNull(label(strip, 0).getText());
    }

    @Test
    void extraLoadersCollapseIntoACountWithTheirNames() {
        JPanel strip = LoaderIndicatorStrip.create(EnumSet.of(LoaderType.FABRIC, LoaderType.FORGE,
                LoaderType.LEGACY_FABRIC, LoaderType.NEOFORGE, LoaderType.QUILT), "CurseForge");

        assertEquals(LoaderIndicatorStrip.MAX_VISIBLE + 1, strip.getComponentCount());
        JLabel more = label(strip, LoaderIndicatorStrip.MAX_VISIBLE);
        assertEquals("+2", more.getText());
        assertTrue(more.getToolTipText().endsWith(": NeoForge, Quilt"), more.getToolTipText());
        assertEquals("2 more: NeoForge, Quilt", more.getAccessibleContext().getAccessibleName());
    }

    @Test
    void officialIconsAreBundledAtDisplaySizeAndLoadedOnce() {
        for (LoaderType loader : Arrays.asList(LoaderType.NEOFORGE, LoaderType.QUILT)) {
            Icon icon = LoaderIndicatorStrip.icon(loader);

            assertInstanceOf(ImageIcon.class, icon, loader.toString());
            assertEquals(LoaderIndicatorStrip.ICON_SIZE, icon.getIconWidth());
            assertEquals(LoaderIndicatorStrip.ICON_SIZE, icon.getIconHeight());
            assertSame(icon, LoaderIndicatorStrip.icon(loader));
        }
    }

    @Test
    void loadersWithoutClearedArtworkGetDistinctLetterTiles() {
        Set<String> seen = new HashSet<>();
        for (LoaderType loader : EnumSet.of(LoaderType.FABRIC, LoaderType.FORGE, LoaderType.LEGACY_FABRIC,
                LoaderType.PAPER, LoaderType.PURPUR)) {
            Icon icon = LoaderIndicatorStrip.icon(loader);

            assertInstanceOf(LoaderIndicatorStrip.LetterTile.class, icon, loader.toString());
            assertTrue(seen.add(((LoaderIndicatorStrip.LetterTile) icon).letters), loader.toString());
        }
    }

    @Test
    void everyBundledIconHasAnAttributionEntry() throws IOException {
        String attribution;
        try (InputStream in = LoaderIndicatorStrip.class.getResourceAsStream("/assets/image/loader/ATTRIBUTION.md");
                Scanner scanner = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            attribution = scanner.next();
        }

        for (LoaderType loader : LoaderType.values()) {
            String file = loader.name().toLowerCase(Locale.ENGLISH) + ".png";
            if (LoaderIndicatorStrip.class.getResource("/assets/image/loader/" + file) != null) {
                assertTrue(attribution.contains("## " + file), file + " is bundled without an attribution entry");
            }
        }
    }
}
