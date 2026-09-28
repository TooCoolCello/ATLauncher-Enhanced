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

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.data.Instance;
import com.atlauncher.data.ModManagement;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.managers.LogManager;

/**
 * Copies server configs from one of an instance's worlds into the instance's defaultconfigs folder. Forge and NeoForge
 * keep server configs per world, in its serverconfig folder, and fill that from defaultconfigs when a NEW world is
 * created. So the copies only affect worlds created afterwards: existing worlds keep their own, and the world they're
 * copied from is only read.
 *
 * <p>
 * The user chooses the world and exactly which of its files to copy; nothing is matched or filtered by mod. The
 * files already in defaultconfigs can be listed, and one at a time replaced from a world or removed.
 */
public final class DefaultConfigsFromWorld {
    static final String SAVES = "saves";
    static final String SERVER_CONFIG = "serverconfig";
    static final String DEFAULT_CONFIGS = "defaultconfigs";
    // how deep in serverconfig to look for configs
    private static final int MAX_DEPTH = 8;
    // a release, such as 1.20.1 or 26.1: not a snapshot or pre-release
    private static final Pattern RELEASE = Pattern.compile("\\d+\\.\\d+(\\.\\d+)?");

    public enum Outcome {
        NO_WORLDS, NO_SERVER_CONFIG, NO_FILES, CANCELLED, UNSAFE, FAILED, COPIED, REPLACED, REMOVED,
        // the default config changed or went since it was listed, so nothing was done
        STALE
    }

    /**
     * What to do with the copies shown: all of them, replacing what's there, only those that replace nothing, or
     * none.
     */
    public enum Decision {
        COPY_ALL, COPY_NEW_ONLY, CANCEL
    }

    /**
     * The user's part. Each choice can be declined, by returning null or {@link Decision#CANCEL}, which copies nothing.
     */
    public interface Choices {
        // one of these worlds, or null
        Path chooseWorld(List<Path> worlds);

        // some of these files in the world's serverconfig, relative to it, or null or none
        List<String> chooseFiles(Path world, List<String> files);

        // every copy, each saying whether it replaces an existing file
        Decision confirm(List<Copy> copies);

        void tell(String message);

        // for replacing a default config: one of these files, preferring the one at the same path, or null
        String chooseReplacement(Path world, List<String> files, String preferred);

        // replacing the default config at target with source
        boolean confirmReplace(Path source, Path target);

        // removing the default config at path; recycle is whether it goes to the recycle bin/trash where there's one
        boolean confirmRemove(String relative, Path path, boolean recycle);
    }

    /**
     * A file in defaultconfigs as it was listed. It's only acted on while it's still that file: the same regular file,
     * the same size, modified at the same time.
     */
    public static final class DefaultConfig {
        // relative to defaultconfigs, with / separators
        public final String relative;
        final long size;
        final FileTime modified;

        DefaultConfig(String relative, long size, FileTime modified) {
            this.relative = relative;
            this.size = size;
            this.modified = modified;
        }

        @Override
        public String toString() {
            return relative;
        }
    }

    /**
     * One file to copy, with exactly where it's read from and written to.
     */
    public static final class Copy {
        // the same in serverconfig and defaultconfigs, with / separators
        public final String relative;
        public final Path source;
        public final Path destination;
        // there's already a file there, which the copy would replace
        public final boolean replaces;

        Copy(String relative, Path source, Path destination, boolean replaces) {
            this.relative = relative;
            this.source = source;
            this.destination = destination;
            this.replaces = replaces;
        }
    }

    private DefaultConfigsFromWorld() {
    }

    /**
     * Whether to offer it: a local instance whose loader keeps server configs per world and fills new worlds' from
     * defaultconfigs.
     */
    public static boolean isSupported(ModManagement target) {
        return target instanceof Instance
            && supportsServerConfigs(target.getLoaderVersion(), target.getMinecraftVersion());
    }

    /**
     * Forge and NeoForge for Minecraft releases from 1.14, including year-numbered ones. Not Fabric, Quilt, older
     * Forge, snapshots or pre-releases.
     */
    static boolean supportsServerConfigs(LoaderVersion loader, String minecraftVersion) {
        return loader != null && (loader.isForge() || loader.isNeoForge()) && minecraftVersion != null
            && RELEASE.matcher(minecraftVersion).matches()
            && Utils.matchVersion(minecraftVersion, "1.14", false, true);
    }

    /**
     * The instance's worlds, by folder name: those in its saves folder with a level.dat, really inside it.
     */
    public static List<Path> worlds(Path instanceRoot) throws IOException {
        Path saves = instanceRoot.resolve(SAVES);
        if (!Files.isDirectory(saves)) {
            return Collections.emptyList();
        }

        Path realSaves = saves.toRealPath();
        try (Stream<Path> children = Files.list(saves)) {
            return children.filter(world -> Files.isRegularFile(world.resolve("level.dat")))
                .filter(world -> isInside(world, realSaves))
                .sorted(Comparator.comparing(world -> world.getFileName().toString().toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
    }

    /**
     * The regular files in a world's serverconfig folder that can be copied, including those in folders inside it,
     * relative to it with / separators, sorted. Links out of it are left out, linked folders aren't followed, and
     * readme.txt files, in any case, aren't offered: they're notes, not configs.
     */
    public static List<String> serverConfigFiles(Path world) throws IOException {
        Path serverConfig = world.resolve(SERVER_CONFIG);
        if (!Files.isDirectory(serverConfig) || !isInside(serverConfig, world.toRealPath())) {
            return Collections.emptyList();
        }

        Path realServerConfig = serverConfig.toRealPath();
        try (Stream<Path> files = Files.walk(serverConfig, MAX_DEPTH)) {
            return files.filter(Files::isRegularFile).filter(file -> !isReadme(file))
                .filter(file -> isInside(file, realServerConfig))
                .map(file -> serverConfig.relativize(file).toString().replace('\\', '/')).sorted()
                .collect(Collectors.toList());
        }
    }

    static boolean isReadme(Path file) {
        return file.getFileName().toString().equalsIgnoreCase("readme.txt");
    }

    /**
     * A config file in a world's serverconfig folder, which must really be inside it, as serverconfig must be inside
     * the world.
     *
     * @throws IllegalArgumentException when it isn't
     */
    public static Path source(Path world, String relative) throws IOException {
        Path serverConfig = world.resolve(SERVER_CONFIG);
        Path source = within(serverConfig, relative);
        Path realServerConfig = serverConfig.toRealPath();

        if (!realServerConfig.startsWith(world.toRealPath()) || !Files.isRegularFile(source)
            || !source.toRealPath().startsWith(realServerConfig)) {
            throw new IllegalArgumentException(relative + " isn't a file inside " + serverConfig);
        }
        return source;
    }

    /**
     * Where a config goes in the instance's defaultconfigs folder: at the same path it had in serverconfig, as Forge
     * and NeoForge look for it there. Neither it nor anything on the way to it may lead out of defaultconfigs, which
     * may not lead out of the instance.
     *
     * @throws IllegalArgumentException when it would
     */
    public static Path destination(Path instanceRoot, String relative) throws IOException {
        Path defaultConfigs = instanceRoot.resolve(DEFAULT_CONFIGS);
        Path destination = within(defaultConfigs, relative);
        Path realRoot = instanceRoot.toRealPath();

        if (Files.isSymbolicLink(destination)) {
            throw new IllegalArgumentException(destination + " is a link");
        }

        // what exists of the way there
        Path existing = destination;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }

        Path allowed = Files.exists(defaultConfigs) ? defaultConfigs.toRealPath() : realRoot;
        if (!allowed.startsWith(realRoot) || !existing.toRealPath().startsWith(allowed)) {
            throw new IllegalArgumentException(destination + " isn't inside " + defaultConfigs);
        }
        return destination;
    }

    // a relative path strictly inside base, with no way out of it
    static Path within(Path base, String relative) {
        if (relative == null || relative.trim().isEmpty()) {
            throw new IllegalArgumentException("no file");
        }

        Path path = base.getFileSystem().getPath(relative);
        if (path.isAbsolute() || path.getRoot() != null) {
            throw new IllegalArgumentException(relative + " isn't relative");
        }
        for (Path name : path) {
            if (name.toString().equals("..")) {
                throw new IllegalArgumentException(relative + " leads out");
            }
        }

        Path normalBase = base.normalize();
        Path resolved = normalBase.resolve(path).normalize();
        if (!resolved.startsWith(normalBase) || resolved.equals(normalBase)) {
            throw new IllegalArgumentException(relative + " isn't inside " + base);
        }
        return resolved;
    }

    private static boolean isInside(Path path, Path realParent) {
        try {
            Path real = path.toRealPath();
            return real.startsWith(realParent) && !real.equals(realParent);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The copies for the files chosen, in the order offered, each checked to be safely inside the world's serverconfig
     * and the instance's defaultconfigs.
     *
     * @param offered the files the user was offered, from {@link #serverConfigFiles}
     * @throws IllegalArgumentException when a file chosen wasn't offered, or isn't safe to copy
     */
    public static List<Copy> plan(Path instanceRoot, Path world, List<String> offered, List<String> chosen)
        throws IOException {
        for (String relative : chosen) {
            if (!offered.contains(relative)) {
                throw new IllegalArgumentException(relative + " wasn't offered");
            }
        }

        List<Copy> copies = new ArrayList<>();
        for (String relative : offered) {
            if (chosen.contains(relative)) {
                Path destination = destination(instanceRoot, relative);
                copies.add(new Copy(relative, source(world, relative), destination,
                    Files.exists(destination, LinkOption.NOFOLLOW_LINKS)));
            }
        }
        return copies;
    }

    /**
     * The regular files in the instance's defaultconfigs folder, including those in folders inside it, by their path
     * relative to it. Links aren't listed, and nothing a folder link leads to outside it is.
     *
     * @throws IllegalArgumentException when defaultconfigs itself leads out of the instance
     */
    public static List<DefaultConfig> defaultConfigs(Path instanceRoot) throws IOException {
        Path defaultConfigs = instanceRoot.resolve(DEFAULT_CONFIGS);
        if (!Files.isDirectory(defaultConfigs)) {
            return Collections.emptyList();
        }
        if (Files.isSymbolicLink(defaultConfigs) || !isInside(defaultConfigs, instanceRoot.toRealPath())) {
            throw new IllegalArgumentException(defaultConfigs + " isn't inside the instance");
        }

        Path realDefaultConfigs = defaultConfigs.toRealPath();
        List<DefaultConfig> configs = new ArrayList<>();
        try (Stream<Path> files = Files.walk(defaultConfigs, MAX_DEPTH)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && isInside(file, realDefaultConfigs)) {
                    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                    configs.add(new DefaultConfig(defaultConfigs.relativize(file).toString().replace('\\', '/'),
                        attributes.size(), attributes.lastModifiedTime()));
                }
            }
        }
        configs.sort(Comparator.comparing(config -> config.relative));
        return configs;
    }

    /**
     * Where a listed default config is, while it's still exactly what was listed and safely inside defaultconfigs.
     *
     * @throws IllegalStateException when it's gone or changed
     * @throws IllegalArgumentException when it isn't safely inside defaultconfigs
     */
    public static Path current(Path instanceRoot, DefaultConfig listed) throws IOException {
        Path path = destination(instanceRoot, listed.relative);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(listed.relative + " is gone");
        }

        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        if (attributes.size() != listed.size || !attributes.lastModifiedTime().equals(listed.modified)) {
            throw new IllegalStateException(listed.relative + " changed");
        }
        return path;
    }

    /**
     * Copies the source, which is only read, to the destination. An existing destination is only replaced when
     * replace is true; otherwise it fails with {@link FileAlreadyExistsException}.
     */
    static void copy(Path source, Path destination, boolean replace) throws IOException {
        Files.createDirectories(destination.getParent());

        if (replace) {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.copy(source, destination);
        }
    }

    /**
     * Replaces one listed default config with a file chosen from a world's serverconfig: the file at the same path
     * when the user keeps it chosen, or any other the user chooses instead. Nothing is written unless the user
     * confirms that exact source and target, and it's still the file that was listed.
     */
    public static Outcome replaceFromWorld(Path instanceRoot, DefaultConfig target, Choices choices) {
        try {
            current(instanceRoot, target);

            List<Path> worlds = worlds(instanceRoot);
            if (worlds.isEmpty()) {
                choices.tell(GetText.tr("This instance has no worlds yet, so there's no config to replace it with. "
                    + "Nothing was changed."));
                return Outcome.NO_WORLDS;
            }

            Path world = choices.chooseWorld(worlds);
            if (world == null || !worlds.contains(world)) {
                return Outcome.CANCELLED;
            }

            String worldName = world.getFileName().toString();
            if (!Files.isDirectory(world.resolve(SERVER_CONFIG))) {
                choices.tell(GetText.tr("The world {0} has no serverconfig folder, so there's no config to replace "
                    + "it with. Nothing was changed.", worldName));
                return Outcome.NO_SERVER_CONFIG;
            }

            List<String> files = serverConfigFiles(world);
            if (files.isEmpty()) {
                choices.tell(GetText.tr("The serverconfig folder of {0} has no config files to copy (readme.txt "
                    + "files aren't offered). Nothing was changed.", worldName));
                return Outcome.NO_FILES;
            }

            String chosen = choices.chooseReplacement(world, files, target.relative);
            if (chosen == null || !files.contains(chosen)) {
                return Outcome.CANCELLED;
            }

            Path source = source(world, chosen);
            Path destination = current(instanceRoot, target);
            if (!choices.confirmReplace(source, destination)) {
                return Outcome.CANCELLED;
            }

            // still the file that was confirmed
            current(instanceRoot, target);
            copy(source, destination, true);
            choices.tell(GetText.tr("Replaced {0} in defaultconfigs. New worlds created in this instance will start "
                + "with it; existing worlds keep their own.", target.relative));
            return Outcome.REPLACED;
        } catch (IllegalStateException e) {
            return stale(choices, target, e);
        } catch (IllegalArgumentException e) {
            return unsafe(choices, e);
        } catch (IOException e) {
            LogManager.logStackTrace("Failed to replace a default config", e);
            choices.tell(GetText.tr("{0} couldn't be replaced: {1}.", target.relative, e.toString()));
            return Outcome.FAILED;
        }
    }

    /**
     * Removes one listed default config, and nothing else, once the user confirms it and while it's still the file
     * that was listed.
     *
     * @param recycle whether to move it to the recycle bin/trash where the system has one, as the launcher's setting
     *                says; otherwise, or when there's none, it's deleted permanently
     */
    public static Outcome remove(Path instanceRoot, DefaultConfig target, boolean recycle, Choices choices) {
        try {
            Path path = current(instanceRoot, target);
            if (!choices.confirmRemove(target.relative, path, recycle)) {
                return Outcome.CANCELLED;
            }

            path = current(instanceRoot, target);
            if (!FileUtils.delete(path, recycle)) {
                throw new IOException(path + " couldn't be removed");
            }
            return Outcome.REMOVED;
        } catch (IllegalStateException e) {
            return stale(choices, target, e);
        } catch (IllegalArgumentException e) {
            return unsafe(choices, e);
        } catch (IOException e) {
            LogManager.logStackTrace("Failed to remove a default config", e);
            choices.tell(GetText.tr("{0} couldn't be removed: {1}.", target.relative, e.toString()));
            return Outcome.FAILED;
        }
    }

    private static Outcome stale(Choices choices, DefaultConfig target, IllegalStateException e) {
        LogManager.warn("Not acting on a default config: " + e.getMessage());
        choices.tell(GetText.tr("{0} was changed or removed since it was listed, so nothing was done. The list will "
            + "be refreshed.", target.relative));
        return Outcome.STALE;
    }

    private static Outcome unsafe(Choices choices, IllegalArgumentException e) {
        LogManager.warn("Not acting on a default config: " + e.getMessage());
        choices.tell(GetText.tr("That file isn't safely inside the world's serverconfig folder or the instance's "
            + "defaultconfigs folder, so nothing was done."));
        return Outcome.UNSAFE;
    }

    /**
     * The whole of it: choose a world, then which of its server configs to copy, confirm every exact copy, and copy
     * only those. Nothing is written unless the user confirms, and nothing that exists is replaced unless the user
     * says to replace it.
     */
    public static Outcome run(Path instanceRoot, Choices choices) {
        List<Copy> copied = new ArrayList<>();
        try {
            List<Path> worlds = worlds(instanceRoot);
            if (worlds.isEmpty()) {
                choices.tell(GetText.tr("This instance has no worlds yet, so there are no configs to copy. Nothing "
                    + "was changed."));
                return Outcome.NO_WORLDS;
            }

            Path world = choices.chooseWorld(worlds);
            if (world == null || !worlds.contains(world)) {
                return Outcome.CANCELLED;
            }

            String worldName = world.getFileName().toString();
            if (!Files.isDirectory(world.resolve(SERVER_CONFIG))) {
                choices.tell(GetText.tr("The world {0} has no serverconfig folder, so there are no configs to copy. "
                    + "Nothing was changed.", worldName));
                return Outcome.NO_SERVER_CONFIG;
            }

            List<String> files = serverConfigFiles(world);
            if (files.isEmpty()) {
                choices.tell(GetText.tr("The serverconfig folder of {0} has no config files to copy (readme.txt "
                    + "files aren't offered). Nothing was changed.", worldName));
                return Outcome.NO_FILES;
            }

            List<String> chosen = choices.chooseFiles(world, files);
            if (chosen == null || chosen.isEmpty()) {
                return Outcome.CANCELLED;
            }

            List<Copy> copies = plan(instanceRoot, world, files, chosen);
            Decision decision = choices.confirm(copies);
            if (decision == null || decision == Decision.CANCEL) {
                return Outcome.CANCELLED;
            }

            List<Copy> toCopy = decision == Decision.COPY_ALL ? copies
                : copies.stream().filter(copy -> !copy.replaces).collect(Collectors.toList());
            if (toCopy.isEmpty()) {
                choices.tell(GetText.tr("Every file chosen is already in defaultconfigs, and none was to be "
                    + "replaced. Nothing was changed."));
                return Outcome.CANCELLED;
            }

            for (Copy copy : toCopy) {
                copy(copy.source, copy.destination, copy.replaces);
                copied.add(copy);
            }

            choices.tell(GetText.tr("Copied {0} file(s) to {1}. Worlds created in this instance from now on will "
                + "start with them; existing worlds keep their own.", copied.size(),
                instanceRoot.resolve(DEFAULT_CONFIGS).toString()));
            return Outcome.COPIED;
        } catch (IllegalArgumentException e) {
            LogManager.warn("Not copying world configs: " + e.getMessage());
            choices.tell(GetText.tr("A file chosen isn't safely inside the world's serverconfig folder, or its "
                + "destination isn't inside the instance's defaultconfigs folder, so nothing was copied."));
            return Outcome.UNSAFE;
        } catch (IOException e) {
            LogManager.logStackTrace("Failed to copy world configs to defaultconfigs", e);
            String done = copied.stream().map(copy -> copy.relative).collect(Collectors.joining(", "));
            choices.tell(copied.isEmpty()
                ? GetText.tr("The configs couldn't be copied: {0}. Nothing was changed.", e.toString())
                : GetText.tr("Copying stopped: {0}. Only these were copied: {1}.", e.toString(), done));
            return Outcome.FAILED;
        }
    }
}
