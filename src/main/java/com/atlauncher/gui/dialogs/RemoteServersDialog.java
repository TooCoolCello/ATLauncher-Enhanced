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
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.FileSystem;
import com.atlauncher.data.Instance;
import com.atlauncher.data.RemoteServer;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.network.sftp.JschSftpClient;
import com.atlauncher.network.sftp.SftpClient;

/**
 * Lists the remote SFTP servers, with ways to add, edit, remove and test them. Also has the small prompt that links an
 * instance to one.
 */
public final class RemoteServersDialog extends JDialog {
    private final DefaultListModel<RemoteServer> model = new DefaultListModel<>();
    private final JList<RemoteServer> list = new JList<>(model);
    private final JButton add = new JButton(GetText.tr("Add..."));
    private final JButton edit = new JButton(GetText.tr("Edit..."));
    private final JButton remove = new JButton(GetText.tr("Remove"));
    private final JButton test = new JButton(GetText.tr("Test Connection"));
    private final JLabel status = new JLabel(" ");
    // null if the file couldn't be read, so it's never overwritten
    private final List<RemoteServer> servers;

    public static void open(Window parent) {
        new RemoteServersDialog(parent).setVisible(true);
    }

    /**
     * @return the saved servers, or null after telling the user they couldn't be read
     */
    static List<RemoteServer> load(Window parent) {
        try {
            return RemoteServer.loadAll(FileSystem.REMOTE_SERVERS);
        } catch (IOException e) {
            LogManager.logStackTrace("Couldn't read the remote servers", e);
            RemoteServerConnect.showError(parent, GetText.tr("Remote Servers"), new IOException(GetText.tr(
                "{0} couldn't be read, so remote servers can't be used until it's fixed or removed.",
                FileSystem.REMOTE_SERVERS), e));
            return null;
        }
    }

    /**
     * @return the server the instance is linked to, or null if none (or it was removed)
     */
    public static RemoteServer linkedServer(Instance instance) {
        if (instance.launcher.remoteServerId == null) {
            return null;
        }
        try {
            return RemoteServer.find(RemoteServer.loadAll(FileSystem.REMOTE_SERVERS), instance.launcher.remoteServerId);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Asks which remote server to link the instance to, or none.
     */
    public static void chooseLink(Window parent, Instance instance) {
        List<RemoteServer> servers = load(parent);
        if (servers == null) {
            return;
        }
        JComboBox<Object> choice = new JComboBox<>();
        fillChoice(choice, servers, RemoteServer.find(servers, instance.launcher.remoteServerId));

        JButton manage = new JButton(GetText.tr("Manage Remote Servers..."));
        manage.addActionListener(e -> {
            open(SwingUtilities.getWindowAncestor(manage));
            List<RemoteServer> reloaded = load(parent);
            if (reloaded != null) {
                Object selected = choice.getSelectedItem();
                fillChoice(choice, reloaded,
                    selected instanceof RemoteServer ? RemoteServer.find(reloaded, ((RemoteServer) selected).id) : null);
            }
        });

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        // #. {0} is the name of the instance
        panel.add(new JLabel(GetText.tr("Remote server for {0}:", instance.launcher.name)), BorderLayout.NORTH);
        panel.add(choice, BorderLayout.CENTER);
        JPanel manageRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        manageRow.add(manage);
        panel.add(manageRow, BorderLayout.SOUTH);

        int ret = DialogManager.okCancelDialog().setParent(parent).setTitle(GetText.tr("Link Remote Server"))
            .setContent(panel).setType(DialogManager.QUESTION).show();
        if (ret == 0) {
            Object selected = choice.getSelectedItem();
            instance.launcher.remoteServerId = selected instanceof RemoteServer ? ((RemoteServer) selected).id : null;
            instance.save();
        }
    }

    private static void fillChoice(JComboBox<Object> choice, List<RemoteServer> servers, RemoteServer selected) {
        DefaultComboBoxModel<Object> model = new DefaultComboBoxModel<>();
        model.addElement(GetText.tr("None"));
        servers.forEach(model::addElement);
        model.setSelectedItem(selected == null ? model.getElementAt(0) : selected);
        choice.setModel(model);
    }

    private RemoteServersDialog(Window parent) {
        super(parent, GetText.tr("Remote Servers"), ModalityType.DOCUMENT_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(520, 320));
        setSize(620, 400);
        setLocationRelativeTo(parent);

        servers = load(parent);
        if (servers != null) {
            servers.forEach(model::addElement);
        }

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                    boolean focused) {
                RemoteServer server = (RemoteServer) value;
                return super.getListCellRendererComponent(list, server.name + "  -  " + server.describe(), index,
                    selected, focused);
            }
        });
        list.addListSelectionListener(e -> updateButtons());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && list.getSelectedValue() != null) {
                    edit();
                }
            }
        });

        JLabel explanation = new JLabel("<html><div style='width:540px'>" + GetText.tr("SFTP servers you can upload "
            + "instance files to. For a Pterodactyl server, copy the address and username from the server's Settings "
            + "page; the password is your panel password.") + "</div></html>");

        add.addActionListener(e -> add());
        edit.addActionListener(e -> edit());
        remove.addActionListener(e -> remove());
        test.addActionListener(e -> testConnection(this, list.getSelectedValue(), status, test));
        JButton close = new JButton(GetText.tr("Close"));
        close.addActionListener(e -> dispose());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        actions.add(add);
        actions.add(edit);
        actions.add(remove);
        actions.add(test);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(actions, BorderLayout.CENTER);
        buttons.add(close, BorderLayout.EAST);
        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.add(status, BorderLayout.NORTH);
        bottom.add(buttons, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(explanation, BorderLayout.NORTH);
        content.add(new JScrollPane(list), BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);

        updateButtons();
    }

    private void updateButtons() {
        boolean selected = list.getSelectedValue() != null;
        add.setEnabled(servers != null);
        edit.setEnabled(servers != null && selected);
        remove.setEnabled(servers != null && selected);
        test.setEnabled(selected);
    }

    private void add() {
        RemoteServer added = new ServerForm(this, new RemoteServer()).showForm();
        if (added != null) {
            servers.add(added);
            if (save()) {
                model.addElement(added);
                list.setSelectedValue(added, true);
            } else {
                servers.remove(added);
            }
        }
    }

    private void edit() {
        RemoteServer original = list.getSelectedValue();
        RemoteServer edited = new ServerForm(this, original).showForm();
        if (edited == null) {
            return;
        }
        int index = servers.indexOf(original);
        servers.set(index, edited);
        if (save()) {
            if (!sameLogin(original, edited)) {
                RemoteServerConnect.forget(original.id);
            }
            model.set(list.getSelectedIndex(), edited);
        } else {
            servers.set(index, original);
        }
    }

    private void remove() {
        RemoteServer server = list.getSelectedValue();
        int ret = DialogManager.yesNoDialog(false).setParent(this).setTitle(GetText.tr("Remove Remote Server"))
            .setContent(GetText.tr("Remove {0}? Instances linked to it won't be linked to a server any more.",
                server.name))
            .setType(DialogManager.WARNING).show();
        if (ret != 0) {
            return;
        }
        servers.remove(server);
        if (save()) {
            RemoteServerConnect.forget(server.id);
            model.removeElement(server);
        } else {
            servers.add(server);
        }
    }

    private boolean save() {
        try {
            RemoteServer.saveAll(FileSystem.REMOTE_SERVERS, servers);
            return true;
        } catch (IOException e) {
            LogManager.logStackTrace("Couldn't save the remote servers", e);
            RemoteServerConnect.showError(this, GetText.tr("Remote Servers"), e);
            return false;
        }
    }

    // whether a remembered password or passphrase still applies
    private static boolean sameLogin(RemoteServer a, RemoteServer b) {
        return Objects.equals(a.host, b.host) && a.port == b.port && Objects.equals(a.username, b.username)
            && a.auth == b.auth && Objects.equals(a.privateKeyPath, b.privateKeyPath);
    }

    /**
     * Connects and lists the server's folder in the background, then shows how it went.
     */
    static void testConnection(Window parent, RemoteServer server, JLabel status, JButton button) {
        button.setEnabled(false);
        status.setText(GetText.tr("Connecting to {0}...", server.host + ":" + server.port));
        new Thread(() -> {
            String result;
            try (SftpClient client = RemoteServerConnect.connect(parent, server)) {
                if (client == null) {
                    result = " ";
                } else {
                    String folder = server.getRemotePath();
                    try {
                        result = GetText.tr("Connected. {0} has {1} files and folders.", folder,
                            client.list(folder).size());
                    } catch (NoSuchFileException e) {
                        result = GetText.tr("Connected, but the folder {0} doesn't exist on the server.", folder);
                    }
                }
            } catch (IOException e) {
                result = GetText.tr("Couldn't connect.");
                RemoteServerConnect.showError(parent, GetText.tr("Test Connection"), e);
            }
            String shown = result;
            SwingUtilities.invokeLater(() -> {
                status.setText(shown);
                button.setEnabled(true);
            });
        }, "RemoteServerTest").start();
    }

    /**
     * Adds or edits one server. Works on a copy, so nothing changes unless it's saved.
     */
    private static final class ServerForm extends JDialog {
        private final RemoteServer original;
        private RemoteServer result = null;

        private final JTextField name = new JTextField(24);
        private final JTextField host = new JTextField(24);
        private final JSpinner port = new JSpinner(new SpinnerNumberModel(22, 1, 65535, 1));
        private final JTextField username = new JTextField(24);
        private final JComboBox<String> auth = new JComboBox<>(
            new String[] { GetText.tr("Password"), GetText.tr("Private Key") });
        private final JTextField keyPath = new JTextField(24);
        private final JButton browse = new JButton(GetText.tr("Browse..."));
        private final JTextField folder = new JTextField(24);
        private final JTextArea notes = new JTextArea(3, 24);
        private final JLabel status = new JLabel(" ");

        ServerForm(Window parent, RemoteServer server) {
            super(parent, server.name == null ? GetText.tr("Add Remote Server") : GetText.tr("Edit Remote Server"),
                ModalityType.DOCUMENT_MODAL);
            this.original = server;
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);

            name.setText(server.name);
            host.setText(server.host);
            host.putClientProperty("JTextField.placeholderText", "node.example.com");
            host.setToolTipText(GetText.tr("You can paste an address like sftp://node.example.com:2022"));
            host.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    showNormalised(read());
                }
            });
            port.setValue(server.port);
            port.setEditor(new JSpinner.NumberEditor(port, "#"));
            username.setText(server.username);
            username.putClientProperty("JTextField.placeholderText", "name.1a2b3c4d");
            auth.setSelectedIndex(server.usesKey() ? 1 : 0);
            auth.addActionListener(e -> updateKeyFields());
            keyPath.setText(server.privateKeyPath);
            browse.addActionListener(e -> browseForKey());
            folder.setText(server.getRemotePath());
            folder.setToolTipText(GetText.tr("The server's own folder. Uploads keep their paths below it, so mods "
                + "go into its mods folder. On Pterodactyl this is /."));
            notes.setText(server.notes);
            notes.setLineWrap(true);
            notes.setWrapStyleWord(true);

            JPanel keyRow = new JPanel(new BorderLayout(4, 0));
            keyRow.add(keyPath, BorderLayout.CENTER);
            keyRow.add(browse, BorderLayout.EAST);

            JPanel form = new JPanel(new GridBagLayout());
            int row = 0;
            row = addRow(form, row, GetText.tr("Name:"), name);
            row = addRow(form, row, GetText.tr("Host:"), host);
            row = addRow(form, row, GetText.tr("Port:"), port);
            row = addRow(form, row, GetText.tr("Username:"), username);
            row = addRow(form, row, GetText.tr("Log In With:"), auth);
            row = addRow(form, row, GetText.tr("Private Key File:"), keyRow);
            row = addRow(form, row, GetText.tr("Server Folder:"), folder);
            addRow(form, row, GetText.tr("Notes:"), new JScrollPane(notes));

            JLabel secrets = new JLabel("<html><div style='width:380px'>" + GetText.tr("Passwords and passphrases "
                + "aren't saved: you're asked once each time the launcher starts. Pterodactyl's SFTP port is usually "
                + "2022.") + "</div></html>");

            JButton test = new JButton(GetText.tr("Test Connection"));
            test.addActionListener(e -> {
                RemoteServer tested = readValid();
                if (tested != null) {
                    testConnection(this, tested, status, test);
                }
            });
            JButton forget = new JButton(GetText.tr("Forget Host Key..."));
            forget.setToolTipText(GetText.tr("Forget the saved host key, so the next connection asks to trust the "
                + "server again"));
            forget.addActionListener(e -> forgetHostKey());
            JButton save = new JButton(GetText.tr("Save"));
            save.addActionListener(e -> {
                result = readValid();
                if (result != null) {
                    dispose();
                }
            });
            JButton cancel = new JButton(GetText.tr("Cancel"));
            cancel.addActionListener(e -> dispose());

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
            actions.add(test);
            actions.add(forget);
            JPanel window = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
            window.add(save);
            window.add(cancel);
            JPanel buttons = new JPanel(new BorderLayout());
            buttons.add(actions, BorderLayout.CENTER);
            buttons.add(window, BorderLayout.EAST);
            JPanel bottom = new JPanel(new BorderLayout(0, 6));
            bottom.add(secrets, BorderLayout.NORTH);
            bottom.add(status, BorderLayout.CENTER);
            bottom.add(buttons, BorderLayout.SOUTH);

            JPanel content = new JPanel(new BorderLayout(0, 8));
            content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            content.add(form, BorderLayout.CENTER);
            content.add(bottom, BorderLayout.SOUTH);
            setContentPane(content);
            getRootPane().setDefaultButton(save);
            getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

            updateKeyFields();
            pack();
            setMinimumSize(getSize());
            setLocationRelativeTo(parent);
        }

        RemoteServer showForm() {
            setVisible(true);
            return result;
        }

        private static int addRow(JPanel form, int row, String label, Component field) {
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridy = row;
            gbc.insets = new Insets(3, 0, 3, 8);
            gbc.anchor = GridBagConstraints.FIRST_LINE_END;
            form.add(new JLabel(label), gbc);
            gbc.gridx = 1;
            gbc.insets = new Insets(3, 0, 3, 0);
            gbc.weightx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.LINE_START;
            form.add(field, gbc);
            return row + 1;
        }

        private void updateKeyFields() {
            boolean key = auth.getSelectedIndex() == 1;
            keyPath.setEnabled(key);
            browse.setEnabled(key);
        }

        private void browseForKey() {
            JFileChooser chooser = new JFileChooser(keyPath.getText().isEmpty()
                ? System.getProperty("user.home") + "/.ssh"
                : keyPath.getText());
            chooser.setDialogTitle(GetText.tr("Choose Private Key File"));
            chooser.setFileHidingEnabled(false);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                keyPath.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        }

        private RemoteServer read() {
            RemoteServer server = original.copy();
            server.name = name.getText();
            server.host = host.getText();
            server.port = (Integer) port.getValue();
            server.username = username.getText();
            server.auth = auth.getSelectedIndex() == 1 ? RemoteServer.Auth.KEY : RemoteServer.Auth.PASSWORD;
            server.privateKeyPath = keyPath.getText();
            server.remotePath = folder.getText();
            server.notes = notes.getText().trim().isEmpty() ? null : notes.getText().trim();
            server.normalise();
            return server;
        }

        // a pasted sftp://host:port is shown split into the host and port fields
        private void showNormalised(RemoteServer server) {
            if (server.host != null && !server.host.equals(host.getText())) {
                host.setText(server.host);
                if (server.port >= 1 && server.port <= 65535) {
                    port.setValue(server.port);
                }
                if (username.getText().trim().isEmpty() && server.username != null) {
                    username.setText(server.username);
                }
            }
        }

        // the form as a server, or null after saying what's wrong
        private RemoteServer readValid() {
            RemoteServer server = read();
            showNormalised(server);
            String problem = server.validate();
            if (problem != null) {
                DialogManager.okDialog().setParent(this).setTitle(getTitle()).setContent(problem)
                    .setType(DialogManager.ERROR).show();
                return null;
            }
            return server;
        }

        private void forgetHostKey() {
            RemoteServer server = read();
            if (server.host == null) {
                return;
            }
            String where = server.host + ":" + server.port;
            int ret = DialogManager.yesNoDialog(false).setParent(this).setTitle(GetText.tr("Forget Host Key"))
                .setContent(RemoteServerConnect.message(GetText.tr("Forget the saved host key for {0}? Only do this "
                    + "if you know why the server's key changed, such as after reinstalling it. The next connection "
                    + "will ask you to trust its new key.", where)))
                .setType(DialogManager.WARNING).show();
            if (ret != 0) {
                return;
            }
            try {
                boolean forgotten = JschSftpClient.forgetHostKey(FileSystem.REMOTE_SERVERS_KNOWN_HOSTS, server.host,
                    server.port);
                status.setText(forgotten ? GetText.tr("The host key for {0} was forgotten.", where)
                    : GetText.tr("No host key was saved for {0}.", where));
            } catch (IOException e) {
                RemoteServerConnect.showError(this, GetText.tr("Forget Host Key"), e);
            }
        }
    }
}
