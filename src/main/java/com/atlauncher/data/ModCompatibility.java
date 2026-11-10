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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.constants.Constants;
import com.atlauncher.data.curseforge.CurseForgeGameVersionLatestFiles;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthVersion;
import com.atlauncher.managers.ConfigManager;

/**
 * Whether a project has a file that actually runs on an instance, judged from file data (CurseForge's latest-file
 * index, Modrinth's version list), never from the loaders a project reports as a whole.
 *
 * <p>
 * Entry points: build a {@link Target} with {@link Target#of(ModManagement)}, then call
 * {@link #forCurseForgeProject} or {@link #forModrinthVersions} with the project's data and the Add Mods section it's
 * shown in ("Mods", "Plugins", "Data Packs"...). {@link ModCompatibilityChecker} fetches Modrinth versions for search
 * results in the background.
 */
public final class ModCompatibility {
    public enum State {
        /** A native file exists for this Minecraft version and loader (or platform). */
        COMPATIBLE,
        /** Only a Fabric file exists, and an enabled Sinytra Connector would load it. */
        VIA_CONNECTOR,
        /** No file for this Minecraft version and loader. */
        NOT_AVAILABLE,
        /** Being checked. */
        CHECKING,
        /** Couldn't be checked, or the data doesn't settle it. */
        UNKNOWN
    }

    public static final class Verdict {
        public final State state;
        /** What the verdict is based on, for a tooltip. */
        public final String reason;

        Verdict(State state, String reason) {
            this.state = state;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return state + ": " + reason;
        }
    }

    /**
     * What a project is checked against: the instance's Minecraft version, its loader or server platform, and whether
     * Connector can load Fabric files on it.
     */
    public static final class Target {
        final String minecraftVersion;
        final LoaderVersion loader;
        final boolean forgeCompatible;
        final boolean connectorActive;

        /**
         * @param forgeCompatible whether NeoForge still loads Forge files on this Minecraft version
         * @param connectorActive whether Add Mods offers Fabric files through Connector here
         */
        public Target(String minecraftVersion, LoaderVersion loader, boolean forgeCompatible,
                boolean connectorActive) {
            this.minecraftVersion = minecraftVersion;
            this.loader = loader;
            this.forgeCompatible = forgeCompatible;
            this.connectorActive = connectorActive;
        }

        /**
         * Reads the instance's mods, so call it on the event dispatch thread.
         */
        public static Target of(ModManagement instanceOrServer) {
            List<String> forgeCompatibleVersions = ConfigManager
                    .getConfigItem("loaders.neoforge.forgeCompatibleMinecraftVersions", new ArrayList<>());
            String minecraftVersion = instanceOrServer.getMinecraftVersion();
            // the same rule the selectors use to offer Connector builds
            return new Target(minecraftVersion, instanceOrServer.getLoaderVersion(),
                    forgeCompatibleVersions.contains(minecraftVersion),
                    instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector());
        }

        String describe(boolean checkLoader) {
            return checkLoader && loader != null
                    // #. {0} is the Minecraft version, {1} the loader or server platform (e.g. NeoForge)
                    ? GetText.tr("Minecraft {0} {1}", minecraftVersion, loader.getLoaderType())
                    : GetText.tr("Minecraft {0}", minecraftVersion);
        }
    }

    /**
     * One file's Minecraft versions and loader tags. Tags are matched ignoring case, so CurseForge's ("NeoForge") and
     * Modrinth's ("neoforge") both work.
     */
    static final class FileFacts {
        final Collection<String> gameVersions;
        final Collection<String> loaders;

        FileFacts(Collection<String> gameVersions, Collection<String> loaders) {
            this.gameVersions = gameVersions == null ? Collections.emptyList() : gameVersions;
            this.loaders = loaders == null ? Collections.emptyList() : loaders;
        }
    }

    // tags that name a loader or server platform; a file with none of them doesn't say what it runs on
    private static final List<String> KNOWN_LOADER_TAGS = Arrays.asList("forge", "neoforge", "fabric", "quilt",
            "legacy-fabric", "liteloader", "cauldron", "paper", "purpur", "bukkit", "spigot", "folia");

    private static final Map<String, String> TAG_NAMES = new HashMap<>();

    static {
        TAG_NAMES.put("forge", "Forge");
        TAG_NAMES.put("neoforge", "NeoForge");
        TAG_NAMES.put("fabric", "Fabric");
        TAG_NAMES.put("quilt", "Quilt");
        TAG_NAMES.put("legacy-fabric", "Legacy Fabric");
        TAG_NAMES.put("paper", "Paper");
        TAG_NAMES.put("purpur", "Purpur");
        TAG_NAMES.put("bukkit", "Bukkit");
        TAG_NAMES.put("spigot", "Spigot");
    }

    private ModCompatibility() {
    }

    /**
     * Whether "Hide incompatible" hides a result: only one known to have no file. Unchecked, unknown and Connector
     * results stay.
     */
    public static boolean hiddenWhenFiltering(boolean hideIncompatible, State state) {
        return hideIncompatible && state == State.NOT_AVAILABLE;
    }

    /**
     * CurseForge's verdict from the search result alone: its latest-file index lists the newest file for every game
     * version and loader, so it settles every case without another request. Null where nothing is checked: resource
     * packs, shaders, worlds, and plugins, whose CurseForge files name no server platform.
     */
    public static Verdict forCurseForgeProject(Target target, String section, CurseForgeProject project) {
        if (!"Mods".equals(section) && !"Data Packs".equals(section)) {
            return null;
        }

        boolean checkLoader = "Mods".equals(section);
        if (checkLoader && target.loader == null) {
            return null;
        }

        if (project == null || project.latestFilesIndexes == null || project.latestFilesIndexes.isEmpty()) {
            return new Verdict(State.UNKNOWN, GetText.tr("CurseForge gave no file data for this project, so it "
                    + "wasn't checked against {0}.", target.describe(checkLoader)));
        }

        return judge(target, checkLoader, true, "CurseForge", curseForgeFacts(project.latestFilesIndexes));
    }

    static List<FileFacts> curseForgeFacts(List<CurseForgeGameVersionLatestFiles> index) {
        return index.stream().filter(f -> f != null && f.gameVersion != null)
                .map(f -> new FileFacts(Collections.singletonList(f.gameVersion), curseForgeLoaderTags(f.modLoader)))
                .collect(Collectors.toList());
    }

    private static List<String> curseForgeLoaderTags(int modLoader) {
        switch (modLoader) {
            case Constants.CURSEFORGE_FORGE_MODLOADER_ID:
                return Collections.singletonList("forge");
            case Constants.CURSEFORGE_FABRIC_MODLOADER_ID:
                return Collections.singletonList("fabric");
            case Constants.CURSEFORGE_QUILT_MODLOADER_ID:
                return Collections.singletonList("quilt");
            case Constants.CURSEFORGE_NEOFORGE_MODLOADER_ID:
                return Collections.singletonList("neoforge");
            case 2:
                return Collections.singletonList("cauldron");
            case 3:
                return Collections.singletonList("liteloader");
            default:
                // 0 is "any": the file isn't tagged with a loader
                return Collections.emptyList();
        }
    }

    /**
     * Modrinth's verdict from a project's versions, as many as given: the full list, or one already narrowed to this
     * Minecraft version. Null versions mean the request failed. Null where nothing is checked: resource packs and
     * shaders.
     */
    public static Verdict forModrinthVersions(Target target, String section, List<ModrinthVersion> versions) {
        return forModrinthFacts(target, section, versions == null ? null : modrinthFacts(versions));
    }

    static List<FileFacts> modrinthFacts(List<ModrinthVersion> versions) {
        return versions.stream().filter(v -> v != null).map(v -> new FileFacts(v.gameVersions, v.loaders))
                .collect(Collectors.toList());
    }

    static Verdict forModrinthFacts(Target target, String section, List<FileFacts> facts) {
        Boolean checkLoader = modrinthChecksLoader(target, section);
        if (checkLoader == null) {
            return null;
        }

        if (facts == null) {
            return checkFailed(target, section);
        }

        // a data pack's files are the versions marked as one; a project can also have mod versions
        List<FileFacts> files = checkLoader ? facts
                : facts.stream().filter(f -> hasAny(f.loaders, Collections.singletonList("datapack")))
                        .collect(Collectors.toList());

        // Modrinth versions always name their loaders; the selector never offers one that doesn't
        return judge(target, checkLoader, false, "Modrinth", files);
    }

    /**
     * What a Modrinth search result's project-wide game versions and loaders already settle: those are the union of
     * its versions', so a Minecraft version or loader missing from them means no version has it. Null where nothing
     * is checked, {@link State#CHECKING} where its versions must be fetched.
     *
     * @param categories the search result's categories, which include its loaders
     */
    static Verdict modrinthProjectLevel(Target target, String section, List<String> gameVersions,
            List<String> categories) {
        Boolean checkLoader = modrinthChecksLoader(target, section);
        if (checkLoader == null) {
            return null;
        }

        String describe = target.describe(checkLoader);
        if (gameVersions != null && !gameVersions.isEmpty() && !gameVersions.contains(target.minecraftVersion)) {
            return notAvailable(describe, "Modrinth");
        }

        // only when the categories name some loader, so a result without them isn't ruled out
        if (checkLoader && hasAny(categories, KNOWN_LOADER_TAGS) && !hasAny(categories, acceptedTags(target))) {
            return notAvailable(describe, "Modrinth");
        }

        // #. {0} is the platform (Modrinth), {1} is like "Minecraft 1.20.1 NeoForge"
        return new Verdict(State.CHECKING, GetText.tr("Checking {0} for a file for {1}...", "Modrinth", describe));
    }

    /**
     * The loaders to ask Modrinth for: the instance's own, Fabric too on Forge and NeoForge so the answer holds
     * whether or not Connector is active later, or data packs. Empty where nothing is checked.
     */
    static List<String> modrinthLoaderFilter(Target target, String section) {
        Boolean checkLoader = modrinthChecksLoader(target, section);
        if (checkLoader == null) {
            return Collections.emptyList();
        }

        if (!checkLoader) {
            return Collections.singletonList("datapack");
        }

        List<String> tags = new ArrayList<>(nativeTags(target.loader, target.forgeCompatible));
        if ((target.loader.isForge() || target.loader.isNeoForge()) && !tags.contains("fabric")) {
            tags.add("fabric");
        }
        return tags;
    }

    static Verdict checkFailed(Target target, String section) {
        Boolean checkLoader = modrinthChecksLoader(target, section);
        // #. {0} is the platform (Modrinth), {1} is like "Minecraft 1.20.1 NeoForge"
        return new Verdict(State.UNKNOWN, GetText.tr("Couldn't check {0} for a file for {1}.", "Modrinth",
                target.describe(checkLoader != null && checkLoader)));
    }

    // whether a Modrinth section's check includes the loader (true) or only the Minecraft version (false); null when
    // it isn't checked
    private static Boolean modrinthChecksLoader(Target target, String section) {
        if ("Mods".equals(section) || "Plugins".equals(section)) {
            return target.loader == null ? null : Boolean.TRUE;
        }

        return "Data Packs".equals(section) ? Boolean.FALSE : null;
    }

    /**
     * The verdict from files' Minecraft versions and loader tags: compatible when a file for this Minecraft version
     * is native to the loader; via Connector when the only such files are Fabric ones and Connector is active; not
     * available otherwise. Only this exact Minecraft version counts.
     *
     * @param checkLoader    whether the loader matters, or only the Minecraft version (data packs)
     * @param untaggedMayRun whether a file tagged with no loader might still run, as CurseForge's selector assumes
     */
    static Verdict judge(Target target, boolean checkLoader, boolean untaggedMayRun, String provider,
            List<FileFacts> files) {
        String describe = target.describe(checkLoader);
        List<FileFacts> forVersion = files.stream().filter(f -> f.gameVersions.contains(target.minecraftVersion))
                .collect(Collectors.toList());

        if (!checkLoader) {
            return forVersion.isEmpty() ? notAvailable(describe, provider)
                    // #. {0} is the platform (CurseForge/Modrinth), {1} is the Minecraft version
                    : new Verdict(State.COMPATIBLE, GetText.tr("{0} has a file for Minecraft {1}.", provider,
                            target.minecraftVersion));
        }

        List<String> nativeTags = nativeTags(target.loader, target.forgeCompatible);
        for (FileFacts file : forVersion) {
            for (String tag : nativeTags) {
                if (hasAny(file.loaders, Collections.singletonList(tag))) {
                    return new Verdict(State.COMPATIBLE,
                            // #. {0} is the platform, {1} the Minecraft version, {2} a loader such as Forge
                            GetText.tr("{0} has a file for Minecraft {1} marked {2}.", provider,
                                    target.minecraftVersion, TAG_NAMES.getOrDefault(tag, tag)));
                }
            }
        }

        if (target.connectorActive
                && forVersion.stream().anyMatch(f -> hasAny(f.loaders, Collections.singletonList("fabric")))) {
            // #. {0} is the platform, {1} the Minecraft version
            return new Verdict(State.VIA_CONNECTOR, GetText.tr("{0} only has a Fabric file for Minecraft {1}.",
                    provider, target.minecraftVersion) + " "
                    + GetText.tr("Uses Sinytra Connector; compatibility is not guaranteed."));
        }

        if (untaggedMayRun && forVersion.stream().anyMatch(f -> !hasAny(f.loaders, KNOWN_LOADER_TAGS))) {
            // #. {0} is the platform, {1} the Minecraft version
            return new Verdict(State.UNKNOWN, GetText.tr("{0} has a file for Minecraft {1}, but it doesn't say "
                    + "which loader it's for.", provider, target.minecraftVersion));
        }

        return notAvailable(describe, provider);
    }

    /**
     * The tags a file needs to run on this loader or platform without Connector, as the selectors accept them: Fabric
     * files on Quilt and Legacy Fabric, Forge ones on NeoForge's Forge-compatible versions, and Bukkit-family plugins
     * on Paper and Purpur.
     */
    public static List<String> nativeTags(LoaderVersion loader, boolean forgeCompatible) {
        if (loader == null) {
            return Collections.emptyList();
        }

        if (loader.isFabric()) {
            return Collections.singletonList("fabric");
        }

        if (loader.isLegacyFabric()) {
            return Arrays.asList("legacy-fabric", "fabric");
        }

        if (loader.isQuilt()) {
            return Arrays.asList("quilt", "fabric");
        }

        if (loader.isNeoForge()) {
            return forgeCompatible ? Arrays.asList("neoforge", "forge") : Collections.singletonList("neoforge");
        }

        if (loader.isForge()) {
            return Collections.singletonList("forge");
        }

        if (loader.isPaper()) {
            return Arrays.asList("paper", "bukkit", "spigot");
        }

        if (loader.isPurpur()) {
            return Arrays.asList("purpur", "paper", "bukkit", "spigot");
        }

        return Collections.emptyList();
    }

    // native tags, plus Fabric while Connector is active
    private static List<String> acceptedTags(Target target) {
        List<String> tags = new ArrayList<>(nativeTags(target.loader, target.forgeCompatible));
        if (target.connectorActive) {
            tags.add("fabric");
        }
        return tags;
    }

    private static Verdict notAvailable(String describe, String provider) {
        // #. {0} is like "Minecraft 1.20.1 NeoForge", {1} the platform (CurseForge/Modrinth)
        return new Verdict(State.NOT_AVAILABLE, GetText.tr("Not available for {0}: {1} lists no file for it.",
                describe, provider));
    }

    private static boolean hasAny(Collection<String> tags, Collection<String> wanted) {
        return tags != null && tags.stream()
                .anyMatch(t -> t != null && wanted.contains(t.toLowerCase(Locale.ENGLISH)));
    }
}
