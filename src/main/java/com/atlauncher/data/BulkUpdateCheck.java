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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.atlauncher.App;
import com.atlauncher.data.DisableableMod.UpdateStatus;
import com.atlauncher.data.curseforge.CurseForgeGameVersionLatestFiles;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthVersion;
import com.atlauncher.exceptions.InvalidMinecraftVersion;
import com.atlauncher.managers.ConfigManager;
import com.atlauncher.managers.MinecraftManager;
import com.atlauncher.utils.CurseForgeApi;
import com.atlauncher.utils.ModrinthApi;

/**
 * Update checks for a whole mod list in a few requests: one for every CurseForge project, one per loader group for
 * Modrinth. They settle only what they can be sure of; {@link DisableableMod#fetchUpdateStatus} decides the rest, so
 * the results always match Check For Updates.
 */
public final class BulkUpdateCheck {
    private BulkUpdateCheck() {
    }

    /**
     * The statuses the batch requests settle. Mods left out need the full per-mod check. Makes network requests, so
     * call it off the event dispatch thread.
     */
    public static Map<DisableableMod, UpdateStatus> settle(ModManagement instanceOrServer,
            Collection<DisableableMod> mods) {
        Map<DisableableMod, UpdateStatus> settled = new HashMap<>();

        settleCurseForge(instanceOrServer, mods.stream()
                .filter(m -> m.defaultUpdatePlatform() == ModPlatform.CURSEFORGE).collect(Collectors.toList()),
                settled);
        settleModrinth(instanceOrServer, mods.stream()
                .filter(m -> m.defaultUpdatePlatform() == ModPlatform.MODRINTH).collect(Collectors.toList()),
                settled);

        return settled;
    }

    private static void settleCurseForge(ModManagement instanceOrServer, List<DisableableMod> mods,
            Map<DisableableMod, UpdateStatus> settled) {
        if (mods.isEmpty()) {
            return;
        }

        Map<Integer, CurseForgeProject> projects = CurseForgeApi
                .getProjectsAsMap(mods.stream().mapToInt(m -> m.curseForgeProjectId).distinct().toArray());
        if (projects == null) {
            return;
        }

        Predicate<String> inRestriction = gameVersionsInRestriction(instanceOrServer.getMinecraftVersion());
        for (DisableableMod mod : mods) {
            CurseForgeProject project = projects.get(mod.curseForgeProjectId);

            if (project != null && project.latestFilesIndexes != null && !project.latestFilesIndexes.isEmpty()
                    && !mayHaveNewerFile(mod.curseForgeFileId, project.latestFilesIndexes, inRestriction)) {
                settled.put(mod, UpdateStatus.UP_TO_DATE);
            }
        }
    }

    /**
     * Whether the project's latest file for any game version the restriction allows is newer than the installed one.
     * CurseForge lists the latest file for each game version and loader, so when none of those is newer, no newer file
     * passes the restriction at all, and there's no update whatever the loader rules.
     */
    static boolean mayHaveNewerFile(int installedFileId, List<CurseForgeGameVersionLatestFiles> latestFiles,
            Predicate<String> inRestriction) {
        return latestFiles.stream().anyMatch(f -> f != null && f.fileId > installedFileId
                && (f.gameVersion == null || inRestriction.test(f.gameVersion)));
    }

    // the game versions the Add Mod Restriction lets an update be for, as hasCurseForgeUpdate filters them
    private static Predicate<String> gameVersionsInRestriction(String minecraftVersion) {
        if (App.settings.addModRestriction == AddModRestriction.STRICT) {
            return v -> v.equals(minecraftVersion);
        }

        if (App.settings.addModRestriction == AddModRestriction.LAX) {
            try {
                Set<String> family = MinecraftManager.getMajorMinecraftVersions(minecraftVersion).stream()
                        .map(mv -> mv.id).collect(Collectors.toSet());
                return family::contains;
            } catch (InvalidMinecraftVersion e) {
                // hasCurseForgeUpdate doesn't filter then either
                return v -> true;
            }
        }

        return v -> true;
    }

    private static void settleModrinth(ModManagement instanceOrServer, List<DisableableMod> mods,
            Map<DisableableMod, UpdateStatus> settled) {
        LoaderVersion loader = instanceOrServer.getLoaderVersion();
        if (mods.isEmpty() || loader == null) {
            return;
        }

        List<String> neoForgeForgeCompatibleVersions = ConfigManager
                .getConfigItem("loaders.neoforge.forgeCompatibleMinecraftVersions", new ArrayList<>());
        boolean connectorActive = instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector();

        // grouped by the loaders their updates can be for: the instance's own, or Fabric through Connector
        Map<String, DisableableMod> nativeByHash = new HashMap<>();
        Map<String, DisableableMod> connectorByHash = new HashMap<>();
        Map<DisableableMod, SinytraConnector.InstalledPath> paths = new HashMap<>();

        for (DisableableMod mod : mods) {
            String sha1 = mod.installedModrinthSha1();
            if (sha1 == null || mod.modrinthVersion.loaders == null || mod.modrinthVersion.loaders.isEmpty()) {
                continue;
            }

            SinytraConnector.InstalledPath path = mod.modrinthInstalledPath(instanceOrServer, null,
                    neoForgeForgeCompatibleVersions);
            if (path == SinytraConnector.InstalledPath.CONNECTOR && !connectorActive) {
                // a Connector install only updates while Connector is active, as the full check finds too
                settled.put(mod, UpdateStatus.UP_TO_DATE);
                continue;
            }

            paths.put(mod, path);
            (path == SinytraConnector.InstalledPath.CONNECTOR ? connectorByHash : nativeByHash).putIfAbsent(sha1, mod);
        }

        settleModrinthGroup(instanceOrServer, nativeByHash,
                ModrinthApi.loaderFilter(loader, instanceOrServer.getMinecraftVersion()), paths,
                neoForgeForgeCompatibleVersions, settled);
        settleModrinthGroup(instanceOrServer, connectorByHash, Collections.singletonList("fabric"), paths,
                neoForgeForgeCompatibleVersions, settled);
    }

    private static void settleModrinthGroup(ModManagement instanceOrServer, Map<String, DisableableMod> byHash,
            List<String> loaders, Map<DisableableMod, SinytraConnector.InstalledPath> paths,
            List<String> neoForgeForgeCompatibleVersions, Map<DisableableMod, UpdateStatus> settled) {
        if (byHash.isEmpty() || loaders.isEmpty()) {
            return;
        }

        Map<String, ModrinthVersion> newest = ModrinthApi.getLatestVersionsFromSha1Hashes(
                byHash.keySet().toArray(new String[0]), loaders, instanceOrServer.getMinecraftVersion());
        if (newest == null) {
            return;
        }

        byHash.forEach((sha1, mod) -> {
            UpdateStatus status = mod.modrinthBatchVerdict(instanceOrServer, newest.get(sha1), paths.get(mod),
                    neoForgeForgeCompatibleVersions);
            if (status != null) {
                settled.put(mod, status);
            }
        });
    }
}
