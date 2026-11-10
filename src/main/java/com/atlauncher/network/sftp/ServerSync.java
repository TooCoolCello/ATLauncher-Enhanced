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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.ModSide;
import com.atlauncher.utils.Hashing;

/**
 * Compares an instance with a remote server and makes the server match it: the server-relevant mods, configs,
 * defaultconfigs and KubeJS server and startup scripts and data. Client-only mods, disabled mods, client configs and
 * KubeJS client scripts and assets are left out.
 *
 * <p>
 * Planning only reads the server. Applying uploads through {@link SftpUpload} (temporary names) and moves every file it
 * replaces or removes into one dated backup folder, so nothing is deleted.
 */
public final class ServerSync {
    // compared file by file, besides mods
    static final List<String> FOLDERS = Collections.unmodifiableList(Arrays.asList("config", "defaultconfigs",
        "kubejs/server_scripts", "kubejs/startup_scripts", "kubejs/data"));

    // words that differ between builds of the same mod rather than between mods
    private static final Set<String> BUILD_WORDS = new HashSet<>(Arrays.asList("alpha", "beta", "rc", "pre",
        "release", "snapshot", "build", "universal", "all", "mc", "forge", "neoforge", "fabric", "quilt"));

    public enum Kind {
        ADD, UPDATE, REMOVE, UNCHANGED
    }

    public static final class Change {
        public final Kind kind;
        // the path below the instance and the server's folder; for REMOVE, the server's file
        public final String relative;
        // the instance's file; null for REMOVE
        public final Path local;
        // a server file with another name that this replaces, such as an older jar of the same mod
        public final String replaces;
        public final long size;
        // why, for the preview
        public final String detail;
        public boolean selected;

        Change(Kind kind, String relative, Path local, String replaces, long size, String detail) {
            this.kind = kind;
            this.relative = relative;
            this.local = local;
            this.replaces = replaces;
            this.size = size;
            this.detail = detail;
            // removals are often server-only mods or files added on the server, so they're opt-in
            this.selected = kind == Kind.ADD || kind == Kind.UPDATE;
        }

        @Override
        public String toString() {
            return kind + " " + relative + (replaces == null ? "" : " (replaces " + replaces + ")");
        }
    }

    public static final class Result {
        public int uploaded = 0;
        public int replaced = 0;
        public int removed = 0;
        public long bytes = 0;
        // where replaced and removed files were moved; null if none were
        public String backupFolder = null;
        public boolean cancelled = false;
        public IOException error = null;
    }

    private ServerSync() {
    }

    /**
     * The instance's mod files a server needs: those in its mods folder, except mods it marks client-only.
     * Disabled mods aren't in the mods folder, so they're left out too.
     *
     * @param skipped filled with the names of mods left out, and why
     */
    public static Map<String, Path> serverMods(Path instanceRoot, List<DisableableMod> mods,
            Map<String, String> skipped) throws IOException {
        Map<String, DisableableMod> byFile = new HashMap<>();
        for (DisableableMod mod : mods) {
            if (mod.file != null) {
                byFile.put(mod.file, mod);
                if (mod.disabled) {
                    skipped.put(mod.file, GetText.tr("Disabled in the instance"));
                }
            }
        }
        Map<String, Path> files = new TreeMap<>();
        for (Path file : files(instanceRoot.resolve("mods"), false)) {
            String name = file.getFileName().toString();
            DisableableMod mod = byFile.get(name);
            // a mod nobody knows the side of goes to the server, so nothing is left out on a guess
            if (mod != null && mod.getEffectiveSide() == ModSide.CLIENT) {
                skipped.put(name, GetText.tr("Client-only, so the server doesn't need it"));
            } else if (isJar(name)) {
                files.put(name, file);
            }
        }
        return files;
    }

    /**
     * Works out what syncing would change. Reads the server and downloads only what it must to decide: config and
     * script files the same size on both sides, and server jars that no instance mod matches by name.
     *
     * @param mods the instance's mods, for their sides and platform projects
     * @param identify the Modrinth or CurseForge projects of the jars it knows, such as modrinth:AANobbMI or
     *            curseforge:238222
     * @param work an empty folder for downloads, which the caller removes
     * @param step told what's happening (null while downloading); returns false to cancel, which throws
     *            {@link InterruptedIOException}
     */
    public static List<Change> plan(SftpClient client, String base, Path instanceRoot, List<DisableableMod> mods,
            Function<Collection<Path>, Map<Path, Set<String>>> identify, Path work, Predicate<String> step)
            throws IOException {
        List<Change> changes = new ArrayList<>();
        planMods(client, base, instanceRoot, mods, identify, work, step, changes);
        for (String folder : FOLDERS) {
            check(step, GetText.tr("Comparing {0}...", folder));
            planFolder(client, base, instanceRoot, folder, work, step, changes);
        }
        changes.sort(Comparator.comparing((Change c) -> c.kind).thenComparing(c -> c.relative));
        return changes;
    }

    private static void planMods(SftpClient client, String base, Path instanceRoot, List<DisableableMod> mods,
            Function<Collection<Path>, Map<Path, Set<String>>> identify, Path work, Predicate<String> step,
            List<Change> changes) throws IOException {
        check(step, GetText.tr("Comparing mods..."));
        Map<String, String> skipped = new HashMap<>();
        Map<String, Path> local = serverMods(instanceRoot, mods, skipped);
        Map<String, SftpClient.RemoteFile> remote = new TreeMap<>();
        for (SftpClient.RemoteFile file : list(client, SftpClient.join(base, "mods"))) {
            if (!file.directory && isJar(file.name)) {
                remote.put(file.name, file);
            }
        }

        // the same name: unchanged, or a rebuilt file if the size differs
        for (String name : new ArrayList<>(local.keySet())) {
            SftpClient.RemoteFile there = remote.remove(name);
            if (there != null) {
                Path file = local.remove(name);
                long size = Files.size(file);
                changes.add(size == there.size
                    ? new Change(Kind.UNCHANGED, "mods/" + name, file, null, size, "")
                    : new Change(Kind.UPDATE, "mods/" + name, file, null, size,
                        GetText.tr("The server's copy is different")));
            }
        }
        // mods the instance has but leaves out: the server's copies show as removals, with why
        for (Map.Entry<String, String> left : skipped.entrySet()) {
            SftpClient.RemoteFile there = remote.remove(left.getKey());
            if (there != null) {
                changes.add(new Change(Kind.REMOVE, "mods/" + there.name, null, null, there.size, left.getValue()));
            }
        }

        // an older build of the same mod usually differs only in its version numbers
        pairByStem(local, remote, changes);

        if (!local.isEmpty() && !remote.isEmpty()) {
            pairByContent(client, base, local, remote, mods, identify, work, step, changes);
        }

        for (Map.Entry<String, Path> left : local.entrySet()) {
            changes.add(new Change(Kind.ADD, "mods/" + left.getKey(), left.getValue(), null,
                Files.size(left.getValue()), GetText.tr("Not on the server")));
        }
        for (SftpClient.RemoteFile left : remote.values()) {
            changes.add(new Change(Kind.REMOVE, "mods/" + left.name, null, null, left.size,
                GetText.tr("Not in the instance; maybe a server-only mod")));
        }
    }

    private static void pairByStem(Map<String, Path> local, Map<String, SftpClient.RemoteFile> remote,
            List<Change> changes) throws IOException {
        Map<String, List<String>> localStems = local.keySet().stream()
            .collect(Collectors.groupingBy(ServerSync::stem));
        Map<String, List<String>> remoteStems = remote.keySet().stream()
            .collect(Collectors.groupingBy(ServerSync::stem));
        for (Map.Entry<String, List<String>> stem : localStems.entrySet()) {
            List<String> there = remoteStems.get(stem.getKey());
            if (!stem.getKey().isEmpty() && stem.getValue().size() == 1 && there != null && there.size() == 1) {
                String name = stem.getValue().get(0);
                Path file = local.remove(name);
                remote.remove(there.get(0));
                changes.add(new Change(Kind.UPDATE, "mods/" + name, file, "mods/" + there.get(0), Files.size(file),
                    GetText.tr("Replaces {0}, which looks like another version of it", there.get(0))));
            }
        }
    }

    // downloads the unmatched server jars to compare their contents and Modrinth/CurseForge projects
    private static void pairByContent(SftpClient client, String base, Map<String, Path> local,
            Map<String, SftpClient.RemoteFile> remote, List<DisableableMod> mods,
            Function<Collection<Path>, Map<Path, Set<String>>> identify, Path work, Predicate<String> step,
            List<Change> changes) throws IOException {
        Path folder = Files.createDirectories(work.resolve("mods")).toAbsolutePath().normalize();
        Map<Path, String> downloaded = new LinkedHashMap<>();
        for (SftpClient.RemoteFile file : remote.values()) {
            check(step, GetText.tr("Checking {0} on the server...", file.name));
            Path target = folder.resolve(file.name).normalize();
            // a name the server allows can still mean a path here, such as one with backslashes on Windows
            if (!folder.equals(target.getParent())) {
                continue;
            }
            client.download(SftpClient.join(base, "mods/" + file.name), target, bytes -> step.test(null));
            downloaded.put(target, file.name);
        }

        // the same file under another name is already there
        Map<String, String> remoteBySha1 = new HashMap<>();
        for (Map.Entry<Path, String> file : downloaded.entrySet()) {
            remoteBySha1.put(Hashing.sha1(file.getKey()).toString(), file.getValue());
        }
        for (String name : new ArrayList<>(local.keySet())) {
            String there = remoteBySha1.get(Hashing.sha1(local.get(name)).toString());
            if (there != null && remote.remove(there) != null) {
                Path file = local.remove(name);
                changes.add(new Change(Kind.UNCHANGED, "mods/" + name, file, null, Files.size(file),
                    GetText.tr("The server has it as {0}", there)));
            }
        }
        if (local.isEmpty() || remote.isEmpty()) {
            return;
        }

        check(step, GetText.tr("Identifying mods on Modrinth and CurseForge..."));
        List<Path> jars = new ArrayList<>(local.values());
        downloaded.keySet().stream().filter(p -> remote.containsKey(downloaded.get(p))).forEach(jars::add);
        Map<Path, Set<String>> projects = new HashMap<>(identify.apply(jars));
        Map<String, DisableableMod> byFile = new HashMap<>();
        mods.stream().filter(m -> m.file != null).forEach(m -> byFile.put(m.file, m));
        for (Map.Entry<String, Path> file : local.entrySet()) {
            Set<String> keys = new HashSet<>(projects.getOrDefault(file.getValue(), Collections.emptySet()));
            keys.addAll(storedProjects(byFile.get(file.getKey())));
            projects.put(file.getValue(), keys);
        }

        for (String name : new ArrayList<>(local.keySet())) {
            Set<String> keys = projects.get(local.get(name));
            List<String> matches = downloaded.entrySet().stream()
                .filter(e -> remote.containsKey(e.getValue()))
                .filter(e -> !Collections.disjoint(keys, projects.getOrDefault(e.getKey(), Collections.emptySet())))
                .map(Map.Entry::getValue).collect(Collectors.toList());
            if (matches.size() == 1) {
                Path file = local.remove(name);
                remote.remove(matches.get(0));
                changes.add(new Change(Kind.UPDATE, "mods/" + name, file, "mods/" + matches.get(0),
                    Files.size(file), GetText.tr("Replaces {0}, the same mod on Modrinth or CurseForge",
                        matches.get(0))));
            }
        }
    }

    private static Set<String> storedProjects(DisableableMod mod) {
        Set<String> keys = new HashSet<>();
        if (mod != null && mod.modrinthProject != null && mod.modrinthProject.id != null) {
            keys.add("modrinth:" + mod.modrinthProject.id);
        }
        if (mod != null && mod.curseForgeProjectId != null) {
            keys.add("curseforge:" + mod.curseForgeProjectId);
        }
        return keys;
    }

    private static void planFolder(SftpClient client, String base, Path instanceRoot, String folder, Path work,
            Predicate<String> step, List<Change> changes) throws IOException {
        Map<String, Path> local = new TreeMap<>();
        Path localFolder = instanceRoot.resolve(folder);
        for (Path file : files(localFolder, true)) {
            String relative = folder + "/" + localFolder.relativize(file).toString().replace('\\', '/');
            if (isServerFile(relative)) {
                local.put(relative, file);
            }
        }
        Map<String, SftpClient.RemoteFile> remote = new TreeMap<>();
        walk(client, SftpClient.join(base, folder), folder, remote);
        remote.keySet().removeIf(relative -> !isServerFile(relative));

        for (Map.Entry<String, Path> file : local.entrySet()) {
            SftpClient.RemoteFile there = remote.remove(file.getKey());
            long size = Files.size(file.getValue());
            if (there == null) {
                changes.add(new Change(Kind.ADD, file.getKey(), file.getValue(), null, size,
                    GetText.tr("Not on the server")));
            } else if (there.size != size || !sameContent(client, there.path, file.getValue(), work, step)) {
                changes.add(new Change(Kind.UPDATE, file.getKey(), file.getValue(), null, size,
                    GetText.tr("The server's copy is different")));
            } else {
                changes.add(new Change(Kind.UNCHANGED, file.getKey(), file.getValue(), null, size, ""));
            }
        }
        for (Map.Entry<String, SftpClient.RemoteFile> left : remote.entrySet()) {
            changes.add(new Change(Kind.REMOVE, left.getKey(), null, null, left.getValue().size,
                GetText.tr("Not in the instance; maybe made by the server")));
        }
    }

    private static boolean sameContent(SftpClient client, String remotePath, Path local, Path work,
            Predicate<String> step) throws IOException {
        Path target = Files.createDirectories(work.resolve("compare")).resolve("file");
        client.download(remotePath, target, bytes -> step.test(null));
        try {
            return Hashing.sha1(target).equals(Hashing.sha1(local));
        } finally {
            Files.deleteIfExists(target);
        }
    }

    /**
     * Whether a file in the compared folders matters to a server. Forge and NeoForge only read
     * &lt;modid&gt;-client.toml on clients.
     */
    static boolean isServerFile(String relative) {
        String lower = relative.toLowerCase(Locale.ENGLISH);
        return !lower.endsWith("-client.toml") && !lower.endsWith(SftpUpload.PARTIAL_SUFFIX)
            && !lower.endsWith(SftpUpload.OLD_SUFFIX);
    }

    /**
     * A mod file's name without its version numbers and build words, such as "create" for
     * create-1.20.1-0.5.1.f.jar. Empty if nothing is left.
     */
    static String stem(String fileName) {
        String name = fileName.toLowerCase(Locale.ENGLISH).replaceFirst("\\.jar$", "");
        return Arrays.stream(name.split("[-_+\\s\\[\\]()]+"))
            .filter(t -> !t.isEmpty() && !t.matches(".*\\d.*") && !BUILD_WORDS.contains(t))
            .collect(Collectors.joining("-"));
    }

    /**
     * Makes the server match the ticked changes, in order. Stops at the first error or cancel, and returns what was
     * done before it.
     *
     * @param backupFolder where replaced and removed files are moved
     * @param progress told the change being made and the bytes uploaded so far; returns false to cancel
     */
    public static Result apply(SftpClient client, String base, List<Change> changes, String backupFolder,
            BiPredicate<Change, Long> progress) {
        Result result = new Result();
        for (Change change : changes) {
            if (!change.selected || change.kind == Kind.UNCHANGED) {
                continue;
            }
            long before = result.bytes;
            if (!progress.test(change, before)) {
                result.cancelled = true;
                break;
            }
            try {
                if (change.kind == Kind.REMOVE) {
                    moveToBackup(client, base, change.relative, backupFolder);
                    result.backupFolder = backupFolder;
                    result.removed++;
                    continue;
                }

                SftpUpload.Item item = new SftpUpload.Item(change.local, change.relative,
                    SftpClient.join(base, change.relative), change.size);
                item.exists = change.kind == Kind.UPDATE && change.replaces == null;
                SftpUpload.Result upload = SftpUpload.run(client, Collections.singletonList(item),
                    SftpUpload.Existing.BACK_UP_AND_REPLACE, backupFolder,
                    (i, done) -> progress.test(change, before + done));
                if (upload.cancelled) {
                    result.cancelled = true;
                    break;
                }
                if (upload.error != null) {
                    result.error = upload.error;
                    break;
                }
                result.uploaded++;
                result.bytes += item.size;
                if (upload.backupFolder != null) {
                    result.backupFolder = upload.backupFolder;
                }
                if (item.exists) {
                    result.replaced++;
                }
                // the new jar is in place, so the old one goes straight away: both loading would crash the server
                if (change.replaces != null) {
                    moveToBackup(client, base, change.replaces, backupFolder);
                    result.backupFolder = backupFolder;
                    result.replaced++;
                }
            } catch (IOException e) {
                result.error = e;
                break;
            }
        }
        return result;
    }

    private static void moveToBackup(SftpClient client, String base, String relative, String backupFolder)
            throws IOException {
        String backup = SftpClient.join(backupFolder, relative);
        client.mkdirs(SftpClient.parent(backup));
        client.rename(SftpClient.join(base, relative), backup);
    }

    private static void check(Predicate<String> step, String message) throws InterruptedIOException {
        if (!step.test(message)) {
            throw new InterruptedIOException("Cancelled");
        }
    }

    private static boolean isJar(String name) {
        return name.toLowerCase(Locale.ENGLISH).endsWith(".jar");
    }

    // a remote folder's contents, or none if it isn't there
    static List<SftpClient.RemoteFile> list(SftpClient client, String folder) throws IOException {
        try {
            return client.list(folder);
        } catch (NoSuchFileException e) {
            return Collections.emptyList();
        }
    }

    // every file below a remote folder, by its path below the server's folder
    static void walk(SftpClient client, String folder, String relative, Map<String, SftpClient.RemoteFile> into)
            throws IOException {
        for (SftpClient.RemoteFile file : list(client, folder)) {
            String path = relative + "/" + file.name;
            if (file.directory) {
                walk(client, file.path, path, into);
            } else {
                into.put(path, file);
            }
        }
    }

    // a local folder's files, or none if it isn't there
    private static List<Path> files(Path folder, boolean recursive) throws IOException {
        if (!Files.isDirectory(folder)) {
            return Collections.emptyList();
        }
        try (Stream<Path> stream = recursive ? Files.walk(folder) : Files.list(folder)) {
            return stream.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
    }
}
