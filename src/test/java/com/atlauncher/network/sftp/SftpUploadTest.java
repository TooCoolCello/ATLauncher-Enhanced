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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SftpUploadTest {
    @TempDir
    Path temp;

    Path instance;
    Path server;
    LocalDirSftpClient client;

    @BeforeEach
    void setUp() throws IOException {
        instance = Files.createDirectories(temp.resolve("instance"));
        server = Files.createDirectories(temp.resolve("server"));
        client = new LocalDirSftpClient(server);
        write(instance.resolve("mods/a.jar"), "new a");
        write(instance.resolve("mods/b.jar"), "new b");
        write(instance.resolve("config/sub/c.toml"), "new c");
        write(instance.resolve("options.txt"), "client only");
    }

    static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    List<SftpUpload.Item> plan(String... selected) throws IOException {
        List<Path> paths = Arrays.stream(selected).map(instance::resolve).collect(Collectors.toList());
        List<SftpUpload.Item> items = SftpUpload.plan(instance, paths, "/");
        SftpUpload.findExisting(client, items);
        return items;
    }

    // nothing but the expected files: no partial uploads left over
    List<String> serverFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(server)) {
            return walk.filter(Files::isRegularFile).map(p -> server.relativize(p).toString().replace('\\', '/'))
                .sorted().collect(Collectors.toList());
        }
    }

    @Test
    void planExpandsFoldersOnceInPathOrder() throws IOException {
        List<SftpUpload.Item> items = SftpUpload.plan(instance,
            Arrays.asList(instance.resolve("mods"), instance.resolve("config"), instance.resolve("mods/a.jar")),
            "/srv/mc/");

        assertEquals(Arrays.asList("config/sub/c.toml", "mods/a.jar", "mods/b.jar"),
            items.stream().map(i -> i.relative).collect(Collectors.toList()));
        assertEquals("/srv/mc/mods/a.jar", items.get(1).remote);
        assertEquals(5, items.get(1).size);
    }

    @Test
    void planRefusesPathsOutsideTheInstance() {
        assertThrows(IOException.class,
            () -> SftpUpload.plan(instance, Collections.singletonList(temp.resolve("server")), "/"));
        assertThrows(IOException.class,
            () -> SftpUpload.plan(instance, Collections.singletonList(instance.resolve("../server")), "/"));
    }

    @Test
    void uploadsNewFilesIntoMissingFolders() throws IOException {
        List<SftpUpload.Item> items = plan("mods", "config");
        assertFalse(items.stream().anyMatch(i -> i.exists));

        SftpUpload.Result result = SftpUpload.run(client, items, SftpUpload.Existing.BACK_UP_AND_REPLACE,
            "/atlauncher-backups/x", (item, done) -> true);

        assertNull(result.error);
        assertEquals(3, result.uploaded);
        assertEquals(15, result.bytes);
        assertNull(result.backupFolder);
        assertEquals(Arrays.asList("config/sub/c.toml", "mods/a.jar", "mods/b.jar"), serverFiles());
        assertEquals("new c", read(server.resolve("config/sub/c.toml")));
    }

    @Test
    void backsUpReplacedFilesIntoTheDatedFolder() throws IOException {
        write(server.resolve("mods/a.jar"), "old a");
        write(server.resolve("mods/keep.jar"), "untouched");
        List<SftpUpload.Item> items = plan("mods");
        assertTrue(items.get(0).exists);
        assertFalse(items.get(1).exists);

        String backup = SftpUpload.backupFolder("/", LocalDateTime.of(2026, 11, 10, 14, 3, 22));
        assertEquals("/atlauncher-backups/2026-11-10_14-03-22", backup);
        SftpUpload.Result result = SftpUpload.run(client, items, SftpUpload.Existing.BACK_UP_AND_REPLACE, backup,
            (item, done) -> true);

        assertNull(result.error);
        assertEquals(2, result.uploaded);
        assertEquals(1, result.replaced);
        assertEquals(backup, result.backupFolder);
        assertEquals("new a", read(server.resolve("mods/a.jar")));
        assertEquals("old a", read(server.resolve("atlauncher-backups/2026-11-10_14-03-22/mods/a.jar")));
        assertEquals("untouched", read(server.resolve("mods/keep.jar")));
        assertEquals(Arrays.asList("atlauncher-backups/2026-11-10_14-03-22/mods/a.jar", "mods/a.jar", "mods/b.jar",
            "mods/keep.jar"), serverFiles());
    }

    @Test
    void replacesWithoutBackup() throws IOException {
        write(server.resolve("mods/a.jar"), "old a");

        SftpUpload.Result result = SftpUpload.run(client, plan("mods"), SftpUpload.Existing.REPLACE,
            "/atlauncher-backups/x", (item, done) -> true);

        assertEquals(1, result.replaced);
        assertNull(result.backupFolder);
        assertEquals("new a", read(server.resolve("mods/a.jar")));
        assertEquals(Arrays.asList("mods/a.jar", "mods/b.jar"), serverFiles());
    }

    @Test
    void skipsFilesAlreadyThere() throws IOException {
        write(server.resolve("mods/a.jar"), "old a");

        SftpUpload.Result result = SftpUpload.run(client, plan("mods"), SftpUpload.Existing.SKIP,
            "/atlauncher-backups/x", (item, done) -> true);

        assertEquals(1, result.skipped);
        assertEquals(1, result.uploaded);
        assertEquals("old a", read(server.resolve("mods/a.jar")));
        assertEquals("new b", read(server.resolve("mods/b.jar")));
    }

    @Test
    void cancellingPartWayLeavesNoHalfWrittenFile() throws IOException {
        write(server.resolve("mods/a.jar"), "old a");

        // "new a" is 5 bytes, sent in 4-byte chunks: cancel after the first chunk
        SftpUpload.Result result = SftpUpload.run(client, plan("mods/a.jar"),
            SftpUpload.Existing.BACK_UP_AND_REPLACE, "/atlauncher-backups/x", (item, done) -> done < 4);

        assertTrue(result.cancelled);
        assertEquals(0, result.uploaded);
        assertEquals("old a", read(server.resolve("mods/a.jar")));
        assertEquals(Collections.singletonList("mods/a.jar"), serverFiles());
    }

    // a server that loses the connection when moving an uploaded file into place
    SftpUpload.Result runWithFailingSwap(SftpUpload.Existing existing) throws IOException {
        LocalDirSftpClient failing = new LocalDirSftpClient(server) {
            @Override
            public void rename(String from, String to) throws IOException {
                if (from.endsWith(SftpUpload.PARTIAL_SUFFIX)) {
                    throw new IOException("connection lost");
                }
                super.rename(from, to);
            }
        };
        List<SftpUpload.Item> items = SftpUpload.plan(instance, Collections.singletonList(instance.resolve("mods")),
            "/");
        SftpUpload.findExisting(failing, items);
        return SftpUpload.run(failing, items, existing, "/atlauncher-backups/x", (item, done) -> true);
    }

    @Test
    void failedSwapPutsTheBackedUpFileBack() throws IOException {
        write(server.resolve("mods/a.jar"), "old a");

        SftpUpload.Result result = runWithFailingSwap(SftpUpload.Existing.BACK_UP_AND_REPLACE);

        assertNotNull(result.error);
        assertEquals(0, result.uploaded);
        assertEquals("old a", read(server.resolve("mods/a.jar")));
        assertEquals(Collections.singletonList("mods/a.jar"), serverFiles());
    }

    @Test
    void failedSwapWithoutBackupKeepsTheOldFile() throws IOException {
        write(server.resolve("mods/a.jar"), "old a");

        SftpUpload.Result result = runWithFailingSwap(SftpUpload.Existing.REPLACE);

        assertNotNull(result.error);
        assertEquals(0, result.uploaded);
        assertEquals("old a", read(server.resolve("mods/a.jar")));
        assertEquals(Collections.singletonList("mods/a.jar"), serverFiles());
    }

    @Test
    void joinsAndSplitsRemotePaths() {
        assertEquals("/mods/a.jar", SftpClient.join("/", "mods/a.jar"));
        assertEquals("/srv/mods/a.jar", SftpClient.join("/srv", "/mods\\a.jar"));
        assertEquals("mods/a.jar", SftpClient.join(".", "mods/a.jar"));
        assertEquals("/srv/mods", SftpClient.parent("/srv/mods/a.jar"));
        assertEquals("/", SftpClient.parent("/a.jar"));
        assertEquals("", SftpClient.parent("a.jar"));
    }
}
