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
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.mini2Dx.gettext.GetText;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.util.UIScale;

/**
 * The minimum (-Xms) and maximum (-Xmx) memory as two thumbs on one track, kept in step with the spinners that hold
 * the settings' values. Those spinners aren't shown: the values above the track take an exact number of MB when
 * clicked. The track is the same for everyone: 0 to 24 GiB, or 0 to 48 GiB expanded, with markers every 4 GiB and at
 * 32 and 48 GiB. Past the RAM detected on this machine it's greyed out, and no thumb can be moved there. The minimum
 * at 0 is Auto: no -Xms is added.
 *
 * <p>
 * It never changes a value on its own: a saved or inherited value past the RAM or past the track is kept, its exact
 * value shown and its thumb marked as out of range, until the user moves it. A drag only sets the spinners once it's
 * let go of; the keyboard sets them straight away.
 *
 * <p>
 * Where the thumbs overlap, the drag's direction picks one: left moves the minimum, right the maximum. The link toggle
 * moves both together instead. It's part of this dialog only, not a saved setting.
 */
public final class MemoryRangeSlider extends JPanel {
    public static final int STEP = 512;
    // the maximum memory spinner's minimum
    public static final int LOWEST_MAXIMUM = 512;
    // the track's two fixed scales, the same whatever the RAM. They're views, not limits on what's saved
    public static final int NORMAL_SCALE = 24576;
    public static final int EXPANDED_SCALE = 49152;
    // markers, which positions near them snap to
    public static final List<Integer> NORMAL_MARKS = Collections.unmodifiableList(Arrays.asList(4096, 8192, 12288,
        16384, 20480, 24576));
    public static final List<Integer> EXPANDED_MARKS = Collections.unmodifiableList(Arrays.asList(4096, 8192, 12288,
        16384, 20480, 24576, 32768, 49152));

    private final JSpinner minimumSpinner;
    private final JSpinner maximumSpinner;
    final int totalRam;
    // showing the 48 GiB scale
    boolean expanded = false;
    final JButton expand = new JButton();

    final JToggleButton link = new JToggleButton(new FlatSVGIcon("assets/icon/link-broken.svg"));
    final Track track = new Track();
    final Thumb minimumThumb = new Thumb(true);
    final Thumb maximumThumb = new Thumb(false);
    final ValueEntry minimumEntry;
    final ValueEntry maximumEntry;
    final WrappingLabel hint = new WrappingLabel();

    // as shown: the spinners' values, or a drag's until it's let go of
    int minimum;
    int maximum;
    private boolean updating = false;
    private final List<Consumer<Boolean>> linkListeners = new ArrayList<>();
    // what the link means at launch, when that isn't simply both values the same
    private String linkNote;

    private MemoryRangeSlider(JSpinner minimumSpinner, JSpinner maximumSpinner, int totalRam) {
        super(new BorderLayout(0, UIScale.scale(2)));
        this.minimumSpinner = minimumSpinner;
        this.maximumSpinner = maximumSpinner;
        this.totalRam = totalRam;
        // after the detected RAM, which bounds what they take
        minimumEntry = new ValueEntry(minimumThumb);
        maximumEntry = new ValueEntry(maximumThumb);

        link.setSelectedIcon(new FlatSVGIcon("assets/icon/link.svg"));
        link.putClientProperty("JButton.buttonType", "toolBarButton");
        link.addActionListener(e -> {
            // linking starts both from the maximum; unlinking keeps them as they are
            if (link.isSelected()) {
                set(minimumSpinner, value(maximumSpinner));
            }
            showLinkState();
            followSpinners();
            fireLinkChanged();
        });
        showLinkState();

        // the link first, as it's for both values
        JPanel values = new JPanel();
        values.setLayout(new BoxLayout(values, BoxLayout.X_AXIS));
        values.add(link);
        values.add(Box.createHorizontalStrut(UIScale.scale(4)));
        values.add(minimumEntry);
        values.add(Box.createHorizontalStrut(UIScale.scale(12)));
        values.add(maximumEntry);
        values.add(Box.createHorizontalGlue());

        track.add(maximumThumb);
        track.add(minimumThumb);

        // only when there's RAM to use past the normal scale
        expand.putClientProperty("JButton.buttonType", "toolBarButton");
        expand.setVisible(totalRam > NORMAL_SCALE);
        expand.addActionListener(e -> {
            expanded = !expanded;
            showValues();
        });
        JPanel expandPanel = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
        expandPanel.add(expand);
        JPanel trackRow = new JPanel(new BorderLayout(UIScale.scale(4), 0));
        trackRow.add(track, BorderLayout.CENTER);
        trackRow.add(expandPanel, BorderLayout.EAST);

        add(values, BorderLayout.NORTH);
        add(trackRow, BorderLayout.CENTER);
        add(hint, BorderLayout.SOUTH);

        maximumSpinner.addChangeListener(e -> {
            if (updating) {
                return;
            }

            if (link.isSelected()) {
                // later, once the change has reached the settings, which keep the minimum at or below the maximum:
                // this listener runs before the settings tab's own
                SwingUtilities.invokeLater(() -> {
                    if (link.isSelected()) {
                        set(minimumSpinner, value(maximumSpinner));
                        followSpinners();
                    }
                });
            }
            followSpinners();
        });

        minimumSpinner.addChangeListener(e -> {
            if (updating) {
                return;
            }

            if (link.isSelected()) {
                if (value(minimumSpinner) < LOWEST_MAXIMUM) {
                    // Auto has no maximum to match, so it ends the link
                    link.setSelected(false);
                    showLinkState();
                    fireLinkChanged();
                } else {
                    // the maximum first, as the settings keep the minimum at or below it
                    set(maximumSpinner, value(minimumSpinner));
                }
            }
            followSpinners();
        });

        followSpinners();
    }

    /**
     * A slider for these spinners, or null when this machine's RAM couldn't be detected (or is below the lowest
     * maximum), so the spinners stay on their own.
     *
     * @param totalRam the detected physical RAM in MB, or null/0 when unknown
     */
    public static MemoryRangeSlider forSpinners(JSpinner minimumSpinner, JSpinner maximumSpinner, Integer totalRam) {
        if (totalRam == null || totalRam < LOWEST_MAXIMUM) {
            return null;
        }

        return new MemoryRangeSlider(minimumSpinner, maximumSpinner, totalRam);
    }

    private static int value(JSpinner spinner) {
        return (Integer) spinner.getValue();
    }

    private void set(JSpinner spinner, int value) {
        if (value(spinner) == value) {
            return;
        }

        updating = true;
        try {
            spinner.setValue(value);
        } finally {
            updating = false;
        }
    }

    private void followSpinners() {
        minimum = value(minimumSpinner);
        maximum = value(maximumSpinner);
        showValues();
    }

    // the spinners, and so the settings, take the values shown
    private void commit() {
        // the maximum first, as the settings keep the minimum at or below it
        set(maximumSpinner, maximum);
        set(minimumSpinner, minimum);
        followSpinners();
    }

    private void showLinkState() {
        String action = link.isSelected() ? GetText.tr("Unlink minimum and maximum")
            : GetText.tr("Link minimum and maximum");
        String state = link.isSelected() ? GetText.tr("The minimum and maximum memory/ram move together.")
            : GetText.tr("The minimum and maximum memory/ram move separately.");
        if (link.isSelected() && linkNote != null) {
            state = state + " " + linkNote;
        }
        link.setToolTipText(link.isSelected() && linkNote != null ? action + ". " + linkNote : action);
        link.getAccessibleContext().setAccessibleName(action);
        link.getAccessibleContext().setAccessibleDescription(state);
    }

    /**
     * Shows the link as saved, without changing any value or telling the link listeners. It's only shown linked
     * while both values are the same: nothing else is what linked means.
     */
    public void showLinked(boolean linked) {
        link.setSelected(linked && minimum == maximum);
        showLinkState();
    }

    public boolean isLinked() {
        return link.isSelected();
    }

    /**
     * Told whenever the user links or unlinks them, which includes setting the minimum to Auto while linked.
     */
    public void addLinkListener(Consumer<Boolean> listener) {
        linkListeners.add(listener);
    }

    private void fireLinkChanged() {
        for (Consumer<Boolean> listener : linkListeners) {
            listener.accept(link.isSelected());
        }
    }

    /**
     * Something the link's tooltip adds while linked, such as a pack raising only -Xmx at launch; null for nothing.
     */
    public void setLinkNote(String note) {
        linkNote = note;
        showLinkState();
    }

    // the scale shown: 24 GiB, or 48 GiB expanded
    int scale() {
        return expanded ? EXPANDED_SCALE : NORMAL_SCALE;
    }

    // how far along it a thumb can go: the scale, or the detected RAM when that's less, whole step or not
    int usableEnd() {
        return Math.min(totalRam, scale());
    }

    // the most a new value can be, dragged or typed: the RAM, and at most the expanded scale
    int limit() {
        return Math.min(totalRam, EXPANDED_SCALE);
    }

    private void showRange() {
        // a value past the normal scale (saved, inherited or typed) shows the 48 GiB one, when this machine has
        // RAM to use there
        boolean pastNormalScale = expand.isVisible() && Math.max(minimum, maximum) > NORMAL_SCALE;
        if (pastNormalScale) {
            expanded = true;
        }

        expand.setText(expanded ? GetText.tr("Normal range") : GetText.tr("Expand range"));
        expand.setEnabled(!pastNormalScale);
        // #. {0} is a whole number of gibibytes (e.g. 48)
        if (!expanded) {
            expand.setToolTipText(GetText.tr("Show the slider up to {0} GiB", EXPANDED_SCALE / 1024));
        } else if (pastNormalScale) {
            // #. {0} is a whole number of gibibytes (e.g. 24)
            expand.setToolTipText(GetText.tr("A value is past {0} GiB, so the whole range stays shown",
                NORMAL_SCALE / 1024));
        } else {
            // #. {0} is a whole number of gibibytes (e.g. 24)
            expand.setToolTipText(GetText.tr("Show the slider up to {0} GiB again", NORMAL_SCALE / 1024));
        }
    }

    // why a value can't be shown where it is on the track, or null when it can
    String outOfRange(int value) {
        if (value > totalRam) {
            return GetText.tr("more than this machine's RAM");
        }
        if (value > scale()) {
            // #. {0} is a whole number of gibibytes (e.g. 48)
            return GetText.tr("past the slider's {0} GiB", scale() / 1024);
        }
        return null;
    }

    private String readout(String name, int value) {
        String reason = outOfRange(value);
        return reason == null ? GetText.tr("{0}: {1}", name, amount(value))
            : GetText.tr("{0}: {1} ({2})", name, amount(value), reason);
    }

    private void showValues() {
        showRange();
        String minimumReadout = readout(GetText.tr("Minimum"), minimum);
        String maximumReadout = readout(GetText.tr("Maximum"), maximum);
        minimumEntry.button.setText(minimumReadout);
        maximumEntry.button.setText(maximumReadout);
        minimumThumb.getAccessibleContext().setAccessibleDescription(minimumReadout);
        maximumThumb.getAccessibleContext().setAccessibleDescription(maximumReadout);

        hint.setWrappedText(hint(maximum, totalRam));
        // a saved value above this machine's RAM or past the slider is a warning, not a greyed-out aside
        boolean warning = maximum > totalRam || maximum > EXPANDED_SCALE;
        hint.setEnabled(warning);
        hint.setForeground(warning ? UIManager.getColor("yellow") : null);

        track.doLayout();
        track.repaint();
    }

    boolean isWarning() {
        return hint.isEnabled();
    }

    /**
     * Where a thumb goes for a value, kept within the detected RAM and the 48 GiB scale, and the minimum never above
     * the maximum. While linked, both go there. Past the normal scale, the 48 GiB one is shown.
     */
    void place(Thumb thumb, int value) {
        if (link.isSelected()) {
            minimum = maximum = clamp(value, LOWEST_MAXIMUM, limit());
        } else if (thumb == minimumThumb) {
            minimum = clamp(value, 0, Math.min(maximum, limit()));
        } else {
            maximum = clamp(value, Math.max(LOWEST_MAXIMUM, minimum), limit());
        }
        showValues();
    }

    private static int clamp(int value, int lowest, int highest) {
        return Math.max(lowest, Math.min(value, highest));
    }

    // the keyboard sets the spinners straight away
    private void keyTo(Thumb thumb, int value) {
        // a key that can't move it leaves it be, even a saved value above the track
        if (value == thumb.value()) {
            return;
        }

        place(thumb, value);
        commit();
    }

    /**
     * An exact value typed for a thumb, following the same rules as moving it. 0 for the minimum is Auto, which ends a
     * link, as nothing can match it.
     */
    void enterExactly(Thumb thumb, int value) {
        if (thumb == minimumThumb && value <= 0) {
            toAuto();
        } else {
            keyTo(thumb, value);
        }
    }

    // the minimum unset: no -Xms is added
    void toAuto() {
        if (link.isSelected()) {
            link.setSelected(false);
            showLinkState();
            fireLinkChanged();
        }
        keyTo(minimumThumb, 0);
    }

    /**
     * The next 512 MB step from a value, up or down, stopping at the track's end even when that isn't a whole step. A
     * saved value past the end only goes down, to the end.
     */
    static int step(int value, boolean up, int end) {
        if (up) {
            return value >= end ? value : Math.min((value / STEP + 1) * STEP, end);
        }

        return value > end ? end : ((value + STEP - 1) / STEP - 1) * STEP;
    }

    /**
     * The next marker from a value on this scale, up or down, or the usable end when there's none before it.
     */
    static int quickStep(int value, boolean up, int scale, int end) {
        List<Integer> points = quickPoints(scale, end);
        if (up) {
            return value >= end ? value : points.stream().filter(point -> point > value).findFirst().orElse(end);
        }
        if (value > end) {
            return end;
        }

        return points.stream().filter(point -> point < value).reduce((first, second) -> second).orElse(0);
    }

    /**
     * The markers on this scale: every 4 GiB to 24 GiB, then 32 and 48 GiB on the expanded one.
     */
    static List<Integer> marks(int scale) {
        return scale > NORMAL_SCALE ? EXPANDED_MARKS : NORMAL_MARKS;
    }

    /**
     * The markers on this scale a thumb can reach, up to the usable end.
     */
    public static List<Integer> quickPoints(int scale, int end) {
        List<Integer> points = new ArrayList<>();
        for (int point : marks(scale)) {
            if (point <= end) {
                points.add(point);
            }
        }
        return points;
    }

    /**
     * The value for a position on a scale usable up to this end: the nearest marker when close enough to one (a
     * fortieth of the scale, at least half a step), otherwise the nearest 512 MB step, or the usable end itself when
     * that's nearer than the last step before it.
     */
    static int snap(int position, int scale, int end) {
        int reach = Math.max(STEP / 2, scale / 40);

        for (int point : quickPoints(scale, end)) {
            if (Math.abs(position - point) <= reach) {
                return point;
            }
        }

        int lastStep = end / STEP * STEP;
        if (position > lastStep) {
            return position - lastStep < end - position ? lastStep : end;
        }
        return Math.round(position / (float) STEP) * STEP;
    }

    /**
     * A value as the labels above the track show it, exactly: Auto, GiB for whole 512 MB steps, else MB.
     */
    static String amount(int value) {
        if (value <= 0) {
            return GetText.tr("Auto");
        }
        if (value % STEP != 0) {
            return GetText.tr("{0} MB", value);
        }

        // #. {0} is a number of gibibytes, maybe with a half (e.g. 8 or 4.5)
        return GetText.tr("{0} GiB", value / 1024.0);
    }

    /**
     * What to say under the slider about the maximum against the machine's RAM.
     */
    public static String hint(int value, int totalRam) {
        if (value > totalRam) {
            return GetText.tr("Your saved {0} MB is more than the {1} MB of RAM detected here. It's kept as it is; "
                + "lower it if Minecraft won't start.", value, totalRam);
        }

        if (value > EXPANDED_SCALE) {
            return GetText.tr("Your saved {0} MB is past the slider, which goes up to {1} MB. It's kept as it is; "
                + "click the value above to change it.", value, EXPANDED_SCALE);
        }

        if (value > totalRam * 3 / 4) {
            return GetText.tr("This leaves little of the {0} MB of RAM detected here for your system and other "
                + "programs.", totalRam);
        }

        return GetText.tr("{0} MB of RAM detected here. Leave some for your system and other programs.", totalRam);
    }

    // the thumbs' focus ring
    private static int ring() {
        return UIScale.scale(2);
    }

    private static Color color(String key, String fallbackKey) {
        Color color = UIManager.getColor(key);
        if (color == null) {
            color = UIManager.getColor(fallbackKey);
        }
        return color == null ? Color.GRAY : color;
    }

    /**
     * A value above the track: a button showing it which, when pressed, becomes a spinner for typing an exact number
     * of MB, until Enter, Escape (which drops what was typed) or focus leaves it.
     */
    final class ValueEntry extends JPanel {
        private static final String SHOWN = "shown";
        private static final String EDITING = "editing";

        final Thumb thumb;
        final JButton button = new JButton();
        final JSpinner exact = new JSpinner(new SpinnerNumberModel(Integer.valueOf(0), null, null,
            Integer.valueOf(STEP)));
        final JButton auto = new JButton(GetText.tr("Auto"));
        final JLabel linkedNote = new JLabel(GetText.tr("sets both"));
        final JFormattedTextField field;
        private final CardLayout cards = new CardLayout();
        private boolean editing = false;

        ValueEntry(Thumb thumb) {
            this.thumb = thumb;
            setLayout(cards);
            int side = FlowLayout.LEADING;

            button.putClientProperty("JButton.buttonType", "toolBarButton");
            button.setToolTipText(thumb.isMinimum ? GetText.tr("Type an exact minimum in MB")
                : GetText.tr("Type an exact maximum in MB"));
            button.addActionListener(e -> startEditing());
            JPanel shown = new JPanel(new FlowLayout(side, 0, 0));
            shown.add(button);

            SpinnerNumberModel model = (SpinnerNumberModel) exact.getModel();
            model.setMinimum(thumb.isMinimum ? 0 : LOWEST_MAXIMUM);
            // any value up to the detected RAM and the 48 GiB scale; past 24 GiB, it shows the 48 GiB scale
            model.setMaximum(limit());
            exact.setEditor(new JSpinner.NumberEditor(exact, "#"));
            field = ((JSpinner.DefaultEditor) exact.getEditor()).getTextField();
            field.setColumns(5);
            field.getAccessibleContext().setAccessibleName(thumb.isMinimum
                ? GetText.tr("Minimum memory/ram in MB")
                : GetText.tr("Maximum memory/ram in MB"));

            // arrows and committed typing apply straight away, like the thumb's keys
            exact.addChangeListener(e -> {
                if (editing) {
                    enterExactly(thumb, value(exact));
                    showExactly(thumb.value());
                }
            });
            // Enter, once what was typed is committed
            field.addActionListener(e -> stopEditing());
            field.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelExactly");
            field.getActionMap().put("cancelExactly", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    cancelEditing();
                }
            });
            field.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    Component to = e.getOppositeComponent();
                    if (editing && !e.isTemporary()
                        && (to == null || !SwingUtilities.isDescendingFrom(to, ValueEntry.this))) {
                        commitTyped();
                        stopEditing();
                    }
                }
            });

            JPanel editor = new JPanel(new FlowLayout(side, UIScale.scale(4), 0));
            editor.add(new JLabel(thumb.isMinimum ? GetText.tr("Minimum:") : GetText.tr("Maximum:")));
            editor.add(exact);
            editor.add(new JLabel(GetText.tr("MB")));
            linkedNote.setEnabled(false);
            linkedNote.setToolTipText(GetText.tr("The minimum and maximum are linked, so this sets both"));
            editor.add(linkedNote);
            if (thumb.isMinimum) {
                auto.putClientProperty("JButton.buttonType", "toolBarButton");
                auto.setToolTipText(GetText.tr("Set the minimum to Auto, so Java decides it"));
                auto.addActionListener(e -> {
                    toAuto();
                    stopEditing();
                });
                editor.add(auto);
            }

            add(shown, SHOWN);
            add(editor, EDITING);
        }

        boolean isEditing() {
            return editing;
        }

        void startEditing() {
            showExactly(thumb.value());
            linkedNote.setVisible(link.isSelected());
            field.getAccessibleContext().setAccessibleDescription(link.isSelected()
                ? GetText.tr("The minimum and maximum are linked, so this sets both")
                : null);
            editing = true;
            cards.show(this, EDITING);
            field.requestFocusInWindow();
            SwingUtilities.invokeLater(field::selectAll);
        }

        void stopEditing() {
            if (!editing) {
                return;
            }

            Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            boolean hadFocus = focused != null && SwingUtilities.isDescendingFrom(focused, this);
            editing = false;
            cards.show(this, SHOWN);
            if (hadFocus) {
                button.requestFocusInWindow();
            }
        }

        // drops what was typed but not yet entered
        void cancelEditing() {
            showExactly(thumb.value());
            stopEditing();
        }

        void commitTyped() {
            try {
                field.commitEdit();
            } catch (ParseException e) {
                // not a number in range: left as it was
                showExactly(thumb.value());
            }
        }

        private void showExactly(int value) {
            boolean wasEditing = editing;
            editing = false;
            try {
                exact.setValue(value);
                field.setValue(value);
            } finally {
                editing = wasEditing;
            }
        }

        // as big as what it shows, so the values sit together, and the maximum moves over while the minimum's edited
        @Override
        public Dimension getPreferredSize() {
            for (Component card : getComponents()) {
                if (card.isVisible()) {
                    return card.getPreferredSize();
                }
            }
            return super.getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }
    }

    /**
     * A thumb: focusable, so each can be moved with the keyboard, and named for screen readers. The track handles the
     * mouse for both.
     */
    final class Thumb extends JComponent implements Accessible {
        final boolean isMinimum;

        Thumb(boolean isMinimum) {
            this.isMinimum = isMinimum;
            setFocusable(true);
            getAccessibleContext().setAccessibleName(isMinimum ? GetText.tr("Minimum memory/ram (-Xms)")
                : GetText.tr("Maximum memory/ram (-Xmx)"));

            bind("decrease", () -> keyTo(this, step(value(), false, usableEnd())), KeyEvent.VK_LEFT,
                KeyEvent.VK_DOWN);
            bind("increase", () -> keyTo(this, step(value(), true, usableEnd())), KeyEvent.VK_RIGHT, KeyEvent.VK_UP);
            bind("previousQuickPoint", () -> keyTo(this, quickStep(value(), false, scale(), usableEnd())),
                KeyEvent.VK_PAGE_DOWN);
            bind("nextQuickPoint", () -> keyTo(this, quickStep(value(), true, scale(), usableEnd())),
                KeyEvent.VK_PAGE_UP);
            bind("lowest", () -> keyTo(this, 0), KeyEvent.VK_HOME);
            // a saved value past the usable end is already past it
            bind("highest", () -> keyTo(this, Math.max(value(), usableEnd())), KeyEvent.VK_END);
            // as a double-click does
            bind("editExactly", () -> entry().startEditing(), KeyEvent.VK_ENTER);

            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    repaint();
                }
            });
        }

        private void bind(String name, Runnable action, int... keys) {
            for (int key : keys) {
                getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
            }
            getActionMap().put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    action.run();
                }
            });
        }

        int value() {
            return isMinimum ? minimum : maximum;
        }

        ValueEntry entry() {
            return isMinimum ? minimumEntry : maximumEntry;
        }

        // a saved value no thumb could be moved to: past the RAM or past the scale
        boolean isOutOfRange() {
            return value() > usableEnd();
        }

        // past the end of the scale, so it can't be shown where it is at all
        boolean isOffScale() {
            return value() > scale();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (isFocusOwner()) {
                g2.setColor(color("Slider.focusedColor", "Component.focusColor"));
                g2.fillOval(0, 0, getWidth(), getHeight());
            }

            int ring = ring();
            int diameter = getWidth() - ring * 2;

            // out of range, it's hollow in the warning colour; past the scale, it points off the end
            if (isOutOfRange()) {
                Color warning = UIManager.getColor("yellow");
                g2.setColor(warning == null ? color("Component.warning.focusedBorderColor", "Label.foreground")
                    : warning);
                if (isOffScale()) {
                    int[] xs = { ring, getWidth() - ring, ring };
                    int[] ys = { ring, getHeight() / 2, getHeight() - ring };
                    g2.fillPolygon(xs, ys, 3);
                } else {
                    g2.fillOval(ring, ring, diameter, diameter);
                    int inset = UIScale.scale(3);
                    g2.setColor(color("Slider.background", "Panel.background"));
                    g2.fillOval(ring + inset, ring + inset, diameter - inset * 2, diameter - inset * 2);
                }
                g2.dispose();
                return;
            }

            g2.setColor(color("Slider.thumbColor", "Component.accentColor"));
            g2.fillOval(ring, ring, diameter, diameter);

            // hollow at Auto: no minimum is set
            if (isMinimum && minimum <= 0) {
                int inset = UIScale.scale(3);
                g2.setColor(color("Slider.background", "Panel.background"));
                g2.fillOval(ring + inset, ring + inset, diameter - inset * 2, diameter - inset * 2);
            }
            g2.dispose();
        }

        @Override
        public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) {
                accessibleContext = new AccessibleJComponent() {
                    @Override
                    public AccessibleRole getAccessibleRole() {
                        return AccessibleRole.SLIDER;
                    }
                };
            }
            return accessibleContext;
        }
    }

    /**
     * The track, its quick-selection marks and the thumbs on it. The thumbs don't take the mouse themselves, so the
     * track decides which one a press grabs and keeps it until release.
     */
    final class Track extends JComponent implements Accessible {
        private Thumb grabbed;
        // the last choice offered by a double-click where the thumbs overlap
        JPopupMenu choice;
        // pressed where the thumbs overlap: the drag's direction picks one
        private boolean undecided;
        private int pressX;
        private int grabOffset;

        Track() {
            setLayout(null);
            setToolTipText(GetText.tr("Drag the left thumb for the minimum and the right one for the maximum. At the "
                + "far left, the minimum is Auto. Double-click a thumb to type its value."));

            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        press(e.getX());
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    drag(e.getX());
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    release();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                        doubleClick(e.getX());
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private int diameter() {
            return UIScale.scale(14);
        }

        // the thumbs' size, focus ring included
        private int thumbSize() {
            return diameter() + ring() * 2;
        }

        private int trackY() {
            return thumbSize() / 2;
        }

        private int left() {
            return thumbSize() / 2;
        }

        private int right() {
            return Math.max(left() + 1, getWidth() - thumbSize() / 2);
        }

        // on the fixed scale; a value past it sits at its end
        int xFor(int value) {
            return left() + Math.round(clamp(value, 0, scale()) / (float) scale() * (right() - left()));
        }

        int valueAt(int x) {
            return Math.round((x - left()) / (float) (right() - left()) * scale());
        }

        void press(int x) {
            int minimumX = xFor(minimum);
            int maximumX = xFor(maximum);
            int reach = diameter() / 2;
            boolean onMinimum = Math.abs(x - minimumX) <= reach;
            boolean onMaximum = Math.abs(x - maximumX) <= reach;

            pressX = x;
            undecided = false;
            grabbed = null;

            if (onMinimum && onMaximum && !link.isSelected()) {
                undecided = true;
                return;
            }

            if (onMinimum || onMaximum) {
                grabbed = onMinimum ? minimumThumb : maximumThumb;
                grabOffset = x - xFor(grabbed.value());
            } else {
                // on the track: the nearer thumb jumps there, and the outer one where they overlap
                int toMinimum = Math.abs(x - minimumX);
                int toMaximum = Math.abs(x - maximumX);
                grabbed = toMinimum < toMaximum || (toMinimum == toMaximum && x < minimumX) ? minimumThumb
                    : maximumThumb;
                grabOffset = 0;
                place(grabbed, snap(valueAt(x), scale(), usableEnd()));
            }
            grabbed.requestFocusInWindow();
        }

        void drag(int x) {
            if (undecided) {
                if (x == pressX) {
                    return;
                }

                grabbed = x < pressX ? minimumThumb : maximumThumb;
                grabOffset = pressX - xFor(grabbed.value());
                undecided = false;
                grabbed.requestFocusInWindow();
            }

            if (grabbed != null) {
                place(grabbed, snap(valueAt(x - grabOffset), scale(), usableEnd()));
            }
        }

        /**
         * A thumb double-clicked opens its exact editor. Where unlinked thumbs overlap, it asks which, rather than
         * guessing; linked, they're the same value and either editor sets both.
         */
        void doubleClick(int x) {
            int reach = diameter() / 2;
            boolean onMinimum = Math.abs(x - xFor(minimum)) <= reach;
            boolean onMaximum = Math.abs(x - xFor(maximum)) <= reach;

            if (onMinimum && onMaximum && !link.isSelected()) {
                choice = new JPopupMenu();
                JMenuItem editMinimum = new JMenuItem(GetText.tr("Edit minimum ({0})", amount(minimum)));
                editMinimum.addActionListener(e -> minimumEntry.startEditing());
                JMenuItem editMaximum = new JMenuItem(GetText.tr("Edit maximum ({0})", amount(maximum)));
                editMaximum.addActionListener(e -> maximumEntry.startEditing());
                choice.add(editMinimum);
                choice.add(editMaximum);
                if (isShowing()) {
                    choice.show(this, x, trackY() + thumbSize() / 2);
                }
            } else if (onMaximum) {
                maximumEntry.startEditing();
            } else if (onMinimum) {
                minimumEntry.startEditing();
            }
        }

        void release() {
            if (grabbed != null) {
                commit();
            }
            grabbed = null;
            undecided = false;
        }

        @Override
        public void doLayout() {
            int size = thumbSize();
            maximumThumb.setBounds(xFor(maximum) - size / 2, trackY() - size / 2, size, size);
            minimumThumb.setBounds(xFor(minimum) - size / 2, trackY() - size / 2, size, size);
        }

        // Auto at the start, then the scale's markers, those past the RAM included
        List<Integer> marks() {
            List<Integer> marks = new ArrayList<>();
            marks.add(0);
            marks.addAll(MemoryRangeSlider.marks(scale()));
            return marks;
        }

        private String label(int mark) {
            // #. {0} is a whole number of gibibytes (e.g. 8)
            return mark == 0 ? GetText.tr("Auto") : GetText.tr("{0} GiB", mark / 1024);
        }

        int labelX(int mark, FontMetrics metrics) {
            int width = metrics.stringWidth(label(mark));
            return clamp(xFor(mark) - width / 2, 0, Math.max(0, getWidth() - width));
        }

        /**
         * The marks labelled at this width, kept inside the track: Auto and the scale's end first, then from the left
         * each that doesn't crowd one already labelled. The rest still have their tick and a tooltip.
         */
        List<Integer> labelledMarks(FontMetrics metrics) {
            List<Integer> marks = marks();
            List<Integer> order = new ArrayList<>();
            order.add(marks.get(0));
            order.add(marks.get(marks.size() - 1));
            order.addAll(marks.subList(1, marks.size() - 1));

            int gap = UIScale.scale(8);
            List<Integer> labelled = new ArrayList<>();
            for (int mark : order) {
                int x = labelX(mark, metrics);
                int end = x + metrics.stringWidth(label(mark));
                boolean crowded = false;
                for (int other : labelled) {
                    int otherX = labelX(other, metrics);
                    int otherEnd = otherX + metrics.stringWidth(label(other));
                    crowded |= x < otherEnd + gap && otherX < end + gap;
                }
                if (!crowded) {
                    labelled.add(mark);
                }
            }
            labelled.sort(null);
            return labelled;
        }

        // where the part of the track past the RAM starts, or null when there's none on this scale
        Integer greyedFrom() {
            return totalRam < scale() ? xFor(totalRam) : null;
        }

        private int tickTop() {
            return trackY() + diameter() / 2 + UIScale.scale(2);
        }

        private int tickLength() {
            return UIScale.scale(4);
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int x = e.getX();
            int reach = diameter() / 2;

            if (Math.abs(x - xFor(minimum)) <= reach && Math.abs(x - xFor(maximum)) <= reach) {
                return GetText.tr("Minimum {0}, maximum {1}. Drag left for the minimum or right for the maximum.",
                    amount(minimum), amount(maximum));
            }
            if (Math.abs(x - xFor(minimum)) <= reach) {
                return GetText.tr("Minimum memory/ram (-Xms): {0}", amount(minimum));
            }
            if (Math.abs(x - xFor(maximum)) <= reach) {
                return GetText.tr("Maximum memory/ram (-Xmx): {0}", amount(maximum));
            }

            // the nearest mark, as they can crowd together on a narrow track
            Integer nearest = null;
            for (int mark : marks()) {
                if (Math.abs(x - xFor(mark)) <= reach
                    && (nearest == null || Math.abs(x - xFor(mark)) < Math.abs(x - xFor(nearest)))) {
                    nearest = mark;
                }
            }

            Integer greyedFrom = greyedFrom();
            if (nearest == null) {
                if (greyedFrom != null && x > greyedFrom) {
                    return GetText.tr("Past the {0} MB of RAM detected here, so it can't be used", totalRam);
                }
                return super.getToolTipText(e);
            }
            if (nearest == 0) {
                return GetText.tr("Auto: no minimum, Java decides");
            }
            return nearest > totalRam
                ? GetText.tr("{0} GiB ({1} MB), more than the RAM detected here", nearest / 1024, nearest)
                : GetText.tr("{0} GiB ({1} MB)", nearest / 1024, nearest);
        }

        // so screen readers find the thumbs inside it
        @Override
        public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) {
                accessibleContext = new AccessibleJComponent() {
                    @Override
                    public AccessibleRole getAccessibleRole() {
                        return AccessibleRole.PANEL;
                    }
                };
                accessibleContext.setAccessibleName(GetText.tr("Memory/Ram"));
            }
            return accessibleContext;
        }

        @Override
        public Dimension getPreferredSize() {
            if (isPreferredSizeSet()) {
                return super.getPreferredSize();
            }

            FontMetrics metrics = getFontMetrics(getFont());
            // a modest width: the layout stretches it to fill its column
            return new Dimension(UIScale.scale(300), tickTop() + tickLength() + UIScale.scale(2)
                + metrics.getHeight());
        }

        @Override
        public Dimension getMinimumSize() {
            Dimension preferred = getPreferredSize();
            return new Dimension(UIScale.scale(150), preferred.height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int height = UIScale.scale(4);
            int y = trackY() - height / 2;
            g2.setColor(color("Slider.trackColor", "Component.borderColor"));
            g2.fillRoundRect(left(), y, right() - left(), height, height, height);

            // between the thumbs, in the theme's accent
            int from = xFor(Math.min(minimum, maximum));
            int to = xFor(Math.max(minimum, maximum));
            g2.setColor(color("Slider.trackValueColor", "Component.accentColor"));
            g2.fillRoundRect(from, y, Math.max(height, to - from), height, height, height);

            // past the RAM, greyed out: the scale goes on, but no thumb can go there
            Integer greyedFrom = greyedFrom();
            if (greyedFrom != null) {
                g2.setColor(color("Slider.disabledTrackColor", "Component.disabledBorderColor"));
                g2.fillRect(greyedFrom, y, right() - greyedFrom, height);
                g2.setColor(color("Slider.tickColor", "Label.disabledForeground"));
                g2.fillRect(greyedFrom, trackY() - diameter() / 2, Math.max(1, UIScale.scale(1)), diameter());
            }

            g2.setFont(getFont());
            FontMetrics metrics = g2.getFontMetrics();
            List<Integer> labelled = labelledMarks(metrics);
            int baseline = tickTop() + tickLength() + UIScale.scale(2) + metrics.getAscent();

            for (int mark : marks()) {
                int x = xFor(mark);
                g2.setColor(color("Slider.tickColor", "Label.disabledForeground"));
                g2.fillRect(x, tickTop(), Math.max(1, UIScale.scale(1)), tickLength());

                if (labelled.contains(mark)) {
                    g2.setColor(mark > totalRam ? color("Label.disabledForeground", "Slider.tickColor")
                        : getForeground());
                    g2.drawString(label(mark), labelX(mark, metrics), baseline);
                }
            }
            g2.dispose();
        }
    }
}
