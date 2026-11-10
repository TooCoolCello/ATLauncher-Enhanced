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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import javax.swing.JPanel;

import org.junit.jupiter.api.Test;

import com.atlauncher.data.ModCompatibility;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthVersion;

public class CompatibilityBadgeTest {
    private static final ModCompatibility.Target NEOFORGE_WITH_CONNECTOR = new ModCompatibility.Target("1.21.1",
            new LoaderVersion("21.1.77", false, "NeoForge"), false, true);

    private static ModCompatibility.Verdict verdict(String... loaders) {
        ModrinthVersion version = new ModrinthVersion();
        version.gameVersions = Collections.singletonList("1.21.1");
        version.loaders = Arrays.asList(loaders);
        return ModCompatibility.forModrinthVersions(NEOFORGE_WITH_CONNECTOR, "Mods",
                Collections.singletonList(version));
    }

    @Test
    void hiddenUntilThereIsAVerdict() {
        CompatibilityBadge badge = new CompatibilityBadge(true);
        assertFalse(badge.isVisible());
        assertNull(badge.state());

        badge.setVerdict(verdict("neoforge"));
        assertTrue(badge.isVisible());
        assertEquals(ModCompatibility.State.COMPATIBLE, badge.state());

        badge.setVerdict(null);
        assertFalse(badge.isVisible());
    }

    @Test
    void cardsNameTheStateAndRowsOnlyShowTheIconBothExplainInTheTooltip() {
        ModCompatibility.Verdict notAvailable = verdict("forge");
        CompatibilityBadge card = new CompatibilityBadge(true);
        CompatibilityBadge row = new CompatibilityBadge(false);
        card.setVerdict(notAvailable);
        row.setVerdict(notAvailable);

        assertEquals("Not available", card.getText());
        assertNull(row.getText());
        assertEquals(notAvailable.reason, row.getToolTipText());
        assertEquals("Not available", row.getAccessibleContext().getAccessibleName());
    }

    @Test
    void sinytraMarkOnlyForResultsThatRunThroughConnector() {
        CompatibilityBadge badge = new CompatibilityBadge(false);

        badge.setVerdict(verdict("fabric"));
        assertSame(CompatibilityBadge.connectorIcon(), badge.getIcon());

        badge.setVerdict(verdict("fabric", "neoforge"));
        assertNotSame(CompatibilityBadge.connectorIcon(), badge.getIcon());
    }

    @Test
    void sitsBeforeTheLoaderIcons() {
        CompatibilityBadge badge = new CompatibilityBadge(false);
        JPanel strip = new JPanel();
        JPanel both = CompatibilityBadge.withLoaders(badge, strip);

        assertSame(badge, both.getComponent(0));
        assertSame(strip, both.getComponent(1));
        assertEquals(1, CompatibilityBadge.withLoaders(badge, null).getComponentCount());
    }
}
