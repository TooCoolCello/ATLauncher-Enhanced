/*
 * ATLauncher - https://github.com/ATLauncher/ATLauncher
 * Copyright (C) 2013-2022 ATLauncher
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
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.atlauncher.App;
import com.atlauncher.Gsons;
import com.atlauncher.Network;
import com.atlauncher.constants.Constants;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthCategory;
import com.atlauncher.data.modrinth.ModrinthCollection;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthProjectType;
import com.atlauncher.data.modrinth.ModrinthSearchResult;
import com.atlauncher.data.modrinth.ModrinthVersion;
import com.atlauncher.managers.ConfigManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.network.NetworkClient;
import com.google.gson.reflect.TypeToken;

import okhttp3.CacheControl;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.RequestBody;

/**
 * Various utility methods for interacting with the Modrinth API.
 */
public class ModrinthApi {
    // search API limit: results per request
    public static final int MAX_SEARCH_LIMIT = 100;

    private static Headers getHeaders() {
        if (App.settings.modrinthApiKey == null || App.settings.modrinthApiKey.isEmpty()) {
            return null;
        }

        return Headers.of("Authorization", App.settings.modrinthApiKey);
    }

    public static ModrinthSearchResult searchModrinth(List<String> gameVersions, String query, int page, String index,
        List<List<String>> categories, ModrinthProjectType projectType) {
        return searchModrinth(gameVersions, query, page * Constants.MODRINTH_PAGINATION_SIZE,
            Constants.MODRINTH_PAGINATION_SIZE, index, categories, projectType);
    }

    /**
     * Searches from the given result offset for up to limit results (at most {@link #MAX_SEARCH_LIMIT}). Returns
     * null if the request failed.
     */
    public static ModrinthSearchResult searchModrinth(List<String> gameVersions, String query, int offset, int limit,
        String index, List<List<String>> categories, ModrinthProjectType projectType) {
        try {
            List<List<String>> facets = new ArrayList<>();

            String url = String.format(Locale.ENGLISH, "%s/search?limit=%d&offset=%d&query=%s&index=%s",
                Constants.MODRINTH_API_URL,
                limit, offset,
                URLEncoder.encode(query, StandardCharsets.UTF_8.name()), index);

            if (gameVersions != null && !gameVersions.isEmpty()) {
                facets.add(
                    gameVersions.stream().map(gv -> String.format("versions:%s", gv)).collect(Collectors.toList()));
            }

            if (categories != null) {
                categories.forEach(c -> facets
                    .add(c.stream().map(s -> String.format("categories:%s", s)).collect(Collectors.toList())));
            }

            if (projectType != null) {
                List<String> projectTypeFacets = new ArrayList<>();
                projectTypeFacets
                    .add(String.format("project_type:%s", projectType.toString().toLowerCase(Locale.ENGLISH)));
                facets.add(projectTypeFacets);
            }

            if (!facets.isEmpty()) {
                url += String.format("&facets=%s", Gsons.DEFAULT_SLIM.toJson(facets));
            }

            return NetworkClient.getCached(url, getHeaders(), ModrinthSearchResult.class,
                new CacheControl.Builder().maxStale(10, TimeUnit.MINUTES).build());
        } catch (UnsupportedEncodingException e) {
            LogManager.logStackTrace(e);
        }

        return null;
    }

    public static ModrinthSearchResult searchResourcePacks(List<String> gameVersions, String query, int page,
        String sort, String category) {
        return searchResourcePacks(gameVersions, query, page * Constants.MODRINTH_PAGINATION_SIZE,
            Constants.MODRINTH_PAGINATION_SIZE, sort, category);
    }

    public static ModrinthSearchResult searchResourcePacks(List<String> gameVersions, String query, int offset,
        int limit, String sort, String category) {
        List<List<String>> categories = category == null ? null
            : Collections.singletonList(Collections.singletonList(category));

        return searchModrinth(gameVersions, query, offset, limit, sort, categories, ModrinthProjectType.RESOURCEPACK);
    }

    public static ModrinthSearchResult searchShaders(List<String> gameVersions, String query, int page,
        String sort, String category) {
        return searchShaders(gameVersions, query, page * Constants.MODRINTH_PAGINATION_SIZE,
            Constants.MODRINTH_PAGINATION_SIZE, sort, category);
    }

    public static ModrinthSearchResult searchShaders(List<String> gameVersions, String query, int offset, int limit,
        String sort, String category) {
        List<List<String>> categories = category == null ? null
            : Collections.singletonList(Collections.singletonList(category));

        return searchModrinth(gameVersions, query, offset, limit, sort, categories, ModrinthProjectType.SHADER);
    }

    public static ModrinthSearchResult searchDataPacks(List<String> gameVersions, String query, int page,
        String sort, String category) {
        return searchDataPacks(gameVersions, query, page * Constants.MODRINTH_PAGINATION_SIZE,
            Constants.MODRINTH_PAGINATION_SIZE, sort, category);
    }

    public static ModrinthSearchResult searchDataPacks(List<String> gameVersions, String query, int offset, int limit,
        String sort, String category) {
        List<List<String>> categories = new ArrayList<>();

        categories.add(Collections.singletonList("datapack"));

        if (category != null) {
            categories.add(Collections.singletonList(category));
        }

        ModrinthSearchResult searchResult = searchModrinth(gameVersions, query, offset, limit, sort, categories,
            ModrinthProjectType.DATAPACK);

        if (searchResult == null || searchResult.totalHits == 0) {
            return searchModrinth(gameVersions, query, offset, limit, sort, categories, ModrinthProjectType.MOD);
        }

        return searchResult;
    }

    public static ModrinthSearchResult searchModsForForge(List<String> gameVersions, String query, int page,
        String sort, String category) {
        return searchModsForForge(gameVersions, query, page * Constants.MODRINTH_PAGINATION_SIZE,
            Constants.MODRINTH_PAGINATION_SIZE, sort, category);
    }

    public static ModrinthSearchResult searchModsForForge(List<String> gameVersions, String query, int offset,
        int limit, String sort, String category) {
        List<List<String>> categories = category == null ? Collections.singletonList(Collections.singletonList("forge"))
            : Arrays.asList(Collections.singletonList(category), Collections.singletonList("forge"));

        return searchModrinth(gameVersions, query, offset, limit, sort, categories, ModrinthProjectType.MOD);
    }

    public static ModrinthSearchResult searchModsForForgeOrFabric(List<String> gameVersions, String query, int page,
        String sort, String category) {
        return searchModsForForgeOrFabric(gameVersions, query, page * Constants.MODRINTH_PAGINATION_SIZE,
            Constants.MODRINTH_PAGINATION_SIZE, sort, category);
    }

    public static ModrinthSearchResult searchModsForForgeOrFabric(List<String> gameVersions, String query, int offset,
        int limit, String sort, String category) {
        List<List<String>> categories = category == null ? Arrays.asList(Arrays.asList("forge", "fabric"))
            : Arrays.asList(Arrays.asList(category), Arrays.asList("forge", "fabric"));

        return searchModrinth(gameVersions, query, offset, limit, sort, categories, ModrinthProjectType.MOD);
    }

    public static ModrinthSearchResult searchModsForNeoForge(List<String> gameVersions, String query, int page,
        String sort, String category) {
        return searchModsForNeoForge(gameVersions, query, page * Constants.MODRINTH_PAGINATION_SIZE,
            Constants.MODRINTH_PAGINATION_SIZE, sort, category);
    }

    public static ModrinthSearchResult searchModsForNeoForge(List<String> gameVersions, String query, int offset,
        int limit, String sort, String category) {
        List<List<String>> categories = new ArrayList<>();

        if (category != null) {
            categories.add(Collections.singletonList(category));
        }

        List<String> neoForgeForgeCompatabilityVersions = ConfigManager
            .getConfigItem("loaders.neoforge.forgeCompatibleMinecraftVersions", new ArrayList<>());
        if (gameVersions.stream().anyMatch(neoForgeForgeCompatabilityVersions::contains)) {
            categories.add(Arrays.asList("neoforge", "forge"));
        } else {
            categories.add(Collections.singletonList("neoforge"));
        }

        return searchModrinth(gameVersions, query, offset, limit, sort, categories, ModrinthProjectType.MOD);
    }

    public static ModrinthSearchResult searchModsForNeoForgeOrFabric(List<String> gameVersions, String query, int page,
        String sort, String category) {
        return searchModsForNeoForgeOrFabric(gameVersions, query, page * Constants.MODRINTH_PAGINATION_SIZE,
            Constants.MODRINTH_PAGINATION_SIZE, sort, category);
    }

    public static ModrinthSearchResult searchModsForNeoForgeOrFabric(List<String> gameVersions, String query,
        int offset, int limit, String sort, String category) {
        List<List<String>> categories = new ArrayList<>();

        if (category != null) {
            categories.add(Arrays.asList(category));
        }

        List<String> neoForgeForgeCompatabilityVersions = ConfigManager
            .getConfigItem("loaders.neoforge.forgeCompatibleMinecraftVersions", new ArrayList<String>());
        if (gameVersions.stream().anyMatch(gv -> neoForgeForgeCompatabilityVersions.contains(gv))) {
            categories.add(Arrays.asList("neoforge", "forge", "fabric"));
        } else {
            categories.add(Arrays.asList("neoforge", "fabric"));
        }

        return searchModrinth(gameVersions, query, offset, limit, sort, categories, ModrinthProjectType.MOD);
    }

    public static ModrinthSearchResult searchModsForFabric(List<String> gameVersions, String query, int page,
        String sort, String category) {
        return searchModsForFabric(gameVersions, query, page * Constants.MODRINTH_PAGINATION_SIZE,
            Constants.MODRINTH_PAGINATION_SIZE, sort, category);
    }

    public static ModrinthSearchResult searchModsForFabric(List<String> gameVersions, String query, int offset,
        int limit, String sort, String category) {
        List<List<String>> categories = category == null
            ? Collections.singletonList(Collections.singletonList("fabric"))
            : Arrays.asList(Collections.singletonList(category), Collections.singletonList("fabric"));

        return searchModrinth(gameVersions, query, offset, limit, sort, categories, ModrinthProjectType.MOD);
    }

    public static ModrinthSearchResult searchModsForLegacyFabric(List<String> gameVersions, String query, int page,
        String sort, String category) {
        return searchModsForLegacyFabric(gameVersions, query, page * Constants.MODRINTH_PAGINATION_SIZE,
            Constants.MODRINTH_PAGINATION_SIZE, sort, category);
    }

    public static ModrinthSearchResult searchModsForLegacyFabric(List<String> gameVersions, String query, int offset,
        int limit, String sort, String category) {
        List<List<String>> categories = category == null
            ? Collections.singletonList(Collections.singletonList("legacy-fabric"))
            : Arrays.asList(Collections.singletonList(category), Collections.singletonList("legacy-fabric"));

        return searchModrinth(gameVersions, query, offset, limit, sort, categories, ModrinthProjectType.MOD);
    }

    public static ModrinthSearchResult searchModsForQuilt(List<String> gameVersions, String query, int page,
        String sort, String category) {
        List<List<String>> categories = category == null ? Collections.singletonList(Collections.singletonList("quilt"))
            : Arrays.asList(Collections.singletonList(category), Collections.singletonList("quilt"));

        return searchModrinth(gameVersions, query, page, sort, categories, ModrinthProjectType.MOD);
    }

    public static ModrinthSearchResult searchModsForQuiltOrFabric(List<String> gameVersions, String query, int page,
        String sort, String category) {
        return searchModsForQuiltOrFabric(gameVersions, query, page * Constants.MODRINTH_PAGINATION_SIZE,
            Constants.MODRINTH_PAGINATION_SIZE, sort, category);
    }

    public static ModrinthSearchResult searchModsForQuiltOrFabric(List<String> gameVersions, String query, int offset,
        int limit, String sort, String category) {
        List<List<String>> categories = category == null ? Collections.singletonList(Arrays.asList("quilt", "fabric"))
            : Arrays.asList(Collections.singletonList(category), Arrays.asList("quilt", "fabric"));

        return searchModrinth(gameVersions, query, offset, limit, sort, categories, ModrinthProjectType.MOD);
    }

    public static ModrinthSearchResult searchModPacks(String minecraftVersion, String query, int page, String sort,
        String category) {
        List<List<String>> categories = category == null ? null
            : Collections.singletonList(Collections.singletonList(category));

        return searchModrinth(minecraftVersion == null ? null : Collections.singletonList(minecraftVersion), query,
            page, sort,
            categories, ModrinthProjectType.MODPACK);
    }

    public static ModrinthSearchResult searchPluginsForPaper(List<String> gameVersions, String query, int page,
        String sort, String category) {
        return searchPluginsForPaper(gameVersions, query, page * Constants.MODRINTH_PAGINATION_SIZE,
            Constants.MODRINTH_PAGINATION_SIZE, sort, category);
    }

    public static ModrinthSearchResult searchPluginsForPaper(List<String> gameVersions, String query, int offset,
        int limit, String sort, String category) {
        List<List<String>> categories = category == null
            ? Collections.singletonList(Arrays.asList("paper", "bukkit", "spigot"))
            : Arrays.asList(Collections.singletonList(category), Arrays.asList("paper", "bukkit", "spigot"));

        return searchModrinth(gameVersions, query, offset, limit, sort, categories, ModrinthProjectType.PLUGIN);
    }

    public static ModrinthSearchResult searchPluginsForPurpur(List<String> gameVersions, String query, int page,
        String sort, String category) {
        return searchPluginsForPurpur(gameVersions, query, page * Constants.MODRINTH_PAGINATION_SIZE,
            Constants.MODRINTH_PAGINATION_SIZE, sort, category);
    }

    public static ModrinthSearchResult searchPluginsForPurpur(List<String> gameVersions, String query, int offset,
        int limit, String sort, String category) {
        List<List<String>> categories = category == null
            ? Collections.singletonList(Arrays.asList("purpur", "paper", "bukkit", "spigot"))
            : Arrays.asList(Collections.singletonList(category),
                Arrays.asList("purpur", "paper", "bukkit", "spigot"));

        return searchModrinth(gameVersions, query, offset, limit, sort, categories, ModrinthProjectType.PLUGIN);
    }

    public static @Nullable ModrinthProject getProject(String projectId) {
        return NetworkClient.getCached(
            String.format("%s/project/%s", Constants.MODRINTH_API_URL, projectId.replace("local-", "")),
            getHeaders(), ModrinthProject.class,
            new CacheControl.Builder().maxStale(10, TimeUnit.MINUTES).build());
    }

    /**
     * A public collection, always fresh from Modrinth. The ID must already be checked to be only letters and digits.
     *
     * @throws IOException when Modrinth can't be reached, or a DownloadException with its status code when it answers
     *                     with an error, such as 404 for one that doesn't exist or is private
     */
    public static @Nullable ModrinthCollection getCollection(String collectionId) throws IOException {
        return NetworkClient.getWithThrow(
            String.format("%s/collection/%s", Constants.MODRINTH_API_V3_URL, collectionId), getHeaders(),
            ModrinthCollection.class);
    }

    /**
     * A creator's collections that this requester can see, always fresh from Modrinth: their listed ones with
     * projects in them, and with the launcher's Modrinth API key set, the key owner's own unlisted and private ones.
     * The username or ID must already be checked to be only letters, digits, _ and -.
     *
     * @throws IOException when Modrinth can't be reached, or a DownloadException with its status code when it answers
     *                     with an error, such as 404 for a creator that doesn't exist
     */
    public static @Nullable List<ModrinthCollection> getUserCollections(String user) throws IOException {
        java.lang.reflect.Type type = new TypeToken<List<ModrinthCollection>>() {
        }.getType();

        return NetworkClient.getWithThrow(
            String.format("%s/user/%s/collections", Constants.MODRINTH_API_V3_URL, user), getHeaders(), type);
    }

    public static @Nullable List<ModrinthVersion> getVersions(String projectId) {
        return getVersions(projectId, null, null);
    }

    @Nullable
    public static List<ModrinthVersion> getVersions(String projectId, String minecraftVersion,
        LoaderVersion loaderVersion) {
        return getVersionsForLoaders(projectId, minecraftVersion,
            loaderVersion == null ? null : loaderFilter(loaderVersion, minecraftVersion));
    }

    /**
     * A project's versions for a Minecraft version and any of these Modrinth loaders; either filter is skipped when
     * null. Null when the request fails.
     */
    @Nullable
    public static List<ModrinthVersion> getVersionsForLoaders(String projectId, String minecraftVersion,
        List<String> loaders) {
        String queryParamsString = "";

        if (minecraftVersion != null) {
            if (queryParamsString.isEmpty()) {
                queryParamsString += "?";
            }

            queryParamsString += String.format("game_versions=[\"%s\"]", minecraftVersion);
        }

        if (loaders != null) {
            if (queryParamsString.isEmpty()) {
                queryParamsString += "?";
            } else {
                queryParamsString += "&";
            }

            queryParamsString += String.format("loaders=%s", Gsons.DEFAULT_SLIM.toJson(loaders));
        }

        java.lang.reflect.Type type = new TypeToken<List<ModrinthVersion>>() {
        }.getType();

        return NetworkClient.get(
            String.format("%s/project/%s/version%s", Constants.MODRINTH_API_URL, projectId, queryParamsString),
            getHeaders(),
            type);
    }

    /**
     * The Modrinth loaders a loader's mods can be for: Forge as well on NeoForge's Forge-compatible Minecraft versions,
     * Fabric as well on Quilt. Empty for loaders Modrinth has no mods for.
     */
    public static List<String> loaderFilter(LoaderVersion loaderVersion, String minecraftVersion) {
        List<String> loaders = new ArrayList<>();

        if (loaderVersion.isForge()) {
            loaders.add("forge");
        } else if (loaderVersion.isNeoForge()) {
            List<String> neoForgeForgeCompatabilityVersions = ConfigManager
                .getConfigItem("loaders.neoforge.forgeCompatibleMinecraftVersions", new ArrayList<>());
            if (neoForgeForgeCompatabilityVersions.contains(minecraftVersion)) {
                loaders.add("forge");
            }

            loaders.add("neoforge");
        } else if (loaderVersion.isFabric()) {
            loaders.add("fabric");
        } else if (loaderVersion.isQuilt()) {
            loaders.add("fabric");
            loaders.add("quilt");
        }

        return loaders;
    }

    /**
     * For each installed file (by SHA-1), its project's newest version for this Minecraft version and these loaders,
     * in one request. Hashes with no such version are left out. Null when the request fails.
     */
    public static @Nullable Map<String, ModrinthVersion> getLatestVersionsFromSha1Hashes(String[] hashes,
        List<String> loaders, String minecraftVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("hashes", hashes);
        body.put("algorithm", "sha1");
        body.put("loaders", loaders);
        body.put("game_versions", Collections.singletonList(minecraftVersion));

        java.lang.reflect.Type type = new TypeToken<Map<String, ModrinthVersion>>() {
        }.getType();

        return NetworkClient.post(
            String.format("%s/version_files/update", Constants.MODRINTH_API_URL),
            getHeaders(), RequestBody.create(Gsons.DEFAULT_SLIM.toJson(body),
                MediaType.get("application/json; charset=utf-8")),
            type);
    }

    public static @Nonnull List<ModrinthCategory> getCategories() {
        java.lang.reflect.Type type = new TypeToken<List<ModrinthCategory>>() {
        }.getType();

        List<ModrinthCategory> categories = NetworkClient.getCached(
            String.format("%s/tag/category", Constants.MODRINTH_API_URL),
            getHeaders(), type,
            new CacheControl.Builder().maxStale(1, TimeUnit.HOURS).build());

        if (categories == null) {
            Network.removeUrlFromCache(String.format("%s/tag/category", Constants.MODRINTH_API_URL));
            categories = new ArrayList<>();
        }

        return categories;
    }

    public static List<ModrinthCategory> getCategoriesForModpacks() {
        List<ModrinthCategory> categories = getCategories();

        return categories.stream().filter(c -> c.projectType == ModrinthProjectType.MODPACK)
            .sorted(Comparator.comparing(c -> c.name)).collect(Collectors.toList());
    }

    public static List<ModrinthCategory> getCategoriesForMods() {
        List<ModrinthCategory> categories = getCategories();

        return categories.stream().filter(c -> c.projectType == ModrinthProjectType.MOD)
            .sorted(Comparator.comparing(c -> c.name)).collect(Collectors.toList());
    }

    public static List<ModrinthCategory> getCategoriesForPlugins() {
        List<ModrinthCategory> categories = getCategories();

        return categories.stream().filter(c -> c.projectType == ModrinthProjectType.PLUGIN)
            .sorted(Comparator.comparing(c -> c.name)).collect(Collectors.toList());
    }

    public static List<ModrinthCategory> getCategoriesForShaders() {
        List<ModrinthCategory> categories = getCategories();

        return categories.stream().filter(c -> c.projectType == ModrinthProjectType.SHADER)
            .sorted(Comparator.comparing(c -> c.name)).collect(Collectors.toList());
    }

    public static List<ModrinthCategory> getCategoriesForResourcePacks() {
        List<ModrinthCategory> categories = getCategories();

        return categories.stream().filter(c -> c.projectType == ModrinthProjectType.RESOURCEPACK)
            .sorted(Comparator.comparing(c -> c.name)).collect(Collectors.toList());
    }

    public static List<ModrinthCategory> getCategoriesForDataPacks() {
        List<ModrinthCategory> categories = getCategories();

        return categories.stream()
            .filter(c -> c.projectType == ModrinthProjectType.MOD || c.projectType == ModrinthProjectType.DATAPACK)
            .collect(Collectors.toMap(c -> c.name, c -> c, (existing, replacement) -> existing))
            .values().stream()
            .sorted(Comparator.comparing(c -> c.name))
            .collect(Collectors.toList());
    }

    public static ModrinthVersion getVersionFromSha1Hash(String hash) {
        return getVersionFromHash(hash, "sha1");
    }

    public static ModrinthVersion getVersionFromSha512Hash(String hash) {
        return getVersionFromHash(hash, "sha512");
    }

    private static ModrinthVersion getVersionFromHash(String hash, String algorithm) {
        return NetworkClient.getCached(
            String.format("%s/version_file/%s?algorithm=%s", Constants.MODRINTH_API_URL, hash,
                algorithm),
            getHeaders(), ModrinthVersion.class,
            new CacheControl.Builder().maxStale(10, TimeUnit.MINUTES).build());
    }

    public static Map<String, ModrinthVersion> getVersionsFromSha1Hashes(String[] hashes) {
        return getVersionsFromHashes(hashes, "sha1");
    }

    public static Map<String, ModrinthVersion> getVersionsFromSha512Hashes(String[] hashes) {
        return getVersionsFromHashes(hashes, "sha512");
    }

    private static Map<String, ModrinthVersion> getVersionsFromHashes(String[] hashes, String algorithm) {
        if (hashes.length == 0) {
            return new HashMap<>();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("hashes", hashes);
        body.put("algorithm", algorithm);

        java.lang.reflect.Type type = new TypeToken<Map<String, ModrinthVersion>>() {
        }.getType();

        Map<String, ModrinthVersion> versions = NetworkClient.post(
            String.format("%s/version_files", Constants.MODRINTH_API_URL),
            getHeaders(), RequestBody.create(Gsons.DEFAULT_SLIM.toJson(body),
                MediaType.get("application/json; charset=utf-8")),
            type);

        if (versions == null) {
            return new HashMap<>();
        }

        return versions;
    }

    public static List<ModrinthProject> getProjects(String[] projectIds) {
        java.lang.reflect.Type type = new TypeToken<List<ModrinthProject>>() {
        }.getType();

        return NetworkClient.getCached(
            String.format("%s/projects?ids=%s", Constants.MODRINTH_API_URL,
                Gsons.DEFAULT_SLIM.toJson(projectIds)),
            getHeaders(),
            type, new CacheControl.Builder().maxStale(10, TimeUnit.MINUTES).build());
    }

    public static Map<String, ModrinthProject> getProjectsAsMap(String[] projectIds) {
        try {
            List<ModrinthProject> projects = getProjects(projectIds);

            if (projects != null) {
                return projects.stream().distinct()
                    .collect(Collectors.toMap(p -> p.id, p -> p, (existing, replacement) -> existing));
            }
        } catch (Throwable t) {
            LogManager.logStackTrace("Error trying to get Modrinth projects as map", t);
        }

        return null;
    }
}
