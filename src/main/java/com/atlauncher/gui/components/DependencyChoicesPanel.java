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
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.builders.HTMLBuilder;
import com.atlauncher.data.DependencyChoice;
import com.atlauncher.data.DependencyChoice.Action;
import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.ModManagement;
import com.atlauncher.data.RequiredDependency;
import com.atlauncher.gui.dialogs.ProgressDialog;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.utils.ComboItem;

/**
 * The dependencies of the file being added, one row each with a choice of what to do (see {@link DependencyChoice}).
 * The selectors fill it, and carry the choices out with {@link #apply} after adding the file itself.
 *
 * @param <F> the platform's file type (a Modrinth version or a CurseForge file)
 */
public final class DependencyChoicesPanel<F> extends JPanel implements Scrollable {
    /**
     * One dependency, as checked against the installed mods.
     */
    public static final class Item<F> {
        /** Tells two checks apart, to spot the installed mods changing under the list. */
        public final String key;
        public final String title;
        public final DependencyChoice.Kind kind;
        /** How it stands against the installed mods; null for an incompatible mod. */
        public final RequiredDependency.State state;
        /** What the row says about its installed state, or null. */
        public final String status;
        /** The installed mod the row is about, or null. */
        public final DisableableMod installed;
        /**
         * The installed mod is another project (a conflict, or a stand-in that isn't usable), so installing the
         * dependency takes it out.
         */
        public final boolean replacesInstalled;
        /** The dependency's project, for installing it; null when it couldn't be looked up. */
        public final Object project;
        /** Finds the file to install; null when there's nothing to install. Runs off the event thread. */
        public Supplier<Pick<F>> picker;
        /** Opens the dependency's own selector, as an alternative to the file picked here. */
        public Runnable choose;
        public Runnable view;

        public Item(String key, String title, DependencyChoice.Kind kind, RequiredDependency.State state,
            String status, DisableableMod installed, boolean replacesInstalled, Object project) {
            this.key = key;
            this.title = title;
            this.kind = kind;
            this.state = state;
            this.status = status;
            this.installed = installed;
            this.replacesInstalled = replacesInstalled;
            this.project = project;
        }
    }

    /**
     * The file picked for a dependency.
     */
    public static final class Pick<F> {
        public final F file;
        public final String name;
        /** Why it may not meet the requirement, or null. */
        public final String note;
        /** Whether it's newer than the installed version of the same project; null when unknown. */
        public final Boolean newerThanInstalled;

        public Pick(F file, String name, String note, Boolean newerThanInstalled) {
            this.file = file;
            this.name = name;
            this.note = note;
            this.newerThanInstalled = newerThanInstalled;
        }
    }

    private final List<Row<F>> rows = new ArrayList<>();

    public DependencyChoicesPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    }

    /**
     * Lists these dependencies, each with its default choice, and starts finding files for the required ones.
     */
    public void show(List<Item<F>> items) {
        removeAll();
        rows.clear();
        items.forEach(item -> {
            Row<F> row = new Row<>(item);
            rows.add(row);
            add(row);
        });
        revalidate();
        repaint();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * Whether a fresh check finds the dependencies as listed, so the choices still apply.
     */
    public boolean matches(List<Item<F>> fresh) {
        return rows.stream().map(r -> r.item.key).collect(Collectors.toList())
            .equals(fresh.stream().map(i -> i.key).collect(Collectors.toList()));
    }

    /**
     * A line for each choice that leaves a required dependency unmet or an incompatible mod enabled.
     */
    public List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        for (Row<F> row : rows) {
            String warning = row.warning();
            if (warning != null) {
                warnings.add(row.item.title + ": " + warning);
            }
        }
        return warnings;
    }

    /**
     * Carries the choices out: disables, removes and enables first, then installs. Returns what couldn't be done.
     *
     * @param progress shows which dependency is being installed; may be null
     * @param install  installs the picked file for a dependency, true when it's now installed
     */
    public List<String> apply(ModManagement instanceOrServer, ProgressDialog<Void> progress,
        BiPredicate<Item<F>, F> install) {
        List<String> problems = new ArrayList<>();

        for (Row<F> row : rows) {
            Item<F> item = row.item;
            Action action = row.action();
            if (action == Action.ENABLE && !item.installed.enable(instanceOrServer)) {
                problems.add(GetText.tr("{0}: it couldn't be enabled.", item.title));
            } else if (action == Action.DISABLE && !item.installed.disable(instanceOrServer)) {
                problems.add(GetText.tr("{0}: it couldn't be disabled.", item.title));
            } else if (action == Action.REMOVE) {
                instanceOrServer.removeMod(item.installed);
            }
        }

        for (Row<F> row : rows) {
            Item<F> item = row.item;
            if (!row.action().installs()) {
                continue;
            }

            Pick<F> pick = row.waitForPick();
            if (pick == null) {
                problems.add(GetText.tr("{0}: no file for this instance was found, so it wasn't installed.",
                    item.title));
                continue;
            }

            // first, as the new file may have the same name as the one coming out
            if (item.installed != null && item.replacesInstalled) {
                instanceOrServer.removeMod(item.installed);
            }

            if (progress != null) {
                // #. {0} is the name of the dependency being installed
                progress.setLabel(GetText.tr("Installing {0}", item.title));
            }
            if (!install.test(item, pick.file)) {
                problems.add(GetText.tr("{0}: it couldn't be installed.", item.title));
            }
        }

        instanceOrServer.save();
        return problems;
    }

    /**
     * Tells the user what of their dependency choices couldn't be done, if anything.
     */
    public static void showProblems(Window parent, List<String> problems) {
        if (problems.isEmpty()) {
            return;
        }

        StringBuilder content = new StringBuilder(GetText.tr("The mod was added, but not all of its dependency "
            + "choices could be carried out:")).append("<br/>");
        problems.forEach(p -> content.append("<br/>- ").append(escape(p)));

        DialogManager.okDialog().setParent(parent).setTitle(GetText.tr("Dependencies"))
            .setContent(new HTMLBuilder().text(content.toString()).build()).setType(DialogManager.WARNING).show();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String hex(Color color) {
        return color == null ? "#d8a000" : String.format("#%02x%02x%02x", color.getRed(), color.getGreen(),
            color.getBlue());
    }

    // one dependency's row: what it is, where it stands, and the choice
    private static final class Row<F> extends JPanel {
        private final Item<F> item;
        private final JComboBox<ComboItem<Action>> choice = new JComboBox<>();
        private final WrappingLabel text = new WrappingLabel();
        private volatile CompletableFuture<Pick<F>> pick;
        // set once the user picks, so a late default never overrides them
        private boolean touched = false;
        private boolean settingDefault = false;

        Row(Item<F> item) {
            super(new BorderLayout(8, 0));
            this.item = item;
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));

            for (Action action : DependencyChoice.actions(item.kind, item.state)) {
                choice.addItem(new ComboItem<>(action, action.label()));
            }
            choice.setEnabled(choice.getItemCount() > 1);
            select(DependencyChoice.defaultAction(item.kind, item.state, null));
            choice.addActionListener(e -> {
                touched |= !settingDefault;
                if (action().installs()) {
                    startPick();
                }
                refresh();
            });

            JPanel controls = new JPanel();
            controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
            choice.setAlignmentX(Component.RIGHT_ALIGNMENT);
            controls.add(choice);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
            buttons.setAlignmentX(Component.RIGHT_ALIGNMENT);
            if (item.choose != null) {
                JButton chooseButton = new JButton(GetText.tr("Choose File"));
                chooseButton.setToolTipText(GetText.tr("Pick the file yourself and install it now"));
                chooseButton.addActionListener(e -> item.choose.run());
                buttons.add(chooseButton);
            }
            if (item.view != null) {
                JButton viewButton = new JButton(GetText.tr("View"));
                viewButton.addActionListener(e -> item.view.run());
                buttons.add(viewButton);
            }
            controls.add(buttons);

            add(text, BorderLayout.CENTER);
            add(controls, BorderLayout.EAST);

            // a required one's file is looked up straight away, as it may decide the default
            if (item.kind == DependencyChoice.Kind.REQUIRED
                && DependencyChoice.actions(item.kind, item.state).stream().anyMatch(Action::installs)) {
                startPick();
            }
            refresh();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        private void select(Action action) {
            for (int i = 0; i < choice.getItemCount(); i++) {
                if (choice.getItemAt(i).getValue() == action) {
                    choice.setSelectedIndex(i);
                }
            }
        }

        Action action() {
            Object selected = choice.getSelectedItem();
            return selected == null ? Action.SKIP : ((ComboItem<Action>) selected).getValue();
        }

        String warning() {
            return DependencyChoice.warning(item.kind, item.state, action(), item.status);
        }

        private void startPick() {
            if (pick != null || item.picker == null) {
                return;
            }

            pick = CompletableFuture.supplyAsync(item.picker);
            pick.whenComplete((found, error) -> SwingUtilities.invokeLater(() -> {
                if (error != null) {
                    LogManager.logStackTrace("Finding a file for " + item.title, error);
                }
                // an older installed version is replaced by default, now that it's known to be older
                if (!touched && found != null && item.state == RequiredDependency.State.OTHER_VERSION) {
                    settingDefault = true;
                    select(DependencyChoice.defaultAction(item.kind, item.state, found.newerThanInstalled));
                    settingDefault = false;
                }
                refresh();
            }));
        }

        Pick<F> waitForPick() {
            startPick();
            try {
                return pick == null ? null : pick.join();
            } catch (RuntimeException e) {
                return null;
            }
        }

        private void refresh() {
            StringBuilder html = new StringBuilder("<b>").append(escape(item.title)).append("</b> &middot; ")
                .append(escape(item.kind.label()));

            if (item.status != null) {
                html.append("<br/>").append(escape(item.status));
            }

            if (action().installs()) {
                html.append("<br/>");
                if (pick == null || !pick.isDone()) {
                    html.append(escape(item.picker == null ? GetText.tr("Its project couldn't be found.")
                        : GetText.tr("Finding a file for this instance...")));
                } else {
                    Pick<F> found = pick.isCompletedExceptionally() ? null : pick.join();
                    html.append(escape(found == null
                        ? GetText.tr("No file for this instance was found. Use Choose File to pick one.")
                        // #. {0} is the file or version that will be installed
                        : GetText.tr("Installs {0}.", found.name)));
                    if (found != null && found.note != null) {
                        html.append("<br/>").append(warn(found.note));
                    }
                }
            }

            String warning = warning();
            if (warning != null && !warning.equals(item.status)) {
                html.append("<br/>").append(warn(warning));
            }

            text.setWrappedText(html.toString());
            revalidate();
        }

        private static String warn(String text) {
            return "<font color='" + hex(UIManager.getColor("yellow")) + "'>" + escape(text) + "</font>";
        }
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return visibleRect.height;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        // rows wrap to the dialog's width instead of scrolling sideways
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
