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
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.UIManager;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.InstalledDependencies;
import com.atlauncher.data.InstalledDependencies.DependentsChoice;
import com.atlauncher.gui.components.WrappingLabel;
import com.formdev.flatlaf.util.UIScale;

/**
 * Asked before Edit Mods removes mods that other installed mods need, or that leave dependencies nothing else needs.
 * It decides with the dependency data stored for each mod (see {@link InstalledDependencies}), and says which mods
 * have none.
 */
public final class ModRemovalDialog extends JDialog {
    private static final int MAX_NAMES = 6;

    private final InstalledDependencies deps;
    private final List<DisableableMod> selected;
    private final boolean everythingHasData;
    private final Map<DependentsChoice, JRadioButton> choices = new EnumMap<>(DependentsChoice.class);
    private final JPanel unneededPanel = new JPanel();
    // ticked unneeded dependencies, and the ones already shown, so a default never overrides the user
    private final Set<DisableableMod> ticked = new LinkedHashSet<>();
    private final Set<DisableableMod> seen = new HashSet<>();
    private InstalledDependencies.Plan result;

    private ModRemovalDialog(Window parent, InstalledDependencies deps, List<DisableableMod> selected,
        List<DisableableMod> affected, List<DisableableMod> withoutData) {
        super(parent, GetText.tr("Remove Mods"), ModalityType.DOCUMENT_MODAL);
        this.deps = deps;
        this.selected = selected;
        this.everythingHasData = withoutData.isEmpty();

        JPanel content = new WidthTrackingPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        content.add(text(GetText.tr("Remove {0}?", names(selected))));

        if (!affected.isEmpty()) {
            content.add(Box.createVerticalStrut(10));
            content.add(heading(GetText.tr("Other mods need these")));
            StringBuilder needing = new StringBuilder();
            Set<DisableableMod> going = new LinkedHashSet<>(selected);
            going.addAll(affected);
            for (DisableableMod mod : affected) {
                List<DisableableMod> needed = going.stream().filter(g -> deps.needs(mod, g))
                    .collect(Collectors.toList());
                // #. {0} is an installed mod, {1} the mods it needs
                needing.append(GetText.tr("{0} needs {1}.", mod.name, names(needed))).append("\n");
            }
            content.add(text(needing.toString().trim()));

            Set<DisableableMod> kept = new LinkedHashSet<>(selected);
            kept.removeAll(deps.withoutNeeded(selected));
            ButtonGroup group = new ButtonGroup();
            addChoice(content, group, DependentsChoice.KEEP_NEEDED,
                // #. {0} is the mods being kept
                GetText.tr("Keep the mods they need ({0}) and remove the rest", names(kept)));
            addChoice(content, group, DependentsChoice.REMOVE_ANYWAY, GetText.tr("Remove anyway"));
            addChoice(content, group, DependentsChoice.DISABLE_DEPENDENTS,
                GetText.tr("Remove, and disable the mods that need them"));
            addChoice(content, group, DependentsChoice.REMOVE_DEPENDENTS,
                GetText.tr("Remove, and remove the mods that need them too"));
            // the one that loses nothing
            choices.get(DependentsChoice.KEEP_NEEDED).setSelected(true);
        }

        unneededPanel.setLayout(new BoxLayout(unneededPanel, BoxLayout.Y_AXIS));
        unneededPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(unneededPanel);

        List<DisableableMod> selectedWithoutData = selected.stream().filter(m -> !InstalledDependencies.hasData(m))
            .collect(Collectors.toList());
        String notes = dataNotes(withoutData, selectedWithoutData);
        if (notes != null) {
            content.add(Box.createVerticalStrut(10));
            content.add(text(notes, UIManager.getColor("Label.disabledForeground")));
        }

        refreshUnneeded();

        JButton remove = new JButton(GetText.tr("Remove"));
        remove.addActionListener(e -> {
            result = deps.plan(selected, choice(), ticked);
            dispose();
        });
        JButton cancel = new JButton(GetText.tr("Cancel"));
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(remove);
        buttons.add(cancel);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        setLayout(new BorderLayout());
        add(scroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(remove);
        // Escape cancels, as in the launcher's other dialogs
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        // the notes wrap to the width, so the size is set rather than packed
        setSize(UIScale.scale(580), UIScale.scale(480));
        setLocationRelativeTo(parent);
    }

    /**
     * Asks what to do, when another mod needs one being removed or a dependency would be left unneeded. Returns the
     * plan, or null when the user cancels.
     *
     * @param affected    the mods that lose a dependency (see {@link InstalledDependencies#brokenByAll})
     * @param withoutData the mods staying that have no stored dependency data
     */
    static InstalledDependencies.Plan ask(Window parent, InstalledDependencies deps, List<DisableableMod> selected,
        List<DisableableMod> affected, List<DisableableMod> withoutData) {
        ModRemovalDialog dialog = new ModRemovalDialog(parent, deps, selected, affected, withoutData);
        dialog.setVisible(true);
        return dialog.result;
    }

    /**
     * What the user should know about mods with no stored dependency data, or null when every mod has it.
     */
    static String dataNotes(List<DisableableMod> withoutData, List<DisableableMod> selectedWithoutData) {
        List<String> notes = new ArrayList<>();
        if (!withoutData.isEmpty()) {
            // #. {0} is a list of installed mods
            notes.add(GetText.tr("No dependency data is stored for {0} (added by hand, or not found on CurseForge "
                + "or Modrinth), so the launcher can't tell whether they need what's being removed.",
                names(withoutData)));
        }
        if (!selectedWithoutData.isEmpty()) {
            // #. {0} is a list of mods being removed
            notes.add(GetText.tr("No dependency data is stored for {0}, so dependencies they leave unneeded "
                + "can't be found.", names(selectedWithoutData)));
        }
        return notes.isEmpty() ? null : String.join("\n\n", notes);
    }

    private DependentsChoice choice() {
        return choices.entrySet().stream().filter(e -> e.getValue().isSelected()).map(Map.Entry::getKey)
            .findFirst().orElse(DependentsChoice.REMOVE_ANYWAY);
    }

    private void addChoice(JPanel content, ButtonGroup group, DependentsChoice choice, String label) {
        JRadioButton button = new JRadioButton(label);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.addActionListener(e -> refreshUnneeded());
        group.add(button);
        choices.put(choice, button);
        content.add(button);
    }

    /**
     * Lists the dependencies nothing staying needs once the chosen mods are gone. Ticking one may leave what only it
     * needed unneeded too, so the list is worked out again until it settles. Libraries are ticked at first when every
     * mod staying has dependency data to go on.
     */
    private void refreshUnneeded() {
        Set<DisableableMod> base = deps.plan(selected, choice(), Collections.emptyList()).remove;
        List<DisableableMod> offered = Collections.emptyList();

        for (int i = 0; i <= selected.size() + seen.size() + 64; i++) {
            Set<DisableableMod> gone = new LinkedHashSet<>(base);
            gone.addAll(ticked);
            List<DisableableMod> now = deps.unneededAfter(base, gone);
            Set<DisableableMod> tickedBefore = new HashSet<>(ticked);

            ticked.retainAll(now);
            for (DisableableMod mod : now) {
                if (seen.add(mod) && everythingHasData && InstalledDependencies.isLibrary(mod)) {
                    ticked.add(mod);
                }
            }

            boolean settled = now.equals(offered) && ticked.equals(tickedBefore);
            offered = now;
            if (settled) {
                break;
            }
        }

        unneededPanel.removeAll();
        if (!offered.isEmpty()) {
            unneededPanel.add(Box.createVerticalStrut(10));
            unneededPanel.add(heading(GetText.tr("Nothing else needs these any more")));
            unneededPanel.add(text(everythingHasData
                ? GetText.tr("Tick the ones to remove too. Libraries are ticked.")
                : GetText.tr("Tick the ones to remove too. None are ticked, since some mods have no dependency "
                    + "data.")));
            for (DisableableMod mod : offered) {
                JCheckBox box = new JCheckBox(InstalledDependencies.isLibrary(mod)
                    // #. {0} is an installed mod's name
                    ? GetText.tr("{0} (library)", mod.name)
                    : mod.name, ticked.contains(mod));
                box.setAlignmentX(Component.LEFT_ALIGNMENT);
                box.addActionListener(e -> {
                    if (box.isSelected()) {
                        ticked.add(mod);
                    } else {
                        ticked.remove(mod);
                    }
                    refreshUnneeded();
                });
                unneededPanel.add(box);
            }
        }
        unneededPanel.revalidate();
        unneededPanel.repaint();
    }

    private static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        return label;
    }

    private static JPanel text(String text) {
        return text(text, null);
    }

    // wrapped to the dialog's width: the panel around it takes the full width but only the height it needs
    private static JPanel text(String text, Color colour) {
        WrappingLabel label = new WrappingLabel();
        label.setWrappedText(escape(text).replace("\n", "<br/>"));
        if (colour != null) {
            label.setForeground(colour);
        }

        JPanel row = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.add(label, BorderLayout.CENTER);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    // lays its rows out at the dialog's width, so the notes wrap instead of running off the side
    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
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
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    // a short list of mod names, with how many more there are
    static String names(Collection<DisableableMod> mods) {
        List<String> names = mods.stream().map(m -> m.name == null ? m.file : m.name).collect(Collectors.toList());
        if (names.size() <= MAX_NAMES) {
            return String.join(", ", names);
        }

        // #. {0} is a list of mod names, {1} how many more there are
        return GetText.tr("{0} and {1} more", String.join(", ", names.subList(0, MAX_NAMES)),
            names.size() - MAX_NAMES);
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
