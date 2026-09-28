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

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.mclauncher.MCLauncher;

/**
 * The heap flags the launcher adds from the memory settings: shown greyed out at the start of the Java parameters box,
 * though never part of the saved parameters, with a short note below it when they need explaining. The note takes the
 * width its layout gives it, so lay it out to fill the width of the Java parameters.
 */
public final class HeapFlagsPreview extends JPanel {
    private final JavaParametersEditor editor;
    final WrappingLabel note = new WrappingLabel();

    public HeapFlagsPreview(JavaParametersEditor editor) {
        super(new BorderLayout());
        this.editor = editor;

        note.setEnabled(false);
        add(note, BorderLayout.CENTER);
        setVisible(false);
    }

    /**
     * @param configuredMaximum the maximum memory setting the preview was built from
     */
    public void show(MCLauncher.HeapPreview preview, int configuredMaximum) {
        show(preview, configuredMaximum, false);
    }

    /**
     * @param linked whether the minimum and maximum are linked in the settings shown
     */
    public void show(MCLauncher.HeapPreview preview, int configuredMaximum, boolean linked) {
        editor.setGeneratedFlags(flagsText(preview));

        String text = note(preview, configuredMaximum, linked);
        note.setWrappedText(text);
        setVisible(text != null);
    }

    /**
     * Whether a linked minimum is launched below a maximum the pack raised: the settings are the same, the launch
     * isn't. Not when there's no -Xms, or the Java parameters set the initial heap.
     */
    public static boolean linkedMinimumStays(MCLauncher.HeapPreview preview) {
        return preview.raisedByPack && preview.savedInitialMemory > 0 && !preview.initialHeapFromParameters;
    }

    static String flagsText(MCLauncher.HeapPreview preview) {
        return String.join(" ", preview.flags);
    }

    /**
     * Why the flags look as they do, or may differ at launch; null when they're exactly the settings.
     */
    static String note(MCLauncher.HeapPreview preview, int configuredMaximum) {
        return note(preview, configuredMaximum, false);
    }

    static String note(MCLauncher.HeapPreview preview, int configuredMaximum, boolean linked) {
        List<String> parts = new ArrayList<>();

        if (preview.initialHeapFromParameters) {
            parts.add(GetText.tr("Your Java parameters set the initial heap, so no -Xms is added."));
        }

        if (preview.initialCapped) {
            // as it appears in the flag, without digit grouping
            parts.add(GetText.tr("The saved minimum of {0}M is more than -Xmx, so -Xms is lowered to match it at "
                + "launch. The saved setting isn't changed.", String.valueOf(preview.savedInitialMemory)));
        }

        if (preview.raisedByPack) {
            // as it appears in the flag, without digit grouping
            parts.add(GetText.tr("-Xmx is raised from {0}M to the pack's required memory.",
                String.valueOf(configuredMaximum)));

            // the link keeps the settings together; at launch only -Xmx is raised
            if (linked && linkedMinimumStays(preview)) {
                parts.add(GetText.tr("The linked minimum isn't raised with it, so -Xms stays at {0}M.",
                    String.valueOf(preview.savedInitialMemory)));
            }
        }

        if (preview.packUnknown) {
            parts.add(GetText.tr("An instance's own settings, or its pack's required memory, can change these at "
                + "launch."));
        }

        return parts.isEmpty() ? null : String.join(" ", parts);
    }
}
