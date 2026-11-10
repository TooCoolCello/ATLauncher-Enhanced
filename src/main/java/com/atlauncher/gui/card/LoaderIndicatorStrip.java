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

import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.net.URL;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.data.minecraft.loaders.LoaderType;

/**
 * A compact row of 16px icons for the loaders a provider reports for a project. The loaders are project-level, so
 * tooltips say so rather than implying a file exists for the instance's Minecraft version.
 */
final class LoaderIndicatorStrip {
    static final int ICON_SIZE = 16;
    static final int MAX_VISIBLE = 3;

    // one icon per loader, shared by every result: official artwork only where its license is verified (see
    // assets/image/loader/ATTRIBUTION.md), a generic letter tile otherwise; only touched on the EDT
    private static final Map<LoaderType, Icon> ICONS = new EnumMap<>(LoaderType.class);

    private LoaderIndicatorStrip() {
    }

    /**
     * @param provider platform name for tooltips, such as "Modrinth"
     * @return the strip, or null when there is nothing to show
     */
    static JPanel create(Set<LoaderType> loaders, String provider) {
        if (loaders == null || loaders.isEmpty()) {
            return null;
        }

        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        strip.setOpaque(false);

        List<LoaderType> ordered = new ArrayList<>(loaders);
        for (LoaderType loader : ordered.subList(0, Math.min(ordered.size(), MAX_VISIBLE))) {
            JLabel label = new JLabel(icon(loader));
            label.setToolTipText(GetText.tr(
                    "{0}: reported by {1} for this project, not checked against your Minecraft version", loader,
                    provider));
            label.getAccessibleContext().setAccessibleName(loader.toString());
            strip.add(label);
        }

        // ponytail: fixed cap keeps buttons visible in narrow cards and rows; the rest sit behind +N
        if (ordered.size() > MAX_VISIBLE) {
            List<LoaderType> hidden = ordered.subList(MAX_VISIBLE, ordered.size());
            String names = hidden.stream().map(LoaderType::toString).collect(Collectors.joining(", "));
            JLabel more = new JLabel("+" + hidden.size());
            more.setToolTipText(GetText.tr(
                    "Also reported by {0} for this project, not checked against your Minecraft version: {1}",
                    provider, names));
            more.getAccessibleContext().setAccessibleName(GetText.tr("{0} more: {1}", hidden.size(), names));
            strip.add(more);
        }

        return strip;
    }

    static Icon icon(LoaderType loader) {
        return ICONS.computeIfAbsent(loader, LoaderIndicatorStrip::loadIcon);
    }

    private static Icon loadIcon(LoaderType loader) {
        URL official = LoaderIndicatorStrip.class
                .getResource("/assets/image/loader/" + loader.name().toLowerCase(Locale.ENGLISH) + ".png");

        return official == null ? new LetterTile(letters(loader)) : new ImageIcon(official);
    }

    static String letters(LoaderType loader) {
        switch (loader) {
            case LEGACY_FABRIC:
                return "LF";
            case NEOFORGE:
                return "NF";
            default:
                return loader.toString().substring(0, 2);
        }
    }

    /**
     * Neutral stand-in for loaders without cleared artwork: a rounded outline with a two-letter abbreviation, drawn in
     * the label's own foreground so it follows the theme. Deliberately not a logo.
     */
    static final class LetterTile implements Icon {
        final String letters;

        LetterTile(String letters) {
            this.letters = letters;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(c.getForeground());
                g2.drawRoundRect(x, y, ICON_SIZE - 1, ICON_SIZE - 1, 5, 5);

                g2.setFont(c.getFont().deriveFont(Font.BOLD, 8f));
                FontMetrics metrics = g2.getFontMetrics();
                g2.drawString(letters, x + (ICON_SIZE - metrics.stringWidth(letters)) / 2,
                        y + (ICON_SIZE - metrics.getHeight()) / 2 + metrics.getAscent());
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return ICON_SIZE;
        }

        @Override
        public int getIconHeight() {
            return ICON_SIZE;
        }
    }
}
