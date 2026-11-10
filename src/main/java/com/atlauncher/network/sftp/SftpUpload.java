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
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.mini2Dx.gettext.GetText;

/**
 * Uploads files from an instance to the same relative paths below a remote server's folder. Each file is written to a
 * temporary name first and only moved into place once complete, so a cancelled or failed upload never leaves a
 * half-written file where the server would load it. A file it replaces is moved aside first and put back if the new one
 * can't go in; once the new one is in place, the old one is either removed or kept in a dated backup folder on the
 * server.
 */
public final class SftpUpload {
    // added to a file's name while it's being uploaded
    static final String PARTIAL_SUFFIX = ".atlauncher-part";
    // added to the name of a file being replaced without a backup, until its replacement is in place
    static final String OLD_SUFFIX = ".atlauncher-old";
    // below the server's folder, outside mods and config, so nothing loads the backups
    static final String BACKUPS_FOLDER = "atlauncher-backups";

    public enum Existing {
        BACK_UP_AND_REPLACE, REPLACE, SKIP
    }

    public static final class Item {
        public final Path local;
        // the path below the instance and the server's folder, with forward slashes
        public final String relative;
        public final String remote;
        public final long size;
        // whether something is already at the remote path
        public boolean exists = false;

        Item(Path local, String relative, String remote, long size) {
            this.local = local;
            this.relative = relative;
            this.remote = remote;
            this.size = size;
        }

        @Override
        public String toString() {
            return relative;
        }
    }

    public static final class Result {
        public int uploaded = 0;
        public int replaced = 0;
        public int skipped = 0;
        public long bytes = 0;
        // where replaced files were moved; null if none were
        public String backupFolder = null;
        public boolean cancelled = false;
        // what stopped the upload part way, if anything
        public IOException error = null;
    }

    private SftpUpload() {
    }

    /**
     * Every file in the selection, with folders expanded, each once and sorted by path.
     *
     * @param selection files and folders inside the root
     */
    public static List<Item> plan(Path root, Collection<Path> selection, String remoteBase) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        TreeMap<String, Path> files = new TreeMap<>();
        for (Path selected : selection) {
            Path path = selected.toAbsolutePath().normalize();
            if (!path.startsWith(base) || path.equals(base)) {
                throw new IOException(GetText.tr("{0} isn't inside the instance folder.", selected));
            }
            List<Path> found;
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    found = walk.filter(Files::isRegularFile).collect(Collectors.toList());
                }
            } else if (Files.isRegularFile(path)) {
                found = Collections.singletonList(path);
            } else {
                throw new NoSuchFileException(selected.toString());
            }
            for (Path file : found) {
                files.put(base.relativize(file).toString().replace('\\', '/'), file);
            }
        }

        List<Item> items = new ArrayList<>();
        for (Map.Entry<String, Path> file : files.entrySet()) {
            items.add(new Item(file.getValue(), file.getKey(), SftpClient.join(remoteBase, file.getKey()),
                Files.size(file.getValue())));
        }
        return items;
    }

    /**
     * Marks the items already on the server, listing each remote folder once rather than checking every file.
     */
    public static void findExisting(SftpClient client, List<Item> items) throws IOException {
        Map<String, Set<String>> folders = new HashMap<>();
        for (Item item : items) {
            String folder = SftpClient.parent(item.remote);
            Set<String> names = folders.get(folder);
            if (names == null) {
                names = new HashSet<>();
                try {
                    for (SftpClient.RemoteFile file : client.list(folder.isEmpty() ? "." : folder)) {
                        names.add(file.name);
                    }
                } catch (NoSuchFileException e) {
                    // a folder that isn't there yet has nothing in it to replace
                }
                folders.put(folder, names);
            }
            item.exists = names.contains(item.remote.substring(item.remote.lastIndexOf('/') + 1));
        }
    }

    /**
     * A new backup folder's path, named for when the upload started.
     */
    public static String backupFolder(String remoteBase, LocalDateTime now) {
        return SftpClient.join(remoteBase,
            BACKUPS_FOLDER + "/" + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")));
    }

    /**
     * Uploads the items in order. Stops at the first error, and returns what was done before it.
     *
     * @param progress told the item being uploaded and the bytes uploaded so far in total; returns false to cancel
     */
    public static Result run(SftpClient client, List<Item> items, Existing existing, String backupFolder,
            BiPredicate<Item, Long> progress) {
        Result result = new Result();
        Set<String> folders = new HashSet<>();
        long[] done = { 0 };

        for (Item item : items) {
            if (item.exists && existing == Existing.SKIP) {
                result.skipped++;
                continue;
            }
            if (!progress.test(item, done[0])) {
                result.cancelled = true;
                break;
            }

            String partial = item.remote + PARTIAL_SUFFIX;
            // where the server's old copy was moved, until the new one is in place
            String movedTo = null;
            try {
                makeFolder(client, folders, SftpClient.parent(item.remote));
                client.upload(item.local, partial, bytes -> {
                    done[0] += bytes;
                    return progress.test(item, done[0]);
                });

                if (item.exists) {
                    // the old copy is only moved aside, never deleted, until the new one is in place
                    String aside = item.remote + OLD_SUFFIX;
                    if (existing == Existing.BACK_UP_AND_REPLACE) {
                        aside = SftpClient.join(backupFolder, item.relative);
                        makeFolder(client, folders, SftpClient.parent(aside));
                    }
                    client.rename(item.remote, aside);
                    movedTo = aside;
                    if (existing == Existing.BACK_UP_AND_REPLACE) {
                        result.backupFolder = backupFolder;
                    }
                    result.replaced++;
                }
                client.rename(partial, item.remote);
                result.uploaded++;
                result.bytes += item.size;
                if (movedTo != null && existing == Existing.REPLACE) {
                    // ponytail: a copy that won't delete stays as clutter next to the new file; nothing is lost
                    removeQuietly(client, movedTo);
                }
            } catch (IOException e) {
                boolean restored = true;
                if (movedTo != null) {
                    // the new file didn't go in, so put the old one back
                    try {
                        client.rename(movedTo, item.remote);
                    } catch (IOException notRestored) {
                        // the old copy is still at movedTo; keep the upload too, so neither is lost
                        restored = false;
                    }
                }
                if (restored) {
                    removeQuietly(client, partial);
                }
                if (e instanceof InterruptedIOException) {
                    result.cancelled = true;
                } else {
                    result.error = e;
                }
                break;
            }
        }
        return result;
    }

    private static void makeFolder(SftpClient client, Set<String> made, String folder) throws IOException {
        if (made.add(folder)) {
            client.mkdirs(folder);
        }
    }

    private static void removeQuietly(SftpClient client, String path) {
        try {
            if (client.stat(path) != null) {
                client.remove(path);
            }
        } catch (IOException ignored) {
            // the partial file is only clutter; the error that caused this is what matters
        }
    }
}
