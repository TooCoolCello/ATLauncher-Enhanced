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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.data.curseforge.CurseForgeGameVersionLatestFiles;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.ftb.FTBPackVersion;

/**
 * Why a modpack in the packs browser won't install or run here, from data the browser already has.
 *
 * Each method returns a short reason, or null when nothing proves the pack incompatible. Missing or unknown data
 * always means null: a pack is only flagged on evidence, never on a guess.
 */
public final class PackCompatibility {
    // the platforms InstanceInstallerDialog can install a unified search result from; it does nothing for others
    private static final Set<String> INSTALLABLE_UNIFIED_PLATFORMS = new HashSet<>(
        Arrays.asList("ATLAUNCHER", "CURSEFORGE", "FTB", "MODRINTH", "TECHNIC"));

    private PackCompatibility() {
    }

    /**
     * A CurseForge modpack whose files all name a loader, none of which the installer handles. It installs Forge,
     * NeoForge, Fabric and Quilt packs and throws on any other loader (such as LiteLoader or Cauldron).
     */
    public static String curseForge(CurseForgeProject project) {
        if (project == null || project.latestFilesIndexes == null || project.latestFilesIndexes.isEmpty()) {
            return null;
        }

        for (CurseForgeGameVersionLatestFiles index : project.latestFilesIndexes) {
            // 0 is "any" or not stated, so the file might use a supported loader
            if (index == null || index.modLoader == 0) {
                return null;
            }
        }

        if (!LoaderIndicators.fromCurseForge(project).isEmpty()) {
            return null;
        }

        return GetText.tr("Only has files for mod loaders ATLauncher can't install.");
    }

    /**
     * An FTB pack with nothing to install, or whose every version states a minimum memory above this computer's
     * physical RAM.
     *
     * @param systemRamMb physical RAM in MB as detected, or null when unknown
     */
    public static String ftb(List<FTBPackVersion> versions, Integer systemRamMb) {
        if (versions == null || versions.isEmpty()) {
            return GetText.tr("Has no versions to install.");
        }

        if (systemRamMb == null || systemRamMb <= 0) {
            return null;
        }

        int lowestMinimum = Integer.MAX_VALUE;
        for (FTBPackVersion version : versions) {
            int minimum = version == null || version.specs == null ? 0 : version.specs.minimum;

            // a version without a stated minimum might fit
            if (minimum <= 0) {
                return null;
            }

            lowestMinimum = Math.min(lowestMinimum, minimum);
        }

        if (lowestMinimum <= systemRamMb) {
            return null;
        }

        return GetText.tr("Needs at least {0} MB of memory, but this computer has {1} MB.",
            String.valueOf(lowestMinimum), String.valueOf(systemRamMb));
    }

    /**
     * A unified search result the installer can't act on: a platform it doesn't know, or an ATLauncher pack that
     * isn't in the launcher's pack list or that {@link Pack#canInstall()} rules out, as the ATLauncher tab does.
     *
     * @param platform                  the result's platform name, such as "CURSEFORGE"
     * @param atlauncherPackInstallable for an ATLauncher result, whether the pack was found and can be installed
     */
    public static String unified(String platform, boolean atlauncherPackInstallable) {
        if (!INSTALLABLE_UNIFIED_PLATFORMS.contains(platform)) {
            return GetText.tr("Comes from a platform ATLauncher can't install packs from.");
        }

        if ("ATLAUNCHER".equals(platform) && !atlauncherPackInstallable) {
            return GetText.tr("Isn't available for you to install.");
        }

        return null;
    }
}
