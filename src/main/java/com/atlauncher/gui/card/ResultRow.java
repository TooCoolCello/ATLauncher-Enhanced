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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.Border;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.formdev.flatlaf.extras.FlatSVGIcon;

/**
 * A result as a full-width row, as Add Mods and Collections show both providers' projects. The first line is the
 * icon, the name with its installed mark, the author, the download count and the actions at the far right; List adds
 * the description below, at most two lines. Author and downloads sit in columns that line up from row to row, and an
 * author or count that isn't known is left out. Text gives way with an ellipsis, so rows in a view are all one height.
 */
final class ResultRow {
    static final int LIST_ICON_SIZE = 32;
    static final int COMPACT_ICON_SIZE = 20;
    static final int DESCRIPTION_LINES = 2;
    // between the name and the installed mark after it
    static final int MARK_GAP = 4;
    // between the first line's columns
    static final int COLUMN_GAP = 10;
    // between the actions
    static final int ACTION_GAP = 4;
    // buttons sized to their text rather than the theme's minimum width, so the name keeps room in narrow windows;
    // slimmer still for Compact List, at the usual text size
    static final Insets LIST_MARGIN = new Insets(2, 10, 2, 10);
    static final Insets COMPACT_MARGIN = new Insets(1, 8, 1, 8);

    private ResultRow() {
    }

    static int iconSize(boolean compact) {
        return compact ? COMPACT_ICON_SIZE : LIST_ICON_SIZE;
    }

    /**
     * @param author    or null when not known
     * @param downloads   or 0 when not known
     * @param loaderStrip reported-loader icons shown before the download count, or null for none
     * @param actions     the card's buttons: the first (Add) is shown instead of those between it and the last (View)
     */
    static void layout(JPanel row, boolean compact, JLabel icon, String name, String author, String description,
            int downloads, JPanel loaderStrip, JLabel installedLabel, JButton... actions) {
        row.setLayout(new BorderLayout(compact ? 8 : 10, 0));
        row.setBorder(border(compact ? 3 : 8, compact ? 8 : 10));

        // a fixed slot so text lines up whether or not the icon has loaded yet
        int iconSize = iconSize(compact);
        icon.setBorder(null);
        icon.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel iconSlot = new JPanel(new BorderLayout());
        iconSlot.setOpaque(false);
        iconSlot.setPreferredSize(new Dimension(iconSize, iconSize));
        iconSlot.add(icon, BorderLayout.CENTER);

        JPanel firstLine = firstLine(compact, name, author, downloads, loaderStrip, installedLabel, actions);
        if (compact) {
            JPanel iconColumn = new JPanel(new GridBagLayout());
            iconColumn.setOpaque(false);
            iconColumn.add(iconSlot);
            row.add(iconColumn, BorderLayout.WEST);
            row.add(firstLine, BorderLayout.CENTER);
            return;
        }

        JPanel iconColumn = new JPanel(new BorderLayout());
        iconColumn.setOpaque(false);
        iconColumn.add(iconSlot, BorderLayout.NORTH);
        row.add(iconColumn, BorderLayout.WEST);

        JPanel lines = new JPanel(new BorderLayout(0, 2));
        lines.setOpaque(false);
        lines.add(firstLine, BorderLayout.NORTH);
        lines.add(new WrappedText(description, DESCRIPTION_LINES), BorderLayout.CENTER);
        row.add(lines, BorderLayout.CENTER);
    }

    private static JPanel firstLine(boolean compact, String name, String author, int downloads,
            JPanel loaderStrip, JLabel installedLabel, JButton... actions) {
        boolean hasAuthor = author != null && !author.trim().isEmpty();

        // shortened by its own measure: the theme's bold is drawn wider than a plain label reckons
        WrappedText nameLabel = new WrappedText(name, 1);
        nameLabel.setFont(App.THEME.getBoldFont().deriveFont(compact ? 12f : 14f));
        // the author too, for when there's no room to show it
        nameLabel.setToolTipText(hasAuthor ? GetText.tr("{0} by {1}", name, author) : name);
        JPanel nameCell = new JPanel();
        nameCell.setLayout(new BoxLayout(nameCell, BoxLayout.X_AXIS));
        nameCell.setOpaque(false);
        nameCell.add(nameLabel);
        nameCell.add(installedMark(installedLabel, nameLabel.getPreferredSize().height));
        nameCell.add(Box.createHorizontalGlue());

        JPanel line = new JPanel(new NameAndAuthor());
        line.setOpaque(false);
        line.add(nameCell);
        if (hasAuthor) {
            JLabel authorLabel = secondary(new JLabel(author, icon("assets/icon/person.svg"), SwingConstants.LEADING));
            authorLabel.setToolTipText(GetText.tr("Author: {0}", author));
            authorLabel.getAccessibleContext().setAccessibleName(GetText.tr("Author: {0}", author));
            line.add(authorLabel);
        }

        // fixed widths at the right, so they sit in the same place in every row
        JPanel fixed = new JPanel(new BorderLayout(COLUMN_GAP, 0));
        fixed.setOpaque(false);
        // the loader icons go left of the downloads, so a row with more of them only takes from the name
        if (loaderStrip != null) {
            fixed.add(loaderStrip, BorderLayout.WEST);
        }
        fixed.add(downloads(downloads), BorderLayout.CENTER);
        fixed.add(actionColumn(compact ? COMPACT_MARGIN : LIST_MARGIN, actions), BorderLayout.EAST);

        JPanel whole = new JPanel(new BorderLayout(COLUMN_GAP, 0));
        whole.setOpaque(false);
        whole.add(line, BorderLayout.CENTER);
        whole.add(fixed, BorderLayout.EAST);
        return whole;
    }

    /**
     * The name and author columns, from the width alone so they line up in every row whatever the text: the author
     * column takes its share within limits, right before the downloads, and the name the rest; when that leaves the
     * name too little, the author column is dropped. Without an author, the name has it all.
     */
    static final class NameAndAuthor implements LayoutManager {
        static final int NAME_MIN = 90;
        static final int AUTHOR_MIN = 70;
        static final int AUTHOR_MAX = 180;
        static final double AUTHOR_SHARE = 0.3;

        @Override
        public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int width = parent.getWidth() - insets.left - insets.right;
            int height = parent.getHeight() - insets.top - insets.bottom;

            int authorWidth = 0;
            if (parent.getComponentCount() > 1 && width >= NAME_MIN + COLUMN_GAP + AUTHOR_MIN) {
                authorWidth = Math.max(AUTHOR_MIN, Math.min(AUTHOR_MAX, (int) (width * AUTHOR_SHARE)));
                authorWidth = Math.min(authorWidth, width - NAME_MIN - COLUMN_GAP);
            }
            int nameWidth = authorWidth == 0 ? width : width - authorWidth - COLUMN_GAP;

            place(parent.getComponent(0), insets.left, insets.top, nameWidth, height);
            if (parent.getComponentCount() > 1) {
                place(parent.getComponent(1), insets.left + width - authorWidth, insets.top, authorWidth, height);
            }
        }

        // at full height is too tall for a label; centred on the line instead
        private static void place(Component component, int x, int top, int width, int height) {
            int own = Math.min(height, component.getPreferredSize().height);
            component.setBounds(x, top + (height - own) / 2, width, own);
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            int height = 0;
            for (Component component : parent.getComponents()) {
                height = Math.max(height, component.getPreferredSize().height);
            }
            Insets insets = parent.getInsets();
            return new Dimension(0, height + insets.top + insets.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void addLayoutComponent(String name, Component component) {
        }

        @Override
        public void removeLayoutComponent(Component component) {
        }
    }

    private static FlatSVGIcon icon(String path) {
        return new FlatSVGIcon(path, 12, 12);
    }

    private static JLabel secondary(JLabel label) {
        Color color = UIManager.getColor("Label.disabledForeground");
        label.setForeground(color == null ? Color.GRAY : color);
        label.setIconTextGap(4);
        return label;
    }

    /**
     * A download count short enough to scan (such as 91.2M), with the exact count in its tooltip, in a column as wide
     * as the longest such count; blank when the count isn't known.
     */
    static JLabel downloads(int downloads) {
        JLabel label = secondary(new JLabel(icon("assets/icon/download.svg")));
        Dimension size = new Dimension(label.getIcon().getIconWidth() + label.getIconTextGap()
                + label.getFontMetrics(label.getFont()).stringWidth("999.9M") + 2, label.getPreferredSize().height);
        label.setPreferredSize(size);
        label.setMinimumSize(size);

        if (downloads <= 0) {
            label.setIcon(null);
            return label;
        }
        label.setText(shortCount(downloads));
        String exact = GetText.tr("{0} downloads", NumberFormat.getIntegerInstance().format(downloads));
        label.setToolTipText(exact);
        label.getAccessibleContext().setAccessibleName(exact);
        return label;
    }

    static String shortCount(long count) {
        if (count < 1000) {
            return String.valueOf(Math.max(0, count));
        }

        String[] units = { "K", "M", "B" };
        double value = count;
        int unit = -1;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }
        // one decimal below 100 (91.2M), none above (412M)
        String number = value >= 100 ? String.valueOf((long) Math.floor(value))
                : String.format(Locale.ENGLISH, "%.1f", Math.floor(value * 10) / 10).replace(".0", "");
        return number + units[unit];
    }

    /**
     * The actions, right-aligned in a column wide enough for either set shown, Add and View or Reinstall, Remove and
     * View, so an installed row lines up with one that isn't.
     *
     * @param margin the buttons' margin, or null for the theme's
     */
    static JPanel actionColumn(Insets margin, JButton... actions) {
        JPanel column = new JPanel(new FlowLayout(FlowLayout.RIGHT, ACTION_GAP, 0));
        column.setOpaque(false);

        int height = 0;
        for (JButton action : actions) {
            if (margin != null) {
                action.setMargin(margin);
            }
            column.add(action);
            height = Math.max(height, action.getPreferredSize().height);
        }

        int width = actions.length == 1 ? width(actions[0])
                : Math.max(width(Arrays.copyOfRange(actions, 1, actions.length)),
                        width(actions[0], actions[actions.length - 1]));
        Dimension fixed = new Dimension(width, height);
        column.setPreferredSize(fixed);
        column.setMinimumSize(fixed);
        return column;
    }

    // the buttons side by side, as the column's flow lays them out
    private static int width(JButton... shown) {
        int width = ACTION_GAP;
        for (JButton action : shown) {
            width += action.getPreferredSize().width + ACTION_GAP;
        }
        return width;
    }

    private static Border border(int vertical, int horizontal) {
        Color separator = UIManager.getColor("Separator.foreground");
        return BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, separator == null ? Color.GRAY : separator),
                BorderFactory.createEmptyBorder(vertical, horizontal, vertical, horizontal));
    }

    /**
     * The installed mark as a small status icon just after the name: scaled to fit within the name's line and fixed
     * at that size, so it never makes an installed row taller than one that isn't.
     */
    static JLabel installedMark(JLabel installedLabel, int lineHeight) {
        int size = Math.max(8, lineHeight - 2);
        if (installedLabel.getIcon() instanceof ImageIcon) {
            Image image = ((ImageIcon) installedLabel.getIcon()).getImage();
            installedLabel.setIcon(new ImageIcon(image.getScaledInstance(size, size, Image.SCALE_SMOOTH)));
        }

        installedLabel.setBorder(BorderFactory.createEmptyBorder(0, MARK_GAP, 0, 0));
        Dimension fixed = new Dimension(size + MARK_GAP, size);
        installedLabel.setPreferredSize(fixed);
        installedLabel.setMinimumSize(fixed);
        installedLabel.setMaximumSize(fixed);
        installedLabel.setToolTipText(GetText.tr("Installed"));
        installedLabel.getAccessibleContext().setAccessibleName(GetText.tr("Installed"));
        return installedLabel;
    }

    /**
     * A row only asks for its height; its width comes from the list, so long names or descriptions never widen the
     * results past the viewport.
     */
    static Dimension preferredSize(Dimension natural) {
        return new Dimension(0, natural.height);
    }

    /**
     * Text wrapped to the width it's given, always exactly this many lines tall so no row is taller than another; a
     * line that doesn't fit ends in an ellipsis. One line asks for its text's width, more for none, taking what
     * they're given. The whole text is its tooltip and accessible name.
     */
    static final class WrappedText extends JLabel {
        private final int lines;

        WrappedText(String text, int lines) {
            super(text == null ? "" : text.trim());
            this.lines = lines;
            if (!getText().isEmpty()) {
                setToolTipText(getText());
                getAccessibleContext().setAccessibleName(getText());
            }
        }

        int lines() {
            return lines;
        }

        @Override
        public Dimension getPreferredSize() {
            Insets insets = getInsets();
            FontMetrics metrics = getFontMetrics(getFont());
            int width = lines == 1 ? metrics.stringWidth(getText()) + insets.left + insets.right : 1;
            return new Dimension(width, lines * metrics.getHeight() + insets.top + insets.bottom);
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, getPreferredSize().height);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(lines == 1 ? getPreferredSize().width : Integer.MAX_VALUE,
                    getPreferredSize().height);
        }

        /**
         * The lines shown at a width: words wrapped greedily, ending in an ellipsis where there's more.
         */
        List<String> wrap(FontMetrics metrics, int width) {
            List<String> shown = new ArrayList<>();
            if (getText().isEmpty()) {
                return shown;
            }

            String[] words = getText().split("\\s+");
            int word = 0;
            while (word < words.length && shown.size() < lines) {
                StringBuilder line = new StringBuilder(words[word++]);
                while (word < words.length && metrics.stringWidth(line + " " + words[word]) <= width) {
                    line.append(' ').append(words[word++]);
                }
                shown.add(line.toString());
            }

            for (int i = 0; i < shown.size(); i++) {
                String line = shown.get(i);
                // the last line runs on into the next word, so it's filled before it's cut
                if (i == shown.size() - 1 && word < words.length) {
                    shown.set(i, ellipsize(metrics, line + " " + words[word], width));
                } else if (metrics.stringWidth(line) > width) {
                    shown.set(i, ellipsize(metrics, line, width));
                }
            }
            return shown;
        }

        private static String ellipsize(FontMetrics metrics, String text, int width) {
            String shortened = text;
            while (!shortened.isEmpty() && metrics.stringWidth(shortened.trim() + "...") > width) {
                shortened = shortened.substring(0, shortened.length() - 1);
            }
            return shortened.trim() + "...";
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(getFont());
                g2.setColor(getForeground());
                // measured as its size was, so text that was given its width is never shortened
                FontMetrics metrics = getFontMetrics(getFont());
                Insets insets = getInsets();
                int y = insets.top + metrics.getAscent();
                for (String line : wrap(metrics, getWidth() - insets.left - insets.right)) {
                    g2.drawString(line, insets.left, y);
                    y += metrics.getHeight();
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
