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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.atlauncher.data.RemoteServer;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;

/**
 * Trust on first use, checked against JSch's real known_hosts handling, without a network.
 */
class JschSftpClientTest {
    @TempDir
    Path temp;

    // a public key blob as a server sends it: the type name, then key data
    static byte[] key(String type, int seed) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        out.writeInt(name.length);
        out.write(name);
        byte[] data = new byte[32];
        Arrays.fill(data, (byte) seed);
        out.writeInt(data.length);
        out.write(data);
        return bytes.toByteArray();
    }

    // a fresh repository read from the file, as each connection gets
    HostKeyRepository known(Path file) throws JSchException {
        JSch jsch = new JSch();
        jsch.setKnownHosts(file.toString());
        return jsch.getHostKeyRepository();
    }

    Path knownHostsFile() throws IOException {
        return Files.createFile(temp.resolve("known_hosts"));
    }

    @Test
    void asksOnceForANewHostAndSavesTheKey() throws Exception {
        Path file = knownHostsFile();
        String host = JschSftpClient.hostKeyName("node.example.com", 2022);
        byte[] key = key("ssh-ed25519", 1);
        List<String> asked = new ArrayList<>();

        JschSftpClient.TrustOnFirstUse first = new JschSftpClient.TrustOnFirstUse(known(file), (h, type, print) -> {
            asked.add(h + " " + type + " " + print);
            return true;
        });
        assertEquals(HostKeyRepository.OK, first.check(host, key));
        assertEquals(Arrays.asList(host + " ssh-ed25519 " + JschSftpClient.fingerprint(key)), asked);
        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).startsWith(host + " ssh-ed25519 "));

        JschSftpClient.TrustOnFirstUse second = new JschSftpClient.TrustOnFirstUse(known(file), (h, type, print) -> {
            throw new AssertionError("a saved key mustn't be asked about again");
        });
        assertEquals(HostKeyRepository.OK, second.check(host, key));
    }

    @Test
    void declinedHostIsNotSaved() throws Exception {
        Path file = knownHostsFile();
        String host = JschSftpClient.hostKeyName("node.example.com", 2022);

        JschSftpClient.TrustOnFirstUse check = new JschSftpClient.TrustOnFirstUse(known(file), (h, t, p) -> false);

        assertEquals(HostKeyRepository.NOT_INCLUDED, check.check(host, key("ssh-ed25519", 1)));
        assertEquals(0, Files.size(file));
    }

    @Test
    void changedKeyIsBlockedWithoutAsking() throws Exception {
        Path file = knownHostsFile();
        String host = JschSftpClient.hostKeyName("node.example.com", 2022);
        new JschSftpClient.TrustOnFirstUse(known(file), (h, t, p) -> true).check(host, key("ssh-ed25519", 1));
        byte[] other = key("ssh-ed25519", 2);

        JschSftpClient.TrustOnFirstUse check = new JschSftpClient.TrustOnFirstUse(known(file), (h, t, p) -> {
            throw new AssertionError("a changed key must never be offered for trust");
        });

        assertEquals(HostKeyRepository.CHANGED, check.check(host, other));
        assertEquals(JschSftpClient.fingerprint(other), check.changedFingerprint);
        // and the saved key is left as it was
        assertEquals(HostKeyRepository.OK, known(file).check(host, key("ssh-ed25519", 1)));
    }

    @Test
    void keyOfAnotherTypeForAKnownHostCountsAsChanged() throws Exception {
        Path file = knownHostsFile();
        String host = JschSftpClient.hostKeyName("node.example.com", 2022);
        new JschSftpClient.TrustOnFirstUse(known(file), (h, t, p) -> true).check(host, key("ssh-ed25519", 1));

        JschSftpClient.TrustOnFirstUse check = new JschSftpClient.TrustOnFirstUse(known(file), (h, t, p) -> true);

        assertEquals(HostKeyRepository.CHANGED, check.check(host, key("ssh-rsa", 3)));
    }

    @Test
    void anotherPortOnTheSameHostIsASeparateServer() throws Exception {
        Path file = knownHostsFile();
        new JschSftpClient.TrustOnFirstUse(known(file), (h, t, p) -> true)
            .check(JschSftpClient.hostKeyName("node.example.com", 22), key("ssh-ed25519", 1));

        List<String> asked = new ArrayList<>();
        JschSftpClient.TrustOnFirstUse check = new JschSftpClient.TrustOnFirstUse(known(file), (h, t, p) -> {
            asked.add(h);
            return true;
        });

        assertEquals(HostKeyRepository.OK,
            check.check(JschSftpClient.hostKeyName("node.example.com", 2022), key("ssh-ed25519", 2)));
        assertEquals(1, asked.size());
        assertNull(check.changedFingerprint);
    }

    @Test
    void forgettingAKeyMeansTheNextConnectionAsksAgain() throws Exception {
        Path file = knownHostsFile();
        String host = JschSftpClient.hostKeyName("node.example.com", 2022);
        new JschSftpClient.TrustOnFirstUse(known(file), (h, t, p) -> true).check(host, key("ssh-ed25519", 1));

        assertTrue(JschSftpClient.forgetHostKey(file, "node.example.com", 2022));
        assertFalse(JschSftpClient.forgetHostKey(file, "node.example.com", 2022));

        List<String> asked = new ArrayList<>();
        new JschSftpClient.TrustOnFirstUse(known(file), (h, t, p) -> asked.add(h)).check(host, key("ssh-ed25519", 2));
        assertEquals(1, asked.size());
    }

    @Test
    void fingerprintIsOpenSshSha256WithoutPadding() throws Exception {
        String print = JschSftpClient.fingerprint(key("ssh-ed25519", 1));

        assertTrue(print.startsWith("SHA256:"));
        assertEquals(7 + 43, print.length());
        assertFalse(print.endsWith("="));
    }

    @Test
    void aRememberedPassphraseTheKeyNoLongerTakesIsAnAuthFailure() throws Exception {
        Path keyFile = temp.resolve("id_rsa");
        KeyPair pair = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048);
        pair.writePrivateKey(keyFile.toString(), "changed".getBytes(StandardCharsets.UTF_8));
        pair.dispose();
        RemoteServer server = new RemoteServer();
        server.host = "node.example.com";
        server.port = 2022;
        server.username = "name.1a2b3c4d";
        server.auth = RemoteServer.Auth.KEY;
        server.privateKeyPath = keyFile.toString();

        // fails before any network: so it's forgotten and asked for again, not blamed on the key file
        IOException wrong = assertThrows(IOException.class, () -> JschSftpClient.connect(server,
            "remembered".getBytes(StandardCharsets.UTF_8), temp.resolve("known_hosts"), null));

        assertInstanceOf(JschSftpClient.AuthFailedException.class, wrong);
        assertTrue(wrong.getMessage().contains(keyFile.toString()));
    }

    @Test
    void connectErrorsAreReadable() {
        RemoteServer server = new RemoteServer();
        server.host = "node.example.com";
        server.port = 2022;
        server.username = "name.1a2b3c4d";

        IOException auth = JschSftpClient.connectError(new JSchException("Auth fail for methods 'password'"),
            server, null);
        assertInstanceOf(JschSftpClient.AuthFailedException.class, auth);
        assertTrue(auth.getMessage().contains("name.1a2b3c4d"));

        // TrustOnFirstUse records the fingerprint whenever it reports a changed key
        IOException changed = JschSftpClient.connectError(new JSchException("HostKey has been changed"), server,
            "SHA256:abc");
        assertTrue(changed.getMessage().startsWith("WARNING"));
        assertTrue(changed.getMessage().contains("SHA256:abc"));

        IOException unknown = JschSftpClient.connectError(
            new JSchException("java.net.UnknownHostException: node.example.com",
                new UnknownHostException("node.example.com")),
            server, null);
        assertTrue(unknown.getMessage().contains("Couldn't find the host node.example.com"));
    }
}
