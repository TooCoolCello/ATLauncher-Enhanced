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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;

import javax.swing.JTextPane;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.EditorKit;
import javax.swing.text.Element;
import javax.swing.text.ParagraphView;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;

import org.mini2Dx.gettext.GetText;

/**
 * The Java parameters box. The heap flags the launcher adds from the memory settings show greyed out at its start, but
 * they're only painted there: the document, and so what's edited, selected, copied and saved, is only the custom
 * parameters. Their first line is indented to leave room for the flags.
 */
public final class JavaParametersEditor extends JTextPane {
    // as the text area this replaced
    private static final int ROWS = 6;
    private static final int COLUMNS = 40;

    private String generatedFlags = "";
    // the room left for the flags at the start of the first line
    private int indent;

    public JavaParametersEditor() {
        ToolTipManager.sharedInstance().registerComponent(this);
        getAccessibleContext().setAccessibleName(GetText.tr("Java Parameters"));
    }

    @Override
    protected EditorKit createDefaultEditorKit() {
        return new StyledEditorKit() {
            @Override
            public ViewFactory getViewFactory() {
                ViewFactory styled = super.getViewFactory();
                return element -> element.getName().equals(AbstractDocument.ParagraphElementName)
                    ? new FlagsParagraphView(element)
                    : styled.create(element);
            }
        };
    }

    /**
     * Shows these flags at the start of the box, without changing the parameters in it.
     */
    public void setGeneratedFlags(String flags) {
        String text = flags == null ? "" : flags;
        if (text.equals(generatedFlags)) {
            return;
        }

        generatedFlags = text;
        getAccessibleContext().setAccessibleDescription(text.isEmpty() ? null
            : GetText.tr("The launcher adds {0} before these. They aren't saved with them.", text));
        updateIndent();
    }

    public String getGeneratedFlags() {
        return generatedFlags;
    }

    private void updateIndent() {
        FontMetrics metrics = getFontMetrics(getFont());
        indent = generatedFlags.isEmpty() ? 0 : metrics.stringWidth(generatedFlags + " ");
        indentParagraphs(getUI().getRootView(this));
        revalidate();
        repaint();
    }

    private void indentParagraphs(View view) {
        if (view instanceof FlagsParagraphView) {
            ((FlagsParagraphView) view).indent();
        }
        for (int i = 0; i < view.getViewCount(); i++) {
            indentParagraphs(view.getView(i));
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // a new font takes a new width
        if (generatedFlags != null) {
            updateIndent();
        }
    }

    // where the flags are painted: before the first character
    Rectangle generatedFlagsBounds() {
        if (generatedFlags.isEmpty()) {
            return null;
        }

        try {
            // modelToView2D is newer than the Java 8 the launcher still runs on
            @SuppressWarnings("deprecation")
            Rectangle first = modelToView(0);
            if (first == null) {
                return null;
            }
            Insets insets = getInsets();
            return new Rectangle(insets.left, first.y, first.x - insets.left, first.height);
        } catch (BadLocationException e) {
            return null;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        Rectangle bounds = generatedFlagsBounds();
        if (bounds == null) {
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        FontMetrics metrics = g2.getFontMetrics(getFont());
        Color grey = UIManager.getColor("TextComponent.inactiveForeground");
        g2.setColor(grey == null ? UIManager.getColor("Label.disabledForeground") : grey);
        g2.setFont(getFont());
        g2.drawString(generatedFlags, bounds.x,
            bounds.y + (bounds.height - metrics.getHeight()) / 2 + metrics.getAscent());
        g2.dispose();
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        Rectangle bounds = generatedFlagsBounds();
        if (bounds != null && bounds.contains(e.getPoint())) {
            return GetText.tr("Added by the launcher from the memory settings when Minecraft starts. Not saved with "
                + "your Java parameters.");
        }
        return null;
    }

    // as big as the text area this replaced
    @Override
    public Dimension getPreferredScrollableViewportSize() {
        FontMetrics metrics = getFontMetrics(getFont());
        Insets insets = getInsets();
        return new Dimension(COLUMNS * metrics.charWidth('m') + insets.left + insets.right,
            ROWS * metrics.getHeight() + insets.top + insets.bottom);
    }

    /**
     * A paragraph whose first line starts after the generated flags.
     */
    private final class FlagsParagraphView extends ParagraphView {
        FlagsParagraphView(Element element) {
            super(element);
        }

        @Override
        protected void setPropertiesFromAttributes() {
            super.setPropertiesFromAttributes();
            setFirstLineIndent(indent);
        }

        void indent() {
            setFirstLineIndent(indent);
            layoutChanged(View.X_AXIS);
            layoutChanged(View.Y_AXIS);
            preferenceChanged(null, true, true);
        }
    }
}
