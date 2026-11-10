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
package com.atlauncher.network.sftp;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.LongPredicate;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.data.RemoteServer;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchAlgoNegoFailException;
import com.jcraft.jsch.JSchChangedHostKeyException;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.JSchUnknownHostKeyException;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import com.jcraft.jsch.UserInfo;

/**
 * SFTP over JSch. Host keys are trusted on first use: a new host's key is shown to the user and, once accepted, saved
 * to the launcher's own known_hosts file. A key that doesn't match the saved one blocks the connection; it is never
 * replaced automatically.
 */
public final class JschSftpClient implements SftpClient {
    private static final int TIMEOUT_MS = 20000;

    /**
     * Asks the user whether to trust a host seen for the first time. Called on the connecting thread.
     */
    public interface HostKeyPrompt {
        boolean trustNewHost(String host, String keyType, String fingerprint);
    }

    /**
     * The server didn't accept the password or key, so a remembered one is wrong.
     */
    public static final class AuthFailedException extends IOException {
        public AuthFailedException(String message) {
            super(message);
        }
    }

    private final Session session;
    private final ChannelSftp sftp;

    private JschSftpClient(Session session, ChannelSftp sftp) {
        this.session = session;
        this.sftp = sftp;
    }

    /**
     * @param secret the password, or the private key's passphrase (null if it has none); not kept
     */
    public static JschSftpClient connect(RemoteServer server, byte[] secret, Path knownHosts, HostKeyPrompt prompt)
            throws IOException {
        JSch jsch = new JSch();
        TrustOnFirstUse hostKeys = new TrustOnFirstUse(openKnownHosts(jsch, knownHosts), prompt);
        jsch.setHostKeyRepository(hostKeys);

        if (server.usesKey()) {
            try {
                jsch.addIdentity(server.privateKeyPath, secret == null ? null : secret.clone());
            } catch (JSchException e) {
                if (secret != null && e.getMessage() != null && e.getMessage().startsWith("Incorrect passphrase")) {
                    // a remembered passphrase the key no longer takes: an auth failure, so it's forgotten and asked
                    throw new AuthFailedException(GetText.tr("The passphrase doesn't unlock the private key {0}.",
                        server.privateKeyPath));
                }
                throw keyError(server.privateKeyPath, e);
            }
        }

        Session session = null;
        try {
            session = jsch.getSession(server.username, server.host, server.port);
            // always [host]:port, so a key saved for another port on the same host is never compared with this one
            session.setHostKeyAlias(hostKeyName(server.host, server.port));
            if (!server.usesKey() && secret != null) {
                session.setPassword(secret.clone());
            }
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("prefer_known_host_key_types", "yes");
            session.setConfig("PreferredAuthentications", server.usesKey() ? "publickey" : "password");
            session.setServerAliveInterval(15000);
            session.connect(TIMEOUT_MS);

            ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(TIMEOUT_MS);
            return new JschSftpClient(session, sftp);
        } catch (JSchException e) {
            if (session != null) {
                session.disconnect();
            }
            throw connectError(e, server, hostKeys.changedFingerprint);
        }
    }

    /**
     * @return whether the private key is protected by a passphrase
     */
    public static boolean keyNeedsPassphrase(String keyPath) throws IOException {
        KeyPair key = loadKey(keyPath);
        try {
            return key.isEncrypted();
        } finally {
            key.dispose();
        }
    }

    public static boolean passphraseMatches(String keyPath, byte[] passphrase) throws IOException {
        KeyPair key = loadKey(keyPath);
        try {
            return key.decrypt(passphrase.clone());
        } finally {
            key.dispose();
        }
    }

    /**
     * Forgets the saved host key for a server, so the next connection asks to trust it again.
     *
     * @return whether a key was saved
     */
    public static boolean forgetHostKey(Path knownHosts, String host, int port) throws IOException {
        HostKeyRepository known = openKnownHosts(new JSch(), knownHosts);
        String name = hostKeyName(host, port);
        boolean saved = known.getHostKey(name, null).length > 0;
        known.remove(name, null);
        return saved;
    }

    static String hostKeyName(String host, int port) {
        return "[" + host + "]:" + port;
    }

    /**
     * The key's fingerprint in OpenSSH's format, such as SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s.
     */
    static String fingerprint(byte[] key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HostKeyRepository openKnownHosts(JSch jsch, Path knownHosts) throws IOException {
        Files.createDirectories(knownHosts.getParent());
        if (!Files.exists(knownHosts)) {
            // JSch only saves accepted keys to a file that already exists
            Files.createFile(knownHosts);
        }
        try {
            jsch.setKnownHosts(knownHosts.toString());
        } catch (JSchException e) {
            throw new IOException(GetText.tr("Couldn't read the saved host keys in {0}.", knownHosts), e);
        }
        return jsch.getHostKeyRepository();
    }

    private static KeyPair loadKey(String keyPath) throws IOException {
        try {
            return KeyPair.load(new JSch(), keyPath);
        } catch (JSchException e) {
            throw keyError(keyPath, e);
        }
    }

    private static IOException keyError(String keyPath, JSchException e) {
        return new IOException(GetText.tr("Couldn't read the private key file {0}. Check that it exists and is an "
            + "OpenSSH or PuTTY private key.", keyPath), e);
    }

    static IOException connectError(JSchException e, RemoteServer server, String changedFingerprint) {
        String where = server.host + ":" + server.port;
        String message = e.getMessage() == null ? "" : e.getMessage();
        Throwable cause = e.getCause();

        if (e instanceof JSchChangedHostKeyException || changedFingerprint != null) {
            return new IOException(GetText.tr("WARNING: the host key of {0} has changed since you first connected, so "
                + "the connection was blocked. Someone may be intercepting it, or the server may have been "
                + "reinstalled or moved. The key it sent has the fingerprint {1}. Only if you're sure the change is "
                + "expected, edit this server in Remote Servers, use Forget Host Key, and connect again.", where,
                changedFingerprint == null ? "?" : changedFingerprint), e);
        }
        if (e instanceof JSchUnknownHostKeyException) {
            return new IOException(GetText.tr("The server's host key wasn't trusted, so the connection was cancelled."),
                e);
        }
        if (message.startsWith("Auth fail")
            || message.startsWith("Auth cancel")) {
            return new AuthFailedException(server.usesKey()
                ? GetText.tr("The server didn't accept the username {0} with this private key.", server.username)
                : GetText.tr("The server didn't accept the username {0} with this password.", server.username));
        }
        if (cause instanceof UnknownHostException) {
            return new IOException(GetText.tr("Couldn't find the host {0}. Check the address.", server.host), e);
        }
        if (cause instanceof ConnectException || cause instanceof NoRouteToHostException) {
            return new IOException(GetText.tr("Couldn't connect to {0}. Check the host and port (Pterodactyl's SFTP "
                + "port is usually 2022).", where), e);
        }
        if (cause instanceof SocketTimeoutException || message.toLowerCase(Locale.ENGLISH).contains("timeout")) {
            return new IOException(GetText.tr("Timed out connecting to {0}.", where), e);
        }
        if (e instanceof JSchAlgoNegoFailException) {
            return new IOException(GetText.tr("The server and the launcher have no encryption method in common. "
                + "Ed25519 keys need the launcher to run on Java 15 or newer."), e);
        }
        return new IOException(GetText.tr("Couldn't connect to {0}: {1}", where, message), e);
    }

    private static IOException error(SftpException e, String path) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
            return new NoSuchFileException(path, null, GetText.tr("doesn't exist on the server"));
        }
        if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) {
            return new AccessDeniedException(path, null, GetText.tr("permission denied"));
        }
        return new IOException(path + ": " + e.getMessage(), e);
    }

    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static SftpClient.RemoteFile file(String name, String path, SftpATTRS attrs) {
        // mtime is unsigned seconds
        return new SftpClient.RemoteFile(name, path, attrs.getSize(), (attrs.getMTime() & 0xffffffffL) * 1000L,
            attrs.isDir());
    }

    @Override
    public List<RemoteFile> list(String path) throws IOException {
        List<RemoteFile> files = new ArrayList<>();
        try {
            for (ChannelSftp.LsEntry entry : sftp.ls(path)) {
                String name = entry.getFilename();
                if (!name.equals(".") && !name.equals("..")) {
                    files.add(file(name, SftpClient.join(path, name), entry.getAttrs()));
                }
            }
        } catch (SftpException e) {
            throw error(e, path);
        }
        return files;
    }

    @Override
    public RemoteFile stat(String path) throws IOException {
        try {
            return file(name(path), path, sftp.stat(path));
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return null;
            }
            throw error(e, path);
        }
    }

    @Override
    public void upload(Path local, String remotePath, LongPredicate progress) throws IOException {
        Monitor monitor = new Monitor(progress);
        try (InputStream in = Files.newInputStream(local)) {
            sftp.put(in, remotePath, monitor, ChannelSftp.OVERWRITE);
        } catch (SftpException e) {
            throw error(e, remotePath);
        }
        monitor.throwIfCancelled();
    }

    @Override
    public void download(String remotePath, Path local, LongPredicate progress) throws IOException {
        Monitor monitor = new Monitor(progress);
        Path partial = local.resolveSibling(local.getFileName() + ".part");
        try {
            try (OutputStream out = Files.newOutputStream(partial)) {
                sftp.get(remotePath, out, monitor);
            } catch (SftpException e) {
                throw error(e, remotePath);
            }
            monitor.throwIfCancelled();
            Files.move(partial, local, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    @Override
    public void mkdirs(String path) throws IOException {
        if (path.isEmpty() || path.equals("/") || path.equals(".")) {
            return;
        }
        RemoteFile existing = stat(path);
        if (existing != null) {
            if (!existing.directory) {
                throw new IOException(GetText.tr("{0} on the server is a file, not a folder.", path));
            }
            return;
        }
        mkdirs(SftpClient.parent(path));
        try {
            sftp.mkdir(path);
        } catch (SftpException e) {
            throw error(e, path);
        }
    }

    @Override
    public void remove(String path) throws IOException {
        RemoteFile existing = stat(path);
        if (existing == null) {
            throw new NoSuchFileException(path, null, GetText.tr("doesn't exist on the server"));
        }
        try {
            if (existing.directory) {
                sftp.rmdir(path);
            } else {
                sftp.rm(path);
            }
        } catch (SftpException e) {
            throw error(e, path);
        }
    }

    @Override
    public void rename(String from, String to) throws IOException {
        // JSch uses OpenSSH's posix-rename when offered, which replaces what's there; this interface never does
        if (stat(to) != null) {
            throw new FileAlreadyExistsException(to, null, GetText.tr("already exists on the server"));
        }
        try {
            sftp.rename(from, to);
        } catch (SftpException e) {
            throw error(e, from);
        }
    }

    @Override
    public void close() {
        sftp.disconnect();
        session.disconnect();
    }

    /**
     * Wraps the saved known hosts: asks about a new host and saves its key if trusted, and treats any other key for
     * a known host, including one of another type, as changed.
     */
    static final class TrustOnFirstUse implements HostKeyRepository {
        private final HostKeyRepository known;
        private final HostKeyPrompt prompt;
        // set when the server sent a key that doesn't match the saved one
        String changedFingerprint;

        TrustOnFirstUse(HostKeyRepository known, HostKeyPrompt prompt) {
            this.known = known;
            this.prompt = prompt;
        }

        @Override
        public int check(String host, byte[] key) {
            int result = known.check(host, key);
            if (result == NOT_INCLUDED && known.getHostKey(host, null).length > 0) {
                // a server switching key type would otherwise get a fresh first-use prompt
                result = CHANGED;
            }
            if (result == CHANGED) {
                changedFingerprint = fingerprint(key);
                return CHANGED;
            }
            if (result == OK) {
                return OK;
            }

            HostKey hostKey;
            try {
                hostKey = new HostKey(host, key);
            } catch (JSchException e) {
                return NOT_INCLUDED;
            }
            if (prompt == null || !prompt.trustNewHost(host, hostKey.getType(), fingerprint(key))) {
                return NOT_INCLUDED;
            }
            known.add(hostKey, null);
            return OK;
        }

        @Override
        public void add(HostKey hostkey, UserInfo ui) {
            known.add(hostkey, ui);
        }

        @Override
        public void remove(String host, String type) {
            known.remove(host, type);
        }

        @Override
        public void remove(String host, String type, byte[] key) {
            known.remove(host, type, key);
        }

        @Override
        public String getKnownHostsRepositoryID() {
            return known.getKnownHostsRepositoryID();
        }

        @Override
        public HostKey[] getHostKey() {
            return known.getHostKey();
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            return known.getHostKey(host, type);
        }
    }

    private static final class Monitor implements SftpProgressMonitor {
        private final LongPredicate progress;
        private boolean cancelled = false;

        Monitor(LongPredicate progress) {
            this.progress = progress;
        }

        @Override
        public void init(int op, String src, String dest, long max) {
        }

        @Override
        public boolean count(long count) {
            if (progress != null && !progress.test(count)) {
                cancelled = true;
            }
            return !cancelled;
        }

        @Override
        public void end() {
        }

        void throwIfCancelled() throws InterruptedIOException {
            if (cancelled) {
                throw new InterruptedIOException(GetText.tr("Cancelled"));
            }
        }
    }
}
