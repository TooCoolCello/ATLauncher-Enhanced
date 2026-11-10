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
import java.awt.Window;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.FileSystem;
import com.atlauncher.data.RemoteServer;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.network.sftp.JschSftpClient;
import com.atlauncher.network.sftp.SftpClient;

/**
 * Opens SFTP connections to remote servers from a background thread, asking on the event dispatch thread for what's
 * needed: the password or key passphrase, and whether to trust a host seen for the first time.
 *
 * <p>
 * The launcher has no safe place to save secrets (account tokens are plain JSON), so passwords and passphrases are
 * only kept in memory, by server, until the launcher closes. They're never written to disk or logged.
 */
public final class RemoteServerConnect {
    private static final Map<String, byte[]> SECRETS = new ConcurrentHashMap<>();

    private RemoteServerConnect() {
    }

    /**
     * Connects, asking for anything needed. Call off the event dispatch thread.
     *
     * @return the connection, or null if the user cancelled a prompt
     */
    public static SftpClient connect(Window parent, RemoteServer server) throws IOException {
        byte[] secret = SECRETS.get(server.id);
        if (secret == null) {
            secret = server.usesKey() ? askPassphrase(parent, server) : askPassword(parent, server);
            if (secret == null) {
                return null;
            }
        }

        try {
            SftpClient client = JschSftpClient.connect(server, secret.length == 0 && server.usesKey() ? null : secret,
                FileSystem.REMOTE_SERVERS_KNOWN_HOSTS,
                (host, type, fingerprint) -> Boolean.TRUE
                    .equals(onEdt(() -> trustHost(parent, server, type, fingerprint))));
            // only a secret that worked is remembered
            SECRETS.put(server.id, secret);
            return client;
        } catch (IOException e) {
            if (e instanceof JschSftpClient.AuthFailedException) {
                forget(server.id);
            }
            if (SECRETS.get(server.id) != secret) {
                Arrays.fill(secret, (byte) 0);
            }
            throw e;
        }
    }

    /**
     * Forgets the password or passphrase remembered for a server, such as after it's edited or removed.
     */
    public static void forget(String serverId) {
        byte[] secret = SECRETS.remove(serverId);
        if (secret != null) {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /**
     * Shows a failure: the message alone, since every SFTP error is written to be read by the user.
     */
    public static void showError(Window parent, String title, IOException e) {
        onEdt(() -> DialogManager.okDialog().setParent(parent).setTitle(title)
            .setContent(message(e.getMessage() == null ? e.toString() : e.getMessage()))
            .setType(DialogManager.ERROR).show());
    }

    /**
     * Wrapped, selectable text for a dialog, so long messages and fingerprints can be read and copied.
     */
    static JTextArea message(String text) {
        JTextArea area = new JTextArea(text);
        area.setColumns(48);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setOpaque(false);
        area.setBorder(null);
        area.setFont(UIManager.getFont("Label.font"));
        area.setSize(area.getPreferredSize());
        return area;
    }

    private static byte[] askPassword(Window parent, RemoteServer server) {
        // #. {0} is the username and server, such as name.1a2b3c4d@node.example.com:2022
        return onEdt(() -> askSecret(parent, GetText.tr("Password Required"),
            GetText.tr("Password for {0}:", server.username + "@" + server.host + ":" + server.port)));
    }

    // the key's passphrase, or an empty array if it has none
    private static byte[] askPassphrase(Window parent, RemoteServer server) throws IOException {
        if (!JschSftpClient.keyNeedsPassphrase(server.privateKeyPath)) {
            return new byte[0];
        }
        String prompt = GetText.tr("Passphrase for the private key {0}:", server.privateKeyPath);
        while (true) {
            String shown = prompt;
            byte[] passphrase = onEdt(() -> askSecret(parent, GetText.tr("Passphrase Required"), shown));
            if (passphrase == null || JschSftpClient.passphraseMatches(server.privateKeyPath, passphrase)) {
                return passphrase;
            }
            Arrays.fill(passphrase, (byte) 0);
            prompt = GetText.tr("That passphrase is wrong. Passphrase for the private key {0}:",
                server.privateKeyPath);
        }
    }

    private static byte[] askSecret(Window parent, String title, String prompt) {
        JPasswordField field = new JPasswordField(24);
        field.addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent event) {
                field.requestFocusInWindow();
            }

            @Override
            public void ancestorRemoved(AncestorEvent event) {
            }

            @Override
            public void ancestorMoved(AncestorEvent event) {
            }
        });
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JLabel(prompt), BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        panel.add(new JLabel(GetText.tr("It's kept in memory until the launcher closes, and never saved.")),
            BorderLayout.SOUTH);

        int ret = DialogManager.okCancelDialog().setParent(parent).setTitle(title).setContent(panel)
            .setType(DialogManager.QUESTION).show();
        char[] chars = field.getPassword();
        try {
            if (ret != 0) {
                return null;
            }
            ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            encoded.rewind();
            while (encoded.hasRemaining()) {
                encoded.put((byte) 0);
            }
            return bytes;
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    private static boolean trustHost(Window parent, RemoteServer server, String keyType, String fingerprint) {
        String text = GetText.tr("This is the first time the launcher has connected to {0}, so it can't tell yet "
            + "whether this is really that server.", server.host + ":" + server.port) + "\n\n"
            + GetText.tr("Its {0} host key fingerprint is:", keyType) + "\n" + fingerprint + "\n\n"
            + GetText.tr("If your host shows the fingerprint, check that it matches. Once trusted, the key is saved, "
                + "and the launcher will refuse to connect if it ever changes.");
        return DialogManager.optionDialog().setParent(parent).setTitle(GetText.tr("Trust This Server?"))
            .setContent(message(text)).setType(DialogManager.WARNING)
            .addOption(GetText.tr("Trust and Connect"), true).addOption(GetText.tr("Cancel")).show() == 0;
    }

    /**
     * Runs on the event dispatch thread and waits for the result.
     */
    static <T> T onEdt(Supplier<T> task) {
        if (SwingUtilities.isEventDispatchThread()) {
            return task.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> result.set(task.get()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
        return result.get();
    }
}
