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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.data.ModSide;
import com.atlauncher.data.curseforge.CurseForgeFingerprint;
import com.atlauncher.data.curseforge.CurseForgeFingerprintedMod;
import com.atlauncher.data.minecraft.loaders.LoaderType;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthVersion;
import com.atlauncher.data.modrinth.pack.ModrinthModpackManifest;
import com.atlauncher.managers.LogManager;
import com.atlauncher.utils.CurseForgeApi;
import com.atlauncher.utils.Hashing;
import com.atlauncher.utils.ModrinthApi;

/**
 * Makes a client instance from a server: copies its mods, configs and KubeJS scripts, leaves out mods Modrinth or
 * CurseForge mark server-only, and describes it as a Modrinth pack, so the launcher's own importer installs the right
 * Minecraft version and loader and then identifies the mods on both platforms for updates.
 */
public final class ServerImport {
    /**
     * A mod jar found on Modrinth or CurseForge.
     */
    public static final class Match {
        // such as modrinth:AANobbMI or curseforge:238222
        public final Set<String> projects = new HashSet<>();
        // null when the platforms don't say
        public ModSide side;
        public String name;
    }

    public static final class Gathered {
        public int mods = 0;
        public int files = 0;
        // the mods left out because they only run on servers, by name
        public final List<String> serverOnly = new ArrayList<>();
    }

    private ServerImport() {
    }

    /**
     * Why an instance can't be made for this server, readable by the user, or null if it can.
     */
    public static String problem(ServerDetection.Info info) {
        if (info.minecraftVersion == null) {
            return GetText.tr("The server's Minecraft version couldn't be worked out. {0}", info.minecraftProblem);
        }
        if (info.loader == null) {
            return GetText.tr("The server's mod loader couldn't be worked out. {0}", info.loaderProblem);
        }
        if (info.loader == LoaderType.PAPER || info.loader == LoaderType.PURPUR) {
            return GetText.tr("{0} servers run plugins rather than mods, so there's no client instance to make.",
                info.loader);
        }
        if (info.loaderVersion == null) {
            return GetText.tr("The server's {0} version couldn't be worked out. {1}", info.loader,
                info.loaderVersionProblem);
        }
        return null;
    }

    /**
     * Copies the server's mods, configs, defaultconfigs and KubeJS server and startup scripts and data into
     * folder/overrides, the layout of a Modrinth pack.
     *
     * @param step told what's happening (null while downloading); returns false to cancel, which throws
     *            {@link InterruptedIOException}
     */
    public static Gathered gather(SftpClient client, String base, Path folder, Predicate<String> step)
            throws IOException {
        Map<String, SftpClient.RemoteFile> files = new TreeMap<>();
        // only the jars directly in mods load
        for (SftpClient.RemoteFile file : ServerSync.list(client, SftpClient.join(base, "mods"))) {
            if (!file.directory && file.name.toLowerCase(Locale.ENGLISH).endsWith(".jar")) {
                files.put("mods/" + file.name, file);
            }
        }
        for (String other : ServerSync.FOLDERS) {
            ServerSync.walk(client, SftpClient.join(base, other), other, files);
        }
        files.keySet().removeIf(relative -> !ServerSync.isServerFile(relative));

        Gathered gathered = new Gathered();
        Path overrides = folder.resolve("overrides").toAbsolutePath().normalize();
        for (Map.Entry<String, SftpClient.RemoteFile> file : files.entrySet()) {
            if (!step.test(GetText.tr("Downloading {0}...", file.getKey()))) {
                throw new InterruptedIOException("Cancelled");
            }
            Path target;
            try {
                target = overrides.resolve(file.getKey()).normalize();
            } catch (InvalidPathException e) {
                // a name the server allows but this computer doesn't, such as one with a colon on Windows
                LogManager.warn("Left out " + file.getKey() + " from the server: its name can't be used here");
                continue;
            }
            if (!target.startsWith(overrides)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            client.download(file.getValue().path, target, bytes -> step.test(null));
            if (file.getKey().startsWith("mods/")) {
                gathered.mods++;
            } else {
                gathered.files++;
            }
        }
        return gathered;
    }

    /**
     * Deletes the mods that Modrinth or CurseForge say only run on servers.
     *
     * @return their names, as the platforms give them
     */
    public static List<String> leaveOutServerOnly(Path modsFolder,
            Function<Collection<Path>, Map<Path, Match>> identify) throws IOException {
        List<String> left = new ArrayList<>();
        if (!Files.isDirectory(modsFolder)) {
            return left;
        }
        List<Path> jars;
        try (Stream<Path> list = Files.list(modsFolder)) {
            jars = list.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        Map<Path, Match> matches = identify.apply(jars);
        for (Path jar : jars) {
            Match match = matches.get(jar);
            if (match != null && match.side == ModSide.SERVER) {
                Files.delete(jar);
                left.add(match.name == null ? jar.getFileName().toString() : match.name);
            }
        }
        return left;
    }

    /**
     * A Modrinth pack manifest for the server's Minecraft version and loader, with every file in its overrides.
     */
    public static ModrinthModpackManifest manifest(String name, ServerDetection.Info info) {
        ModrinthModpackManifest manifest = new ModrinthModpackManifest();
        manifest.formatVersion = 1;
        manifest.game = "minecraft";
        manifest.versionId = "1.0.0";
        manifest.name = name;
        // #. {0} is the name of the server the instance was made from
        manifest.summary = GetText.tr("Made from the server {0}", name);
        manifest.files = new ArrayList<>();
        manifest.dependencies = new LinkedHashMap<>();
        manifest.dependencies.put("minecraft", info.minecraftVersion);
        switch (info.loader) {
            case FORGE:
                manifest.dependencies.put("forge", info.loaderVersion);
                break;
            case NEOFORGE:
                manifest.dependencies.put("neoforge", info.loaderVersion);
                break;
            case QUILT:
                manifest.dependencies.put("quilt-loader", info.loaderVersion);
                break;
            default:
                // the importer installs Legacy Fabric for old Minecraft versions itself
                manifest.dependencies.put("fabric-loader", info.loaderVersion);
                break;
        }
        return manifest;
    }

    /**
     * The Modrinth and CurseForge projects of jars, for {@link ServerSync#plan}.
     */
    public static Map<Path, Set<String>> projects(Collection<Path> jars) {
        return lookUp(jars).entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().projects));
    }

    /**
     * Looks jars up on Modrinth (by SHA-1) and CurseForge (by fingerprint), unless the settings say not to. A failed
     * lookup is logged and leaves those jars out.
     */
    public static Map<Path, Match> lookUp(Collection<Path> jars) {
        Map<Path, Match> matches = new HashMap<>();
        if (jars.isEmpty()) {
            return matches;
        }
        if (!App.settings.dontCheckModsOnModrinth) {
            try {
                Map<String, Path> bySha1 = new HashMap<>();
                for (Path jar : jars) {
                    bySha1.put(Hashing.sha1(jar).toString(), jar);
                }
                Map<String, ModrinthVersion> versions = ModrinthApi
                    .getVersionsFromSha1Hashes(bySha1.keySet().toArray(new String[0]));
                Map<String, ModrinthProject> projects = versions.isEmpty() ? null
                    : ModrinthApi.getProjectsAsMap(
                        versions.values().stream().map(v -> v.projectId).distinct().toArray(String[]::new));
                for (Map.Entry<String, ModrinthVersion> version : versions.entrySet()) {
                    Path jar = bySha1.get(version.getKey());
                    if (jar == null || version.getValue().projectId == null) {
                        continue;
                    }
                    Match match = matches.computeIfAbsent(jar, j -> new Match());
                    match.projects.add("modrinth:" + version.getValue().projectId);
                    ModrinthProject project = projects == null ? null : projects.get(version.getValue().projectId);
                    if (project != null) {
                        match.side = ModSide.fromModrinth(project.clientSide, project.serverSide);
                        match.name = project.title;
                    }
                }
            } catch (Throwable t) {
                LogManager.logStackTrace("Couldn't look up the server's mods on Modrinth", t);
            }
        }
        if (!App.settings.dontCheckModsOnCurseForge) {
            try {
                Map<Long, Path> byFingerprint = new HashMap<>();
                for (Path jar : jars) {
                    byFingerprint.put(Hashing.murmur(jar), jar);
                }
                CurseForgeFingerprint found = CurseForgeApi
                    .checkFingerprints(byFingerprint.keySet().toArray(new Long[0]));
                if (found != null && found.exactMatches != null) {
                    for (CurseForgeFingerprintedMod mod : found.exactMatches) {
                        Path jar = mod == null || mod.file == null ? null
                            : byFingerprint.get(mod.file.packageFingerprint);
                        if (jar == null) {
                            continue;
                        }
                        Match match = matches.computeIfAbsent(jar, j -> new Match());
                        match.projects.add("curseforge:" + mod.id);
                        // Modrinth's project sides are clearer than CurseForge's optional file tags
                        if (match.side == null) {
                            match.side = ModSide.fromCurseForge(mod.file.gameVersions);
                        }
                        if (match.name == null) {
                            match.name = mod.file.displayName;
                        }
                    }
                }
            } catch (Throwable t) {
                LogManager.logStackTrace("Couldn't look up the server's mods on CurseForge", t);
            }
        }
        return matches;
    }
}
