/*
 * ATLauncher - https://github.com/ATLauncher/ATLauncher
 * Copyright (C) 2013-2022 ATLauncher
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
package com.atlauncher.managers;

import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.swing.Icon;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.gui.dialogs.BrowserDownloadDialog;
import com.atlauncher.gui.dialogs.ProgressDialog;
import com.atlauncher.utils.DialogDebugMonitor;

public final class DialogManager {
    public static final int OPTION_TYPE = 0;
    public static final int CONFIRM_TYPE = 1;
    public static final int OK_TYPE = 1;

    public static final int ERROR = JOptionPane.ERROR_MESSAGE;
    public static final int INFO = JOptionPane.INFORMATION_MESSAGE;
    public static final int WARNING = JOptionPane.WARNING_MESSAGE;
    public static final int QUESTION = JOptionPane.QUESTION_MESSAGE;

    public static final int DEFAULT_OPTION = JOptionPane.DEFAULT_OPTION;
    public static final int YES_NO_OPTION = JOptionPane.YES_NO_OPTION;
    public static final int YES_NO_CANCEL_OPTION = JOptionPane.YES_NO_CANCEL_OPTION;
    public static final int OK_CANCEL_OPTION = JOptionPane.OK_CANCEL_OPTION;

    public static final int YES_OPTION = JOptionPane.YES_OPTION;
    public static final int NO_OPTION = JOptionPane.NO_OPTION;
    public static final int CANCEL_OPTION = JOptionPane.CANCEL_OPTION;
    public static final int OK_OPTION = JOptionPane.OK_OPTION;
    public static final int CLOSED_OPTION = JOptionPane.CLOSED_OPTION;

    public int dialogType;
    public Window parent;
    public String title;
    public Object content;
    public List<String> options = new ArrayList<>();
    public Icon icon = null;
    public int lookAndFeel = DialogManager.DEFAULT_OPTION;
    public String defaultOption = null;
    public int type = DialogManager.QUESTION;

    private DialogManager(int dialogType) {
        this.dialogType = dialogType;
    }

    public static DialogManager optionDialog() {
        return new DialogManager(DialogManager.OPTION_TYPE);
    }

    public static DialogManager confirmDialog() {
        return new DialogManager(DialogManager.CONFIRM_TYPE);
    }

    public static DialogManager okDialog() {
        DialogManager dialog = new DialogManager(DialogManager.CONFIRM_TYPE);

        dialog.addOption(GetText.tr("Ok"), true);

        return dialog;
    }

    public static DialogManager okCancelDialog() {
        DialogManager dialog = new DialogManager(DialogManager.CONFIRM_TYPE);

        dialog.addOption(GetText.tr("Ok"), true);
        dialog.addOption(GetText.tr("Cancel"));

        return dialog;
    }

    public static DialogManager yesNoDialog() {
        return yesNoDialog(true);
    }

    public static DialogManager yesNoDialog(boolean yesDefault) {
        DialogManager dialog = new DialogManager(DialogManager.CONFIRM_TYPE);

        dialog.addOption(GetText.tr("Yes"), yesDefault);
        dialog.addOption(GetText.tr("No"), !yesDefault);

        return dialog;
    }

    public static DialogManager yesNoCancelDialog() {
        DialogManager dialog = new DialogManager(DialogManager.CONFIRM_TYPE);

        dialog.addOption(GetText.tr("Yes"), true);
        dialog.addOption(GetText.tr("No"));
        dialog.addOption(GetText.tr("Cancel"));

        return dialog;
    }

    public DialogManager setParent(Window parent) {
        this.parent = parent;
        return this;
    }

    public DialogManager setLookAndFeel(int lookAndFeel) {
        this.lookAndFeel = lookAndFeel;
        return this;
    }

    public DialogManager setTitle(String title) {
        this.title = title;
        return this;
    }

    public DialogManager setContent(Object content) {
        this.content = content;
        return this;
    }

    public DialogManager setType(int type) {
        this.type = type;
        return this;
    }

    public DialogManager setDefaultOption(String defaultOption) {
        this.defaultOption = defaultOption;
        return this;
    }

    public DialogManager setIcon(Icon icon) {
        this.icon = icon;
        return this;
    }

    public DialogManager addOption(String option, boolean isDefault) {
        this.options.add(option);

        if (isDefault) {
            this.defaultOption = option;
        }

        return this;
    }

    public DialogManager addOption(String option) {
        return this.addOption(option, false);
    }

    public Object[] getOptions() {
        if (this.options.isEmpty()) {
            return null;
        }

        return this.options.toArray();
    }

    public Window getParent() {
        if (this.parent != null) {
            return this.parent;
        }

        if (App.settings != null && App.launcher != null && App.launcher.getParent() != null) {
            return App.launcher.getParent();
        }

        return JOptionPane.getRootFrame();
    }

    public int show() {
        try {
            Object[] options = this.getOptions();

            JOptionPane jop = new JOptionPane(this.content, this.type, this.lookAndFeel, this.icon, options,
                    this.defaultOption);

            jop.setInitialValue(this.defaultOption);
            jop.setComponentOrientation(this.getParent().getComponentOrientation());

            JDialog dialog = jop.createDialog(this.getParent(), this.title);
            // JOptionPane defaults to application-modal, which also blocks the tray menu (and its Recover UI action)
            dialog.setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
            dialog.setAlwaysOnTop(true);
            dialog.setVisible(true);

            Object selectedValue = jop.getValue();

            if (selectedValue == null) {
                return CLOSED_OPTION;
            }

            if (options == null) {
                if (selectedValue instanceof Integer) {
                    return ((Integer) selectedValue).intValue();
                }
                return CLOSED_OPTION;
            }

            for (int counter = 0, maxCounter = options.length; counter < maxCounter; counter++) {
                if (options[counter].equals(selectedValue)) {
                    return counter;
                }
            }

            return CLOSED_OPTION;
        } catch (Exception e) {
            LogManager.logStackTrace(e, false);
        }

        return CLOSED_OPTION;
    }

    public int showWithFileMonitoring(File firstFile, File secondFile, int size, int returnValue) {
        if (secondFile != null) {
            return showWithFileMonitoring(size, returnValue, firstFile, secondFile);
        }

        return showWithFileMonitoring(size, returnValue, firstFile);
    }

    public int showWithFileMonitoring(int size, int returnValue, File... files) {
        try {
            Object[] options = this.getOptions();

            JOptionPane jop = new JOptionPane(this.content, this.type, this.lookAndFeel, this.icon, options,
                    this.defaultOption);

            jop.setInitialValue(this.defaultOption);
            jop.setComponentOrientation(this.getParent().getComponentOrientation());

            JDialog dialog = jop.createDialog(this.getParent(), this.title);
            dialog.setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
            List<File> filesForMonitoring = Arrays.asList(files);

            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (filesForMonitoring.stream().anyMatch(f -> f.exists() && f.length() == size)) {
                        timer.cancel();
                        jop.setValue(options[returnValue]);
                        dialog.dispose();
                    }
                }
            }, 1000, 1000);

            dialog.setVisible(true);

            Object selectedValue = jop.getValue();

            // make sure this timer gets killed
            timer.cancel();

            if (selectedValue == null) {
                return CLOSED_OPTION;
            }

            if (options == null) {
                if (selectedValue instanceof Integer) {
                    return (Integer) selectedValue;
                }
                return CLOSED_OPTION;
            }

            for (int counter = 0, maxCounter = options.length; counter < maxCounter; counter++) {
                if (options[counter].equals(selectedValue)) {
                    return counter;
                }
            }

            return CLOSED_OPTION;
        } catch (Exception e) {
            LogManager.logStackTrace(e, false);
        }

        return -1;
    }

    public String showInput() {
        return showInput("");
    }

    public String showInput(String defaultValue) {
        try {
            return (String) JOptionPane.showInputDialog(this.getParent(), this.content, this.title, this.type,
                    this.icon, null, defaultValue);
        } catch (Exception e) {
            LogManager.logStackTrace(e, false);
        }

        return null;
    }

    /**
     * Recovers from a stuck or hidden modal dialog (e.g. one opened behind another window or off-screen) by
     * cancelling launcher-owned modal dialogs deepest-first and bringing the launcher frame back. Safe to call
     * from any thread; the window work always runs on the EDT.
     *
     * <p>
     * If any active operation dialog (progress, browser downloads) is showing, no dialogs are closed at all:
     * prompts from that operation aren't necessarily owned by its progress dialog, and cancelling a mod
     * install/update part way can leave the old mod file already deleted with no replacement.
     */
    public static void recoverUi() {
        if (SwingUtilities.isEventDispatchThread()) {
            recoverUiOnEdt();
        } else {
            SwingUtilities.invokeLater(DialogManager::recoverUiOnEdt);
        }
    }

    private static void recoverUiOnEdt() {
        Window launcherFrame = App.launcher == null ? null : App.launcher.getParent();

        // dialogs created without a parent are owned by Swing's shared owner frame
        List<Window> roots = new ArrayList<>();
        roots.add(JOptionPane.getRootFrame());
        if (launcherFrame != null) {
            roots.add(launcherFrame);
        }

        List<Dialog> owned = deepestFirst(showingModalDialogs(Window.getWindows(), roots), Window::getOwner, roots);
        List<Dialog> targets = recoverable(owned, d -> isActiveOperationDialog(d.getClass()));

        if (targets.isEmpty() && !owned.isEmpty()) {
            LogManager.info("[RecoverUI] Active operation detected; skipped closing launcher modal dialogs");
            for (Dialog dialog : owned) {
                if (isActiveOperationDialog(dialog.getClass())) {
                    LogManager.info("[RecoverUI] Active operation: " + DialogDebugMonitor.describe(dialog));
                }
            }
        } else {
            LogManager.info("[RecoverUI] Closing " + targets.size() + " recoverable modal dialog(s)");
        }

        for (Dialog dialog : targets) {
            String name = DialogDebugMonitor.describe(dialog);
            try {
                if (!dialog.isShowing()) {
                    // already closed by an earlier dialog's cancel path
                    LogManager.info("[RecoverUI] " + name + " already closed");
                    continue;
                }

                // let existing cancel paths run (e.g. JOptionPane returns CLOSED)
                dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));

                boolean needsDispose = dialog.isDisplayable() || dialog.isVisible();
                if (needsDispose) {
                    dialog.dispose();
                }
                LogManager.info("[RecoverUI] " + name + " closed" + (needsDispose ? " (disposed)" : ""));
            } catch (Throwable t) {
                LogManager.warn("[RecoverUI] Failed to close " + name + ": " + t);
            }
        }

        try {
            restoreLauncherFrame(launcherFrame);
        } catch (Throwable t) {
            LogManager.warn("[RecoverUI] Failed to restore launcher window: " + t);
        }
    }

    /**
     * Newest first (getWindows() is in creation order), so the stable depth sort closes a prompt before a
     * same-depth dialog it was opened from.
     */
    private static List<Dialog> showingModalDialogs(Window[] windows, Collection<Window> roots) {
        List<Dialog> modals = new ArrayList<>();
        for (int i = windows.length - 1; i >= 0; i--) {
            Window window = windows[i];
            if (window instanceof Dialog && !roots.contains(window) && window.isShowing()
                    && ((Dialog) window).isModal()) {
                modals.add((Dialog) window);
            }
        }
        return modals;
    }

    /**
     * Dialogs for an operation in progress (install, download, copy, metadata refresh, update). Closing these
     * cancels the operation, which isn't safe to do blindly.
     */
    static boolean isActiveOperationDialog(Class<?> dialogClass) {
        return ProgressDialog.class.isAssignableFrom(dialogClass)
                || BrowserDownloadDialog.class.isAssignableFrom(dialogClass);
    }

    /**
     * The dialogs safe to close: all of them, or none if any is an active operation.
     */
    static <T> List<T> recoverable(List<T> items, Predicate<T> isActive) {
        // Intentionally closes no launcher modal dialogs while an operation is running, to avoid cancelling an
        // install/update/download and leaving pack files in a partial state.
        for (T item : items) {
            if (isActive.test(item)) {
                return new ArrayList<>();
            }
        }
        return items;
    }

    /**
     * Keeps only items owned (directly or transitively) by one of the roots, ordered by ownership depth so every
     * child comes before its owner. Generic over the owner lookup so it can be tested without a display.
     */
    static <W, T extends W> List<T> deepestFirst(List<T> items, Function<W, W> ownerOf, Collection<W> roots) {
        List<T> owned = new ArrayList<>();
        for (T item : items) {
            if (ownerDepth(item, ownerOf, roots) > 0) {
                owned.add(item);
            }
        }

        owned.sort(Comparator.comparingInt((T item) -> ownerDepth(item, ownerOf, roots)).reversed());
        return owned;
    }

    /**
     * Number of owner hops from the item up to one of the roots, or 0 if it isn't owned by any of them.
     */
    private static <W> int ownerDepth(W item, Function<W, W> ownerOf, Collection<W> roots) {
        int depth = 0;
        for (W owner = ownerOf.apply(item); owner != null; owner = ownerOf.apply(owner)) {
            depth++;
            if (roots.contains(owner)) {
                return depth;
            }
        }
        return 0;
    }

    private static void restoreLauncherFrame(Window launcherFrame) {
        if (!(launcherFrame instanceof Frame) || !launcherFrame.isVisible()) {
            // hidden on purpose (e.g. while Minecraft runs), so leave it alone
            LogManager.info("[RecoverUI] Launcher window not visible, not restoring it");
            return;
        }

        Frame frame = (Frame) launcherFrame;
        if ((frame.getExtendedState() & Frame.ICONIFIED) != 0) {
            frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
            LogManager.info("[RecoverUI] Restored minimised launcher window");
        }

        if (!DialogDebugMonitor.intersectsAnyScreen(frame.getBounds(), DialogDebugMonitor.getScreenBounds())) {
            frame.setLocationRelativeTo(null);
            LogManager.info("[RecoverUI] Launcher window was off-screen, centred it");
        }

        frame.toFront();
        frame.requestFocus();
    }
}
