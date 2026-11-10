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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.atlauncher.data.minecraft.loaders.LoaderType;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;

class ServerDetectionTest {
    @TempDir
    Path server;

    LocalDirSftpClient client;

    @BeforeEach
    void setUp() {
        client = new LocalDirSftpClient(server);
    }

    void write(String relative, String text) throws IOException {
        Path file = server.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    void folder(String relative) throws IOException {
        Files.createDirectories(server.resolve(relative));
    }

    ServerDetection.Info detect() throws IOException {
        return ServerDetection.detect(client, "/");
    }

    @Test
    void neoForgeRunScriptNamesTheVersionThatRuns() throws IOException {
        write("run.sh", "#!/usr/bin/env sh\njava @user_jvm_args.txt "
            + "@libraries/net/neoforged/neoforge/21.1.77/unix_args.txt \"$@\"\n");
        write("libraries/net/neoforged/neoforge/21.1.77/unix_args.txt", "-p libraries/a.jar\n"
            + "--launchTarget forgeserver\n--fml.neoForgeVersion 21.1.77\n--fml.fmlVersion 4.0.24\n"
            + "--fml.mcVersion 1.21.1\n--fml.neoFormVersion 20240808.144430\n");
        // an upgrade leaves the old version's libraries behind
        folder("libraries/net/neoforged/neoforge/21.1.70");
        folder("libraries/net/minecraft/server/1.21.1-20240808.144430");

        ServerDetection.Info info = detect();

        assertEquals(LoaderType.NEOFORGE, info.loader);
        assertEquals("21.1.77", info.loaderVersion);
        assertEquals("1.21.1", info.minecraftVersion);
    }

    @Test
    void pterodactylArgsLinkAndNeoForgeFor1201() throws IOException {
        write("unix_args.txt", "--launchTarget forgeserver\n--fml.forgeVersion 47.1.106\n--fml.mcVersion 1.20.1\n"
            + "--fml.forgeGroup net.neoforged\n--fml.mcpVersion 20230612.114412\n");

        ServerDetection.Info info = detect();

        assertEquals(LoaderType.NEOFORGE, info.loader);
        assertEquals("47.1.106", info.loaderVersion);
        assertEquals("1.20.1", info.minecraftVersion);
    }

    @Test
    void forgeFromItsLibraryFolder() throws IOException {
        folder("libraries/net/minecraftforge/forge/1.20.1-47.2.0");
        folder("libraries/net/minecraftforge/eventbus/6.0.5");

        ServerDetection.Info info = detect();

        assertEquals(LoaderType.FORGE, info.loader);
        assertEquals("47.2.0", info.loaderVersion);
        assertEquals("1.20.1", info.minecraftVersion);
    }

    @Test
    void oldForgeFromItsJarName() throws IOException {
        write("forge-1.12.2-14.23.5.2860.jar", "jar");
        write("forge-1.12.2-14.23.5.2860-installer.jar", "jar");
        write("minecraft_server.1.12.2.jar", "jar");

        ServerDetection.Info info = detect();

        assertEquals(LoaderType.FORGE, info.loader);
        assertEquals("14.23.5.2860", info.loaderVersion);
        assertEquals("1.12.2", info.minecraftVersion);
        assertEquals("1.7.10", ServerDetection.splitForge("1.7.10-10.13.4.1614-1.7.10")[0]);
        assertEquals("10.13.4.1614", ServerDetection.splitForge("1.7.10-10.13.4.1614-1.7.10")[1]);
    }

    @Test
    void serverPacksThatHaveNotBeenInstalledYet() throws IOException {
        write("variables.txt", "# comment\nMINECRAFT_VERSION=1.20.1\nMODLOADER=Forge\nMODLOADER_VERSION=47.2.0\n");
        ServerDetection.Info packCreator = detect();
        assertEquals(LoaderType.FORGE, packCreator.loader);
        assertEquals("47.2.0", packCreator.loaderVersion);
        assertEquals("1.20.1", packCreator.minecraftVersion);

        Files.delete(server.resolve("variables.txt"));
        write("neoforge-21.1.77-installer.jar", "jar");
        ServerDetection.Info installer = detect();
        assertEquals(LoaderType.NEOFORGE, installer.loader);
        assertEquals("21.1.77", installer.loaderVersion);
        assertEquals("1.21.1", installer.minecraftVersion);
        assertEquals("1.21", ServerDetection.neoForgeMinecraft("21.0.167"));
        assertEquals("1.20.4", ServerDetection.neoForgeMinecraft("20.4.237"));
        assertNull(ServerDetection.neoForgeMinecraft("47.1.106"));
    }

    @Test
    void fabricFromLoaderAndIntermediary() throws IOException {
        folder("libraries/net/fabricmc/fabric-loader/0.15.7");
        folder("libraries/net/fabricmc/intermediary/1.20.1");
        folder("libraries/net/fabricmc/sponge-mixin/0.12.5");

        ServerDetection.Info info = detect();

        assertEquals(LoaderType.FABRIC, info.loader);
        assertEquals("0.15.7", info.loaderVersion);
        assertEquals("1.20.1", info.minecraftVersion);
    }

    @Test
    void quiltIsNotMistakenForFabric() throws IOException {
        folder("libraries/org/quiltmc/quilt-loader/0.26.0");
        folder("libraries/net/fabricmc/intermediary/1.20.1");

        ServerDetection.Info info = detect();

        assertEquals(LoaderType.QUILT, info.loader);
        assertEquals("1.20.1", info.minecraftVersion);
    }

    @Test
    void leftoverVersionsWithoutAScriptStayUnknown() throws IOException {
        folder("libraries/net/minecraftforge/forge/1.20.1-47.1.0");
        folder("libraries/net/minecraftforge/forge/1.20.1-47.2.0");

        ServerDetection.Info info = detect();

        assertEquals(LoaderType.FORGE, info.loader);
        assertNull(info.loaderVersion);
        assertNotNull(info.loaderVersionProblem);
        // both versions are for the same Minecraft version, so that much is still known
        assertEquals("1.20.1", info.minecraftVersion);
    }

    @Test
    void twoLoadersOrNothingAreUnknown() throws IOException {
        ServerDetection.Info empty = detect();
        assertNull(empty.loader);
        assertNull(empty.minecraftVersion);
        assertNotNull(empty.loaderProblem);
        assertNotNull(empty.minecraftProblem);

        folder("libraries/net/minecraftforge/forge/1.20.1-47.2.0");
        folder("libraries/net/fabricmc/fabric-loader/0.15.7");
        ServerDetection.Info mixed = detect();
        assertNull(mixed.loader);
        assertTrue(mixed.loaderProblem.contains("Forge"));
    }

    @Test
    void comparingStopsOnlyOnVersionOrLoaderMismatch() {
        ServerDetection.Info server = new ServerDetection.Info();
        server.minecraftVersion = "1.20.1";
        server.loader = LoaderType.FORGE;
        server.loaderVersion = "47.2.0";

        LoaderVersion forge = new LoaderVersion("47.2.0", "1.20.1-47.2.0", false, "Forge");
        ServerDetection.Verdict same = ServerDetection.compare(server, "1.20.1", forge);
        assertTrue(same.mismatches.isEmpty() && same.unknowns.isEmpty() && same.warnings.isEmpty());

        // the raw version, with the Minecraft version in front, is the same Forge
        server.loaderVersion = "1.20.1-47.2.0";
        assertTrue(ServerDetection.compare(server, "1.20.1", forge).warnings.isEmpty());

        server.loaderVersion = "47.1.0";
        ServerDetection.Verdict older = ServerDetection.compare(server, "1.20.1", forge);
        assertTrue(older.mismatches.isEmpty());
        assertEquals(1, older.warnings.size());

        assertEquals(1, ServerDetection.compare(server, "1.20.4", forge).mismatches.size());
        assertEquals(1, ServerDetection.compare(server, "1.20.1",
            new LoaderVersion("47.1.106", false, "NeoForge")).mismatches.size());
        assertEquals(1, ServerDetection.compare(server, "1.20.1", null).mismatches.size());

        server.loader = null;
        server.loaderProblem = "nothing found";
        server.minecraftVersion = null;
        server.minecraftProblem = "nothing found";
        ServerDetection.Verdict unknown = ServerDetection.compare(server, "1.20.1", forge);
        assertTrue(unknown.mismatches.isEmpty());
        assertEquals(2, unknown.unknowns.size());
    }
}
