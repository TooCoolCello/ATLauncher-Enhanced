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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.data.Instance;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.utils.DefaultConfigsFromWorld;
import com.atlauncher.utils.DefaultConfigsFromWorld.Copy;
import com.atlauncher.utils.DefaultConfigsFromWorld.Decision;

/**
 * The dialogs for copying a world's server configs to the instance's defaultconfigs folder. The files are read and
 * copied off the event dispatch thread; each choice is asked on it, and waited for.
 */
public final class WorldConfigDefaultsDialogs implements DefaultConfigsFromWorld.Choices {
    private final Window parent;

    private WorldConfigDefaultsDialogs(Window parent) {
        this.parent = parent;
    }

    public static void start(Window parent, Instance instance) {
        Thread thread = new Thread(() -> DefaultConfigsFromWorld.run(instance.getRoot(),
            new WorldConfigDefaultsDialogs(parent)), "WorldConfigDefaults");
        thread.setDaemon(true);
        thread.start();
    }

    private static <T> T onEdt(Supplier<T> ask) {
        if (SwingUtilities.isEventDispatchThread()) {
            return ask.get();
        }

        AtomicReference<T> answer = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> answer.set(ask.get()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            LogManager.logStackTrace("Failed to ask about copying world configs", e);
        }
        return answer.get();
    }

    private static JLabel wrapped(String text) {
        String html = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br/>");
        return new JLabel("<html><div style='width:460px'>" + html + "</div></html>");
    }

    private static JScrollPane scroll(java.awt.Component view, int rows) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.setPreferredSize(new Dimension(520, Math.min(rows, 12) * 24 + 8));
        return scroll;
    }

    private static JPanel content(String explanation, java.awt.Component main) {
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(wrapped(explanation), BorderLayout.NORTH);
        content.add(main, BorderLayout.CENTER);
        return content;
    }

    private void nothingChosen(String title) {
        DialogManager.okDialog().setParent(parent).setTitle(title)
            .setContent(GetText.tr("Nothing was chosen, so nothing was copied.")).show();
    }

    @Override
    public Path chooseWorld(List<Path> worlds) {
        return onEdt(() -> {
            String title = GetText.tr("Manage World Config Defaults");
            String[] names = worlds.stream().map(world -> world.getFileName().toString()).toArray(String[]::new);
            JList<String> list = new JList<>(names);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.getAccessibleContext().setAccessibleName(GetText.tr("Worlds"));

            int option = DialogManager.okCancelDialog().setParent(parent).setTitle(title)
                .setContent(content(GetText.tr("Choose the world whose server configs to copy to this instance's "
                    + "defaultconfigs folder. New worlds start with what's in defaultconfigs. The world chosen is "
                    + "only read, not changed."), scroll(list, names.length)))
                .show();
            if (option != 0) {
                return null;
            }
            if (list.getSelectedIndex() < 0) {
                nothingChosen(title);
                return null;
            }
            return worlds.get(list.getSelectedIndex());
        });
    }

    @Override
    public List<String> chooseFiles(Path world, List<String> files) {
        return onEdt(() -> {
            String title = GetText.tr("Choose Config Files");
            List<JCheckBox> boxes = new ArrayList<>();
            JPanel checklist = new JPanel();
            checklist.setLayout(new BoxLayout(checklist, BoxLayout.Y_AXIS));
            for (String file : files) {
                // all of them, now the world's chosen; any can be left out
                JCheckBox box = new JCheckBox(file, true);
                boxes.add(box);
                checklist.add(box);
            }

            JButton all = new JButton(GetText.tr("Select All"));
            all.addActionListener(e -> boxes.forEach(box -> box.setSelected(true)));
            JButton none = new JButton(GetText.tr("Select None"));
            none.addActionListener(e -> boxes.forEach(box -> box.setSelected(false)));
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
            buttons.add(all);
            buttons.add(none);

            JPanel main = new JPanel(new BorderLayout(0, 4));
            main.add(scroll(checklist, files.size()), BorderLayout.CENTER);
            main.add(buttons, BorderLayout.SOUTH);

            int option = DialogManager.okCancelDialog().setParent(parent).setTitle(title)
                .setContent(content(GetText.tr("The files in {0}. Choose which to copy; each goes to the same path "
                    + "in defaultconfigs.", world.resolve("serverconfig").toString()), main))
                .show();
            if (option != 0) {
                return null;
            }

            List<String> chosen = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                if (boxes.get(i).isSelected()) {
                    chosen.add(files.get(i));
                }
            }
            if (chosen.isEmpty()) {
                nothingChosen(title);
            }
            return chosen;
        });
    }

    @Override
    public Decision confirm(List<Copy> copies) {
        Decision decision = onEdt(() -> {
            long replacing = copies.stream().filter(copy -> copy.replaces).count();
            StringBuilder mapping = new StringBuilder();
            for (Copy copy : copies) {
                Path world = folderAbove(copy.source, copy.relative).getParent();
                mapping.append("saves/").append(world.getFileName()).append("/serverconfig/").append(copy.relative)
                    .append("\n    \u2192 defaultconfigs/").append(copy.relative);
                if (copy.replaces) {
                    mapping.append("   ").append(GetText.tr("[replaces the existing file]"));
                }
                mapping.append('\n');
            }

            JTextArea area = new JTextArea(mapping.toString().trim());
            area.setEditable(false);
            area.setLineWrap(false);
            area.getAccessibleContext().setAccessibleName(GetText.tr("Files to copy"));
            area.setCaretPosition(0);

            Path root = folderAbove(copies.get(0).destination, copies.get(0).relative).getParent();
            String explanation = GetText.tr("These files will be copied, in {0}. The world's own files aren't "
                + "changed, and neither are existing worlds.", root.toString());

            if (replacing == 0) {
                return DialogManager.confirmDialog().setParent(parent).setTitle(GetText.tr("Copy To defaultconfigs"))
                    .setContent(content(explanation, scroll(area, copies.size() * 2)))
                    .addOption(GetText.tr("Copy")).addOption(GetText.tr("Cancel"), true).show() == 0
                        ? Decision.COPY_ALL
                        : Decision.CANCEL;
            }

            int option = DialogManager.confirmDialog().setParent(parent)
                .setTitle(GetText.tr("Replace Existing Configs")).setType(DialogManager.WARNING)
                .setContent(content(explanation + "\n\n" + GetText.tr("{0} of them already exist in defaultconfigs "
                    + "and would be replaced. They're marked below.", replacing), scroll(area, copies.size() * 2)))
                .addOption(GetText.tr("Replace And Copy")).addOption(GetText.tr("Copy Only New Files"))
                .addOption(GetText.tr("Cancel"), true).show();
            return option == 0 ? Decision.COPY_ALL : option == 1 ? Decision.COPY_NEW_ONLY : Decision.CANCEL;
        });
        return decision == null ? Decision.CANCEL : decision;
    }

    // the serverconfig or defaultconfigs folder a file at this relative path is in
    private static Path folderAbove(Path file, String relative) {
        Path folder = file;
        for (int depth = relative.split("/").length; depth > 0; depth--) {
            folder = folder.getParent();
        }
        return folder;
    }

    @Override
    public void tell(String message) {
        onEdt(() -> DialogManager.okDialog().setParent(parent).setTitle(GetText.tr("Manage World Config Defaults"))
            .setContent(message).show());
    }
}
