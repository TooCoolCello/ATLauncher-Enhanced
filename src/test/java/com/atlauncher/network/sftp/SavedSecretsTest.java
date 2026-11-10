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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SavedSecretsTest {
    @TempDir
    Path dir;

    @Test
    public void savesEncryptedAndForgets() throws Exception {
        assumeTrue(SavedSecrets.available(), "DPAPI is Windows only");
        Path file = dir.resolve("passwords.json");
        byte[] password = "hunter2-ünïcode".getBytes(StandardCharsets.UTF_8);

        SavedSecrets.save(file, "a", password);
        assertTrue(SavedSecrets.has(file, "a"));
        assertFalse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("hunter2"));
        assertArrayEquals(password, SavedSecrets.load(file, "a"));

        assertTrue(SavedSecrets.remove(file, "a"));
        assertNull(SavedSecrets.load(file, "a"));
        assertFalse(Files.exists(file));
    }

    @Test
    public void blobCopiedToAnotherServerIsDropped() throws Exception {
        assumeTrue(SavedSecrets.available(), "DPAPI is Windows only");
        Path file = dir.resolve("passwords.json");
        SavedSecrets.save(file, "a", "secret".getBytes(StandardCharsets.UTF_8));
        // hand-edit: a's encrypted password under b's id
        String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace("\"a\"", "\"b\"");
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));

        assertNull(SavedSecrets.load(file, "b"));
        assertFalse(SavedSecrets.has(file, "b"));
    }
}
