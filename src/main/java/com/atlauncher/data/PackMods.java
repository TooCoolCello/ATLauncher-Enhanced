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
package com.atlauncher.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.constants.Constants;
import com.atlauncher.data.curseforge.CurseForgeFile;
import com.atlauncher.data.curseforge.CurseForgeFileDependency;
import com.atlauncher.data.curseforge.CurseForgeGameVersionLatestFiles;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.curseforge.pack.CurseForgeManifest;
import com.atlauncher.data.curseforge.pack.CurseForgeManifestFile;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthDependency;
import com.atlauncher.data.modrinth.ModrinthDependencyType;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthSide;
import com.atlauncher.data.modrinth.ModrinthVersion;
import com.atlauncher.data.modrinth.pack.ModrinthModpackFile;
import com.atlauncher.data.modrinth.pack.ModrinthModpackManifest;
import com.atlauncher.utils.CurseForgeApi;
import com.atlauncher.utils.ModrinthApi;

/**
 * The mods in someone else's modpack, or another instance, checked against this instance: which are already here,
 * which have a file for it (natively or through Connector) and which don't. Parsing a pack's mod list, matching it to
 * what's installed, and working out what selected mods still need are kept free of the network and the UI;
 * {@link #resolve} takes its lookups from an {@link Api}.
 */
public final class PackMods {
    private PackMods() {
    }

    /**
     * One mod as a pack lists it: by file hash (a Modrinth pack's file, or a jar in another instance), looked up on
     * Modrinth, or by CurseForge project.
     */
    public static final class PackFile {
        public final String sha1;
        public final Integer curseForgeProjectId;
        // what the pack calls it, for when it can't be identified
        public final String name;
        public final ModSide side;

        private PackFile(String sha1, Integer curseForgeProjectId, String name, ModSide side) {
            this.sha1 = sha1 == null ? null : sha1.toLowerCase(Locale.ENGLISH);
            this.curseForgeProjectId = curseForgeProjectId;
            this.name = name;
            this.side = side;
        }

        public static PackFile byHash(String sha1, String name, ModSide side) {
            return new PackFile(sha1, null, name, side);
        }

        public static PackFile curseForge(int projectId, String name, ModSide side) {
            return new PackFile(null, projectId, name, side);
        }
    }

    /**
     * A row's state, in the order rows are grouped: what can be added first.
     */
    public enum Status {
        COMPATIBLE, VIA_CONNECTOR, UNKNOWN, NOT_AVAILABLE, INSTALLED
    }

    /**
     * One mod of the pack, checked against this instance, with the file that would be added for it.
     */
    public static final class Row {
        public final String name;
        // null when it couldn't be identified on either site
        public final ModPlatform platform;
        public final ModrinthProject modrinthProject;
        public final String modrinthId;
        public final ModrinthVersion modrinthVersion;
        public final CurseForgeProject curseForgeProject;
        public final Integer curseForgeId;
        public final Integer curseForgeFileId;
        public final Status status;
        public final String reason;
        public final ModSide side;

        Row(String name, ModPlatform platform, ModrinthProject modrinthProject, String modrinthId,
                ModrinthVersion modrinthVersion, CurseForgeProject curseForgeProject, Integer curseForgeId,
                Integer curseForgeFileId, Status status, String reason, ModSide side) {
            this.name = name == null ? "" : name;
            this.platform = platform;
            this.modrinthProject = modrinthProject;
            this.modrinthId = modrinthId;
            this.modrinthVersion = modrinthVersion;
            this.curseForgeProject = curseForgeProject;
            this.curseForgeId = curseForgeId;
            this.curseForgeFileId = curseForgeFileId;
            this.status = status;
            this.reason = reason;
            this.side = side;
        }

        Row withStatus(Status status, String reason) {
            return new Row(name, platform, modrinthProject, modrinthId, modrinthVersion, curseForgeProject,
                    curseForgeId, curseForgeFileId, status, reason, side);
        }

        /**
         * Its project on its site, so the same mod listed twice is shown once.
         */
        public String key() {
            if (modrinthId != null) {
                return "modrinth:" + modrinthId.toLowerCase(Locale.ENGLISH);
            }
            if (curseForgeId != null) {
                return "curseforge:" + curseForgeId;
            }
            return "file:" + name.toLowerCase(Locale.ENGLISH);
        }

        /**
         * Whether it can be ticked: it isn't here yet, and there's a file for it that might run. A file that doesn't
         * say its loader can be ticked by hand; only {@link #compatible} ones are ticked for the user.
         */
        public boolean installable() {
            boolean hasFile = (platform == ModPlatform.MODRINTH && modrinthProject != null && modrinthVersion != null)
                    || (platform == ModPlatform.CURSEFORGE && curseForgeProject != null && curseForgeFileId != null);
            return hasFile && status != Status.INSTALLED && status != Status.NOT_AVAILABLE;
        }

        /**
         * Whether "Select Compatible" ticks it: a native file for this instance, and not a client-only mod for a
         * server. Connector files and files that don't say their loader are left for the user to choose.
         */
        public boolean compatible(boolean server) {
            return installable() && status == Status.COMPATIBLE && !clientOnlyOnServer(server);
        }

        /**
         * A mod only players need, listed for a server, where it's no use and may stop it starting.
         */
        public boolean clientOnlyOnServer(boolean server) {
            return server && side == ModSide.CLIENT;
        }
    }

    /**
     * The mods of the instance being added to, by project, and by name for mods only known on the other site.
     */
    public static final class Installed {
        final Set<String> modrinth = new HashSet<>();
        final Set<Integer> curseForge = new HashSet<>();
        final Set<String> names = new HashSet<>();
        // for the equivalents the selectors accept for a dependency, such as a library added from the other site
        final List<DisableableMod> mods = new ArrayList<>();

        public static Installed of(Collection<DisableableMod> mods) {
            Installed installed = new Installed();
            if (mods != null) {
                for (DisableableMod mod : mods) {
                    if (mod == null) {
                        continue;
                    }
                    installed.mods.add(mod);
                    // a mod added from either site is usually looked up on the other too
                    if (mod.isFromModrinth()) {
                        installed.modrinth.add(mod.modrinthProject.id.toLowerCase(Locale.ENGLISH));
                    }
                    if (mod.isFromCurseForge()) {
                        installed.curseForge.add(mod.getCurseForgeModId());
                    }
                    if (mod.type == Type.mods && mod.name != null) {
                        installed.names.add(nameKey(mod.name));
                    }
                }
            }
            return installed;
        }

        /**
         * Whether a mod of the same name is installed though the project isn't matched, as when it came from the
         * other site and wasn't found there. Adding it again would likely put two copies of one mod in the folder.
         */
        public boolean hasSameName(Row row) {
            return row.platform != null && !row.name.isEmpty() && names.contains(nameKey(row.name));
        }

        private static String nameKey(String name) {
            return name.trim().toLowerCase(Locale.ENGLISH);
        }

        boolean hasModrinth(String id) {
            return id != null && modrinth.contains(id.toLowerCase(Locale.ENGLISH));
        }

        boolean hasCurseForge(Integer id) {
            return id != null && curseForge.contains(id);
        }

        // a dependency counted as here as the selectors count it
        boolean providesModrinth(String id) {
            return hasModrinth(id) || mods.stream().anyMatch(m -> RequiredDependency.providesModrinth(m, id));
        }

        boolean providesCurseForge(int id, LoaderVersion loader) {
            return hasCurseForge(id)
                    || mods.stream().anyMatch(m -> RequiredDependency.providesCurseForgeAsEquivalent(m, id, loader));
        }

        public boolean has(Row row) {
            return hasModrinth(row.modrinthId) || hasCurseForge(row.curseForgeId)
                    || (row.modrinthProject != null && hasModrinth(row.modrinthProject.id))
                    || (row.curseForgeProject != null && hasCurseForge(row.curseForgeProject.id));
        }
    }

    // --- reading a pack's mod list

    /**
     * The mods of a Modrinth pack (modrinth.index.json): its files under mods/, by SHA-1, with the side its env
     * gives. Resource packs, shaders and the rest aren't mods for an instance to add here.
     */
    public static List<PackFile> fromModrinthIndex(ModrinthModpackManifest manifest) {
        List<PackFile> files = new ArrayList<>();
        if (manifest == null || manifest.files == null) {
            return files;
        }

        for (ModrinthModpackFile file : manifest.files) {
            if (file == null || file.path == null || file.hashes == null || file.hashes.get("sha1") == null) {
                continue;
            }
            String path = file.path.replace('\\', '/');
            if (!path.startsWith("mods/")) {
                continue;
            }
            files.add(PackFile.byHash(file.hashes.get("sha1"), path.substring(path.lastIndexOf('/') + 1),
                    sideOfEnv(file.env)));
        }
        return files;
    }

    /**
     * The side a Modrinth pack's env gives a file: unsupported on servers means only players need it.
     */
    static ModSide sideOfEnv(Map<String, String> env) {
        if (env == null) {
            return null;
        }
        ModrinthSide client = side(env.get("client"));
        ModrinthSide server = side(env.get("server"));
        return client == null || server == null ? null : ModSide.fromModrinth(client, server);
    }

    private static ModrinthSide side(String value) {
        if (value == null) {
            return null;
        }
        switch (value.toLowerCase(Locale.ENGLISH)) {
            case "required":
                return ModrinthSide.REQUIRED;
            case "optional":
                return ModrinthSide.OPTIONAL;
            case "unsupported":
                return ModrinthSide.UNSUPPORTED;
            default:
                return null;
        }
    }

    /**
     * The projects of a CurseForge pack (manifest.json). Which of them are mods is only known once CurseForge is
     * asked, by their class.
     */
    public static List<PackFile> fromCurseForgeManifest(CurseForgeManifest manifest) {
        List<PackFile> files = new ArrayList<>();
        if (manifest == null || manifest.files == null) {
            return files;
        }

        for (CurseForgeManifestFile file : manifest.files) {
            if (file != null && file.projectID > 0) {
                files.add(PackFile.curseForge(file.projectID, "CurseForge #" + file.projectID, null));
            }
        }
        return files;
    }

    /**
     * Another instance's mods: those only known on CurseForge by project, the rest by the SHA-1 of their file, so
     * jars added by hand are looked up too. Mods whose file can't be hashed are left out.
     *
     * @param sha1 the SHA-1 of a mod's file, or null when it's missing
     */
    public static List<PackFile> fromInstanceMods(List<DisableableMod> mods, Function<DisableableMod, String> sha1) {
        List<PackFile> files = new ArrayList<>();
        if (mods == null) {
            return files;
        }

        for (DisableableMod mod : mods) {
            if (mod == null || mod.type != Type.mods) {
                continue;
            }
            if (mod.isFromCurseForge() && !mod.isFromModrinth()) {
                files.add(PackFile.curseForge(mod.getCurseForgeModId(), mod.name, mod.getEffectiveSide()));
                continue;
            }
            String hash = sha1.apply(mod);
            if (hash != null) {
                files.add(PackFile.byHash(hash, mod.name, mod.getEffectiveSide()));
            }
        }
        return files;
    }

    // --- choosing the pack's version

    /**
     * The version of a Modrinth pack to read: the newest for this Minecraft version and loader, else the newest for
     * this Minecraft version, else the newest. Modrinth lists versions newest first.
     */
    public static ModrinthVersion bestPackVersion(List<ModrinthVersion> versions, ModCompatibility.Target target) {
        if (versions == null || versions.isEmpty()) {
            return null;
        }

        List<String> nativeTags = ModCompatibility.nativeTags(target.loader, target.forgeCompatible);
        ModrinthVersion sameMinecraft = null;
        for (ModrinthVersion version : versions) {
            if (version == null || version.gameVersions == null
                    || !version.gameVersions.contains(target.minecraftVersion)) {
                continue;
            }
            if (version.loaders != null && version.loaders.stream()
                    .anyMatch(l -> l != null && nativeTags.contains(l.toLowerCase(Locale.ENGLISH)))) {
                return version;
            }
            if (sameMinecraft == null) {
                sameMinecraft = version;
            }
        }
        return sameMinecraft != null ? sameMinecraft
                : versions.stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    /**
     * The file of a CurseForge pack to read: the newest for this Minecraft version, else the newest. Server packs
     * hold the server's files, not a manifest.
     */
    public static CurseForgeFile bestPackFile(List<CurseForgeFile> files, String minecraftVersion) {
        if (files == null) {
            return null;
        }

        List<CurseForgeFile> newestFirst = packFiles(files);
        return newestFirst.stream()
                .filter(f -> f.gameVersions != null && f.gameVersions.contains(minecraftVersion)).findFirst()
                .orElse(newestFirst.isEmpty() ? null : newestFirst.get(0));
    }

    /**
     * A pack's files that hold a manifest, newest (highest ID) first.
     */
    public static List<CurseForgeFile> packFiles(List<CurseForgeFile> files) {
        return files == null ? new ArrayList<>()
                : files.stream().filter(f -> f != null && !f.isServerPack)
                        .sorted(Comparator.comparingInt((CurseForgeFile f) -> f.id).reversed())
                        .collect(Collectors.toList());
    }

    /**
     * The file CurseForge's latest-file index offers for this instance, by the same rules as
     * {@link ModCompatibility#forCurseForgeProject}: a native file first (in the loader's order of preference), a
     * Fabric one with Connector, then one that doesn't say its loader. A release is preferred to a beta or alpha of
     * the same kind, since many mods are added at once without a look at each. Null when there's none.
     */
    public static Integer bestCurseForgeFileId(ModCompatibility.Target target,
            List<CurseForgeGameVersionLatestFiles> index) {
        if (index == null || target.loader == null) {
            return null;
        }

        List<String> order = new ArrayList<>(ModCompatibility.nativeTags(target.loader, target.forgeCompatible));
        if (target.connectorActive && !order.contains("fabric")) {
            order.add("fabric");
        }
        // untagged files last
        order.add("");

        for (String tag : order) {
            CurseForgeGameVersionLatestFiles best = null;
            for (CurseForgeGameVersionLatestFiles file : index) {
                if (file == null || !target.minecraftVersion.equals(file.gameVersion)) {
                    continue;
                }
                List<String> tags = ModCompatibility.curseForgeLoaderTags(file.modLoader);
                boolean matches = tag.isEmpty() ? tags.isEmpty() : tags.contains(tag);
                if (matches && (best == null || isBetter(file, best))) {
                    best = file;
                }
            }
            if (best != null) {
                return best.fileId;
            }
        }
        return null;
    }

    // release (1) before beta (2) before alpha (3), then the newer file
    private static boolean isBetter(CurseForgeGameVersionLatestFiles file, CurseForgeGameVersionLatestFiles than) {
        int type = file.releaseType <= 0 ? 3 : file.releaseType;
        int thanType = than.releaseType <= 0 ? 3 : than.releaseType;
        return type != thanType ? type < thanType : file.fileId > than.fileId;
    }

    // --- links

    /**
     * A modpack named by a link, with the version or file it names, if any.
     */
    public static final class Link {
        public final ModPlatform platform;
        public final String slug;
        public final String version;

        Link(ModPlatform platform, String slug, String version) {
            this.platform = platform;
            this.slug = slug;
            this.version = version;
        }
    }

    private static final Pattern MODRINTH_LINK = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?modrinth\\.com/modpack/([A-Za-z0-9_-]{1,64})(?:/version/([A-Za-z0-9_.+-]{1,64}))?"
                    + "(?:[/?#].*)?$");
    private static final Pattern CURSEFORGE_LINK = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?curseforge\\.com/minecraft/modpacks/([a-z0-9-]{1,128})"
                    + "(?:/(?:files|download)/([0-9]{1,10}))?(?:[/?#].*)?$");

    /**
     * A Modrinth or CurseForge modpack link, or null when the text isn't one.
     */
    public static Link parseLink(String text) {
        if (text == null) {
            return null;
        }

        String link = text.trim();
        Matcher modrinth = MODRINTH_LINK.matcher(link);
        if (modrinth.matches()) {
            return new Link(ModPlatform.MODRINTH, modrinth.group(1), modrinth.group(2));
        }
        Matcher curseForge = CURSEFORGE_LINK.matcher(link);
        if (curseForge.matches()) {
            return new Link(ModPlatform.CURSEFORGE, curseForge.group(1), curseForge.group(2));
        }
        return null;
    }

    // --- checking the mods against this instance

    /**
     * What {@link #resolve} asks the sites. Each returns null when the request fails.
     */
    public interface Api {
        /** Modrinth's version for each file it knows, by SHA-1. */
        Map<String, ModrinthVersion> identify(List<String> sha1s);

        /** For each file, its project's newest version for this Minecraft version and any of these loaders. */
        Map<String, ModrinthVersion> newest(List<String> sha1s, List<String> loaders, String minecraftVersion);

        List<ModrinthProject> modrinthProjects(List<String> ids);

        List<CurseForgeProject> curseForgeProjects(List<Integer> ids);
    }

    /**
     * The sites themselves. Modrinth requests share Add Mods' pacing.
     */
    public static final Api SITES = new Api() {
        @Override
        public Map<String, ModrinthVersion> identify(List<String> sha1s) {
            Map<String, ModrinthVersion> found = new HashMap<>();
            for (List<String> chunk : chunks(sha1s, 500)) {
                if (!paced()) {
                    return null;
                }
                // ModrinthApi gives an empty map for a failed request too
                found.putAll(ModrinthApi.getVersionsFromSha1Hashes(chunk.toArray(new String[0])));
            }
            return found;
        }

        @Override
        public Map<String, ModrinthVersion> newest(List<String> sha1s, List<String> loaders,
                String minecraftVersion) {
            Map<String, ModrinthVersion> found = new HashMap<>();
            for (List<String> chunk : chunks(sha1s, 500)) {
                if (!paced()) {
                    return null;
                }
                Map<String, ModrinthVersion> part = ModrinthApi
                        .getLatestVersionsFromSha1Hashes(chunk.toArray(new String[0]), loaders, minecraftVersion);
                if (part == null) {
                    return null;
                }
                found.putAll(part);
            }
            return found;
        }

        @Override
        public List<ModrinthProject> modrinthProjects(List<String> ids) {
            List<ModrinthProject> found = new ArrayList<>();
            // kept short: the IDs go in the query string
            for (List<String> chunk : chunks(ids, 100)) {
                if (!paced()) {
                    return null;
                }
                List<ModrinthProject> part = ModrinthApi.getProjects(chunk.toArray(new String[0]));
                if (part == null) {
                    return null;
                }
                found.addAll(part);
            }
            return found;
        }

        @Override
        public List<CurseForgeProject> curseForgeProjects(List<Integer> ids) {
            List<CurseForgeProject> found = new ArrayList<>();
            for (List<Integer> chunk : chunks(ids, 500)) {
                List<CurseForgeProject> part = CurseForgeApi
                        .getProjects(chunk.stream().mapToInt(Integer::intValue).toArray());
                if (part == null) {
                    return null;
                }
                found.addAll(part);
            }
            return found;
        }

        private boolean paced() {
            try {
                ModCompatibilityChecker.waitForTurn();
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    };

    static <T> List<List<T>> chunks(List<T> items, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int start = 0; start < items.size(); start += size) {
            chunks.add(new ArrayList<>(items.subList(start, Math.min(items.size(), start + size))));
        }
        return chunks;
    }

    /**
     * What a pack's mods come to here.
     */
    public static final class Result {
        public final List<Row> rows;
        // projects of the pack that aren't mods, such as resource packs, left out
        public final int notMods;
        // set when a site couldn't be reached at all, so the list is missing what it had
        public final String problem;

        Result(List<Row> rows, int notMods, String problem) {
            this.rows = rows;
            this.notMods = notMods;
            this.problem = problem;
        }
    }

    /**
     * Each of the pack's mods, checked against this instance from file data as Add Mods' marks are: Modrinth's
     * newest version for this Minecraft version and loader (a Fabric one only when Connector is active and there's no
     * native one), CurseForge's latest-file index. Mods already here are marked so whatever their files say. The same
     * project listed twice is shown once. Sorted by name within each status.
     */
    public static Result resolve(List<PackFile> files, ModCompatibility.Target target, Installed installed,
            Api api) {
        Map<String, Row> rows = new LinkedHashMap<>();
        int notMods = 0;
        List<String> problems = new ArrayList<>();

        List<PackFile> hashed = files.stream().filter(f -> f.sha1 != null).collect(Collectors.toList());
        if (!hashed.isEmpty()) {
            notMods += resolveModrinth(hashed, target, api, rows, problems);
        }

        List<PackFile> curseForge = files.stream().filter(f -> f.curseForgeProjectId != null)
                .collect(Collectors.toList());
        if (!curseForge.isEmpty()) {
            notMods += resolveCurseForge(curseForge, target, api, rows, problems);
        }

        List<Row> result = markInstalled(new ArrayList<>(rows.values()), installed);
        result.sort(Comparator.comparing((Row r) -> r.status).thenComparing(r -> r.name.toLowerCase(Locale.ENGLISH)));
        return new Result(result, notMods, problems.isEmpty() ? null : String.join(" ", problems));
    }

    /**
     * The rows in the same order, with those the instance has now marked as installed.
     */
    public static List<Row> markInstalled(List<Row> rows, Installed installed) {
        return rows.stream().map(row -> {
            if (row.status == Status.INSTALLED) {
                return row;
            }
            if (installed.has(row)) {
                // #. a mod in someone else's pack that this instance already has
                return row.withStatus(Status.INSTALLED, GetText.tr("Already in this instance."));
            }
            // ponytail: an exact name match only; a mod renamed between sites still shows as addable
            return installed.hasSameName(row) ? row.withStatus(Status.INSTALLED, GetText.tr("A mod with this name "
                    + "is already in this instance, so it's taken to be this one rather than add a second copy."))
                    : row;
        }).collect(Collectors.toList());
    }

    private static int resolveModrinth(List<PackFile> files, ModCompatibility.Target target, Api api,
            Map<String, Row> rows, List<String> problems) {
        List<String> hashes = files.stream().map(f -> f.sha1).distinct().collect(Collectors.toList());
        Map<String, ModrinthVersion> identified = lowerKeys(api.identify(hashes));
        if (identified == null || identified.isEmpty()) {
            // ModrinthApi can't tell a failed lookup from one that found nothing, so this says both
            problems.add(GetText.tr("None of the mods were found on Modrinth. If Modrinth couldn't be reached, try "
                    + "again later."));
            for (PackFile file : files) {
                put(rows, unidentified(file, GetText.tr("Not found on Modrinth, or Modrinth couldn't be reached.")));
            }
            return 0;
        }

        List<String> known = hashes.stream().filter(identified::containsKey).collect(Collectors.toList());
        List<String> nativeTags = ModCompatibility.nativeTags(target.loader, target.forgeCompatible);
        Map<String, ModrinthVersion> newest = nativeTags.isEmpty() ? new HashMap<>()
                : lowerKeys(api.newest(known, nativeTags, target.minecraftVersion));
        boolean newestFailed = newest == null;
        if (newestFailed) {
            newest = new HashMap<>();
        } else if (target.connectorActive && !nativeTags.contains("fabric")) {
            // Fabric files only for those with no native one, as the selectors prefer native files
            Map<String, ModrinthVersion> found = newest;
            List<String> rest = known.stream().filter(h -> !found.containsKey(h)).collect(Collectors.toList());
            Map<String, ModrinthVersion> fabric = rest.isEmpty() ? new HashMap<>()
                    : lowerKeys(api.newest(rest, Collections.singletonList("fabric"), target.minecraftVersion));
            if (fabric == null) {
                newestFailed = true;
            } else {
                newest.putAll(fabric);
            }
        }
        if (newestFailed) {
            problems.add(GetText.tr("Modrinth couldn't be reached to check the mods' files."));
        }

        List<String> projectIds = identified.values().stream().map(v -> v.projectId).filter(Objects::nonNull)
                .distinct().collect(Collectors.toList());
        List<ModrinthProject> projectList = api.modrinthProjects(projectIds);
        Map<String, ModrinthProject> projects = new HashMap<>();
        if (projectList == null) {
            problems.add(GetText.tr("Modrinth couldn't be reached for the mods' details."));
        } else {
            projectList.stream().filter(p -> p != null && p.id != null).forEach(p -> projects.put(p.id, p));
        }

        int notMods = 0;
        for (PackFile file : files) {
            ModrinthVersion identity = identified.get(file.sha1);
            if (identity == null) {
                put(rows, unidentified(file, GetText.tr("Not found on Modrinth, so it can't be checked or added "
                        + "here.")));
                continue;
            }

            ModrinthProject project = projects.get(identity.projectId);
            if (project != null && project.projectType != null
                    && project.projectType != com.atlauncher.data.modrinth.ModrinthProjectType.MOD) {
                notMods++;
                continue;
            }

            ModrinthVersion candidate = newest.get(file.sha1);
            ModCompatibility.Verdict verdict = newestFailed && candidate == null
                    ? ModCompatibility.checkFailed(target, "Mods")
                    : ModCompatibility.forModrinthVersions(target, "Mods",
                            candidate == null ? Collections.emptyList() : Collections.singletonList(candidate));
            Status status = statusOf(verdict);
            String reason = verdict == null ? null : verdict.reason;

            String blocker = fabricApiBlocker(identity.projectId, null, target);
            if (blocker != null) {
                status = Status.NOT_AVAILABLE;
                reason = blocker;
            }

            ModSide side = file.side != null ? file.side
                    : project == null ? null : ModSide.fromModrinth(project.clientSide, project.serverSide);
            put(rows, new Row(project == null ? file.name : project.title, ModPlatform.MODRINTH, project,
                    identity.projectId, candidate, null, null, null, project == null ? Status.UNKNOWN : status,
                    project == null ? GetText.tr("Couldn't get its details from Modrinth.") : reason, side));
        }
        return notMods;
    }

    private static int resolveCurseForge(List<PackFile> files, ModCompatibility.Target target, Api api,
            Map<String, Row> rows, List<String> problems) {
        List<Integer> ids = files.stream().map(f -> f.curseForgeProjectId).distinct().collect(Collectors.toList());
        List<CurseForgeProject> found = api.curseForgeProjects(ids);
        if (found == null) {
            problems.add(GetText.tr("CurseForge couldn't be reached to check the mods. Try again later."));
        }
        Map<Integer, CurseForgeProject> projects = new HashMap<>();
        if (found != null) {
            found.stream().filter(Objects::nonNull).forEach(p -> projects.put(p.id, p));
        }

        int notMods = 0;
        for (PackFile file : files) {
            CurseForgeProject project = projects.get(file.curseForgeProjectId);
            if (project == null) {
                put(rows, new Row(file.name, null, null, null, null, null, file.curseForgeProjectId, null,
                        Status.UNKNOWN, found == null ? GetText.tr("Couldn't be checked: CurseForge couldn't be "
                                + "reached.") : GetText.tr("Not found on CurseForge."),
                        file.side));
                continue;
            }

            if (project.classId != 0 && project.classId != Constants.CURSEFORGE_MODS_SECTION_ID) {
                notMods++;
                continue;
            }

            ModCompatibility.Verdict verdict = ModCompatibility.forCurseForgeProject(target, "Mods", project);
            Status status = statusOf(verdict);
            String reason = verdict == null ? null : verdict.reason;
            String blocker = fabricApiBlocker(null, project.id, target);
            if (blocker != null) {
                status = Status.NOT_AVAILABLE;
                reason = blocker;
            }

            put(rows, new Row(project.name, ModPlatform.CURSEFORGE, null, null, null, project, project.id,
                    bestCurseForgeFileId(target, project.latestFilesIndexes), status, reason, file.side));
        }
        return notMods;
    }

    // Fabric API isn't added with Connector, which takes Forgified Fabric API instead, as the selectors refuse it
    private static String fabricApiBlocker(String modrinthId, Integer curseForgeId, ModCompatibility.Target target) {
        boolean fabricApi = Constants.MODRINTH_FABRIC_MOD_ID.equals(modrinthId)
                || Integer.valueOf(Constants.CURSEFORGE_FABRIC_MOD_ID).equals(curseForgeId);
        return fabricApi && target.connectorActive
                ? GetText.tr("With Sinytra Connector, Fabric API is provided by Forgified Fabric API. Add Forgified "
                        + "Fabric API instead.")
                : null;
    }

    private static Status statusOf(ModCompatibility.Verdict verdict) {
        if (verdict == null) {
            return Status.UNKNOWN;
        }
        switch (verdict.state) {
            case COMPATIBLE:
                return Status.COMPATIBLE;
            case VIA_CONNECTOR:
                return Status.VIA_CONNECTOR;
            case NOT_AVAILABLE:
                return Status.NOT_AVAILABLE;
            default:
                return Status.UNKNOWN;
        }
    }

    private static Row unidentified(PackFile file, String reason) {
        return new Row(file.name, null, null, null, null, null, null, null, Status.UNKNOWN, reason, file.side);
    }

    private static void put(Map<String, Row> rows, Row row) {
        rows.putIfAbsent(row.key(), row);
    }

    private static Map<String, ModrinthVersion> lowerKeys(Map<String, ModrinthVersion> map) {
        if (map == null) {
            return null;
        }
        Map<String, ModrinthVersion> lower = new HashMap<>();
        map.forEach((hash, version) -> {
            if (hash != null && version != null) {
                lower.put(hash.toLowerCase(Locale.ENGLISH), version);
            }
        });
        return lower;
    }

    // --- what the selected mods still need

    /**
     * What adding the selected mods also takes: required dependencies of the files to be added that the pack has and
     * can add (added too), and those it can't, by site.
     */
    public static final class Dependencies {
        public final List<Row> alsoAdd;
        public final Set<String> missingModrinth;
        public final Set<Integer> missingCurseForge;

        Dependencies(List<Row> alsoAdd, Set<String> missingModrinth, Set<Integer> missingCurseForge) {
            this.alsoAdd = alsoAdd;
            this.missingModrinth = missingModrinth;
            this.missingCurseForge = missingCurseForge;
        }
    }

    /**
     * The required dependencies of the selected rows' files, followed through anything added for them. One already
     * here, selected, or in the pack and addable is covered; the rest are missing. CurseForge files not yet in
     * {@code curseForgeFiles} can't be followed, so fetch those of {@link Dependencies#alsoAdd} and ask again.
     *
     * @param curseForgeFiles the CurseForge files to be added, by file ID
     */
    public static Dependencies dependencies(List<Row> selected, List<Row> all, Installed installed,
            Map<Integer, CurseForgeFile> curseForgeFiles, ModCompatibility.Target target) {
        Map<String, Row> byModrinth = new HashMap<>();
        Map<Integer, Row> byCurseForge = new HashMap<>();
        for (Row row : all) {
            if (row.modrinthId != null) {
                byModrinth.putIfAbsent(row.modrinthId.toLowerCase(Locale.ENGLISH), row);
            }
            if (row.curseForgeId != null) {
                byCurseForge.putIfAbsent(row.curseForgeId, row);
            }
        }

        Set<String> covered = new HashSet<>();
        selected.forEach(row -> covered.add(row.key()));
        List<Row> queue = new ArrayList<>(selected);
        List<Row> alsoAdd = new ArrayList<>();
        Set<String> missingModrinth = new LinkedHashSet<>();
        Set<Integer> missingCurseForge = new LinkedHashSet<>();

        for (int i = 0; i < queue.size(); i++) {
            Row row = queue.get(i);
            for (String required : requiredModrinth(row)) {
                // Connector takes Forgified Fabric API for Fabric API, as the selectors do
                String id = target.connectorActive && Constants.MODRINTH_FABRIC_MOD_ID.equals(required)
                        ? Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID
                        : required;
                Row dependency = byModrinth.get(id.toLowerCase(Locale.ENGLISH));
                if (installed.providesModrinth(id) || (dependency != null && covered.contains(dependency.key()))) {
                    continue;
                }
                if (dependency != null && dependency.status != Status.INSTALLED && dependency.installable()) {
                    covered.add(dependency.key());
                    alsoAdd.add(dependency);
                    queue.add(dependency);
                } else if (dependency == null || dependency.status != Status.INSTALLED) {
                    missingModrinth.add(id);
                }
            }
            for (Integer required : requiredCurseForge(row, curseForgeFiles)) {
                Integer id = target.connectorActive && required == Constants.CURSEFORGE_FABRIC_MOD_ID
                        ? Integer.valueOf(Constants.CURSEFORGE_FORGIFIED_FABRIC_API_MOD_ID)
                        : required;
                Row dependency = byCurseForge.get(id);
                if (installed.providesCurseForge(id, target.loader)
                        || (dependency != null && covered.contains(dependency.key()))) {
                    continue;
                }
                if (dependency != null && dependency.status != Status.INSTALLED && dependency.installable()) {
                    covered.add(dependency.key());
                    alsoAdd.add(dependency);
                    queue.add(dependency);
                } else if (dependency == null || dependency.status != Status.INSTALLED) {
                    missingCurseForge.add(id);
                }
            }
        }
        return new Dependencies(alsoAdd, missingModrinth, missingCurseForge);
    }

    static List<String> requiredModrinth(Row row) {
        if (row.platform != ModPlatform.MODRINTH || row.modrinthVersion == null
                || row.modrinthVersion.dependencies == null) {
            return Collections.emptyList();
        }
        return row.modrinthVersion.dependencies.stream()
                .filter(d -> d != null && d.dependencyType == ModrinthDependencyType.REQUIRED && d.projectId != null
                        && !d.projectId.equalsIgnoreCase(row.modrinthId))
                .map((ModrinthDependency d) -> d.projectId).distinct().collect(Collectors.toList());
    }

    static List<Integer> requiredCurseForge(Row row, Map<Integer, CurseForgeFile> files) {
        CurseForgeFile file = row.platform == ModPlatform.CURSEFORGE && row.curseForgeFileId != null
                ? files.get(row.curseForgeFileId)
                : null;
        if (file == null || file.dependencies == null) {
            return Collections.emptyList();
        }
        return file.dependencies.stream()
                .filter(d -> d != null && d.isRequired() && d.modId > 0 && !Integer.valueOf(d.modId)
                        .equals(row.curseForgeId))
                .map((CurseForgeFileDependency d) -> d.modId).distinct().collect(Collectors.toList());
    }

    /**
     * Rows whose name contains the text, ignoring case.
     */
    public static List<Row> filter(List<Row> rows, String text) {
        String wanted = text == null ? "" : text.trim().toLowerCase(Locale.ENGLISH);
        return wanted.isEmpty() ? new ArrayList<>(rows)
                : rows.stream().filter(r -> r.name.toLowerCase(Locale.ENGLISH).contains(wanted))
                        .collect(Collectors.toList());
    }

    /**
     * How many rows there are of each status, for the line under the list.
     */
    public static Map<Status, Integer> counts(List<Row> rows) {
        Map<Status, Integer> counts = new LinkedHashMap<>();
        Arrays.stream(Status.values()).forEach(s -> counts.put(s, 0));
        rows.forEach(r -> counts.merge(r.status, 1, Integer::sum));
        return counts;
    }
}
