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
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.FileSystem;
import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.Instance;
import com.atlauncher.data.RemoteServer;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.network.sftp.ServerDetection;
import com.atlauncher.network.sftp.ServerImport;
import com.atlauncher.network.sftp.ServerSync;
import com.atlauncher.network.sftp.SftpClient;
import com.atlauncher.network.sftp.SftpUpload;
import com.atlauncher.utils.FileUtils;

/**
 * "Sync with Server": checks the instance's linked remote server runs the same Minecraft version and loader, then
 * previews what would make the server's mods, configs and KubeJS scripts match the instance, and applies the ticked
 * changes. See {@link ServerSync} for what's compared.
 */
public final class ServerSyncDialog extends JDialog {
    private final Instance instance;
    private final RemoteServer server;
    private final ChangesModel model = new ChangesModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<ChangesModel> sorter = new TableRowSorter<>(model);
    private final JLabel detected = new JLabel(" ");
    private final JLabel warnings = new JLabel();
    private final JLabel counts = new JLabel(" ");
    private final JCheckBox showUnchanged = new JCheckBox(GetText.tr("Show unchanged files"));
    private final JLabel backupNote = new JLabel(GetText.tr("Replaced and removed files are moved into a dated folder "
        + "in atlauncher-backups on the server."));
    private final JButton tick = new JButton(GetText.tr("Tick Selected"));
    private final JButton untick = new JButton(GetText.tr("Untick Selected"));
    private final JButton check = new JButton(GetText.tr("Check Again"));
    private final JButton apply = new JButton(GetText.tr("Apply"));
    private final JButton close = new JButton(GetText.tr("Close"));
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JLabel status = new JLabel(" ");

    private boolean busy = false;
    private volatile boolean cancelRequested = false;

    /**
     * Syncs with the instance's linked server, first asking which server to link if there's none.
     */
    public static void open(Window parent, Instance instance) {
        if (RemoteServersDialog.linkedServer(instance) == null) {
            RemoteServersDialog.chooseLink(parent, instance);
        }
        RemoteServer server = RemoteServersDialog.linkedServer(instance);
        if (server != null) {
            new ServerSyncDialog(parent, instance, server).setVisible(true);
        }
    }

    private ServerSyncDialog(Window parent, Instance instance, RemoteServer server) {
        // #. {0} is the name of the remote server, {1} the name of the instance
        super(parent, GetText.tr("Sync {1} with {0}", server.name, instance.launcher.name),
            ModalityType.DOCUMENT_MODAL);
        this.instance = instance;
        this.server = server;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(640, 460));
        setSize(860, 600);
        setLocationRelativeTo(parent);

        table.setRowSorter(sorter);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setMaxWidth(32);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setMaxWidth(110);
        table.getColumnModel().getColumn(2).setPreferredWidth(360);
        table.getColumnModel().getColumn(3).setPreferredWidth(360);
        showUnchanged.addActionListener(e -> filter());
        filter();
        tick.addActionListener(e -> tickSelected(true));
        untick.addActionListener(e -> tickSelected(false));

        JLabel where = new JLabel(GetText.tr("Server: {0}", server.name + "  -  " + server.describe()));
        warnings.setIcon(UIManager.getIcon("OptionPane.warningIcon"));
        warnings.setVisible(false);
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(where, BorderLayout.NORTH);
        top.add(detected, BorderLayout.CENTER);
        top.add(warnings, BorderLayout.SOUTH);

        JPanel listTools = new JPanel(new BorderLayout());
        listTools.add(counts, BorderLayout.CENTER);
        JPanel toggles = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        toggles.add(showUnchanged);
        toggles.add(tick);
        toggles.add(untick);
        listTools.add(toggles, BorderLayout.EAST);
        JPanel middle = new JPanel(new BorderLayout(0, 4));
        middle.add(listTools, BorderLayout.NORTH);
        middle.add(new JScrollPane(table), BorderLayout.CENTER);

        check.addActionListener(e -> check());
        // Apply turns into Cancel, so the second click of a double click mustn't cancel what the first started
        apply.setMultiClickThreshhold(500);
        apply.addActionListener(e -> {
            if (busy) {
                cancel();
            } else {
                apply();
            }
        });
        close.addActionListener(e -> dispose());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                check();
            }

            @Override
            public void windowClosing(WindowEvent e) {
                if (busy) {
                    cancel();
                } else {
                    dispose();
                }
            }
        });

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        options.add(backupNote);
        JPanel window = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        window.add(check);
        window.add(apply);
        window.add(close);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(options, BorderLayout.CENTER);
        buttons.add(window, BorderLayout.EAST);
        progress.setStringPainted(true);
        progress.setString("");
        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.add(status, BorderLayout.NORTH);
        bottom.add(progress, BorderLayout.CENTER);
        bottom.add(buttons, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(top, BorderLayout.NORTH);
        content.add(middle, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        setBusy(false);
    }

    private void filter() {
        sorter.setRowFilter(showUnchanged.isSelected() ? null : new RowFilter<ChangesModel, Integer>() {
            @Override
            public boolean include(Entry<? extends ChangesModel, ? extends Integer> entry) {
                return model.changes.get(entry.getIdentifier()).kind != ServerSync.Kind.UNCHANGED;
            }
        });
    }

    private void tickSelected(boolean ticked) {
        for (int row : table.getSelectedRows()) {
            ServerSync.Change change = model.changes.get(table.convertRowIndexToModel(row));
            if (change.kind != ServerSync.Kind.UNCHANGED) {
                change.selected = ticked;
            }
        }
        model.fireTableDataChanged();
        updateCounts();
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        boolean ready = !busy && model.changes.stream().anyMatch(c -> c.kind != ServerSync.Kind.UNCHANGED);
        check.setEnabled(!busy);
        close.setEnabled(!busy);
        tick.setEnabled(ready);
        untick.setEnabled(ready);
        apply.setText(busy ? GetText.tr("Cancel") : GetText.tr("Apply"));
        apply.setEnabled(busy || ready);
        table.setEnabled(!busy);
        if (!busy) {
            progress.setValue(0);
            progress.setString("");
        }
    }

    private void cancel() {
        cancelRequested = true;
        apply.setEnabled(false);
        status.setText(GetText.tr("Cancelling..."));
    }

    // reads the server and fills the preview
    private void check() {
        List<DisableableMod> mods = instance.launcher.mods == null ? new ArrayList<>()
            : new ArrayList<>(instance.launcher.mods);
        String minecraft = instance.getMinecraftVersion();
        LoaderVersion loader = instance.launcher.loaderVersion;
        Path root = instance.getRoot();
        cancelRequested = false;
        model.set(Collections.emptyList());
        warnings.setVisible(false);
        setBusy(true);
        progress.setIndeterminate(true);
        new Thread(() -> {
            Path work = null;
            try (SftpClient client = RemoteServerConnect.connect(this, server)) {
                if (client == null) {
                    setStatus(" ");
                    return;
                }
                setStatus(GetText.tr("Checking which Minecraft version and loader the server runs..."));
                ServerDetection.Info info = ServerDetection.detect(client, server.getRemotePath());
                ServerDetection.Verdict verdict = ServerDetection.compare(info, minecraft, loader);
                SwingUtilities.invokeLater(() -> detected.setText(GetText.tr("The server runs {0}.",
                    info.describe())));

                if (!verdict.mismatches.isEmpty()) {
                    setStatus(GetText.tr("Sync stopped: the server doesn't run what this instance does. Nothing "
                        + "was changed."));
                    RemoteServerConnect.onEdt(() -> DialogManager.okDialog().setParent(this)
                        .setTitle(GetText.tr("Server Doesn't Match"))
                        .setContent(RemoteServerConnect.message(String.join("\n\n", verdict.mismatches)))
                        .setType(DialogManager.ERROR).show());
                    return;
                }
                if (!verdict.unknowns.isEmpty()
                    && !Boolean.TRUE.equals(RemoteServerConnect.onEdt(() -> continueAnyway(verdict.unknowns)))) {
                    setStatus(GetText.tr("Sync stopped. Nothing was changed."));
                    return;
                }

                Files.createDirectories(FileSystem.TEMP);
                work = Files.createTempDirectory(FileSystem.TEMP, "server-sync");
                List<ServerSync.Change> changes = ServerSync.plan(client, server.getRemotePath(), root, mods,
                    ServerImport::projects, work, message -> {
                        if (message != null) {
                            setStatus(message);
                        }
                        return !cancelRequested;
                    });
                List<String> notes = new ArrayList<>(verdict.warnings);
                if (changes.stream().anyMatch(c -> c.kind == ServerSync.Kind.ADD && c.relative.startsWith("mods/"))
                    && changes.stream()
                        .anyMatch(c -> c.kind == ServerSync.Kind.REMOVE && c.relative.startsWith("mods/"))) {
                    notes.add(GetText.tr("Check the mods under Remove: if one is an older copy of a mod being "
                        + "added, tick it, or the server loads both."));
                }
                SwingUtilities.invokeLater(() -> show(changes, notes));
                setStatus(changes.stream().allMatch(c -> c.kind == ServerSync.Kind.UNCHANGED)
                    ? GetText.tr("The server already matches this instance.")
                    : GetText.tr("Untick anything you don't want changed, then Apply."));
            } catch (InterruptedIOException e) {
                setStatus(GetText.tr("Check cancelled. Nothing was changed."));
            } catch (IOException | RuntimeException e) {
                LogManager.logStackTrace("Couldn't check the remote server for sync", e);
                setStatus(GetText.tr("Couldn't check the server."));
                RemoteServerConnect.showError(this, GetText.tr("Sync with Server"), e);
            } finally {
                if (work != null) {
                    FileUtils.deleteDirectoryQuietly(work);
                }
                SwingUtilities.invokeLater(() -> {
                    progress.setIndeterminate(false);
                    setBusy(false);
                });
            }
        }, "ServerSyncCheck").start();
    }

    private boolean continueAnyway(List<String> unknowns) {
        String text = GetText.tr("The launcher couldn't tell whether the server runs the same Minecraft version and "
            + "loader as this instance:") + "\n\n" + String.join("\n\n", unknowns) + "\n\n"
            + GetText.tr("Syncing mods to a server that runs something else can stop it starting. Only continue if "
                + "you know it matches.");
        return DialogManager.optionDialog().setParent(this).setTitle(GetText.tr("Continue Anyway?"))
            .setContent(RemoteServerConnect.message(text)).setType(DialogManager.WARNING)
            .addOption(GetText.tr("Continue Anyway")).addOption(GetText.tr("Stop"), true).show() == 0;
    }

    private void show(List<ServerSync.Change> changes, List<String> notes) {
        model.set(changes);
        warnings.setText("<html><div style='width:680px'>"
            + notes.stream().map(ServerSyncDialog::escape).collect(Collectors.joining("<br/><br/>"))
            + "</div></html>");
        warnings.setVisible(!notes.isEmpty());
        updateCounts();
    }

    private void updateCounts() {
        long add = count(ServerSync.Kind.ADD);
        long update = count(ServerSync.Kind.UPDATE);
        long remove = count(ServerSync.Kind.REMOVE);
        long removeTicked = model.changes.stream().filter(c -> c.kind == ServerSync.Kind.REMOVE && c.selected)
            .count();
        counts.setText(model.changes.isEmpty() ? " "
            // #. {0} {1} {2} {4} are numbers of files; {3} is how many of the {2} removals are ticked
            : GetText.tr("Add {0}, update {1}, remove {2} ({3} ticked), unchanged {4}", add, update, remove,
                removeTicked, count(ServerSync.Kind.UNCHANGED)));
    }

    private long count(ServerSync.Kind kind) {
        return model.changes.stream().filter(c -> c.kind == kind).count();
    }

    private void apply() {
        List<ServerSync.Change> ticked = model.changes.stream()
            .filter(c -> c.selected && c.kind != ServerSync.Kind.UNCHANGED).collect(Collectors.toList());
        if (ticked.isEmpty()) {
            status.setText(GetText.tr("Tick something to change."));
            return;
        }
        long total = ticked.stream().filter(c -> c.kind != ServerSync.Kind.REMOVE).mapToLong(c -> c.size).sum();
        cancelRequested = false;
        setBusy(true);
        new Thread(() -> {
            setStatus(GetText.tr("Connecting to {0}...", server.host + ":" + server.port));
            try (SftpClient client = RemoteServerConnect.connect(this, server)) {
                if (client == null) {
                    setStatus(" ");
                    return;
                }
                String base = server.getRemotePath();
                String backupFolder = SftpUpload.backupFolder(base, LocalDateTime.now(ZoneId.systemDefault()));
                ServerSync.Result result = ServerSync.apply(client, base, ticked, backupFolder,
                    (change, done) -> {
                        SwingUtilities.invokeLater(() -> {
                            if (!cancelRequested) {
                                status.setText(change.kind == ServerSync.Kind.REMOVE
                                    ? GetText.tr("Moving {0} to the backup folder", change.relative)
                                    : GetText.tr("Uploading {0}", change.relative));
                            }
                            progress.setValue(total == 0 ? 1000 : (int) (done * 1000 / total));
                            progress.setString(megabytes(done) + " / " + megabytes(total));
                        });
                        return !cancelRequested;
                    });
                SwingUtilities.invokeLater(() -> {
                    // what's on the server has changed, so the preview is out of date
                    model.set(Collections.emptyList());
                    updateCounts();
                    warnings.setVisible(false);
                });
                showResult(result, ticked.size());
            } catch (IOException | RuntimeException e) {
                LogManager.logStackTrace("Couldn't sync with the remote server", e);
                setStatus(GetText.tr("The sync failed."));
                RemoteServerConnect.showError(this, GetText.tr("Sync with Server"), e);
            } finally {
                SwingUtilities.invokeLater(() -> setBusy(false));
            }
        }, "ServerSyncApply").start();
    }

    private void showResult(ServerSync.Result result, int total) {
        int done = result.uploaded + result.removed;
        StringBuilder text = new StringBuilder();
        if (result.cancelled) {
            text.append(GetText.tr("Sync cancelled after {0} of {1} changes.", done, total));
        } else if (result.error != null) {
            text.append(GetText.tr("Made {0} of {1} changes before an error.", done, total));
        } else {
            text.append(GetText.tr("Uploaded {0} files ({1}) and removed {2}.", result.uploaded,
                megabytes(result.bytes), result.removed));
        }
        if (result.backupFolder != null) {
            text.append(' ').append(GetText.tr("Replaced and removed files were moved to {0}.",
                result.backupFolder));
        }
        if (done > 0) {
            text.append("\n\n").append(GetText.tr("Restart the server so it loads the changes."));
        }
        setStatus(GetText.tr("Check Again to see the server as it is now."));
        if (result.error != null) {
            LogManager.logStackTrace("Server sync stopped", result.error);
            RemoteServerConnect.showError(this, GetText.tr("Sync with Server"),
                new IOException(text + "\n\n" + result.error.getMessage(), result.error));
        } else {
            RemoteServerConnect.onEdt(() -> DialogManager.okDialog().setParent(this)
                .setTitle(GetText.tr("Sync with Server")).setContent(RemoteServerConnect.message(text.toString()))
                .setType(result.cancelled ? DialogManager.WARNING : DialogManager.INFO).show());
        }
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> status.setText(text));
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ENGLISH, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private final class ChangesModel extends AbstractTableModel {
        private List<ServerSync.Change> changes = new ArrayList<>();

        void set(List<ServerSync.Change> changes) {
            this.changes = new ArrayList<>(changes);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return changes.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            switch (column) {
                case 1:
                    return GetText.tr("Change");
                case 2:
                    return GetText.tr("File");
                case 3:
                    return GetText.tr("Details");
                default:
                    return "";
            }
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0 && !busy && changes.get(row).kind != ServerSync.Kind.UNCHANGED;
        }

        @Override
        public Object getValueAt(int row, int column) {
            ServerSync.Change change = changes.get(row);
            switch (column) {
                case 0:
                    return change.selected && change.kind != ServerSync.Kind.UNCHANGED;
                case 1:
                    return kindName(change.kind);
                case 2:
                    return change.relative;
                default:
                    return change.detail;
            }
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 0) {
                changes.get(row).selected = Boolean.TRUE.equals(value);
                fireTableCellUpdated(row, column);
                updateCounts();
            }
        }
    }

    private static String kindName(ServerSync.Kind kind) {
        switch (kind) {
            case ADD:
                return GetText.tr("Add");
            case UPDATE:
                return GetText.tr("Update");
            case REMOVE:
                return GetText.tr("Remove");
            default:
                return GetText.tr("Unchanged");
        }
    }
}
