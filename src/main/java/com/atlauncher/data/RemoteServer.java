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

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.Gsons;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

/**
 * A remote server reached over SFTP, such as a Pterodactyl server. Saved in configs/remote_servers.json. Passwords and
 * passphrases are never part of it: they're asked for once per launcher session, or saved encrypted apart from it
 * when the user chooses (see SavedSecrets).
 */
public class RemoteServer {
    public enum Auth {
        PASSWORD, KEY
    }

    private static final Type LIST_TYPE = new TypeToken<List<RemoteServer>>() {
    }.getType();

    public String id;
    public String name;
    public String host;
    public int port = 22;
    public String username;
    public Auth auth = Auth.PASSWORD;
    // private key file, for KEY auth
    public String privateKeyPath;
    // the server's root folder on the remote side; uploads go below it
    public String remotePath = "/";
    public String notes;

    public RemoteServer() {
        this.id = UUID.randomUUID().toString();
    }

    public RemoteServer copy() {
        RemoteServer copy = Gsons.DEFAULT.fromJson(Gsons.DEFAULT.toJson(this), RemoteServer.class);
        copy.id = id;
        return copy;
    }

    public boolean usesKey() {
        return auth == Auth.KEY;
    }

    public String getRemotePath() {
        return remotePath == null || remotePath.trim().isEmpty() ? "/" : remotePath.trim();
    }

    /**
     * Tidies what was typed: a host pasted from Pterodactyl as sftp://node.example.com:2022 becomes the host
     * node.example.com and the port 2022.
     */
    public void normalise() {
        name = trim(name);
        username = trim(username);
        privateKeyPath = trim(privateKeyPath);
        remotePath = getRemotePath();
        String h = trim(host);
        if (h != null) {
            if (h.toLowerCase(Locale.ENGLISH).startsWith("sftp://")) {
                h = h.substring("sftp://".length());
            }
            int slash = h.indexOf('/');
            if (slash >= 0) {
                h = h.substring(0, slash);
            }
            int at = h.lastIndexOf('@');
            if (at >= 0) {
                if (username == null) {
                    username = h.substring(0, at);
                }
                h = h.substring(at + 1);
            }
            // a single colon is host:port; more than one is a bare IPv6 address
            int colon = h.lastIndexOf(':');
            if (colon > 0 && h.indexOf(':') == colon) {
                try {
                    port = Integer.parseInt(h.substring(colon + 1));
                    h = h.substring(0, colon);
                } catch (NumberFormatException ignored) {
                    // left as typed, and validate() reports it
                }
            }
        }
        host = h;
    }

    /**
     * @return what's wrong with this entry, readable by the user, or null if it can be used
     */
    public String validate() {
        if (name == null || name.isEmpty()) {
            return GetText.tr("Enter a name for the server.");
        }
        // one colon left means the port after it wasn't a number; more than one is an IPv6 address
        boolean ipv6 = host != null && host.indexOf(':') != host.lastIndexOf(':');
        if (host == null || host.matches(".*[\\s/@].*") || (host.contains(":") && !ipv6)) {
            return GetText.tr("Enter the server's host, such as node.example.com.");
        }
        if (port < 1 || port > 65535) {
            return GetText.tr("The port must be between 1 and 65535.");
        }
        if (username == null || username.isEmpty()) {
            return GetText.tr("Enter the username to log in with.");
        }
        if (usesKey() && (privateKeyPath == null || privateKeyPath.isEmpty())) {
            return GetText.tr("Choose a private key file, or use password authentication.");
        }
        return null;
    }

    /**
     * Who and where, such as name.1a2b3c4d@node.example.com:2022/.
     */
    public String describe() {
        return username + "@" + host + ":" + port + getRemotePath();
    }

    @Override
    public String toString() {
        return name;
    }

    private static String trim(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    public static RemoteServer find(List<RemoteServer> servers, String id) {
        if (id == null) {
            return null;
        }
        return servers.stream().filter(s -> Objects.equals(s.id, id)).findFirst().orElse(null);
    }

    /**
     * @return the saved servers; none if the file doesn't exist yet
     * @throws IOException if the file exists but can't be read, so it's never overwritten by mistake
     */
    public static List<RemoteServer> loadAll(Path file) throws IOException {
        List<RemoteServer> servers = new ArrayList<>();
        if (!Files.exists(file)) {
            return servers;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<RemoteServer> read = Gsons.DEFAULT.fromJson(reader, LIST_TYPE);
            if (read != null) {
                for (RemoteServer server : read) {
                    if (server != null && server.id != null && find(servers, server.id) == null) {
                        if (server.auth == null) {
                            server.auth = Auth.PASSWORD;
                        }
                        servers.add(server);
                    }
                }
            }
        } catch (JsonParseException e) {
            throw new IOException(file + " couldn't be read", e);
        }
        return servers;
    }

    public static void saveAll(Path file, List<RemoteServer> servers) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            Gsons.DEFAULT.toJson(servers, LIST_TYPE, writer);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
