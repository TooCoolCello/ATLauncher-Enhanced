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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.atlauncher.data.ModSide;
import com.atlauncher.data.minecraft.loaders.LoaderType;
import com.atlauncher.data.modrinth.pack.ModrinthModpackManifest;

class ServerImportTest {
    @TempDir
    Path temp;

    static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    static List<String> files(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.filter(Files::isRegularFile).map(p -> folder.relativize(p).toString().replace('\\', '/'))
                .sorted().collect(Collectors.toList());
        }
    }

    @Test
    void gathersWhatAClientNeedsFromTheServer() throws IOException {
        Path server = temp.resolve("server");
        write(server.resolve("mods/create.jar"), "create");
        write(server.resolve("mods/.connector/cache.jar"), "cache");
        write(server.resolve("mods/readme.txt"), "text");
        write(server.resolve("config/create-common.toml"), "common");
        write(server.resolve("config/jei-client.toml"), "client");
        write(server.resolve("defaultconfigs/create-server.toml"), "server");
        write(server.resolve("kubejs/server_scripts/recipes.js"), "recipes");
        write(server.resolve("kubejs/client_scripts/tooltips.js"), "tooltips");
        write(server.resolve("world/level.dat"), "world");
        write(server.resolve("server.properties"), "motd=hi");
        Path folder = temp.resolve("import");

        ServerImport.Gathered gathered = ServerImport.gather(new LocalDirSftpClient(server), "/", folder,
            message -> true);

        assertEquals(1, gathered.mods);
        assertEquals(3, gathered.files);
        assertEquals(Arrays.asList("overrides/config/create-common.toml",
            "overrides/defaultconfigs/create-server.toml", "overrides/kubejs/server_scripts/recipes.js",
            "overrides/mods/create.jar"), files(folder));
    }

    @Test
    void leavesOutServerOnlyMods() throws IOException {
        Path mods = temp.resolve("overrides/mods");
        write(mods.resolve("create.jar"), "create");
        write(mods.resolve("spark.jar"), "spark");
        write(mods.resolve("custom.jar"), "custom");

        List<String> left = ServerImport.leaveOutServerOnly(mods, jars -> {
            Map<Path, ServerImport.Match> found = new HashMap<>();
            ServerImport.Match spark = new ServerImport.Match();
            spark.side = ModSide.SERVER;
            spark.name = "Spark";
            found.put(mods.resolve("spark.jar"), spark);
            ServerImport.Match create = new ServerImport.Match();
            create.side = ModSide.BOTH;
            found.put(mods.resolve("create.jar"), create);
            return found;
        });

        assertEquals(Collections.singletonList("Spark"), left);
        assertEquals(Arrays.asList("create.jar", "custom.jar"), files(mods));
    }

    @Test
    void describesTheServerAsAModrinthPack() {
        ServerDetection.Info info = new ServerDetection.Info();
        info.minecraftVersion = "1.21.1";
        info.loader = LoaderType.NEOFORGE;
        info.loaderVersion = "21.1.77";
        assertNull(ServerImport.problem(info));

        ModrinthModpackManifest manifest = ServerImport.manifest("Survival", info);

        assertEquals("minecraft", manifest.game);
        assertEquals(1, manifest.formatVersion);
        assertTrue(manifest.files.isEmpty());
        assertEquals("1.21.1", manifest.dependencies.get("minecraft"));
        assertEquals("21.1.77", manifest.dependencies.get("neoforge"));

        info.loader = LoaderType.FABRIC;
        assertEquals("21.1.77", ServerImport.manifest("Survival", info).dependencies.get("fabric-loader"));

        info.loaderVersion = null;
        info.loaderVersionProblem = "several";
        assertNotNull(ServerImport.problem(info));
        info.loader = LoaderType.PAPER;
        assertNotNull(ServerImport.problem(info));
    }
}
