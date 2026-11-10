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
package com.atlauncher.gui.components;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.data.ResultsView;
import com.formdev.flatlaf.extras.FlatSVGIcon;

/**
 * One View button, as a file manager has, for Add Mods and Collections: it shows the chosen view, and opens a menu of
 * Cards, List and Compact List, each with its own icon and name and the chosen one checked. Each window keeps its own
 * choice; this only shows it and says when the user picks another.
 */
public final class ResultsViewPicker extends JButton {
    // room on the right for the menu arrow
    private static final int ARROW_SPACE = 14;
    // densest last, as the menu lists them
    private static final ResultsView[] ORDER = { ResultsView.CARDS, ResultsView.LIST, ResultsView.COMPACT_LIST };

    private final JPopupMenu menu = new JPopupMenu();
    private final Map<ResultsView, JRadioButtonMenuItem> items = new EnumMap<>(ResultsView.class);
    private ResultsView shown;

    public ResultsViewPicker(ResultsView current, Consumer<ResultsView> picked) {
        setToolTipText(GetText.tr("Change how results are shown"));
        Insets margin = getMargin();
        setMargin(new Insets(margin.top, margin.left, margin.bottom, margin.right + ARROW_SPACE));

        ButtonGroup group = new ButtonGroup();
        for (ResultsView view : ORDER) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(label(view), icon(view));
            item.addActionListener(e -> {
                select(view);
                picked.accept(view);
            });
            group.add(item);
            items.put(view, item);
            menu.add(item);
        }

        addActionListener(e -> openMenu());
        select(current);
    }

    public static String label(ResultsView view) {
        switch (view) {
            case COMPACT_LIST:
                return GetText.tr("Compact List");
            case LIST:
                return GetText.tr("List");
            default:
                return GetText.tr("Cards");
        }
    }

    static Icon icon(ResultsView view) {
        switch (view) {
            case COMPACT_LIST:
                return new FlatSVGIcon("assets/icon/view-compact.svg");
            case LIST:
                return new FlatSVGIcon("assets/icon/view-list.svg");
            default:
                return new FlatSVGIcon("assets/icon/view-cards.svg");
        }
    }

    /**
     * Shows this view as chosen, without saying so.
     */
    public void select(ResultsView view) {
        shown = view == null ? ResultsView.CARDS : view;
        items.get(shown).setSelected(true);
        setText(GetText.tr("View: {0}", label(shown)));
        setIcon(icon(shown));
        getAccessibleContext().setAccessibleName(getText());
    }

    ResultsView shown() {
        return shown;
    }

    JPopupMenu menu() {
        return menu;
    }

    JRadioButtonMenuItem item(ResultsView view) {
        return items.get(view);
    }

    private void openMenu() {
        if (!isShowing()) {
            return;
        }
        menu.show(this, 0, getHeight());
        // start on the chosen view, so arrow keys move from there
        MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[] { menu, items.get(shown) });
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        // a small down arrow, in the text's colour, in the space kept for it
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getForeground());
            int x = getWidth() - getInsets().right + 2;
            int y = getHeight() / 2 - 2;
            Path2D arrow = new Path2D.Float();
            arrow.moveTo(x, y);
            arrow.lineTo(x + 8, y);
            arrow.lineTo(x + 4, y + 4);
            arrow.closePath();
            g2.fill(arrow);
        } finally {
            g2.dispose();
        }
    }
}
