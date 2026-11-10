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

import java.util.EnumSet;

import com.atlauncher.constants.Constants;
import com.atlauncher.data.curseforge.CurseForgeGameVersionLatestFiles;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.minecraft.loaders.LoaderType;
import com.atlauncher.data.modrinth.ModrinthSearchHit;

/**
 * Loaders a provider reports for a whole project, taken from an Add Mods search result without extra requests.
 *
 * These are project-level: they do not prove a file exists for any particular Minecraft version. The returned set
 * iterates in {@link LoaderType} declaration order and is empty when the result carries no known loader.
 */
public final class LoaderIndicators {
    private static final EnumSet<LoaderType> SERVER_PLATFORMS = EnumSet.of(LoaderType.PAPER, LoaderType.PURPUR);

    private LoaderIndicators() {
    }

    /**
     * Narrows reported loaders to the ones worth showing in an Add Mods section: mod loaders for "Mods", server
     * platforms for "Plugins", and nothing elsewhere (resource packs, shaders, data packs and worlds), where a loader
     * would imply requirements that don't apply.
     */
    public static EnumSet<LoaderType> forSection(EnumSet<LoaderType> reported, String section) {
        EnumSet<LoaderType> shown = EnumSet.noneOf(LoaderType.class);
        if (reported == null) {
            return shown;
        }

        shown.addAll(reported);
        if ("Mods".equals(section)) {
            shown.removeAll(SERVER_PLATFORMS);
        } else if ("Plugins".equals(section)) {
            shown.retainAll(SERVER_PLATFORMS);
        } else {
            shown.clear();
        }

        return shown;
    }

    public static EnumSet<LoaderType> fromCurseForge(CurseForgeProject project) {
        EnumSet<LoaderType> loaders = EnumSet.noneOf(LoaderType.class);
        if (project == null || project.latestFilesIndexes == null) {
            return loaders;
        }

        for (CurseForgeGameVersionLatestFiles index : project.latestFilesIndexes) {
            if (index == null) {
                continue;
            }

            // only the loader ids this repository already defines; others (0 "any", Cauldron, LiteLoader) are skipped
            if (index.modLoader == Constants.CURSEFORGE_FORGE_MODLOADER_ID) {
                loaders.add(LoaderType.FORGE);
            } else if (index.modLoader == Constants.CURSEFORGE_FABRIC_MODLOADER_ID) {
                loaders.add(LoaderType.FABRIC);
            } else if (index.modLoader == Constants.CURSEFORGE_QUILT_MODLOADER_ID) {
                loaders.add(LoaderType.QUILT);
            } else if (index.modLoader == Constants.CURSEFORGE_NEOFORGE_MODLOADER_ID) {
                loaders.add(LoaderType.NEOFORGE);
            }
        }

        return loaders;
    }

    public static EnumSet<LoaderType> fromModrinth(ModrinthSearchHit hit) {
        EnumSet<LoaderType> loaders = EnumSet.noneOf(LoaderType.class);
        if (hit == null || hit.categories == null) {
            return loaders;
        }

        // search-hit categories mix loaders with topics; keep only the loader names the Modrinth searches already use
        for (String category : hit.categories) {
            if (category == null) {
                continue;
            }

            switch (category) {
                case "fabric":
                    loaders.add(LoaderType.FABRIC);
                    break;
                case "forge":
                    loaders.add(LoaderType.FORGE);
                    break;
                case "legacy-fabric":
                    loaders.add(LoaderType.LEGACY_FABRIC);
                    break;
                case "neoforge":
                    loaders.add(LoaderType.NEOFORGE);
                    break;
                case "paper":
                    loaders.add(LoaderType.PAPER);
                    break;
                case "purpur":
                    loaders.add(LoaderType.PURPUR);
                    break;
                case "quilt":
                    loaders.add(LoaderType.QUILT);
                    break;
                default:
                    break;
            }
        }

        return loaders;
    }
}
