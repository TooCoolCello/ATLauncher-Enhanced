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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.FontMetrics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;
import javax.swing.undo.UndoManager;

import org.junit.jupiter.api.Test;

/**
 * The generated heap flags are painted at the start of the Java parameters box, but are never part of what's edited,
 * copied or saved.
 */
public class JavaParametersEditorTest {
    private static final String FLAGS = "-Xms1024M -Xmx4096M";
    private static final String CUSTOM = "-XX:+UseG1GC -Dfoo=bar";

    private static JavaParametersEditor editor(String custom) {
        JavaParametersEditor editor = new JavaParametersEditor();
        editor.setText(custom);
        editor.setGeneratedFlags(FLAGS);
        editor.setSize(400, 200);
        return editor;
    }

    @SuppressWarnings("deprecation")
    private static Rectangle at(JavaParametersEditor editor, int position) throws Exception {
        return editor.modelToView(position);
    }

    private static String copied(JavaParametersEditor editor, int action) throws Exception {
        Clipboard clipboard = new Clipboard("test");
        editor.getTransferHandler().exportToClipboard(editor, clipboard, action);
        return (String) clipboard.getData(DataFlavor.stringFlavor);
    }

    private static void run(JavaParametersEditor editor, String action) {
        editor.getActionMap().get(action).actionPerformed(new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, ""));
    }

    @Test
    public void theFlagsAreShownButArentInTheText() throws Exception {
        JavaParametersEditor editor = editor(CUSTOM);

        assertEquals(CUSTOM, editor.getText());
        assertEquals(CUSTOM, editor.getDocument().getText(0, editor.getDocument().getLength()));
        assertEquals(FLAGS, editor.getGeneratedFlags());

        // the first character starts after them, on the same line
        FontMetrics metrics = editor.getFontMetrics(editor.getFont());
        Rectangle flags = editor.generatedFlagsBounds();
        assertNotNull(flags);
        assertTrue(flags.width >= metrics.stringWidth(FLAGS), flags.toString());
        assertEquals(flags.x + flags.width, at(editor, 0).x);
        assertEquals(flags.y, at(editor, 0).y);
    }

    @Test
    public void newTextStillStartsAfterTheFlags() throws Exception {
        JavaParametersEditor editor = editor(CUSTOM);
        int start = at(editor, 0).x;

        editor.setText("-Dother=1");
        assertEquals(start, at(editor, 0).x);
        editor.setText("");
        assertEquals(start, at(editor, 0).x);
        assertEquals("", editor.getText());
    }

    @Test
    public void changingTheFlagsDoesntTouchTheDocumentOrItsUndo() throws Exception {
        JavaParametersEditor editor = editor(CUSTOM);
        UndoManager undo = new UndoManager();
        editor.getDocument().addUndoableEditListener(undo);
        AtomicInteger events = new AtomicInteger();
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                events.incrementAndGet();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                events.incrementAndGet();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                events.incrementAndGet();
            }
        });
        int before = at(editor, 0).x;

        editor.setGeneratedFlags("-Xmx12288M");
        assertEquals(0, events.get());
        assertFalse(undo.canUndo());
        assertTrue(at(editor, 0).x < before, "a shorter indent for shorter flags");

        editor.setGeneratedFlags("");
        assertEquals(editor.getInsets().left, at(editor, 0).x);
        assertNull(editor.generatedFlagsBounds());
        assertEquals(CUSTOM, editor.getText());

        // undo only ever undoes the parameters themselves
        editor.getDocument().insertString(0, "-Da=1 ", null);
        editor.setGeneratedFlags(FLAGS);
        undo.undo();
        assertEquals(CUSTOM, editor.getText());
        assertFalse(undo.canUndo());
        undo.redo();
        assertEquals("-Da=1 " + CUSTOM, editor.getText());
    }

    @Test
    public void selectAllCopyAndCutOnlyTakeTheParameters() throws Exception {
        JavaParametersEditor editor = editor(CUSTOM);

        run(editor, DefaultEditorKit.selectAllAction);
        assertEquals(CUSTOM, editor.getSelectedText());
        assertEquals(CUSTOM, copied(editor, TransferHandler.COPY));

        assertEquals(CUSTOM, copied(editor, TransferHandler.MOVE));
        assertEquals("", editor.getText());
        assertEquals(FLAGS, editor.getGeneratedFlags());
    }

    @Test
    public void pasteAndDeletionAtTheStartOnlyChangeTheParameters() throws Exception {
        JavaParametersEditor editor = editor(CUSTOM);

        editor.setCaretPosition(0);
        run(editor, DefaultEditorKit.deletePrevCharAction);
        assertEquals(CUSTOM, editor.getText());

        run(editor, DefaultEditorKit.beginLineAction);
        assertEquals(0, editor.getCaretPosition());
        editor.getTransferHandler().importData(editor, new StringSelection("-Dpasted=1 "));
        assertEquals("-Dpasted=1 " + CUSTOM, editor.getText());

        editor.setCaretPosition(0);
        run(editor, DefaultEditorKit.deleteNextCharAction);
        assertEquals("Dpasted=1 " + CUSTOM, editor.getText());
        assertEquals(FLAGS, editor.getGeneratedFlags());
    }

    @Test
    public void aClickOnTheFlagsPutsTheCaretBeforeTheParameters() throws Exception {
        JavaParametersEditor editor = editor(CUSTOM);
        Rectangle flags = editor.generatedFlagsBounds();

        @SuppressWarnings("deprecation")
        int position = editor.viewToModel(new Point(flags.x + 2, flags.y + flags.height / 2));
        assertEquals(0, position);
    }

    @Test
    public void theFlagsHaveATooltipAndAnAccessibleDescription() {
        JavaParametersEditor editor = editor(CUSTOM);
        Rectangle flags = editor.generatedFlagsBounds();

        MouseEvent onFlags = new MouseEvent(editor, MouseEvent.MOUSE_MOVED, 0, 0, flags.x + 2, flags.y + 2, 0, false);
        assertTrue(editor.getToolTipText(onFlags).contains("launcher"));
        MouseEvent below = new MouseEvent(editor, MouseEvent.MOUSE_MOVED, 0, 0, flags.x + 2, 150, 0, false);
        assertNull(editor.getToolTipText(below));

        assertTrue(editor.getAccessibleContext().getAccessibleDescription().contains(FLAGS));
    }

    @Test
    public void asBigAsTheTextAreaItReplaced() {
        JavaParametersEditor editor = new JavaParametersEditor();
        FontMetrics metrics = editor.getFontMetrics(editor.getFont());

        assertTrue(editor.getPreferredScrollableViewportSize().height >= 6 * metrics.getHeight());
        assertTrue(editor.getPreferredScrollableViewportSize().width >= 40 * metrics.charWidth('m'));
    }
}
