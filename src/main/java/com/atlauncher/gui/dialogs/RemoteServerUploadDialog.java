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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.data.Instance;
import com.atlauncher.data.RemoteServer;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.network.sftp.SftpClient;
import com.atlauncher.network.sftp.SftpUpload;

/**
 * Uploads chosen files and folders from an instance to a remote server over SFTP, keeping their paths below the
 * server's folder. Asks before replacing anything, and can move what it replaces into a dated backup folder on the
 * server.
 */
public final class RemoteServerUploadDialog extends JDialog {
    // ticked to start with: what a modded server usually needs from the instance
    private static final List<String> SERVER_FOLDERS = Arrays.asList("mods", "config", "defaultconfigs", "kubejs",
        "scripts");
    // the launcher's own files, never useful on a server
    private static final List<String> HIDDEN = Arrays.asList("instance.json", "disabledmods");
    private static final String PATH = "path";

    private final Instance instance;
    private final JComboBox<RemoteServer> server = new JComboBox<>();
    private final JLabel destination = new JLabel();
    private final JPanel choices = new JPanel();
    private final JButton manage = new JButton(GetText.tr("Manage..."));
    private final JButton addFiles = new JButton(GetText.tr("Add Files..."));
    private final JButton upload = new JButton(GetText.tr("Upload"));
    private final JButton close = new JButton(GetText.tr("Close"));
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JLabel status = new JLabel(" ");

    private boolean busy = false;
    private volatile boolean cancelRequested = false;

    public static void open(Window parent, Instance instance) {
        List<RemoteServer> servers = RemoteServersDialog.load(parent);
        if (servers == null) {
            return;
        }
        if (servers.isEmpty()) {
            int ret = DialogManager.yesNoDialog().setParent(parent).setTitle(GetText.tr("Upload to Remote Server"))
                .setContent(GetText.tr("There are no remote servers yet. Add one now?"))
                .setType(DialogManager.QUESTION).show();
            if (ret != 0) {
                return;
            }
            RemoteServersDialog.open(parent);
            servers = RemoteServersDialog.load(parent);
            if (servers == null || servers.isEmpty()) {
                return;
            }
        }
        new RemoteServerUploadDialog(parent, instance, servers).setVisible(true);
    }

    private RemoteServerUploadDialog(Window parent, Instance instance, List<RemoteServer> servers) {
        // #. {0} is the name of the instance
        super(parent, GetText.tr("Upload to Remote Server - {0}", instance.launcher.name),
            ModalityType.DOCUMENT_MODAL);
        this.instance = instance;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(520, 420));
        setSize(600, 520);
        setLocationRelativeTo(parent);

        setServers(servers, RemoteServer.find(servers, instance.launcher.remoteServerId));
        server.addActionListener(e -> updateDestination());
        manage.addActionListener(e -> {
            RemoteServer selected = (RemoteServer) server.getSelectedItem();
            RemoteServersDialog.open(this);
            List<RemoteServer> reloaded = RemoteServersDialog.load(this);
            if (reloaded != null) {
                setServers(reloaded, selected == null ? null : RemoteServer.find(reloaded, selected.id));
            }
        });

        JPanel serverRow = new JPanel(new BorderLayout(6, 0));
        serverRow.add(new JLabel(GetText.tr("Server:")), BorderLayout.WEST);
        serverRow.add(server, BorderLayout.CENTER);
        serverRow.add(manage, BorderLayout.EAST);
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.add(serverRow, BorderLayout.NORTH);
        top.add(destination, BorderLayout.CENTER);
        top.add(new JLabel(GetText.tr("Upload these from the instance:")), BorderLayout.SOUTH);

        choices.setLayout(new BoxLayout(choices, BoxLayout.Y_AXIS));
        listInstanceFiles();
        JScrollPane scroll = new JScrollPane(choices);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        addFiles.setToolTipText(GetText.tr("Choose more files or folders from inside the instance"));
        addFiles.addActionListener(e -> chooseFiles());
        // Upload turns into Cancel, so the second click of a double click mustn't cancel what the first started
        upload.setMultiClickThreshhold(500);
        upload.addActionListener(e -> {
            if (busy) {
                cancel();
            } else {
                start();
            }
        });
        close.addActionListener(e -> dispose());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (busy) {
                    cancel();
                } else {
                    dispose();
                }
            }
        });

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        actions.add(addFiles);
        JPanel window = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        window.add(upload);
        window.add(close);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(actions, BorderLayout.CENTER);
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
        content.add(scroll, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(upload);
    }

    private void setServers(List<RemoteServer> servers, RemoteServer selected) {
        server.setModel(new DefaultComboBoxModel<>(servers.toArray(new RemoteServer[0])));
        if (selected != null) {
            server.setSelectedItem(selected);
        }
        updateDestination();
    }

    private void updateDestination() {
        RemoteServer selected = (RemoteServer) server.getSelectedItem();
        destination.setText(selected == null ? " " : GetText.tr("Uploads to {0}", selected.describe()));
        upload.setEnabled(selected != null || busy);
    }

    // the instance's top-level files and folders, with the ones a server needs ticked
    private void listInstanceFiles() {
        try (Stream<Path> entries = Files.list(instance.getRoot())) {
            List<Path> paths = entries
                .filter(p -> !p.getFileName().toString().startsWith(".")
                    && !HIDDEN.contains(p.getFileName().toString()))
                .sorted((a, b) -> Files.isDirectory(a) != Files.isDirectory(b) ? (Files.isDirectory(a) ? -1 : 1)
                    : a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                .collect(Collectors.toList());
            for (Path path : paths) {
                addChoice(path, SERVER_FOLDERS.contains(path.getFileName().toString()));
            }
        } catch (IOException e) {
            LogManager.logStackTrace("Couldn't list the instance's files", e);
        }
    }

    private void addChoice(Path path, boolean ticked) {
        for (Component existing : choices.getComponents()) {
            if (path.equals(((JCheckBox) existing).getClientProperty(PATH))) {
                ((JCheckBox) existing).setSelected(true);
                return;
            }
        }
        String relative = instance.getRoot().relativize(path).toString().replace('\\', '/');
        JCheckBox box = new JCheckBox(Files.isDirectory(path) ? relative + "/" : relative, ticked);
        box.putClientProperty(PATH, path);
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        choices.add(box);
        choices.revalidate();
        choices.repaint();
    }

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser(instance.getRoot().toFile());
        chooser.setDialogTitle(GetText.tr("Choose Files or Folders to Upload"));
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path root = instance.getRoot().toAbsolutePath().normalize();
        for (File file : chooser.getSelectedFiles()) {
            Path path = file.toPath().toAbsolutePath().normalize();
            if (!path.startsWith(root) || path.equals(root)) {
                DialogManager.okDialog().setParent(this).setTitle(GetText.tr("Choose Files or Folders to Upload"))
                    .setContent(GetText.tr("Only files and folders inside the instance can be uploaded, so they keep "
                        + "their place on the server."))
                    .setType(DialogManager.WARNING).show();
                return;
            }
            addChoice(path, true);
        }
    }

    private List<Path> ticked() {
        List<Path> paths = new ArrayList<>();
        for (Component component : choices.getComponents()) {
            JCheckBox box = (JCheckBox) component;
            if (box.isSelected()) {
                paths.add((Path) box.getClientProperty(PATH));
            }
        }
        return paths;
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        server.setEnabled(!busy);
        manage.setEnabled(!busy);
        addFiles.setEnabled(!busy);
        close.setEnabled(!busy);
        for (Component box : choices.getComponents()) {
            box.setEnabled(!busy);
        }
        upload.setText(busy ? GetText.tr("Cancel") : GetText.tr("Upload"));
        updateDestination();
    }

    private void cancel() {
        cancelRequested = true;
        upload.setEnabled(false);
        status.setText(GetText.tr("Cancelling..."));
    }

    private void start() {
        RemoteServer target = (RemoteServer) server.getSelectedItem();
        List<Path> selected = ticked();
        if (target == null || selected.isEmpty()) {
            status.setText(GetText.tr("Tick something to upload."));
            return;
        }
        cancelRequested = false;
        progress.setValue(0);
        progress.setString("");
        setBusy(true);
        new Thread(() -> {
            try {
                upload(target, selected);
            } finally {
                SwingUtilities.invokeLater(() -> setBusy(false));
            }
        }, "RemoteServerUpload").start();
    }

    // off the event dispatch thread
    private void upload(RemoteServer target, List<Path> selected) {
        setStatus(GetText.tr("Connecting to {0}...", target.host + ":" + target.port));
        try (SftpClient client = RemoteServerConnect.connect(this, target)) {
            if (client == null) {
                setStatus(" ");
                return;
            }
            setStatus(GetText.tr("Checking what's already on the server..."));
            List<SftpUpload.Item> items = SftpUpload.plan(instance.getRoot(), selected, target.getRemotePath());
            SftpUpload.findExisting(client, items);
            String backupFolder = SftpUpload.backupFolder(target.getRemotePath(), LocalDateTime.now(ZoneId.systemDefault()));

            List<SftpUpload.Item> existing = items.stream().filter(i -> i.exists).collect(Collectors.toList());
            SftpUpload.Existing choice = SftpUpload.Existing.BACK_UP_AND_REPLACE;
            if (!existing.isEmpty()) {
                choice = RemoteServerConnect.onEdt(() -> askExisting(existing, items.size(), backupFolder));
                if (choice == null || cancelRequested) {
                    setStatus(GetText.tr("Upload cancelled. Nothing was changed on the server."));
                    return;
                }
            }

            SftpUpload.Existing mode = choice;
            long total = items.stream().filter(i -> !(i.exists && mode == SftpUpload.Existing.SKIP))
                .mapToLong(i -> i.size).sum();
            SftpUpload.Result result = SftpUpload.run(client, items, mode, backupFolder, (item, done) -> {
                SwingUtilities.invokeLater(() -> {
                    if (!cancelRequested) {
                        status.setText(GetText.tr("Uploading {0}", item.relative));
                    }
                    progress.setValue(total == 0 ? 1000 : (int) (done * 1000 / total));
                    progress.setString(megabytes(done) + " / " + megabytes(total));
                });
                return !cancelRequested;
            });
            showResult(result, items.size());
        } catch (IOException | RuntimeException e) {
            LogManager.logStackTrace("Couldn't upload to the remote server", e);
            setStatus(GetText.tr("The upload failed."));
            RemoteServerConnect.showError(this, GetText.tr("Upload to Remote Server"), e);
        }
    }

    private SftpUpload.Existing askExisting(List<SftpUpload.Item> existing, int total, String backupFolder) {
        StringBuilder names = new StringBuilder();
        existing.stream().limit(12).forEach(i -> names.append("\n  ").append(i.relative));
        if (existing.size() > 12) {
            names.append("\n  ").append(GetText.tr("and {0} more", existing.size() - 12));
        }
        String text = GetText.tr("{0} of the {1} files are already on the server:", existing.size(), total) + names
            + "\n\n" + GetText.tr("Back Up and Replace moves the server's copies into {0} first.", backupFolder);
        int ret = DialogManager.optionDialog().setParent(this).setTitle(GetText.tr("Replace Files on the Server?"))
            .setContent(RemoteServerConnect.message(text)).setType(DialogManager.QUESTION)
            .addOption(GetText.tr("Back Up and Replace"), true).addOption(GetText.tr("Replace"))
            .addOption(GetText.tr("Skip Those Files")).addOption(GetText.tr("Cancel")).show();
        switch (ret) {
            case 0:
                return SftpUpload.Existing.BACK_UP_AND_REPLACE;
            case 1:
                return SftpUpload.Existing.REPLACE;
            case 2:
                return SftpUpload.Existing.SKIP;
            default:
                return null;
        }
    }

    private void showResult(SftpUpload.Result result, int total) {
        StringBuilder text = new StringBuilder();
        if (result.cancelled) {
            text.append(GetText.tr("Upload cancelled after {0} of {1} files.", result.uploaded, total));
        } else if (result.error != null) {
            text.append(GetText.tr("Uploaded {0} of {1} files before an error.", result.uploaded, total));
        } else {
            text.append(GetText.tr("Uploaded {0} files ({1}).", result.uploaded, megabytes(result.bytes)));
        }
        if (result.skipped > 0) {
            text.append(' ').append(GetText.tr("Skipped {0} already on the server.", result.skipped));
        }
        if (result.backupFolder != null) {
            text.append(' ').append(GetText.tr("Replaced files were backed up to {0}.", result.backupFolder));
        } else if (result.replaced > 0) {
            text.append(' ').append(GetText.tr("Replaced {0} files.", result.replaced));
        }
        setStatus("<html><div style='width:520px'>" + escape(text.toString()) + "</div></html>");
        if (result.error != null) {
            LogManager.logStackTrace("Remote server upload stopped", result.error);
            RemoteServerConnect.showError(this, GetText.tr("Upload to Remote Server"),
                new IOException(text + "\n\n" + result.error.getMessage(), result.error));
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
}
