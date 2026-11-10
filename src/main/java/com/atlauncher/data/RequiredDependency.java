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
import java.util.function.Predicate;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.constants.Constants;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;

/**
 * Where a required dependency of the file being added stands against what's installed. Nothing here installs
 * anything: it decides what a dependency card says, whether it offers Add, and whether adding the file needs the
 * user's acknowledgement first. The launcher never verifies that a dependency is actually met at runtime.
 */
public final class RequiredDependency {
    private RequiredDependency() {
    }

    public enum State {
        /** Not installed: the card offers Add. */
        MISSING,
        /** Installed on the loader path the file needs, at the declared version if there is one: no card. */
        SATISFIED,
        /** Installed on the other loader path (native or through Connector), so it may not satisfy the file. */
        OTHER_LOADER,
        /** Installed, but its loader can't be told, so it isn't counted as satisfying the file. */
        UNKNOWN_LOADER,
        /** Installed on the right loader path but not at the version the dependency declares. */
        OTHER_VERSION,
        /** The dependency's project can't be found. */
        UNRESOLVED,
        /** Fabric API under Connector: Forgified Fabric API provides it and isn't installed yet. */
        USE_FORGIFIED_FABRIC_API,
        /** Fabric API under Connector while ordinary Fabric API is installed: it has to go before Forgified. */
        FABRIC_API_CONFLICT,
        /** Installed but disabled, so it doesn't load. */
        DISABLED,
        /** Listed but never downloaded: an unselected optional mod or a skipped browser download. */
        NOT_DOWNLOADED,
        /** Installed and enabled, but its file isn't on disk. */
        FILE_MISSING
    }

    /**
     * Whether an installed entry was ever put on disk: unselected optional mods and skipped browser downloads
     * weren't, the launcher's usual convention.
     */
    public static boolean isPresent(DisableableMod mod) {
        return mod.wasSelected && !mod.skipped;
    }

    /**
     * Why an installed entry can't satisfy anything, or null when it's usable: it must have been downloaded, be
     * enabled and have its file on disk. Nothing here changes it.
     */
    public static State activity(DisableableMod mod, boolean fileExists) {
        if (!isPresent(mod)) {
            return State.NOT_DOWNLOADED;
        }

        if (mod.disabled) {
            return State.DISABLED;
        }

        return fileExists ? null : State.FILE_MISSING;
    }

    /**
     * How an installed project stands as this dependency.
     *
     * @param installedPath         the installed file's loader path, or null when the project isn't installed
     * @param activity              why the installed entry isn't usable (see {@link #activity}), or null
     * @param parentNeedsConnector  whether the file needing it is a Fabric build running through Connector
     * @param requiredVersionId     the exact version the dependency declares, or null for any
     * @param installedVersionId    the installed version's ID, when known
     */
    public static State state(SinytraConnector.InstalledPath installedPath, State activity,
            boolean parentNeedsConnector, String requiredVersionId, String installedVersionId) {
        if (installedPath == null) {
            return State.MISSING;
        }

        if (activity != null) {
            return activity;
        }

        if (installedPath == SinytraConnector.InstalledPath.UNKNOWN) {
            return State.UNKNOWN_LOADER;
        }

        // whether it runs through Connector or on the loader itself is what counts here: the loader runs a Forge file
        // on NeoForge and a Fabric file on Quilt like its own, so those still satisfy a native file
        if ((installedPath == SinytraConnector.InstalledPath.CONNECTOR) != parentNeedsConnector) {
            return State.OTHER_LOADER;
        }

        if (requiredVersionId != null && !requiredVersionId.equalsIgnoreCase(installedVersionId)) {
            return State.OTHER_VERSION;
        }

        return State.SATISFIED;
    }

    /**
     * How a Fabric API dependency stands while Connector is active. Only a usable Forgified Fabric API counts as
     * providing it, and the two are never both installed: an ordinary Fabric API that's on disk conflicts even while
     * disabled.
     *
     * @param forgifiedActivity why the installed Forgified Fabric API isn't usable, or null
     */
    public static State fabricApiUnderConnector(boolean fabricApiPresent, boolean forgifiedFabricApiInstalled,
            State forgifiedActivity) {
        if (fabricApiPresent) {
            return State.FABRIC_API_CONFLICT;
        }

        if (!forgifiedFabricApiInstalled) {
            return State.USE_FORGIFIED_FABRIC_API;
        }

        return forgifiedActivity == null ? State.SATISFIED : forgifiedActivity;
    }

    /**
     * Whether the card offers Add. It doesn't where adding would switch an installed project's loader, add a second
     * Fabric API, replace a disabled, undownloaded or missing entry, or there's nothing to add.
     */
    public static boolean offersAdd(State state) {
        return state == State.MISSING || state == State.OTHER_VERSION || state == State.USE_FORGIFIED_FABRIC_API;
    }

    /**
     * Whether adding the file that needs this dependency should be acknowledged first: whenever it isn't satisfied,
     * including when it simply isn't installed yet. Nothing is installed for the user either way.
     */
    public static boolean needsAcknowledgement(State state) {
        return state != State.SATISFIED;
    }

    /**
     * The line for this dependency in the warning before adding.
     */
    public static String warning(State state, boolean parentNeedsConnector, String installedVersion) {
        String status = status(state, parentNeedsConnector, installedVersion);
        return status != null ? status : GetText.tr("Not installed.");
    }

    /**
     * A dependency whose project couldn't be looked up is unresolved, whatever its installed state said.
     */
    public static State withProject(State state, Object project) {
        return project == null ? State.UNRESOLVED : state;
    }

    /**
     * What a dependency card tells the user about its state, or null when there's nothing beyond its Add button.
     *
     * @param installedVersion the installed version's name, when installed
     */
    public static String status(State state, boolean parentNeedsConnector, String installedVersion) {
        String version = installedVersion == null ? GetText.tr("unknown version") : installedVersion;

        switch (state) {
            case OTHER_LOADER:
                return parentNeedsConnector
                        ? GetText.tr("Installed as a native build ({0}), so it may not satisfy this Fabric "
                                + "dependency.", version)
                        : GetText.tr("Installed as a Fabric build via Connector ({0}), so it may not satisfy this "
                                + "dependency.", version);
            case UNKNOWN_LOADER:
                return GetText.tr("Installed ({0}), but its loader is unknown, so it may not satisfy this "
                        + "dependency.", version);
            case OTHER_VERSION:
                return GetText.tr("Installed at {0}, not the version this dependency declares, so it may not "
                        + "satisfy it.", version);
            case UNRESOLVED:
                return GetText.tr("This required dependency couldn't be found.");
            case USE_FORGIFIED_FABRIC_API:
                return GetText.tr("Needs Fabric API. With Sinytra Connector, Forgified Fabric API provides it "
                        + "instead.");
            case FABRIC_API_CONFLICT:
                return GetText.tr("Needs Fabric API. With Sinytra Connector, Forgified Fabric API should replace "
                        + "the installed Fabric API. Remove Fabric API first.");
            case DISABLED:
                return GetText.tr("Installed but disabled ({0}), so it doesn't satisfy this dependency. Enable it "
                        + "in Edit Mods.", version);
            case NOT_DOWNLOADED:
                return GetText.tr("Listed but never downloaded, so it doesn't satisfy this dependency.");
            case FILE_MISSING:
                return GetText.tr("Installed ({0}) but its file is missing, so it doesn't satisfy this "
                        + "dependency.", version);
            default:
                return null;
        }
    }

    /**
     * Why a new install of this project is stopped, or null when it isn't. Ordinary Fabric API stays out of Forge
     * and NeoForge installs using Connector, and Forgified Fabric API isn't added next to an installed Fabric API.
     */
    public static String fabricApiInstallBlocker(boolean isFabricApiProject, boolean isForgifiedFabricApiProject,
            boolean forgeLikeLoader, boolean connectorActive, boolean fabricApiInstalled) {
        if (isFabricApiProject && connectorActive) {
            return GetText.tr("With Sinytra Connector, Fabric API is provided by Forgified Fabric API. Add "
                    + "Forgified Fabric API instead. Nothing was installed.");
        }

        if (isForgifiedFabricApiProject && forgeLikeLoader && fabricApiInstalled) {
            return GetText.tr("Fabric API is installed. Remove it before adding Forgified Fabric API, so the two "
                    + "aren't installed together. Nothing was installed.");
        }

        return null;
    }

    public static boolean isFabricApi(DisableableMod mod) {
        return (mod.isFromCurseForge() && mod.getCurseForgeModId() == Constants.CURSEFORGE_FABRIC_MOD_ID)
                || (mod.isFromModrinth() && Constants.MODRINTH_FABRIC_MOD_ID.equals(mod.modrinthProject.id));
    }

    public static boolean isForgifiedFabricApi(DisableableMod mod) {
        return (mod.isFromCurseForge() && mod.getCurseForgeModId() == Constants.CURSEFORGE_FORGIFIED_FABRIC_API_MOD_ID)
                || (mod.isFromModrinth()
                        && Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID.equals(mod.modrinthProject.id));
    }

    /**
     * Whether an installed mod provides a Modrinth dependency: the same project, the same library installed from
     * CurseForge (matched by its CurseForge mod ID), or Quilted Fabric API for Fabric API.
     */
    public static boolean providesModrinth(DisableableMod installedMod, String dependencyProjectId) {
        if (installedMod.isFromCurseForge()) {
            int modId = installedMod.getCurseForgeModId();
            if ((Constants.MODRINTH_FABRIC_MOD_ID.equals(dependencyProjectId)
                    && modId == Constants.CURSEFORGE_FABRIC_MOD_ID)
                    || (Constants.MODRINTH_LEGACY_FABRIC_MOD_ID.equals(dependencyProjectId)
                            && modId == Constants.CURSEFORGE_LEGACY_FABRIC_MOD_ID)
                    || (Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID.equals(dependencyProjectId)
                            && modId == Constants.CURSEFORGE_FORGIFIED_FABRIC_API_MOD_ID)) {
                return true;
            }
        }

        if (!installedMod.isFromModrinth()) {
            return false;
        }

        return installedMod.modrinthProject.id.equals(dependencyProjectId)
                || (Constants.MODRINTH_FABRIC_MOD_ID.equals(dependencyProjectId)
                        && Constants.MODRINTH_QSL_MOD_ID.equals(installedMod.modrinthProject.id));
    }

    /**
     * Whether an installed mod provides a CurseForge dependency through a known equivalent: Quilted Fabric API for
     * Fabric API on Quilt, or the same library installed from Modrinth only. The same CurseForge project isn't
     * checked here.
     */
    public static boolean providesCurseForgeAsEquivalent(DisableableMod m, int dependencyModId,
            LoaderVersion loader) {
        if (!m.isFromModrinth()) {
            return false;
        }

        if (dependencyModId == Constants.CURSEFORGE_FABRIC_MOD_ID && loader != null && loader.isQuilt()
                && m.modrinthProject.id.equals(Constants.MODRINTH_QSL_MOD_ID)) {
            return true;
        }

        return !m.isFromCurseForge()
                && ((dependencyModId == Constants.CURSEFORGE_FABRIC_MOD_ID
                        && m.modrinthProject.id.equals(Constants.MODRINTH_FABRIC_MOD_ID))
                        || (dependencyModId == Constants.CURSEFORGE_LEGACY_FABRIC_MOD_ID
                                && m.modrinthProject.id.equals(Constants.MODRINTH_LEGACY_FABRIC_MOD_ID))
                        || (dependencyModId == Constants.CURSEFORGE_FORGIFIED_FABRIC_API_MOD_ID
                                && m.modrinthProject.id.equals(Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID)));
    }

    public static boolean anyInstalled(Collection<DisableableMod> mods, Predicate<DisableableMod> which) {
        return mods != null && mods.stream().anyMatch(m -> m != null && which.test(m));
    }

    /**
     * What a dependency's selector selects at first. A declared version is selected if it's listed, and nothing is
     * otherwise, so the user sees it's missing rather than getting another version silently. For a Connector parent,
     * a Fabric build is suggested when one is listed. Otherwise the usual first file.
     */
    public static <T> T initialSelection(List<T> listed, Predicate<T> isRequiredVersion,
            boolean preferConnectorBuilds, Predicate<T> isConnectorBuild) {
        if (isRequiredVersion != null) {
            return listed.stream().filter(isRequiredVersion).findFirst().orElse(null);
        }

        if (preferConnectorBuilds) {
            T connectorBuild = listed.stream().filter(isConnectorBuild).findFirst().orElse(null);
            if (connectorBuild != null) {
                return connectorBuild;
            }
        }

        return listed.isEmpty() ? null : listed.get(0);
    }

    /**
     * Whether the selected file departs from what the dependency asks for: not its declared version, or not a
     * Fabric build for a parent that runs through Connector. The user may still choose it, with a warning.
     */
    public static <T> boolean isOverride(T selected, Predicate<T> isRequiredVersion, boolean preferConnectorBuilds,
            Predicate<T> isConnectorBuild) {
        if (selected == null) {
            return false;
        }

        return (isRequiredVersion != null && !isRequiredVersion.test(selected))
                || (preferConnectorBuilds && !isConnectorBuild.test(selected));
    }
}
