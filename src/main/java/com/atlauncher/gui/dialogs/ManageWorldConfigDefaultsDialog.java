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
package com.atlauncher.gui.dialogs;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.data.Instance;
import com.atlauncher.gui.components.WrappingLabel;
import com.atlauncher.managers.LogManager;
import com.atlauncher.utils.DefaultConfigsFromWorld;
import com.atlauncher.utils.DefaultConfigsFromWorld.DefaultConfig;
import com.atlauncher.utils.OS;

/**
 * The instance's world config defaults: the files in its defaultconfigs folder, which Forge and NeoForge copy into
 * each new world's serverconfig folder, with ways to copy more from a world, and to replace, reveal or remove one.
 * The files are read and written off the event dispatch thread, one action at a time, and the list is refreshed
 * after each.
 */
public final class ManageWorldConfigDefaultsDialog extends JDialog {
    private static final String LIST = "list";
    private static final String MESSAGE = "message";

    private final Instance instance;
    private final WorldConfigDefaultsDialogs prompts;

    private final DefaultListModel<DefaultConfig> model = new DefaultListModel<>();
    private final JList<DefaultConfig> list = new JList<>(model);
    private final JLabel message = new JLabel("", SwingConstants.CENTER);
    private final CardLayout cards = new CardLayout();
    private final JPanel listArea = new JPanel(cards);

    private final JButton copyFromWorld = new JButton(GetText.tr("Copy From World..."));
    private final JButton replaceFromWorld = new JButton(GetText.tr("Replace From World..."));
    private final JButton reveal = new JButton(GetText.tr("Reveal In File Manager"));
    private final JButton remove = new JButton(GetText.tr("Remove..."));
    private final JButton refresh = new JButton(GetText.tr("Refresh"));
    private final JButton close = new JButton(GetText.tr("Close"));

    private boolean busy = false;

    private ManageWorldConfigDefaultsDialog(Window parent, Instance instance) {
        // #. {0} is the name of the instance
        super(parent, GetText.tr("Manage World Config Defaults - {0}", instance.launcher.name),
            ModalityType.DOCUMENT_MODAL);
        this.instance = instance;
        this.prompts = new WorldConfigDefaultsDialogs(this);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(560, 360));
        setSize(680, 440);
        setLocationRelativeTo(parent);

        // wraps to the dialog's width, which can be narrower than the text (down to its minimum size)
        WrappingLabel explanation = new WrappingLabel();
        explanation.setWrappedText(GetText.tr("New worlds created in this "
            + "instance start with these server configs, from its defaultconfigs folder. Existing worlds keep their "
            + "own."));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.getAccessibleContext().setAccessibleName(GetText.tr("Current Default Configs"));
        list.addListSelectionListener(e -> updateButtons());
        message.setEnabled(false);
        listArea.add(new JScrollPane(list), LIST);
        listArea.add(message, MESSAGE);

        JPanel center = new JPanel(new BorderLayout(0, 4));
        center.add(new JLabel(GetText.tr("Current Default Configs")), BorderLayout.NORTH);
        center.add(listArea, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        actions.add(copyFromWorld);
        actions.add(replaceFromWorld);
        actions.add(reveal);
        actions.add(remove);
        JPanel window = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        window.add(refresh);
        window.add(close);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(actions, BorderLayout.CENTER);
        buttons.add(window, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(explanation, BorderLayout.NORTH);
        content.add(center, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);

        copyFromWorld.setToolTipText(GetText.tr("Copy server configs from one of this instance's worlds"));
        copyFromWorld.addActionListener(e -> inBackground(() -> DefaultConfigsFromWorld.run(root(), prompts)));
        replaceFromWorld.setToolTipText(GetText.tr("Replace the selected default config with a file from a world"));
        replaceFromWorld.addActionListener(e -> withSelected(
            selected -> DefaultConfigsFromWorld.replaceFromWorld(root(), selected, prompts)));
        reveal.setToolTipText(GetText.tr("Show the selected default config in your file manager"));
        reveal.addActionListener(e -> withSelected(this::reveal));
        remove.setToolTipText(GetText.tr("Remove the selected default config"));
        remove.addActionListener(e -> withSelected(selected -> DefaultConfigsFromWorld.remove(root(), selected,
            App.settings != null && App.settings.useRecycleBin, prompts)));
        refresh.addActionListener(e -> refresh());
        close.addActionListener(e -> dispose());
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);

        refresh();
    }

    public static void open(Window parent, Instance instance) {
        new ManageWorldConfigDefaultsDialog(parent, instance).setVisible(true);
    }

    private Path root() {
        return instance.getRoot();
    }

    private void withSelected(java.util.function.Consumer<DefaultConfig> action) {
        DefaultConfig selected = list.getSelectedValue();
        if (selected != null) {
            inBackground(() -> action.accept(selected));
        }
    }

    // reveals the file only while it's still what was listed; the file manager shows it, never runs it
    private void reveal(DefaultConfig selected) {
        try {
            OS.openFileExplorer(DefaultConfigsFromWorld.current(root(), selected), true);
        } catch (IllegalStateException e) {
            prompts.tell(GetText.tr("{0} was changed or removed since it was listed, so it wasn't shown. The list "
                + "will be refreshed.", selected.relative));
        } catch (IllegalArgumentException | IOException e) {
            LogManager.warn("Not revealing a default config: " + e.getMessage());
            prompts.tell(GetText.tr("{0} couldn't be shown.", selected.relative));
        }
    }

    // one action at a time, off the event dispatch thread, then the list again
    private void inBackground(Runnable work) {
        if (busy) {
            return;
        }
        setBusy(true);

        Thread thread = new Thread(() -> {
            try {
                work.run();
            } finally {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    refresh();
                });
            }
        }, "WorldConfigDefaults");
        thread.setDaemon(true);
        thread.start();
    }

    private void refresh() {
        if (busy) {
            return;
        }
        setBusy(true);
        String selected = list.getSelectedValue() == null ? null : list.getSelectedValue().relative;
        showMessage(GetText.tr("Loading..."));

        Thread thread = new Thread(() -> {
            List<DefaultConfig> configs = Collections.emptyList();
            String problem = null;
            try {
                configs = DefaultConfigsFromWorld.defaultConfigs(root());
            } catch (IllegalArgumentException e) {
                problem = GetText.tr("This instance's defaultconfigs folder leads outside it, so it isn't listed.");
            } catch (IOException e) {
                LogManager.logStackTrace("Failed to list default configs", e);
                problem = GetText.tr("The default configs couldn't be listed: {0}", e.toString());
            }

            List<DefaultConfig> found = configs;
            String error = problem;
            SwingUtilities.invokeLater(() -> {
                model.clear();
                found.forEach(model::addElement);
                if (error != null) {
                    showMessage(error);
                } else if (found.isEmpty()) {
                    showMessage(GetText.tr("There are no default configs yet. Use Copy From World... to add some."));
                } else {
                    cards.show(listArea, LIST);
                    // the same file stays selected, when it's still there
                    for (int i = 0; i < found.size(); i++) {
                        if (found.get(i).relative.equals(selected)) {
                            list.setSelectedIndex(i);
                            list.ensureIndexIsVisible(i);
                        }
                    }
                }
                setBusy(false);
            });
        }, "WorldConfigDefaults");
        thread.setDaemon(true);
        thread.start();
    }

    private void showMessage(String text) {
        message.setText("<html><div style='text-align:center'>" + text.replace("&", "&amp;").replace("<", "&lt;")
            + "</div></html>");
        cards.show(listArea, MESSAGE);
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        updateButtons();
    }

    private void updateButtons() {
        boolean selected = !busy && list.getSelectedValue() != null;
        copyFromWorld.setEnabled(!busy);
        refresh.setEnabled(!busy);
        replaceFromWorld.setEnabled(selected);
        reveal.setEnabled(selected);
        remove.setEnabled(selected);
    }
}
