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
import com.atlauncher.utils.DefaultConfigsFromWorld.DefaultConfig;
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

        // replacing and removing
        String replacement;
        String preferred;
        boolean confirmReplace = true;
        Path replaceSource;
        Path replaceTarget;
        boolean confirmRemove = true;
        String removeRelative;
        Path removePath;
        Boolean removeRecycle;
        // done while asked to confirm, as if the file changed then
        Runnable whileConfirming = () -> {
        };

        @Override
        public String chooseReplacement(Path world, List<String> files, String preferred) {
            filesOffered = files;
            this.preferred = preferred;
            return replacement;
        }

        @Override
        public boolean confirmReplace(Path source, Path target) {
            replaceSource = source;
            replaceTarget = target;
            whileConfirming.run();
            return confirmReplace;
        }

        @Override
        public boolean confirmRemove(String relative, Path path, boolean recycle) {
            removeRelative = relative;
            removePath = path;
            removeRecycle = recycle;
            whileConfirming.run();
            return confirmRemove;
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

    // --- the current default configs

    private List<String> listed() throws IOException {
        return DefaultConfigsFromWorld.defaultConfigs(root).stream().map(config -> config.relative)
            .collect(Collectors.toList());
    }

    private DefaultConfig listing(String relative) throws IOException {
        return DefaultConfigsFromWorld.defaultConfigs(root).stream().filter(config -> config.relative.equals(relative))
            .findFirst().orElseThrow(() -> new AssertionError(relative + " isn't listed"));
    }

    @Test
    public void noDefaultConfigsListsNothing() throws IOException {
        assertTrue(listed().isEmpty());
        Files.createDirectories(root.resolve("defaultconfigs/empty/folder"));
        assertTrue(listed().isEmpty());
    }

    @Test
    public void everyDefaultConfigIsListedByItsRelativePath() throws IOException {
        write(defaults("create-server.toml"), "a");
        write(defaults("sub/deeper/tweaks.json"), "b");
        write(defaults("alpha.toml"), "c");

        assertEquals(Arrays.asList("alpha.toml", "create-server.toml", "sub/deeper/tweaks.json"), listed());
        assertEquals("sub/deeper/tweaks.json", listing("sub/deeper/tweaks.json").toString());
    }

    @Test
    public void theListFollowsCopiesReplacementsAndRemovals() throws IOException {
        Path serverConfig = world("Survival").resolve("serverconfig");
        write(serverConfig.resolve("create-server.toml"), "from the world, longer");
        assertTrue(listed().isEmpty());

        Answers answers = new Answers();
        answers.world = "Survival";
        answers.files = Collections.singletonList("create-server.toml");
        assertEquals(Outcome.COPIED, run(answers));
        assertEquals(Collections.singletonList("create-server.toml"), listed());

        // replaced: listed again as what it is now
        Path target = write(defaults("create-server.toml"), "edited");
        DefaultConfig before = listing("create-server.toml");
        answers.replacement = "create-server.toml";
        assertEquals(Outcome.REPLACED, DefaultConfigsFromWorld.replaceFromWorld(root, before, answers));
        assertEquals("from the world, longer", read(target));
        DefaultConfig after = listing("create-server.toml");
        assertEquals(Outcome.STALE, DefaultConfigsFromWorld.remove(root, before, false, answers));
        assertTrue(Files.exists(target));

        assertEquals(Outcome.REMOVED, DefaultConfigsFromWorld.remove(root, after, false, answers));
        assertTrue(listed().isEmpty());
    }

    // --- replacing one

    @Test
    public void theSameRelativePathIsPreferredAndReplacedOnlyWhenConfirmed() throws IOException {
        Path serverConfig = world("Survival").resolve("serverconfig");
        Path source = write(serverConfig.resolve("sub/create-server.toml"), "world");
        write(serverConfig.resolve("other-server.toml"), "other");
        Path target = write(defaults("sub/create-server.toml"), "default");
        FileTime modified = Files.getLastModifiedTime(source);

        Answers answers = new Answers();
        answers.world = "Survival";
        answers.replacement = "sub/create-server.toml";
        answers.confirmReplace = false;
        assertEquals(Outcome.CANCELLED,
            DefaultConfigsFromWorld.replaceFromWorld(root, listing("sub/create-server.toml"), answers));
        assertEquals("sub/create-server.toml", answers.preferred);
        assertEquals(Arrays.asList("other-server.toml", "sub/create-server.toml"), answers.filesOffered);
        assertEquals(source, answers.replaceSource);
        assertEquals(target, answers.replaceTarget);
        assertEquals("default", read(target));

        answers.confirmReplace = true;
        assertEquals(Outcome.REPLACED,
            DefaultConfigsFromWorld.replaceFromWorld(root, listing("sub/create-server.toml"), answers));
        assertEquals("world", read(target));
        assertEquals("world", read(source));
        assertEquals(modified, Files.getLastModifiedTime(source));
    }

    @Test
    public void anotherFileIsOnlyUsedWhenTheUserChoosesItAndGoesToTheSelectedTarget() throws IOException {
        Path serverConfig = world("Survival").resolve("serverconfig");
        write(serverConfig.resolve("renamed-server.toml"), "renamed");
        Path target = write(defaults("create-server.toml"), "default");

        // the world has no create-server.toml: nothing is chosen for the user
        Answers answers = new Answers();
        answers.world = "Survival";
        assertEquals(Outcome.CANCELLED,
            DefaultConfigsFromWorld.replaceFromWorld(root, listing("create-server.toml"), answers));
        assertEquals("create-server.toml", answers.preferred);
        assertNull(answers.replaceTarget, "never asked to confirm");
        assertEquals("default", read(target));

        // chosen explicitly, it replaces the selected target, and nothing else is created
        answers.replacement = "renamed-server.toml";
        assertEquals(Outcome.REPLACED,
            DefaultConfigsFromWorld.replaceFromWorld(root, listing("create-server.toml"), answers));
        assertEquals(target, answers.replaceTarget);
        assertEquals("renamed", read(target));
        assertEquals(Collections.singletonList("create-server.toml"), listed());

        // and only a file that was offered
        answers.replacement = "../level.dat";
        assertEquals(Outcome.CANCELLED,
            DefaultConfigsFromWorld.replaceFromWorld(root, listing("create-server.toml"), answers));
        assertEquals("renamed", read(target));
    }

    @Test
    public void replacingWithNoWorldsOrServerConfigSaysSo() throws IOException {
        write(defaults("create-server.toml"), "default");
        Answers answers = new Answers();
        answers.world = "Survival";
        answers.replacement = "create-server.toml";

        assertEquals(Outcome.NO_WORLDS,
            DefaultConfigsFromWorld.replaceFromWorld(root, listing("create-server.toml"), answers));
        world("Survival");
        assertEquals(Outcome.NO_SERVER_CONFIG,
            DefaultConfigsFromWorld.replaceFromWorld(root, listing("create-server.toml"), answers));
        Files.createDirectories(root.resolve("saves/Survival/serverconfig"));
        assertEquals(Outcome.NO_FILES,
            DefaultConfigsFromWorld.replaceFromWorld(root, listing("create-server.toml"), answers));
        assertEquals("default", read(defaults("create-server.toml")));
    }

    // --- removing one

    @Test
    public void removingShowsExactlyWhatAndRemovesOnlyThatWhenConfirmed() throws IOException {
        Path target = write(defaults("sub/create-server.toml"), "default");
        Path sibling = write(defaults("sub/other-server.toml"), "other");

        Answers answers = new Answers();
        answers.confirmRemove = false;
        assertEquals(Outcome.CANCELLED,
            DefaultConfigsFromWorld.remove(root, listing("sub/create-server.toml"), false, answers));
        assertEquals("sub/create-server.toml", answers.removeRelative);
        assertEquals(target, answers.removePath);
        assertFalse(answers.removeRecycle);
        assertTrue(Files.exists(target));

        answers.confirmRemove = true;
        assertEquals(Outcome.REMOVED,
            DefaultConfigsFromWorld.remove(root, listing("sub/create-server.toml"), false, answers));
        assertFalse(Files.exists(target));
        assertEquals("other", read(sibling));
        assertEquals(Collections.singletonList("sub/other-server.toml"), listed());
    }

    // --- stale selections

    @Test
    public void aDefaultConfigChangedOrGoneSinceListingIsntActedOn() throws IOException {
        write(world("Survival").resolve("serverconfig/create-server.toml"), "world");
        Path target = write(defaults("create-server.toml"), "default");
        DefaultConfig listed = listing("create-server.toml");
        Answers answers = new Answers();
        answers.world = "Survival";
        answers.replacement = "create-server.toml";

        // changed, even at the same size
        write(target, "changed");
        Files.setLastModifiedTime(target, FileTime.fromMillis(listed.modified.toMillis() + 5000));
        assertEquals(Outcome.STALE, DefaultConfigsFromWorld.replaceFromWorld(root, listed, answers));
        assertEquals(Outcome.STALE, DefaultConfigsFromWorld.remove(root, listed, false, answers));
        assertThrows(IllegalStateException.class, () -> DefaultConfigsFromWorld.current(root, listed));
        assertEquals("changed", read(target));
        assertNull(answers.replaceTarget, "never asked to confirm");
        assertNull(answers.removePath, "never asked to confirm");

        // gone
        DefaultConfig relisted = listing("create-server.toml");
        Files.delete(target);
        assertEquals(Outcome.STALE, DefaultConfigsFromWorld.replaceFromWorld(root, relisted, answers));
        assertEquals(Outcome.STALE, DefaultConfigsFromWorld.remove(root, relisted, false, answers));
        assertFalse(Files.exists(target));
    }

    @Test
    public void aDefaultConfigChangedWhileConfirmingIsntOverwrittenOrRemoved() throws IOException {
        write(world("Survival").resolve("serverconfig/create-server.toml"), "world");
        Path target = write(defaults("create-server.toml"), "default");
        Answers answers = new Answers();
        answers.world = "Survival";
        answers.replacement = "create-server.toml";
        answers.whileConfirming = () -> {
            try {
                write(target, "edited meanwhile");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        };

        assertEquals(Outcome.STALE,
            DefaultConfigsFromWorld.replaceFromWorld(root, listing("create-server.toml"), answers));
        assertEquals("edited meanwhile", read(target));

        write(target, "default");
        assertEquals(Outcome.STALE, DefaultConfigsFromWorld.remove(root, listing("create-server.toml"), false,
            answers));
        assertEquals("edited meanwhile", read(target));
    }

    // --- escapes

    @Test
    public void aDefaultConfigPathLeadingOutIsNeverActedOn() throws IOException {
        Path outside = write(root.resolve("options.txt"), "keep");
        DefaultConfig escaping = new DefaultConfig("../options.txt", Files.size(outside),
            Files.getLastModifiedTime(outside));

        Answers answers = new Answers();
        assertEquals(Outcome.UNSAFE, DefaultConfigsFromWorld.remove(root, escaping, false, answers));
        assertEquals(Outcome.UNSAFE, DefaultConfigsFromWorld.replaceFromWorld(root, escaping, answers));
        assertThrows(IllegalArgumentException.class, () -> DefaultConfigsFromWorld.current(root, escaping));
        assertNull(answers.removePath);
        assertEquals("keep", read(outside));
    }

    @Test
    public void aFileLinkInDefaultConfigsIsntListedOrActedOn() throws IOException {
        Path outside = write(temp.resolve("elsewhere/secret-server.toml"), "secret");
        write(defaults("create-server.toml"), "a");
        assumeLinks(defaults("linked-server.toml"), outside);

        assertEquals(Collections.singletonList("create-server.toml"), listed());
        DefaultConfig linked = new DefaultConfig("linked-server.toml", Files.size(outside),
            Files.getLastModifiedTime(outside));
        assertEquals(Outcome.UNSAFE, DefaultConfigsFromWorld.remove(root, linked, false, new Answers()));
        assertEquals("secret", read(outside));
    }

    @Test
    public void aFolderLinkInDefaultConfigsLeadingOutIsntListedOrActedOn() throws IOException {
        Path outside = write(temp.resolve("elsewhere/secret-server.toml"), "secret");
        write(defaults("create-server.toml"), "a");
        assumeLinks(defaults("linked"), outside.getParent());

        assertEquals(Collections.singletonList("create-server.toml"), listed());
        DefaultConfig linked = new DefaultConfig("linked/secret-server.toml", Files.size(outside),
            Files.getLastModifiedTime(outside));
        assertEquals(Outcome.UNSAFE, DefaultConfigsFromWorld.remove(root, linked, false, new Answers()));
        assertEquals(Outcome.UNSAFE, DefaultConfigsFromWorld.replaceFromWorld(root, linked, new Answers()));
        assertEquals("secret", read(outside));
    }

    @Test
    public void defaultConfigsLeadingOutOfTheInstanceIsntListed() throws IOException {
        Path outside = Files.createDirectories(temp.resolve("elsewhere"));
        write(outside.resolve("secret-server.toml"), "secret");
        assumeLinks(root.resolve("defaultconfigs"), outside);

        assertThrows(IllegalArgumentException.class, () -> DefaultConfigsFromWorld.defaultConfigs(root));
        assertEquals("secret", read(outside.resolve("secret-server.toml")));
    }

    // --- readme.txt files

    @Test
    public void readmeFilesAreNeverOfferedInAnyCaseOrFolder() throws IOException {
        Path serverConfig = world("Survival").resolve("serverconfig");
        write(serverConfig.resolve("readme.txt"), "notes");
        write(serverConfig.resolve("sub/README.TXT"), "notes");
        write(serverConfig.resolve("sub/deeper/ReadMe.Txt"), "notes");
        write(serverConfig.resolve("create-server.toml"), "a");
        write(serverConfig.resolve("notes.txt"), "b");
        write(serverConfig.resolve("sub/readme.txt.bak"), "c");
        write(serverConfig.resolve("sub/my-readme.txt"), "d");

        assertEquals(Arrays.asList("create-server.toml", "notes.txt", "sub/my-readme.txt", "sub/readme.txt.bak"),
            DefaultConfigsFromWorld.serverConfigFiles(root.resolve("saves/Survival")));

        // left as they are
        assertEquals("notes", read(serverConfig.resolve("readme.txt")));
        assertEquals("notes", read(serverConfig.resolve("sub/README.TXT")));
        assertEquals("notes", read(serverConfig.resolve("sub/deeper/ReadMe.Txt")));
    }

    @Test
    public void readmeFilesArentOfferedToCopyOrReplaceWith() throws IOException {
        Path serverConfig = world("Survival").resolve("serverconfig");
        write(serverConfig.resolve("README.txt"), "notes");
        write(serverConfig.resolve("create-server.toml"), "a");
        write(defaults("create-server.toml"), "default");

        Answers answers = new Answers();
        answers.world = "Survival";
        answers.files = Collections.singletonList("README.txt");
        assertEquals(Outcome.UNSAFE, run(answers));
        assertEquals(Collections.singletonList("create-server.toml"), answers.filesOffered);
        assertFalse(Files.exists(defaults("README.txt")));

        answers.replacement = "README.txt";
        assertEquals(Outcome.CANCELLED,
            DefaultConfigsFromWorld.replaceFromWorld(root, listing("create-server.toml"), answers));
        assertEquals(Collections.singletonList("create-server.toml"), answers.filesOffered);
        assertEquals("default", read(defaults("create-server.toml")));
    }

    @Test
    public void aServerConfigWithOnlyReadmeFilesHasNothingToOffer() throws IOException {
        Path serverConfig = world("Survival").resolve("serverconfig");
        write(serverConfig.resolve("readme.txt"), "notes");
        write(serverConfig.resolve("sub/README.TXT"), "notes");
        write(defaults("create-server.toml"), "default");

        Answers answers = new Answers();
        answers.world = "Survival";
        answers.files = Collections.singletonList("readme.txt");
        assertEquals(Outcome.NO_FILES, run(answers));
        assertNull(answers.filesOffered, "no empty checklist");
        assertTrue(answers.told.get(0).contains("readme.txt"));

        answers.replacement = "readme.txt";
        assertEquals(Outcome.NO_FILES,
            DefaultConfigsFromWorld.replaceFromWorld(root, listing("create-server.toml"), answers));
        assertNull(answers.filesOffered);
        assertEquals("notes", read(serverConfig.resolve("readme.txt")));
        assertFalse(Files.exists(defaults("readme.txt")));
    }

    @Test
    public void readmeFilesAlreadyInDefaultConfigsAreStillListed() throws IOException {
        write(defaults("readme.txt"), "notes");
        write(defaults("sub/README.TXT"), "notes");

        assertEquals(Arrays.asList("readme.txt", "sub/README.TXT"), listed());
    }
}
