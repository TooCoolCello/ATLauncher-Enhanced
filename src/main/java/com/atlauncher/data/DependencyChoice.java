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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.data.RequiredDependency.State;
import com.atlauncher.data.curseforge.CurseForgeFileDependency;
import com.atlauncher.data.modrinth.ModrinthDependencyType;

/**
 * The choices the selectors offer for each dependency of the file being added: install it, keep what's installed,
 * replace or enable it, or skip it; and for an installed mod the file declares incompatible, disable or remove it.
 * Nothing here installs anything. The selectors carry the choices out after the file itself is added.
 */
public final class DependencyChoice {
    private DependencyChoice() {
    }

    public enum Kind {
        REQUIRED, OPTIONAL, INCOMPATIBLE;

        public String label() {
            switch (this) {
                case REQUIRED:
                    return GetText.tr("Required");
                case OPTIONAL:
                    return GetText.tr("Optional");
                default:
                    return GetText.tr("Incompatible");
            }
        }
    }

    public enum Action {
        INSTALL, KEEP, REPLACE, ENABLE, SKIP, DISABLE, REMOVE;

        public String label() {
            switch (this) {
                case INSTALL:
                    return GetText.tr("Install");
                case KEEP:
                    return GetText.tr("Keep as is");
                case REPLACE:
                    return GetText.tr("Replace");
                case ENABLE:
                    return GetText.tr("Enable");
                case SKIP:
                    return GetText.tr("Skip");
                case DISABLE:
                    return GetText.tr("Disable it");
                default:
                    return GetText.tr("Remove it");
            }
        }

        /** Whether this action downloads a file for the dependency. */
        public boolean installs() {
            return this == INSTALL || this == REPLACE;
        }
    }

    /**
     * How a Modrinth dependency is treated, or null for ones that aren't offered (embedded, or a type this launcher
     * doesn't know).
     */
    public static Kind kindOf(ModrinthDependencyType type) {
        if (type == ModrinthDependencyType.REQUIRED) {
            return Kind.REQUIRED;
        }

        if (type == ModrinthDependencyType.OPTIONAL) {
            return Kind.OPTIONAL;
        }

        return type == ModrinthDependencyType.INCOMPATIBLE ? Kind.INCOMPATIBLE : null;
    }

    /**
     * How a CurseForge relation is treated, or null for ones that aren't offered (embedded libraries, tools,
     * includes).
     */
    public static Kind kindOf(CurseForgeFileDependency dependency) {
        if (dependency.isRequired()) {
            return Kind.REQUIRED;
        }

        if (dependency.isOptional()) {
            return Kind.OPTIONAL;
        }

        return dependency.isIncompatible() ? Kind.INCOMPATIBLE : null;
    }

    /**
     * What can be done about a dependency, given how it stands against the installed mods. For an incompatible one,
     * the state is ignored: it's only offered while it's installed.
     */
    public static List<Action> actions(Kind kind, State state) {
        if (kind == Kind.INCOMPATIBLE) {
            return Arrays.asList(Action.DISABLE, Action.REMOVE, Action.KEEP);
        }

        switch (state) {
            case SATISFIED:
                return Collections.singletonList(Action.KEEP);
            case MISSING:
            case USE_FORGIFIED_FABRIC_API:
            case NOT_DOWNLOADED:
            case FILE_MISSING:
                return Arrays.asList(Action.INSTALL, Action.SKIP);
            case OTHER_VERSION:
            case OTHER_LOADER:
            case UNKNOWN_LOADER:
                return Arrays.asList(Action.KEEP, Action.REPLACE);
            case DISABLED:
                return Arrays.asList(Action.ENABLE, Action.KEEP);
            case FABRIC_API_CONFLICT:
                // Forgified Fabric API goes in, and the ordinary Fabric API comes out
                return Arrays.asList(Action.REPLACE, Action.SKIP);
            default:
                return Collections.singletonList(Action.SKIP);
        }
    }

    /**
     * The choice selected at first. Optional dependencies change nothing unless asked. A required one is installed,
     * enabled or swapped for Forgified Fabric API; one installed at another version is replaced only when the
     * declared version is known to be newer; one installed on another loader path is kept, since it often still
     * works.
     *
     * @param declaredIsNewer whether the declared version was published after the installed one; null when unknown
     */
    public static Action defaultAction(Kind kind, State state, Boolean declaredIsNewer) {
        if (kind == Kind.INCOMPATIBLE) {
            // reversible, unlike removing it
            return Action.DISABLE;
        }

        List<Action> actions = actions(kind, state);
        if (kind == Kind.OPTIONAL) {
            return actions.contains(Action.KEEP) ? Action.KEEP : Action.SKIP;
        }

        if (state == State.OTHER_VERSION) {
            return Boolean.TRUE.equals(declaredIsNewer) ? Action.REPLACE : Action.KEEP;
        }

        return actions.get(0);
    }

    /**
     * Whether one Modrinth publish date is later than another, or null when either is unknown. Modrinth gives them
     * all in the same ISO 8601 UTC form, so they compare as text.
     */
    public static Boolean isNewer(String date, String than) {
        return date == null || than == null ? null : date.compareTo(than) > 0;
    }

    /**
     * The line for this choice in the warning before adding, or null when it needs none: a required dependency left
     * unmet, or an incompatible mod left enabled.
     *
     * @param status what the row says about the dependency's installed state, or null
     */
    public static String warning(Kind kind, State state, Action action, String status) {
        if (kind == Kind.INCOMPATIBLE) {
            return action == Action.KEEP ? GetText.tr("Declared incompatible, and left enabled.") : null;
        }

        if (kind != Kind.REQUIRED) {
            return null;
        }

        if (action == Action.SKIP) {
            String skipped = GetText.tr("Required, but skipped.");
            return status == null ? skipped : skipped + " " + status;
        }

        if (action == Action.KEEP && state != State.SATISFIED) {
            return status == null ? GetText.tr("Kept as installed, so it may not satisfy this mod.") : status;
        }

        return null;
    }

    /**
     * What a row says about a dependency that's already met.
     *
     * @param equivalent whether it's met by another project treated as the same library, such as Quilted Fabric API
     *                   for Fabric API: an assumption the launcher can't check
     */
    public static String satisfiedStatus(String providerName, String version, boolean equivalent) {
        String installedVersion = version == null ? GetText.tr("unknown version") : version;
        if (equivalent) {
            // #. {0} is the installed mod providing the dependency, {1} its version
            return GetText.tr("Provided by {0} ({1}), treated as the same library. The launcher can't verify that.",
                    providerName, installedVersion);
        }

        // #. {0} is the installed version
        return GetText.tr("Installed ({0}).", installedVersion);
    }

    /**
     * The file a dependency installs by default: its declared version when that runs here, otherwise the newest file
     * for this exact Minecraft version that runs on the instance's loader, releases before betas and alphas. Native
     * files come before Fabric builds through Connector, the other way round when the file needing it runs through
     * Connector, and files that name no loader come last. Judged by {@link ModCompatibility}, so it agrees with Add
     * Mods' compatibility marks. Null when nothing fits.
     *
     * @param newestFirst     the dependency's files, newest first
     * @param isDeclared      the exact version the dependency declares, or null for any
     * @param preferConnector the file needing it is a Fabric build running through Connector
     */
    public static <T> T pickFile(List<T> newestFirst, Function<T, Collection<String>> loaderTags,
            Function<T, Collection<String>> gameVersions, Predicate<T> isRelease, Predicate<T> isDeclared,
            ModCompatibility.Target target, boolean preferConnector) {
        T best = null;
        int bestRank = Integer.MAX_VALUE;

        for (T file : newestFirst) {
            int fit = fit(target, preferConnector, loaderTags.apply(file), gameVersions.apply(file));
            if (fit < 0) {
                continue;
            }

            if (isDeclared != null && isDeclared.test(file)) {
                return file;
            }

            int rank = fit * 2 + (isRelease.test(file) ? 0 : 1);
            if (rank < bestRank) {
                best = file;
                bestRank = rank;
            }
        }

        return best;
    }

    // 0 is the best fit, -1 doesn't run here
    private static int fit(ModCompatibility.Target target, boolean preferConnector, Collection<String> loaderTags,
            Collection<String> gameVersions) {
        ModCompatibility.Verdict verdict = ModCompatibility.judge(target, target.loader != null, true, "",
                Collections.singletonList(new ModCompatibility.FileFacts(gameVersions, loaderTags)));

        switch (verdict.state) {
            case COMPATIBLE:
                return preferConnector ? 1 : 0;
            case VIA_CONNECTOR:
                return preferConnector ? 0 : 1;
            case UNKNOWN:
                // tagged with no loader at all
                return 2;
            default:
                return -1;
        }
    }
}
