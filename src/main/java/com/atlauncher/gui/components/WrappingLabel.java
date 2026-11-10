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

import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

import javax.swing.JLabel;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;

import com.formdev.flatlaf.util.UIScale;

/**
 * A note that wraps to whatever width its layout gives it. It only asks for a narrow width, so a long note never
 * widens the column it's in or squeezes the controls beside it; it grows taller instead.
 */
public final class WrappingLabel extends JLabel {
    // the width it asks for, and wraps to before it's first laid out
    static final int NARROWEST = 200;

    private int laidOutWidth = -1;

    public WrappingLabel() {
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                // a new width means a new wrapped height, so lay out again once for it
                if (getWidth() != laidOutWidth) {
                    laidOutWidth = getWidth();
                    revalidate();
                }
            }
        });
    }

    public void setWrappedText(String text) {
        setText(text == null ? "" : "<html>" + text + "</html>");
    }

    @Override
    public Dimension getPreferredSize() {
        View view = (View) getClientProperty(BasicHTML.propertyKey);
        if (view == null || isPreferredSizeSet()) {
            return super.getPreferredSize();
        }

        Insets insets = getInsets();
        int narrowest = UIScale.scale(NARROWEST);
        int width = (getWidth() > 0 ? getWidth() : narrowest) - insets.left - insets.right;
        view.setSize(Math.max(1, width), 0);

        return new Dimension(narrowest,
            (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS)) + insets.top + insets.bottom);
    }
}
