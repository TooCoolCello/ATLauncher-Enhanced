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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.ModSide;
import com.atlauncher.data.modrinth.ModrinthProject;

class ServerSyncTest {
    @TempDir
    Path temp;

    Path instance;
    Path server;
    Path work;
    LocalDirSftpClient client;
    List<DisableableMod> mods = new ArrayList<>();
    // what the fake Modrinth/CurseForge lookup knows, by file name
    Map<String, Set<String>> platform = new HashMap<>();
    List<String> identified = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        instance = Files.createDirectories(temp.resolve("instance"));
        server = Files.createDirectories(temp.resolve("server"));
        work = Files.createDirectories(temp.resolve("work"));
        client = new LocalDirSftpClient(server);
    }

    static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    DisableableMod mod(String file, ModSide side, boolean disabled) {
        DisableableMod mod = new DisableableMod();
        mod.file = file;
        mod.side = side;
        mod.disabled = disabled;
        mods.add(mod);
        return mod;
    }

    Function<Collection<Path>, Map<Path, Set<String>>> identify = jars -> {
        Map<Path, Set<String>> found = new HashMap<>();
        for (Path jar : jars) {
            identified.add(jar.getFileName().toString());
            Set<String> keys = platform.get(jar.getFileName().toString());
            if (keys != null) {
                found.put(jar, keys);
            }
        }
        return found;
    };

    List<ServerSync.Change> plan() throws IOException {
        return ServerSync.plan(client, "/", instance, mods, identify, work, message -> true);
    }

    static ServerSync.Change find(List<ServerSync.Change> changes, String relative) {
        return changes.stream().filter(c -> c.relative.equals(relative)).findFirst()
            .orElseThrow(() -> new AssertionError(relative + " not in " + changes));
    }

    List<String> serverFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(server)) {
            return walk.filter(Files::isRegularFile).map(p -> server.relativize(p).toString().replace('\\', '/'))
                .sorted().collect(Collectors.toList());
        }
    }

    // an instance and a server that have drifted apart in every way sync handles
    void driftApart() throws IOException {
        write(instance.resolve("mods/same-1.0.jar"), "same");
        write(server.resolve("mods/same-1.0.jar"), "same");
        write(instance.resolve("mods/rebuilt.jar"), "new build");
        write(server.resolve("mods/rebuilt.jar"), "old");
        write(instance.resolve("mods/create-1.20.1-0.5.1.g.jar"), "create g");
        write(server.resolve("mods/create-1.20.1-0.5.1.f.jar"), "create f");
        write(instance.resolve("mods/bettercombat-forge-1.8.jar"), "combat 1.8");
        write(server.resolve("mods/better-combat-1.7.jar"), "combat 1.7");
        mod("bettercombat-forge-1.8.jar", null, false).modrinthProject = project("5sy6g3kz");
        platform.put("better-combat-1.7.jar", Collections.singleton("modrinth:5sy6g3kz"));
        write(instance.resolve("mods/new-mod-2.0.jar"), "new");
        write(instance.resolve("mods/sodium-0.5.jar"), "sodium");
        write(server.resolve("mods/sodium-0.5.jar"), "sodium");
        mod("sodium-0.5.jar", ModSide.CLIENT, false);
        write(instance.resolve("disabledmods/off-1.0.jar"), "off");
        write(server.resolve("mods/off-1.0.jar"), "off");
        mod("off-1.0.jar", null, true);
        write(server.resolve("mods/serverutils-2.0.jar"), "server only");

        write(instance.resolve("config/a.toml"), "a = 1");
        write(server.resolve("config/a.toml"), "a = 1");
        write(instance.resolve("config/sub/b.toml"), "b = 2");
        write(server.resolve("config/sub/b.toml"), "b = 1");
        write(instance.resolve("config/c.toml"), "c");
        write(instance.resolve("config/jei-client.toml"), "client");
        write(server.resolve("config/made-by-server.toml"), "x");
        write(instance.resolve("kubejs/server_scripts/recipes.js"), "recipes");
        write(instance.resolve("kubejs/client_scripts/tooltips.js"), "tooltips");
        write(instance.resolve("kubejs/assets/a.png"), "png");
        write(instance.resolve("options.txt"), "client only");
    }

    static ModrinthProject project(String id) {
        ModrinthProject project = new ModrinthProject();
        project.id = id;
        return project;
    }

    @Test
    void previewsEveryKindOfChange() throws IOException {
        driftApart();

        List<ServerSync.Change> changes = plan();

        assertEquals(ServerSync.Kind.UNCHANGED, find(changes, "mods/same-1.0.jar").kind);
        assertEquals(ServerSync.Kind.UPDATE, find(changes, "mods/rebuilt.jar").kind);
        assertNull(find(changes, "mods/rebuilt.jar").replaces);

        ServerSync.Change create = find(changes, "mods/create-1.20.1-0.5.1.g.jar");
        assertEquals(ServerSync.Kind.UPDATE, create.kind);
        assertEquals("mods/create-1.20.1-0.5.1.f.jar", create.replaces);

        ServerSync.Change combat = find(changes, "mods/bettercombat-forge-1.8.jar");
        assertEquals(ServerSync.Kind.UPDATE, combat.kind);
        assertEquals("mods/better-combat-1.7.jar", combat.replaces);

        assertEquals(ServerSync.Kind.ADD, find(changes, "mods/new-mod-2.0.jar").kind);
        assertTrue(find(changes, "mods/new-mod-2.0.jar").selected);

        // client-only, disabled and server-only mods on the server are offered for removal, unticked
        for (String removal : Arrays.asList("mods/sodium-0.5.jar", "mods/off-1.0.jar", "mods/serverutils-2.0.jar",
            "config/made-by-server.toml")) {
            assertEquals(ServerSync.Kind.REMOVE, find(changes, removal).kind, removal);
            assertFalse(find(changes, removal).selected, removal);
        }

        assertEquals(ServerSync.Kind.UNCHANGED, find(changes, "config/a.toml").kind);
        // same size, different content: only a hash tells
        assertEquals(ServerSync.Kind.UPDATE, find(changes, "config/sub/b.toml").kind);
        assertEquals(ServerSync.Kind.ADD, find(changes, "config/c.toml").kind);
        assertEquals(ServerSync.Kind.ADD, find(changes, "kubejs/server_scripts/recipes.js").kind);

        // client files never show up
        assertTrue(changes.stream().noneMatch(c -> c.relative.contains("client") || c.relative.contains("assets")
            || c.relative.equals("options.txt")), changes.toString());

        // grouped: adds, then updates, removals and unchanged files
        List<ServerSync.Kind> kinds = changes.stream().map(c -> c.kind).collect(Collectors.toList());
        List<ServerSync.Kind> sorted = new ArrayList<>(kinds);
        Collections.sort(sorted);
        assertEquals(sorted, kinds);
    }

    @Test
    void onlyDownloadsServerJarsNothingMatchedByName() throws IOException {
        write(instance.resolve("mods/a-1.0.jar"), "a");
        write(server.resolve("mods/a-1.0.jar"), "a");
        write(instance.resolve("mods/b-2.0.jar"), "b2");
        write(server.resolve("mods/b-1.0.jar"), "b1");

        plan();

        assertTrue(identified.isEmpty(), identified.toString());
        assertFalse(Files.exists(work.resolve("mods")));
    }

    @Test
    void applyingUploadsAndBacksUpButNeverDeletes() throws IOException {
        driftApart();
        List<ServerSync.Change> changes = plan();
        find(changes, "mods/serverutils-2.0.jar").selected = true;
        find(changes, "config/made-by-server.toml").selected = true;
        String backup = "/atlauncher-backups/2026-11-10_14-03-22";

        ServerSync.Result result = ServerSync.apply(client, "/", changes, true, backup, (change, done) -> true);

        assertNull(result.error);
        assertFalse(result.cancelled);
        assertEquals(7, result.uploaded);
        assertEquals(2, result.removed);
        assertEquals(backup, result.backupFolder);
        assertEquals(Arrays.asList(
            "atlauncher-backups/2026-11-10_14-03-22/config/made-by-server.toml",
            "atlauncher-backups/2026-11-10_14-03-22/config/sub/b.toml",
            "atlauncher-backups/2026-11-10_14-03-22/mods/better-combat-1.7.jar",
            "atlauncher-backups/2026-11-10_14-03-22/mods/create-1.20.1-0.5.1.f.jar",
            "atlauncher-backups/2026-11-10_14-03-22/mods/rebuilt.jar",
            "atlauncher-backups/2026-11-10_14-03-22/mods/serverutils-2.0.jar",
            "config/a.toml", "config/c.toml", "config/sub/b.toml",
            "kubejs/server_scripts/recipes.js",
            "mods/bettercombat-forge-1.8.jar", "mods/create-1.20.1-0.5.1.g.jar", "mods/new-mod-2.0.jar",
            // unticked removals stay
            "mods/off-1.0.jar", "mods/rebuilt.jar", "mods/same-1.0.jar", "mods/sodium-0.5.jar"), serverFiles());
        assertEquals("new build", read(server.resolve("mods/rebuilt.jar")));
        assertEquals("old", read(server.resolve("atlauncher-backups/2026-11-10_14-03-22/mods/rebuilt.jar")));
        assertEquals("b = 2", read(server.resolve("config/sub/b.toml")));

        // synced: nothing left to change
        assertTrue(plan().stream().filter(c -> c.selected).noneMatch(c -> c.kind != ServerSync.Kind.UNCHANGED));
    }

    @Test
    void cancellingStopsBetweenChanges() throws IOException {
        driftApart();
        List<ServerSync.Change> changes = plan();
        List<ServerSync.Change> seen = new ArrayList<>();

        ServerSync.Result result = ServerSync.apply(client, "/", changes, true, "/atlauncher-backups/x",
            (change, done) -> {
                if (!seen.contains(change)) {
                    seen.add(change);
                }
                return seen.size() < 2;
            });

        assertTrue(result.cancelled);
        assertEquals(1, result.uploaded);
    }

    @Test
    void planningCanBeCancelled() throws IOException {
        driftApart();
        assertThrows(InterruptedIOException.class,
            () -> ServerSync.plan(client, "/", instance, mods, identify, work, message -> false));
    }

    @Test
    void stemsIgnoreVersionsAndBuildWords() {
        assertEquals("create", ServerSync.stem("create-1.20.1-0.5.1.f.jar"));
        assertEquals("create", ServerSync.stem("create-1.20.1-0.5.1.g.jar"));
        assertEquals("jei", ServerSync.stem("jei-1.20.1-forge-15.2.0.27.jar"));
        assertEquals("securitycraft", ServerSync.stem("[1.20.1] SecurityCraft v1.9.10.jar"));
        assertEquals("mcw-bridges", ServerSync.stem("mcw-bridges-3.0.0-mc1.20.1forge.jar"));
        assertEquals("", ServerSync.stem("1.2.3.jar"));
    }
}
