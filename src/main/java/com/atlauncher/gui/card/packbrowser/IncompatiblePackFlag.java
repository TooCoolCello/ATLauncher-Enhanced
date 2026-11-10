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
package com.atlauncher.gui.card.packbrowser;

import java.awt.BorderLayout;
import java.awt.Component;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.UIManager;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.utils.Utils;

/**
 * Marks a pack card as incompatible: a warning line across its top, and the reason stored on the card so the packs
 * browser can hide and count flagged cards without knowing each card type.
 */
public final class IncompatiblePackFlag {
    private static final String REASON_KEY = "atlauncher.incompatiblePackReason";

    private IncompatiblePackFlag() {
    }

    /**
     * Flags a card laid out with {@link BorderLayout}; does nothing when reason is null.
     */
    public static void apply(JComponent card, String reason) {
        if (reason == null) {
            return;
        }

        card.putClientProperty(REASON_KEY, reason);

        JLabel label = new JLabel(GetText.tr("Incompatible: {0}", reason),
            Utils.getIconImage(App.THEME.getIconPath("warning")), JLabel.LEADING);
        label.setForeground(UIManager.getColor("yellow"));
        label.setToolTipText(reason);
        label.setBorder(BorderFactory.createEmptyBorder(2, 5, 4, 5));
        card.add(label, BorderLayout.NORTH);
    }

    /**
     * The reason a component was flagged, or null when it's not a flagged card.
     */
    public static String reasonOf(Component component) {
        if (!(component instanceof JComponent)) {
            return null;
        }

        Object reason = ((JComponent) component).getClientProperty(REASON_KEY);
        return reason instanceof String ? (String) reason : null;
    }
}
