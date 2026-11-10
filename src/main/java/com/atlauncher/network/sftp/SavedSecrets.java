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
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import com.atlauncher.Gsons;
import com.atlauncher.utils.OS;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;

/**
 * Remote server passwords and passphrases the user chose to remember, encrypted with Windows' DPAPI so only their
 * Windows account on this computer can read them. Kept apart from remote_servers.json, by server id, so the server list
 * never carries secrets. Windows only: other systems have no equivalent the launcher can reach without a keyring.
 */
public final class SavedSecrets {
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    private SavedSecrets() {
    }

    public static boolean available() {
        return OS.isWindows();
    }

    public static synchronized boolean has(Path file, String id) {
        try {
            return read(file).containsKey(id);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * @return the decrypted secret, or null if none is saved or it can't be decrypted (such as after a Windows
     *         password reset), in which case it's removed
     */
    public static synchronized byte[] load(Path file, String id) throws IOException {
        Map<String, String> saved = read(file);
        String blob = saved.get(id);
        if (blob == null || !available()) {
            return null;
        }
        try {
            // the server id as entropy: a blob copied to another server's entry doesn't decrypt
            return Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(blob), entropy(id),
                WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null);
        } catch (RuntimeException e) {
            saved.remove(id);
            write(file, saved);
            return null;
        }
    }

    public static synchronized void save(Path file, String id, byte[] secret) throws IOException {
        if (!available()) {
            throw new IOException("Passwords can only be remembered on Windows");
        }
        byte[] blob;
        try {
            blob = Crypt32Util.cryptProtectData(secret, entropy(id), WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
                "ATLauncher remote server", null);
        } catch (RuntimeException e) {
            throw new IOException("Windows couldn't encrypt the password", e);
        }
        Map<String, String> saved = read(file);
        saved.put(id, Base64.getEncoder().encodeToString(blob));
        write(file, saved);
    }

    /**
     * @return whether one was saved
     */
    public static synchronized boolean remove(Path file, String id) throws IOException {
        Map<String, String> saved = read(file);
        if (saved.remove(id) == null) {
            return false;
        }
        write(file, saved);
        return true;
    }

    private static byte[] entropy(String id) {
        return id.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new HashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, String> saved = Gsons.DEFAULT.fromJson(reader, MAP_TYPE);
            return saved == null ? new HashMap<>() : new HashMap<>(saved);
        } catch (JsonParseException e) {
            throw new IOException(file + " couldn't be read", e);
        }
    }

    private static void write(Path file, Map<String, String> saved) throws IOException {
        if (saved.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            Gsons.DEFAULT.toJson(saved, MAP_TYPE, writer);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
