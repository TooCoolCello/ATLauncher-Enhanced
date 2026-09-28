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
package com.atlauncher.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.atlauncher.data.Instance;
import com.atlauncher.data.InstanceLauncher;
import com.atlauncher.data.Server;
import com.atlauncher.data.minecraft.MinecraftVersion;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.utils.DefaultConfigsFromWorld.Copy;
import com.atlauncher.utils.DefaultConfigsFromWorld.Decision;
import com.atlauncher.utils.DefaultConfigsFromWorld.Outcome;

/**
 * Copying server configs chosen from a world into an instance's defaultconfigs folder, all in a temporary folder.
 */
public class DefaultConfigsFromWorldTest {
    private static final LoaderVersion FORGE = new LoaderVersion("47.3.0", false, "Forge");
    private static final LoaderVersion NEOFORGE = new LoaderVersion("21.1.77", false, "NeoForge");
    private static final LoaderVersion FABRIC = new LoaderVersion("0.16.9", false, "Fabric");
    private static final LoaderVersion QUILT = new LoaderVersion("0.27.1", false, "Quilt");

    @TempDir
    Path temp;
    private Path root;
    // junctions made by a test, removed before the temporary folder is, as it can't be deleted through them
    private final List<Path> junctions = new ArrayList<>();

    /**
     * Answers as it's told to, and remembers what it was asked.
     */
    private static final class Answers implements DefaultConfigsFromWorld.Choices {
        String world;
        // null: cancel; otherwise these, whatever was offered
        List<String> files;
        Decision decision = Decision.COPY_ALL;
        List<Path> worldsOffered;
        List<String> filesOffered;
        List<Copy> copiesShown;
        final List<String> told = new ArrayList<>();

        @Override
        public Path chooseWorld(List<Path> worlds) {
            worldsOffered = worlds;
            return worlds.stream().filter(world -> world.getFileName().toString().equals(this.world)).findFirst()
                .orElse(null);
        }

        @Override
        public List<String> chooseFiles(Path world, List<String> files) {
            filesOffered = files;
            return this.files;
        }

        @Override
        public Decision confirm(List<Copy> copies) {
            copiesShown = copies;
            return decision;
        }

        @Override
        public void tell(String message) {
            told.add(message);
        }
    }

    @BeforeEach
    public void setUp() throws IOException {
        root = Files.createDirectories(temp.resolve("instances/Test Pack"));
    }

    @AfterEach
    public void removeJunctions() throws IOException {
        for (Path junction : junctions) {
            Files.deleteIfExists(junction);
        }
    }

    private Path world(String name) throws IOException {
        Path world = Files.createDirectories(root.resolve("saves").resolve(name));
        Files.write(world.resolve("level.dat"), new byte[] { 1 });
        return world;
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private Path defaults(String relative) {
        return root.resolve("defaultconfigs").resolve(relative);
    }

    private Outcome run(Answers answers) {
        return DefaultConfigsFromWorld.run(root, answers);
    }

    // a symbolic link, or for a folder on Windows without the right to make those, a junction, which leads out alike
    private void assumeLinks(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(Files.isDirectory(target) && System.getProperty("os.name").startsWith("Windows"),
                "symbolic links can't be made here: " + e);
        }

        try {
            new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start().waitFor();
        } catch (IOException e) {
            assumeTrue(false, "junctions can't be made here: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assumeTrue(Files.isDirectory(link), "junctions can't be made here");
        junctions.add(0, link);
    }

    // --- where it's offered

    private static Instance instance(LoaderVersion loader, String minecraft) {
        MinecraftVersion version = new MinecraftVersion();
        version.id = minecraft;
        Instance instance = new Instance(version);
        instance.launcher = new InstanceLauncher();
        instance.launcher.loaderVersion = loader;
        return instance;
    }

    @Test
    public void offeredForForgeAndNeoForgeInstancesThatKeepServerConfigs() {
        assertTrue(DefaultConfigsFromWorld.isSupported(instance(FORGE, "1.20.1")));
        assertTrue(DefaultConfigsFromWorld.isSupported(instance(FORGE, "1.14.4")));
        assertTrue(DefaultConfigsFromWorld.isSupported(instance(NEOFORGE, "1.21.1")));
        assertTrue(DefaultConfigsFromWorld.isSupported(instance(NEOFORGE, "26.1")));
    }

    @Test
    public void hiddenForOtherLoadersVersionsAndServers() {
        // loaders without per-world server configs and defaultconfigs
        assertFalse(DefaultConfigsFromWorld.isSupported(instance(FABRIC, "1.20.1")));
        assertFalse(DefaultConfigsFromWorld.isSupported(instance(QUILT, "1.20.1")));
        assertFalse(DefaultConfigsFromWorld.isSupported(instance(null, "1.20.1")));
        // Forge before them
        assertFalse(DefaultConfigsFromWorld.isSupported(instance(FORGE, "1.12.2")));
        assertFalse(DefaultConfigsFromWorld.isSupported(instance(FORGE, "1.13.2")));
        // snapshots and pre-releases can't be relied on
        assertFalse(DefaultConfigsFromWorld.isSupported(instance(FORGE, "24w14a")));
        assertFalse(DefaultConfigsFromWorld.isSupported(instance(NEOFORGE, "1.21-pre1")));
        assertFalse(DefaultConfigsFromWorld.isSupported(null));

        // servers aren't in this first version
        Server server = new Server();
        server.minecraftVersion = "1.20.1";
        server.loaderVersion = FORGE;
        assertFalse(DefaultConfigsFromWorld.isSupported(server));
    }

    // --- worlds and their serverconfig

    @Test
    public void noWorldsSaysSoAndChangesNothing() throws IOException {
        Answers answers = new Answers();
        assertEquals(Outcome.NO_WORLDS, run(answers));

        // a folder in saves that isn't a world doesn't count
        Files.createDirectories(root.resolve("saves/not a world"));
        assertEquals(Outcome.NO_WORLDS, run(answers));
        assertEquals(2, answers.told.size());
        assertNull(answers.worldsOffered);
        assertFalse(Files.exists(root.resolve("defaultconfigs")));
    }

    @Test
    public void everyWorldIsOfferedAlphabeticallyAndNoneIsChosenForTheUser() throws IOException {
        Path newest = world("Zeta");
        world("alpha");
        world("Beta");
        Files.setLastModifiedTime(newest, FileTime.fromMillis(System.currentTimeMillis() + 100000));

        Answers answers = new Answers();
        assertEquals(Outcome.CANCELLED, run(answers));
        assertEquals(Arrays.asList("alpha", "Beta", "Zeta"), answers.worldsOffered.stream()
            .map(world -> world.getFileName().toString()).collect(Collectors.toList()));
        // no world, so no files offered
        assertNull(answers.filesOffered);
        assertTrue(answers.told.isEmpty());
    }

    @Test
    public void aWorldWithNoServerConfigOrAnEmptyOneSaysSo() throws IOException {
        world("Survival");
        Answers answers = new Answers();
        answers.world = "Survival";
        assertEquals(Outcome.NO_SERVER_CONFIG, run(answers));
        assertTrue(answers.told.get(0).contains("Survival"));

        Files.createDirectories(root.resolve("saves/Survival/serverconfig/empty/folder"));
        assertEquals(Outcome.NO_FILES, run(answers));
        assertNull(answers.filesOffered);
        assertFalse(Files.exists(root.resolve("defaultconfigs")));
    }

    @Test
    public void everyRegularFileIsOfferedByRelativePathWhateverMadeIt() throws IOException {
        Path serverConfig = world("Survival").resolve("serverconfig");
        write(serverConfig.resolve("create-server.toml"), "a");
        write(serverConfig.resolve("ftbchunks-world.snbt"), "b");
        write(serverConfig.resolve("sub/deeper/tweaks.json"), "c");
        write(serverConfig.resolve("notes.txt"), "d");
        Files.createDirectories(serverConfig.resolve("empty"));

        List<String> expected = Arrays.asList("create-server.toml", "ftbchunks-world.snbt", "notes.txt",
            "sub/deeper/tweaks.json");
        assertEquals(expected, DefaultConfigsFromWorld.serverConfigFiles(root.resolve("saves/Survival")));

        Answers answers = new Answers();
        answers.world = "Survival";
        assertEquals(Outcome.CANCELLED, run(answers));
        assertEquals(expected, answers.filesOffered);
    }

    // --- choosing and copying

    @Test
    public void onlyTheFilesChosenAreCopiedToExactlyTheSamePaths() throws IOException {
        Path serverConfig = world("Survival").resolve("serverconfig");
        Path create = write(serverConfig.resolve("create-server.toml"), "speed = 2");
        Path tweaks = write(serverConfig.resolve("sub/deeper/tweaks.json"), "{}");
        write(serverConfig.resolve("other-server.toml"), "left out");
        FileTime modified = Files.getLastModifiedTime(create);

        Answers answers = new Answers();
        answers.world = "Survival";
        answers.files = Arrays.asList("sub/deeper/tweaks.json", "create-server.toml");
        assertEquals(Outcome.COPIED, run(answers));

        // shown in the order offered, each exact
        assertEquals(2, answers.copiesShown.size());
        Copy first = answers.copiesShown.get(0);
        assertEquals("create-server.toml", first.relative);
        assertEquals(create, first.source);
        assertEquals(defaults("create-server.toml"), first.destination);
        assertFalse(first.replaces);
        assertEquals(tweaks, answers.copiesShown.get(1).source);
        assertEquals(defaults("sub/deeper/tweaks.json"), answers.copiesShown.get(1).destination);

        assertEquals("speed = 2", read(defaults("create-server.toml")));
        assertEquals("{}", read(defaults("sub/deeper/tweaks.json")));
        assertFalse(Files.exists(defaults("other-server.toml")));

        // the world's own are untouched
        assertEquals("speed = 2", read(create));
        assertEquals(modified, Files.getLastModifiedTime(create));
        assertEquals("{}", read(tweaks));
        assertEquals("left out", read(serverConfig.resolve("other-server.toml")));
    }

    @Test
    public void choosingNoFilesOrCancellingWritesNothing() throws IOException {
        write(world("Survival").resolve("serverconfig/create-server.toml"), "x");
        Answers answers = new Answers();
        answers.world = "Survival";

        answers.files = null;
        assertEquals(Outcome.CANCELLED, run(answers));
        answers.files = Collections.emptyList();
        assertEquals(Outcome.CANCELLED, run(answers));
        answers.files = Collections.singletonList("create-server.toml");
        answers.decision = Decision.CANCEL;
        assertEquals(Outcome.CANCELLED, run(answers));
        answers.decision = null;
        assertEquals(Outcome.CANCELLED, run(answers));

        assertFalse(Files.exists(root.resolve("defaultconfigs")));
    }

    @Test
    public void existingTargetsAreMarkedAndOnlyReplacedWhenThatsChosen() throws IOException {
        Path serverConfig = world("Survival").resolve("serverconfig");
        Path source = write(serverConfig.resolve("create-server.toml"), "new");
        write(serverConfig.resolve("fresh-server.toml"), "fresh");
        Path existing = write(defaults("create-server.toml"), "old");

        Answers answers = new Answers();
        answers.world = "Survival";
        answers.files = Arrays.asList("create-server.toml", "fresh-server.toml");

        answers.decision = Decision.CANCEL;
        assertEquals(Outcome.CANCELLED, run(answers));
        assertTrue(answers.copiesShown.get(0).replaces);
        assertFalse(answers.copiesShown.get(1).replaces);
        assertEquals("old", read(existing));
        assertFalse(Files.exists(defaults("fresh-server.toml")));

        // only the new one
        answers.decision = Decision.COPY_NEW_ONLY;
        assertEquals(Outcome.COPIED, run(answers));
        assertEquals("old", read(existing));
        assertEquals("fresh", read(defaults("fresh-server.toml")));

        // replacing, explicitly
        answers.decision = Decision.COPY_ALL;
        assertEquals(Outcome.COPIED, run(answers));
        assertEquals("new", read(existing));
        assertEquals("new", read(source));
    }

    @Test
    public void onlyNewFilesWhenEveryOneExistsCopiesNothing() throws IOException {
        write(world("Survival").resolve("serverconfig/create-server.toml"), "new");
        Path existing = write(defaults("create-server.toml"), "old");

        Answers answers = new Answers();
        answers.world = "Survival";
        answers.files = Collections.singletonList("create-server.toml");
        answers.decision = Decision.COPY_NEW_ONLY;
        assertEquals(Outcome.CANCELLED, run(answers));
        assertEquals("old", read(existing));
    }

    @Test
    public void aCopyNeverReplacesWithoutBeingToldTo() throws IOException {
        Path source = write(temp.resolve("source.toml"), "new");
        Path target = write(temp.resolve("target.toml"), "old");

        assertThrows(FileAlreadyExistsException.class, () -> DefaultConfigsFromWorld.copy(source, target, false));
        assertEquals("old", read(target));
        assertArrayEquals("new".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(source));
    }

    // --- containment

    @Test
    public void pathsLeadingOutAreRejected() throws IOException {
        Path base = temp.resolve("base");
        for (String path : new String[] { "../x.toml", "a/../../x.toml", "a/../..", "..", "", " ",
            temp.resolve("x.toml").toAbsolutePath().toString() }) {
            assertThrows(IllegalArgumentException.class, () -> DefaultConfigsFromWorld.within(base, path), path);
        }
        assertEquals(base.resolve("a/b.toml"), DefaultConfigsFromWorld.within(base, "a/b.toml"));

        Path world = world("Survival");
        write(world.resolve("serverconfig/create-server.toml"), "x");
        write(world.resolve("level-server.toml"), "outside serverconfig");
        assertThrows(IllegalArgumentException.class,
            () -> DefaultConfigsFromWorld.source(world, "../level-server.toml"));
        assertThrows(IllegalArgumentException.class, () -> DefaultConfigsFromWorld.source(world, "missing.toml"));
        assertThrows(IllegalArgumentException.class,
            () -> DefaultConfigsFromWorld.destination(root, "../options.txt"));
        assertEquals(defaults("create-server.toml"), DefaultConfigsFromWorld.destination(root, "create-server.toml"));
    }

    @Test
    public void aFileThatWasntOfferedIsntCopiedNorAnythingElseChosen() throws IOException {
        write(world("Survival").resolve("serverconfig/create-server.toml"), "x");
        write(root.resolve("options.txt"), "keep");

        Answers answers = new Answers();
        answers.world = "Survival";
        answers.files = Arrays.asList("create-server.toml", "../../../options.txt");
        assertEquals(Outcome.UNSAFE, run(answers));
        assertNull(answers.copiesShown, "never asked to confirm");
        assertEquals("keep", read(root.resolve("options.txt")));
        assertFalse(Files.exists(root.resolve("defaultconfigs")));

        assertThrows(IllegalArgumentException.class, () -> DefaultConfigsFromWorld.plan(root,
            root.resolve("saves/Survival"), Collections.singletonList("create-server.toml"),
            Collections.singletonList("other-server.toml")));
    }

    @Test
    public void aFileLinkingOutOfServerConfigIsLeftOut() throws IOException {
        Path outside = write(temp.resolve("elsewhere/secret-server.toml"), "secret");
        Path world = world("Survival");
        write(world.resolve("serverconfig/create-server.toml"), "create");
        assumeLinks(world.resolve("serverconfig/linked-server.toml"), outside);

        assertEquals(Collections.singletonList("create-server.toml"),
            DefaultConfigsFromWorld.serverConfigFiles(world));
        assertThrows(IllegalArgumentException.class,
            () -> DefaultConfigsFromWorld.source(world, "linked-server.toml"));
    }

    @Test
    public void folderLinksOutOfTheWorldOrServerConfigAreLeftOut() throws IOException {
        Path outside = write(temp.resolve("elsewhere/secret-server.toml"), "secret").getParent();

        // serverconfig itself a link out of the world
        Path world = world("Survival");
        assumeLinks(world.resolve("serverconfig"), outside);
        assertTrue(DefaultConfigsFromWorld.serverConfigFiles(world).isEmpty());
        assertThrows(IllegalArgumentException.class,
            () -> DefaultConfigsFromWorld.source(world, "secret-server.toml"));

        // a folder in serverconfig linking out
        Path other = world("Other");
        write(other.resolve("serverconfig/create-server.toml"), "create");
        assumeLinks(other.resolve("serverconfig/linked"), outside);
        assertEquals(Collections.singletonList("create-server.toml"),
            DefaultConfigsFromWorld.serverConfigFiles(other));
        assertThrows(IllegalArgumentException.class,
            () -> DefaultConfigsFromWorld.source(other, "linked/secret-server.toml"));

        // and nothing was copied from them
        Answers answers = new Answers();
        answers.world = "Survival";
        answers.files = Collections.singletonList("secret-server.toml");
        assertEquals(Outcome.NO_FILES, run(answers));
        answers.world = "Other";
        answers.files = Arrays.asList("create-server.toml", "linked/secret-server.toml");
        assertEquals(Outcome.UNSAFE, run(answers));
        assertFalse(Files.exists(root.resolve("defaultconfigs")));
    }

    @Test
    public void aWorldLinkingOutOfSavesIsntOffered() throws IOException {
        Path outside = Files.createDirectories(temp.resolve("elsewhere/Linked"));
        Files.write(outside.resolve("level.dat"), new byte[] { 1 });
        world("Survival");
        assumeLinks(root.resolve("saves/Linked"), outside);

        assertEquals(Collections.singletonList(root.resolve("saves/Survival")),
            DefaultConfigsFromWorld.worlds(root));
    }

    @Test
    public void aDestinationLinkingOutOfDefaultConfigsIsRejected() throws IOException {
        Path outside = Files.createDirectories(temp.resolve("elsewhere"));
        Path target = write(outside.resolve("create-server.toml"), "keep");

        // defaultconfigs itself a link out of the instance
        assumeLinks(root.resolve("defaultconfigs"), outside);
        assertThrows(IllegalArgumentException.class,
            () -> DefaultConfigsFromWorld.destination(root, "create-server.toml"));

        write(world("Survival").resolve("serverconfig/create-server.toml"), "new");
        Answers answers = new Answers();
        answers.world = "Survival";
        answers.files = Collections.singletonList("create-server.toml");
        assertEquals(Outcome.UNSAFE, run(answers));
        assertNull(answers.copiesShown, "never asked to confirm");
        assertEquals("keep", read(target));
    }

    @Test
    public void aDestinationThatIsALinkIsRejected() throws IOException {
        Path outside = write(temp.resolve("elsewhere/create-server.toml"), "keep");
        Files.createDirectories(root.resolve("defaultconfigs"));
        assumeLinks(defaults("create-server.toml"), outside);

        assertThrows(IllegalArgumentException.class,
            () -> DefaultConfigsFromWorld.destination(root, "create-server.toml"));
        assertEquals("keep", read(outside));
    }
}
