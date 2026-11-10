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
package com.atlauncher.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteServerTest {
    @TempDir
    Path temp;

    static RemoteServer server(String host) {
        RemoteServer server = new RemoteServer();
        server.name = "Survival";
        server.host = host;
        server.username = "name.1a2b3c4d";
        return server;
    }

    @Test
    void pterodactylAddressIsSplitIntoHostAndPort() {
        RemoteServer server = server(" sftp://node.example.com:2022 ");
        server.normalise();

        assertEquals("node.example.com", server.host);
        assertEquals(2022, server.port);
        assertNull(server.validate());
    }

    @Test
    void userAtHostFillsAMissingUsername() {
        RemoteServer server = server("name.1a2b3c4d@node.example.com:2022");
        server.username = " ";
        server.normalise();

        assertEquals("name.1a2b3c4d", server.username);
        assertEquals("node.example.com", server.host);
    }

    @Test
    void bareIpv6AddressKeepsItsColons() {
        RemoteServer server = server("2001:db8::1");
        server.normalise();

        assertEquals("2001:db8::1", server.host);
        assertEquals(22, server.port);
        assertNull(server.validate());
    }

    @Test
    void serverFolderTypedWithBackslashesUsesForwardSlashes() {
        RemoteServer server = server("node.example.com");
        server.remotePath = " \\home\\mc\\server ";
        server.normalise();

        assertEquals("/home/mc/server", server.remotePath);
    }

    @Test
    void invalidEntriesSayWhatsWrong() {
        RemoteServer badPort = server("node.example.com:abc");
        badPort.normalise();
        assertNotNull(badPort.validate());

        RemoteServer noKey = server("node.example.com");
        noKey.auth = RemoteServer.Auth.KEY;
        noKey.normalise();
        assertNotNull(noKey.validate());

        RemoteServer noName = server("node.example.com");
        noName.name = "";
        noName.normalise();
        assertNotNull(noName.validate());
    }

    @Test
    void savesAndLoadsWithoutSecrets() throws IOException {
        Path file = temp.resolve("configs/remote_servers.json");
        RemoteServer saved = server("node.example.com");
        saved.port = 2022;
        saved.notes = "Pterodactyl";
        RemoteServer.saveAll(file, Collections.singletonList(saved));

        List<RemoteServer> loaded = RemoteServer.loadAll(file);

        assertEquals(1, loaded.size());
        assertEquals(saved.id, loaded.get(0).id);
        assertEquals(2022, loaded.get(0).port);
        assertEquals("/", loaded.get(0).getRemotePath());
        String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).toLowerCase();
        assertTrue(!json.contains("password\":") && !json.contains("passphrase"));
    }

    @Test
    void entryWrittenWithoutNewerFieldsGetsDefaults() throws IOException {
        Path file = temp.resolve("remote_servers.json");
        Files.write(file, "[{\"id\":\"a\",\"name\":\"Old\",\"host\":\"h\",\"username\":\"u\"}]"
            .getBytes(StandardCharsets.UTF_8));

        RemoteServer loaded = RemoteServer.loadAll(file).get(0);

        assertEquals(RemoteServer.Auth.PASSWORD, loaded.auth);
        assertEquals("/", loaded.getRemotePath());
    }

    @Test
    void unreadableFileIsAnErrorNotAnEmptyList() throws IOException {
        Path file = temp.resolve("remote_servers.json");
        Files.write(file, "{not json".getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> RemoteServer.loadAll(file));
        assertTrue(RemoteServer.loadAll(temp.resolve("missing.json")).isEmpty());
    }
}
