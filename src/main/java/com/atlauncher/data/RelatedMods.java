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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.atlauncher.constants.Constants;
import com.atlauncher.data.curseforge.CurseForgeFile;
import com.atlauncher.data.curseforge.CurseForgeFileDependency;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.modrinth.ModrinthDependency;
import com.atlauncher.data.modrinth.ModrinthDependencyType;
import com.atlauncher.data.modrinth.ModrinthVersion;

/**
 * What the Related window asks for a project. Neither site lists similar projects, so it's built from what they do
 * have: the same author's projects, a search for addons among mods for this instance, and the dependencies of the file
 * Add Mods would offer here.
 */
public final class RelatedMods {
    // a Modrinth username, which goes in a request path
    private static final Pattern MODRINTH_USER = Pattern.compile("[0-9A-Za-z_-]{1,39}");

    private RelatedMods() {
    }

    /**
     * What's searched for a project's addons: its name and "addon", which is how most addons describe themselves.
     */
    public static String addonQuery(String name) {
        return (name == null ? "" : name.trim()) + " addon";
    }

    /**
     * The Modrinth loaders searched: those native to the instance, and Fabric while Connector is active, as Add Mods
     * searches.
     */
    public static List<String> modrinthSearchLoaders(ModCompatibility.Target target) {
        List<String> tags = new ArrayList<>(ModCompatibility.nativeTags(target.loader, target.forgeCompatible));
        if (target.connectorActive && !tags.contains("fabric")) {
            tags.add("fabric");
        }
        return tags;
    }

    /**
     * The same loaders as CurseForge's mod loader types.
     */
    public static List<Integer> curseForgeLoaderTypes(ModCompatibility.Target target) {
        List<Integer> types = new ArrayList<>();
        for (String tag : modrinthSearchLoaders(target)) {
            switch (tag) {
                case "forge":
                    types.add(Constants.CURSEFORGE_FORGE_MODLOADER_ID);
                    break;
                case "neoforge":
                    types.add(Constants.CURSEFORGE_NEOFORGE_MODLOADER_ID);
                    break;
                case "fabric":
                    types.add(Constants.CURSEFORGE_FABRIC_MODLOADER_ID);
                    break;
                case "quilt":
                    types.add(Constants.CURSEFORGE_QUILT_MODLOADER_ID);
                    break;
                default:
                    break;
            }
        }
        return types;
    }

    /**
     * Like "Minecraft 1.20.1 NeoForge", for the notes.
     */
    public static String describe(ModCompatibility.Target target) {
        return target.describe(true);
    }

    public static String minecraftVersion(ModCompatibility.Target target) {
        return target.minecraftVersion;
    }

    /**
     * The loaders to ask Modrinth for a project's versions here, as Add Mods' marks ask.
     */
    public static List<String> versionLoaders(ModCompatibility.Target target) {
        return ModCompatibility.modrinthLoaderFilter(target, "Mods");
    }

    /**
     * The username, if it's one that can go in a request.
     */
    public static String modrinthUser(String author) {
        return author != null && MODRINTH_USER.matcher(author).matches() ? author : null;
    }

    /**
     * The projects that list this author. CurseForge's search is asked for only theirs, but one that ignored the
     * filter would answer with everyone's most popular projects, which mustn't be shown as the author's.
     */
    public static List<CurseForgeProject> byCurseForgeAuthor(List<CurseForgeProject> projects, int authorId) {
        return projects.stream().filter(p -> p != null && p.authors != null
                && p.authors.stream().anyMatch(a -> a != null && a.id == authorId)).collect(Collectors.toList());
    }

    /**
     * The version whose dependencies are shown: the newest native one for this Minecraft version, else the newest
     * Fabric one while Connector is active, as the selector would offer. Null when there's none.
     *
     * @param versions newest first, as Modrinth lists them
     */
    public static ModrinthVersion versionForDependencies(List<ModrinthVersion> versions,
            ModCompatibility.Target target) {
        if (versions == null) {
            return null;
        }

        List<String> nativeTags = ModCompatibility.nativeTags(target.loader, target.forgeCompatible);
        ModrinthVersion fabric = null;
        for (ModrinthVersion version : versions) {
            if (version == null || version.gameVersions == null
                    || !version.gameVersions.contains(target.minecraftVersion) || version.loaders == null) {
                continue;
            }
            if (version.loaders.stream().anyMatch(l -> l != null
                    && nativeTags.contains(l.toLowerCase(Locale.ENGLISH)))) {
                return version;
            }
            if (fabric == null && target.connectorActive && version.loaders.contains("fabric")) {
                fabric = version;
            }
        }
        return fabric;
    }

    /**
     * A file's dependencies, required first, without the project itself or repeats.
     */
    public static final class Needs<T> {
        public final List<T> required = new ArrayList<>();
        public final List<T> optional = new ArrayList<>();

        void add(T id, boolean isRequired) {
            if (id == null || required.contains(id) || optional.contains(id)) {
                return;
            }
            (isRequired ? required : optional).add(id);
        }

        public List<T> all() {
            List<T> all = new ArrayList<>(required);
            all.addAll(optional);
            return all;
        }
    }

    public static Needs<String> modrinthNeeds(ModrinthVersion version, String projectId) {
        Needs<String> needs = new Needs<>();
        List<ModrinthDependency> dependencies = version == null || version.dependencies == null
                ? Collections.emptyList()
                : version.dependencies;
        // required ones first, so one listed as both is shown as required; incompatible and embedded ones aren't
        // known to the enum, so they're null and left out
        for (boolean required : new boolean[] { true, false }) {
            for (ModrinthDependency dependency : dependencies) {
                if (dependency != null && dependency.projectId != null
                        && !dependency.projectId.equalsIgnoreCase(projectId) && dependency.dependencyType == (required
                                ? ModrinthDependencyType.REQUIRED
                                : ModrinthDependencyType.OPTIONAL)) {
                    needs.add(dependency.projectId, required);
                }
            }
        }
        return needs;
    }

    public static Needs<Integer> curseForgeNeeds(CurseForgeFile file, int projectId) {
        Needs<Integer> needs = new Needs<>();
        List<CurseForgeFileDependency> dependencies = file == null || file.dependencies == null
                ? Collections.emptyList()
                : file.dependencies;
        for (boolean required : new boolean[] { true, false }) {
            for (CurseForgeFileDependency dependency : dependencies) {
                // only required and optional; embedded, tools and incompatible ones aren't for adding
                if (dependency != null && dependency.modId > 0 && dependency.modId != projectId
                        && (required ? dependency.isRequired() : dependency.isOptional())) {
                    needs.add(dependency.modId, required);
                }
            }
        }
        return needs;
    }
}
