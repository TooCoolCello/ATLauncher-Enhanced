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
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.FileSystem;
import com.atlauncher.data.Instance;
import com.atlauncher.data.RemoteServer;
import com.atlauncher.data.modrinth.pack.ModrinthModpackManifest;
import com.atlauncher.managers.AccountManager;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.managers.InstanceManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.network.sftp.LocalFolderClient;
import com.atlauncher.network.sftp.ServerDetection;
import com.atlauncher.network.sftp.ServerImport;
import com.atlauncher.network.sftp.SftpClient;
import com.atlauncher.utils.ArchiveUtils;
import com.atlauncher.utils.FileUtils;

/**
 * Makes a new instance from a server: a remote server over SFTP, or a server folder or server pack zip on this
 * computer. The instance gets the server's Minecraft version and loader, its mods (minus server-only ones), configs
 * and KubeJS scripts, and is linked to the remote server it came from.
 */
public final class ServerImportDialog extends JDialog {
    // folders that show a zip holds a server directly, rather than one folder with the server inside
    private static final List<String> SERVER_FOLDERS = Arrays.asList("mods", "config", "libraries", "kubejs",
        "defaultconfigs");

    private final JRadioButton fromRemote = new JRadioButton(GetText.tr("Remote server:"), true);
    private final JRadioButton fromFile = new JRadioButton(GetText.tr("Server folder or server pack zip:"));
    private final JComboBox<RemoteServer> server = new JComboBox<>();
    private final JButton manage = new JButton(GetText.tr("Manage..."));
    private final JTextField path = new JTextField(28);
    private final JButton browse = new JButton(GetText.tr("Browse..."));
    private final JButton start = new JButton(GetText.tr("Import"));
    private final JButton close = new JButton(GetText.tr("Close"));
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel(" ");

    private boolean busy = false;
    private volatile boolean cancelRequested = false;

    /**
     * @param preselected the remote server to import from, or null to choose
     */
    public static void open(Window parent, RemoteServer preselected) {
        if (AccountManager.getSelectedAccount() == null) {
            DialogManager.okDialog().setParent(parent).setTitle(GetText.tr("No Account Selected"))
                .setContent(GetText.tr("Cannot create instance as you have no account selected."))
                .setType(DialogManager.ERROR).show();
            return;
        }
        List<RemoteServer> servers = RemoteServersDialog.load(parent);
        new ServerImportDialog(parent, servers == null ? Collections.emptyList() : servers, preselected)
            .setVisible(true);
    }

    private ServerImportDialog(Window parent, List<RemoteServer> servers, RemoteServer preselected) {
        super(parent, GetText.tr("Import from a Server"), ModalityType.DOCUMENT_MODAL);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        // preselected comes from another copy of the list, and the combo box only selects its own objects
        setServers(servers, preselected == null ? null : RemoteServer.find(servers, preselected.id));
        fromFile.setSelected(servers.isEmpty());
        ButtonGroup group = new ButtonGroup();
        group.add(fromRemote);
        group.add(fromFile);
        fromRemote.addActionListener(e -> updateFields());
        fromFile.addActionListener(e -> updateFields());
        manage.addActionListener(e -> {
            RemoteServer selected = (RemoteServer) server.getSelectedItem();
            RemoteServersDialog.open(this);
            List<RemoteServer> reloaded = RemoteServersDialog.load(this);
            if (reloaded != null) {
                setServers(reloaded, selected == null ? null : RemoteServer.find(reloaded, selected.id));
            }
        });
        browse.addActionListener(e -> browse());

        JLabel explanation = new JLabel("<html><div style='width:460px'>" + GetText.tr("Makes a new instance with "
            + "the server's Minecraft version and loader, its mods, configs and KubeJS scripts. Mods that Modrinth "
            + "or CurseForge mark server-only are left out. An instance made from a remote server is linked to it, "
            + "ready for Sync with Server.") + "</div></html>");

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 0, 3, 6);
        gbc.anchor = GridBagConstraints.LINE_START;
        gbc.gridy = 0;
        form.add(fromRemote, gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(server, gbc);
        gbc.gridx = 2;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        form.add(manage, gbc);
        gbc.gridy = 1;
        gbc.gridx = 0;
        form.add(fromFile, gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(path, gbc);
        gbc.gridx = 2;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        form.add(browse, gbc);

        // Import turns into Cancel, so the second click of a double click mustn't cancel what the first started
        start.setMultiClickThreshhold(500);
        start.addActionListener(e -> {
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

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        buttons.add(start);
        buttons.add(close);
        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.add(status, BorderLayout.NORTH);
        bottom.add(progress, BorderLayout.CENTER);
        bottom.add(buttons, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(explanation, BorderLayout.NORTH);
        content.add(form, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(start);
        updateFields();
        pack();
        setMinimumSize(new Dimension(Math.max(560, getWidth()), getHeight()));
        setLocationRelativeTo(parent);
    }

    private void setServers(List<RemoteServer> servers, RemoteServer selected) {
        server.setModel(new DefaultComboBoxModel<>(servers.toArray(new RemoteServer[0])));
        if (selected != null) {
            server.setSelectedItem(selected);
        }
    }

    private void updateFields() {
        boolean remote = fromRemote.isSelected();
        fromRemote.setEnabled(!busy);
        fromFile.setEnabled(!busy);
        server.setEnabled(!busy && remote);
        manage.setEnabled(!busy && remote);
        path.setEnabled(!busy && !remote);
        browse.setEnabled(!busy && !remote);
        close.setEnabled(!busy);
        start.setText(busy ? GetText.tr("Cancel") : GetText.tr("Import"));
        start.setEnabled(true);
    }

    private void cancel() {
        cancelRequested = true;
        start.setEnabled(false);
        status.setText(GetText.tr("Cancelling..."));
    }

    private void browse() {
        JFileChooser chooser = new JFileChooser(path.getText().isEmpty() ? FileSystem.getUserDownloadsPath().toFile()
            : new File(path.getText()));
        chooser.setDialogTitle(GetText.tr("Choose a Server Folder or Server Pack Zip"));
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setFileFilter(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isDirectory() || file.getName().toLowerCase(Locale.ENGLISH).endsWith(".zip");
            }

            @Override
            public String getDescription() {
                return GetText.tr("Server folders and zips");
            }
        });
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            path.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void start() {
        RemoteServer remote = fromRemote.isSelected() ? (RemoteServer) server.getSelectedItem() : null;
        Path local = null;
        if (fromRemote.isSelected() && remote == null) {
            status.setText(GetText.tr("Choose a remote server, or add one with Manage."));
            return;
        }
        if (!fromRemote.isSelected()) {
            local = path.getText().trim().isEmpty() ? null : Paths.get(path.getText().trim());
            if (local == null || !Files.exists(local)) {
                status.setText(GetText.tr("Choose a server folder or server pack zip."));
                return;
            }
        }
        Path source = local;
        cancelRequested = false;
        busy = true;
        updateFields();
        progress.setIndeterminate(true);
        new Thread(() -> {
            Path work = null;
            try {
                Files.createDirectories(FileSystem.TEMP);
                work = Files.createTempDirectory(FileSystem.TEMP, "server-import");
                importFrom(remote, source, work);
            } catch (InterruptedIOException e) {
                setStatus(GetText.tr("Import cancelled."));
            } catch (IOException | RuntimeException e) {
                LogManager.logStackTrace("Couldn't import from the server", e);
                setStatus(GetText.tr("The import failed."));
                RemoteServerConnect.showError(this, GetText.tr("Import from a Server"), e);
            } finally {
                if (work != null) {
                    FileUtils.deleteDirectoryQuietly(work);
                }
                SwingUtilities.invokeLater(() -> {
                    busy = false;
                    progress.setIndeterminate(false);
                    updateFields();
                });
            }
        }, "ServerImport").start();
    }

    // off the event dispatch thread
    private void importFrom(RemoteServer remote, Path local, Path work) throws IOException {
        String name;
        Path folder = local;
        if (remote != null) {
            name = remote.name;
            setStatus(GetText.tr("Connecting to {0}...", remote.host + ":" + remote.port));
        } else {
            name = local.getFileName().toString().replaceFirst("(?i)\\.zip$", "");
            if (Files.isRegularFile(local)) {
                setStatus(GetText.tr("Extracting {0}...", local.getFileName()));
                folder = work.resolve("zip");
                if (!ArchiveUtils.extract(local, folder)) {
                    throw new IOException(GetText.tr("{0} couldn't be extracted. Is it a zip file?", local));
                }
                // server packs are often zipped with their folder inside
                File[] inside = folder.toFile().listFiles();
                if (inside != null && inside.length == 1 && inside[0].isDirectory()
                    && !SERVER_FOLDERS.contains(inside[0].getName())) {
                    folder = inside[0].toPath();
                }
            }
        }

        try (SftpClient client = remote != null ? RemoteServerConnect.connect(this, remote)
            : new LocalFolderClient(folder)) {
            if (client == null) {
                setStatus(" ");
                return;
            }
            String base = remote != null ? remote.getRemotePath() : "/";
            setStatus(GetText.tr("Checking which Minecraft version and loader the server runs..."));
            ServerDetection.Info info = ServerDetection.detect(client, base);
            String problem = ServerImport.problem(info);
            if (problem != null) {
                setStatus(GetText.tr("Can't import from this server."));
                RemoteServerConnect.onEdt(() -> DialogManager.okDialog().setParent(this)
                    .setTitle(GetText.tr("Import from a Server")).setContent(RemoteServerConnect.message(problem))
                    .setType(DialogManager.ERROR).show());
                return;
            }

            Path pack = work.resolve("pack");
            ServerImport.Gathered gathered = ServerImport.gather(client, base, pack, message -> {
                if (message != null) {
                    setStatus(message);
                }
                return !cancelRequested;
            });
            if (cancelRequested) {
                throw new InterruptedIOException("Cancelled");
            }
            setStatus(GetText.tr("Identifying mods on Modrinth and CurseForge..."));
            gathered.serverOnly.addAll(ServerImport.leaveOutServerOnly(pack.resolve("overrides/mods"),
                ServerImport::lookUp));
            ModrinthModpackManifest manifest = ServerImport.manifest(name, info);

            setStatus(" ");
            if (!Boolean.TRUE.equals(RemoteServerConnect.onEdt(() -> confirm(info, gathered)))) {
                setStatus(GetText.tr("Import cancelled."));
                return;
            }
            String linkTo = remote == null ? null : remote.id;
            RemoteServerConnect.onEdt(() -> {
                install(manifest, pack, linkTo);
                return null;
            });
        }
    }

    private boolean confirm(ServerDetection.Info info, ServerImport.Gathered gathered) {
        StringBuilder text = new StringBuilder(GetText.tr("The server runs {0}.", info.describe()));
        text.append("\n\n").append(GetText.tr("The instance gets its {0} mods and {1} config and script files.",
            gathered.mods - gathered.serverOnly.size(), gathered.files));
        if (!gathered.serverOnly.isEmpty()) {
            text.append("\n\n").append(GetText.tr("Left out because they only run on servers: {0}",
                String.join(", ", gathered.serverOnly)));
        }
        text.append("\n\n").append(GetText.tr("Next, choose a name for the instance."));
        return DialogManager.optionDialog().setParent(this).setTitle(GetText.tr("Import from a Server"))
            .setContent(RemoteServerConnect.message(text.toString())).setType(DialogManager.QUESTION)
            .addOption(GetText.tr("Continue"), true).addOption(GetText.tr("Cancel")).show() == 0;
    }

    // hands over to the launcher's own importer, which shows its own progress and result
    private void install(ModrinthModpackManifest manifest, Path pack, String linkTo) {
        Set<UUID> before = InstanceManager.getInstances().stream().map(Instance::getUUID).collect(Collectors.toSet());
        setVisible(false);
        new InstanceInstallerDialog(manifest, pack).setVisible(true);
        if (linkTo != null) {
            for (Instance made : InstanceManager.getInstances()) {
                if (!before.contains(made.getUUID())) {
                    made.launcher.remoteServerId = linkTo;
                    made.save();
                }
            }
        }
        dispose();
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> status.setText(text));
    }
}
