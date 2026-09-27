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

import java.util.Collection;
import java.util.List;

import com.atlauncher.constants.Constants;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;

/**
 * Shared rules for offering Fabric files through Sinytra Connector on Forge and NeoForge.
 *
 * Loader tags are a file's loader names as the platform reports them: CurseForge puts "NeoForge"/"Forge"/"Fabric" in a
 * file's game versions, Modrinth lists "neoforge"/"forge"/"fabric" as a version's loaders. Both spellings are accepted.
 */
public final class SinytraConnector {
    private SinytraConnector() {
    }

    /**
     * Whether Connector is present on disk and enabled. Follows the instance scan's convention that unselected
     * optional mods and skipped browser downloads were never installed.
     */
    public static boolean isInstalledAndEnabled(Collection<DisableableMod> mods) {
        return mods != null && mods.stream().anyMatch(m -> m != null && isConnector(m) && m.wasSelected
                && !m.skipped && !m.disabled);
    }

    private static boolean isConnector(DisableableMod mod) {
        return (mod.isFromCurseForge() && mod.getCurseForgeModId() == Constants.CURSEFORGE_SINYTRA_CONNECTOR_MOD_ID)
                || (mod.isFromModrinth()
                        && Constants.MODRINTH_SINYTRA_CONNECTOR_MOD_ID.equalsIgnoreCase(mod.modrinthProject.id));
    }

    /**
     * Whether a file runs on the loader without Connector: NeoForge files on NeoForge, plus Forge files on the
     * Minecraft versions where NeoForge still loads them; Forge files on Forge.
     *
     * @param forgeCompatible whether this Minecraft version is in NeoForge's Forge-compatible list
     */
    public static boolean isNativeFile(Collection<String> loaderTags, LoaderVersion loader, boolean forgeCompatible) {
        if (loader == null) {
            return false;
        }

        if (loader.isNeoForge()) {
            return hasTag(loaderTags, "neoforge") || (forgeCompatible && hasTag(loaderTags, "forge"));
        }

        return loader.isForge() && hasTag(loaderTags, "forge");
    }

    /**
     * Whether Fabric files may be offered as a Connector fallback: Connector is active on Forge/NeoForge and none of
     * the files that already match this Minecraft version is native. Native files for other Minecraft versions don't
     * count, since they can't be installed here anyway.
     *
     * @param versionMatchedFiles loader tags of each file that passed the Minecraft version filter
     */
    public static boolean allowsFabricFallback(boolean connectorActive, LoaderVersion loader, boolean forgeCompatible,
            Collection<? extends Collection<String>> versionMatchedFiles) {
        if (!connectorActive || loader == null || !(loader.isForge() || loader.isNeoForge())) {
            return false;
        }

        return versionMatchedFiles == null
                || versionMatchedFiles.stream().noneMatch(tags -> isNativeFile(tags, loader, forgeCompatible));
    }

    /**
     * Whether a file would only run through Connector here: it's tagged Fabric but not native to the loader.
     */
    public static boolean isFabricFallbackFile(Collection<String> loaderTags, LoaderVersion loader,
            boolean forgeCompatible) {
        return loader != null && (loader.isForge() || loader.isNeoForge()) && hasTag(loaderTags, "fabric")
                && !isNativeFile(loaderTags, loader, forgeCompatible);
    }

    /**
     * Which loader path an installed file runs on: the same paths the Add Mods "Mod loader" dropdown tells apart (see
     * {@link LoaderChoice}), so Update and Reinstall can keep a file on the path it was installed through.
     */
    public enum InstalledPath {
        /** Made for the instance's own loader, also tagged for it, or untagged, as the native list showed it. */
        NATIVE,
        /** A Forge-only file on NeoForge. */
        FORGE_COMPATIBLE,
        /** A Fabric-only file on Quilt. */
        FABRIC_VIA_QUILT,
        /** A Fabric-only file on Forge or NeoForge, running through Connector. */
        CONNECTOR,
        /** No loader metadata on a loader with more than one path, so it can't be told which one. */
        UNKNOWN;

        static InstalledPath of(LoaderChoice choice) {
            switch (choice) {
                case FORGE_COMPATIBLE:
                    return FORGE_COMPATIBLE;
                case FABRIC_VIA_QUILT:
                    return FABRIC_VIA_QUILT;
                case FABRIC_VIA_CONNECTOR:
                    return CONNECTOR;
                default:
                    return NATIVE;
            }
        }
    }

    /**
     * Which loader path an installed file runs on, from its loader tags, classified as the Add Mods dropdown lists
     * files. A file tagged for the instance's own loader as well as another, or tagged with no loader, is native, as
     * that's where the dropdown put it. Loaders with only one path (Fabric and the like) have every file native.
     *
     * @param installedTags the installed file's tags; null or empty when unknown
     */
    public static InstalledPath installedPath(Collection<String> installedTags, LoaderVersion loader,
            boolean forgeCompatible) {
        if (loader == null || !(loader.isForge() || loader.isNeoForge() || loader.isQuilt())) {
            return InstalledPath.NATIVE;
        }

        if (installedTags == null || installedTags.isEmpty()) {
            return InstalledPath.UNKNOWN;
        }

        return InstalledPath.of(LoaderChoice.of(installedTags, loader, forgeCompatible));
    }

    /**
     * Whether a file can update an installed one without changing its loader path: the candidate must list under the
     * same path. A file installed through Connector also needs Connector still active. A file on an unknown path gets
     * no updates. Switching paths would need a separate migration action, not an update.
     */
    public static boolean keepsLoaderPath(InstalledPath installed, boolean connectorActive,
            Collection<String> candidateTags, LoaderVersion loader, boolean forgeCompatible) {
        if (installed == InstalledPath.UNKNOWN) {
            return false;
        }

        InstalledPath candidate = InstalledPath.of(LoaderChoice.of(candidateTags, loader, forgeCompatible));
        return candidate == installed && (installed != InstalledPath.CONNECTOR || connectorActive);
    }

    /**
     * Whether a file is a Fabric build that only runs here through Connector and lists this exact Minecraft version.
     * Those are the only Fabric files a new install offers through Connector, whatever the Add Mod Restriction.
     */
    public static boolean isConnectorBuild(Collection<String> loaderTags, Collection<String> gameVersions,
            String minecraftVersion, LoaderVersion loader, boolean forgeCompatible) {
        return isFabricFallbackFile(loaderTags, loader, forgeCompatible) && gameVersions != null
                && gameVersions.contains(minecraftVersion);
    }

    /**
     * Whether Add may install the selected file right now: it's still listed, and if it needs Connector, Connector is
     * still active. Guards a dialog left open while Connector is disabled or removed.
     */
    public static <T> boolean mayInstall(List<T> listed, T selected, boolean needsConnector,
            boolean connectorActiveNow) {
        return selected != null && listed.contains(selected) && (!needsConnector || connectorActiveNow);
    }

    private static boolean hasTag(Collection<String> tags, String loader) {
        return tags != null && tags.stream().anyMatch(loader::equalsIgnoreCase);
    }
}
