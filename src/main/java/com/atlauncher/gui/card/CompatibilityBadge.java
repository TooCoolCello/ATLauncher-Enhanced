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

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.data.ModCompatibility;

/**
 * A result's compatibility with the instance, beside its loader icons: an icon, plus a short word on cards, with the
 * evidence in the tooltip. Hidden until it has a verdict, and for sections that aren't checked.
 */
public final class CompatibilityBadge extends JLabel {
    // the contextual Sinytra mark: a letter tile like the loaders without cleared artwork, since Connector's logo
    // isn't bundled (see assets/image/loader/ATTRIBUTION.md); drawn in the yellow of the Connector notes
    private static final Icon CONNECTOR = new LoaderIndicatorStrip.LetterTile("SC", "yellow");

    private final boolean withText;
    private ModCompatibility.Verdict verdict;

    CompatibilityBadge(boolean withText) {
        this.withText = withText;
        setVisible(false);
    }

    /**
     * The mark for a file that only runs through Sinytra Connector.
     */
    public static Icon connectorIcon() {
        return CONNECTOR;
    }

    /**
     * @param verdict null hides the badge
     */
    public void setVerdict(ModCompatibility.Verdict verdict) {
        this.verdict = verdict;
        setVisible(verdict != null);
        if (verdict == null) {
            return;
        }

        setIcon(icon(verdict.state));
        setText(withText ? label(verdict.state) : null);
        setToolTipText(verdict.reason);
        getAccessibleContext().setAccessibleName(label(verdict.state));
        getAccessibleContext().setAccessibleDescription(verdict.reason);
    }

    /**
     * @return null until there's a verdict
     */
    public ModCompatibility.State state() {
        return verdict == null ? null : verdict.state;
    }

    static String label(ModCompatibility.State state) {
        switch (state) {
            case COMPATIBLE:
                return GetText.tr("Compatible");
            case VIA_CONNECTOR:
                return GetText.tr("Via Connector");
            case NOT_AVAILABLE:
                return GetText.tr("Not available");
            case CHECKING:
                return GetText.tr("Checking...");
            default:
                return GetText.tr("Unknown");
        }
    }

    static Icon icon(ModCompatibility.State state) {
        return state == ModCompatibility.State.VIA_CONNECTOR ? CONNECTOR : new StateIcon(state);
    }

    /**
     * The badge before the loader icons, each left out while it has nothing to show.
     */
    static JPanel withLoaders(CompatibilityBadge badge, JPanel loaderStrip) {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.setOpaque(false);
        panel.add(badge, BorderLayout.WEST);
        if (loaderStrip != null) {
            panel.add(loaderStrip, BorderLayout.CENTER);
        }
        return panel;
    }

    /**
     * Distinct shapes, not only colors: a filled circle with a tick, a circle struck through, a question mark, and
     * dots while checking. Colors come from the theme when it's painted.
     */
    static final class StateIcon implements Icon {
        final ModCompatibility.State state;

        StateIcon(ModCompatibility.State state) {
            this.state = state;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            int size = LoaderIndicatorStrip.ICON_SIZE;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.translate(x, y);

                if (state == ModCompatibility.State.COMPATIBLE) {
                    g2.setColor(color("Actions.Green", new Color(0x499C54)));
                    g2.fillOval(1, 1, size - 2, size - 2);
                    g2.setColor(Color.WHITE);
                    g2.drawPolyline(new int[] { 4, 7, 12 }, new int[] { 8, 11, 5 }, 3);
                } else if (state == ModCompatibility.State.NOT_AVAILABLE) {
                    g2.setColor(color("Actions.Red", new Color(0xC75450)));
                    g2.drawOval(2, 2, size - 4, size - 4);
                    g2.drawLine(4, 12, 12, 4);
                } else {
                    g2.setColor(color("Label.disabledForeground", Color.GRAY));
                    g2.drawOval(2, 2, size - 4, size - 4);
                    if (state == ModCompatibility.State.CHECKING) {
                        for (int dot = 0; dot < 3; dot++) {
                            g2.fillOval(4 + dot * 3, 7, 2, 2);
                        }
                    } else {
                        g2.setFont(c.getFont().deriveFont(Font.BOLD, 9f));
                        FontMetrics metrics = g2.getFontMetrics();
                        g2.drawString("?", (size - metrics.stringWidth("?")) / 2,
                                (size - metrics.getHeight()) / 2 + metrics.getAscent());
                    }
                }
            } finally {
                g2.dispose();
            }
        }

        private static Color color(String key, Color fallback) {
            Color color = UIManager.getColor(key);
            return color == null ? fallback : color;
        }

        @Override
        public int getIconWidth() {
            return LoaderIndicatorStrip.ICON_SIZE;
        }

        @Override
        public int getIconHeight() {
            return LoaderIndicatorStrip.ICON_SIZE;
        }
    }
}
