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
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.data.minecraft.loaders.LoaderVersion;

/**
 * A loader path a new install can take, as offered by the selectors' "Mod loader" dropdown, in the order offered.
 * Only paths with verified rules are here. The first three only split the files the instance's loader already
 * accepted, so choosing one never changes what can be installed. Another loader or bridge (such as Kilt) needs its
 * own filtering, dependency and installed-path rules before it's added.
 */
public enum LoaderChoice {
    /** Files made for the instance's own loader, and untagged ones, as the selectors always listed them. */
    NATIVE,
    /** Forge-only files on a NeoForge instance, for the Minecraft versions NeoForge still loads them on. */
    FORGE_COMPATIBLE,
    /** Fabric-only files on a Quilt instance, which Quilt loads through its Fabric support. */
    FABRIC_VIA_QUILT,
    /** Fabric-only builds for this exact Minecraft version, through an enabled Sinytra Connector. */
    FABRIC_VIA_CONNECTOR;

    /**
     * The one path a file lists under. A file also tagged for the instance's own loader is native, so a file tagged
     * Quilt and Fabric, or NeoForge and Forge, appears once, as native.
     *
     * @param tags a file's loader tags (CurseForge game versions, Modrinth loaders)
     */
    public static LoaderChoice of(Collection<String> tags, LoaderVersion loader, boolean forgeCompatible) {
        if (loader == null) {
            return NATIVE;
        }

        if (loader.isQuilt()) {
            return !hasTag(tags, "quilt") && hasTag(tags, "fabric") ? FABRIC_VIA_QUILT : NATIVE;
        }

        if (loader.isNeoForge() && !hasTag(tags, "neoforge") && forgeCompatible && hasTag(tags, "forge")) {
            return FORGE_COMPATIBLE;
        }

        return SinytraConnector.isFabricFallbackFile(tags, loader, forgeCompatible) ? FABRIC_VIA_CONNECTOR : NATIVE;
    }

    /**
     * The paths to offer, in order, native first: each one the usual files have, plus Fabric via Connector when it's
     * offered for this new install.
     *
     * @param connectorBuildsOffered a new install of a project that isn't installed yet, with Connector active and a
     *                               Fabric build for this Minecraft version to show
     */
    public static <T> List<LoaderChoice> choices(List<T> usual, Function<T, LoaderChoice> choiceOf,
            boolean connectorBuildsOffered) {
        List<LoaderChoice> choices = new ArrayList<>();

        for (LoaderChoice choice : values()) {
            if ((choice == FABRIC_VIA_CONNECTOR && connectorBuildsOffered)
                    || usual.stream().anyMatch(f -> choiceOf.apply(f) == choice)) {
                choices.add(choice);
            }
        }

        return choices;
    }

    /**
     * The path selected at first: the first offered, so native whenever it has files, unless Fabric via Connector is
     * wanted and offered, as for the automatic fallback or a Connector build's dependency. Null when none is offered.
     */
    public static LoaderChoice defaultChoice(List<LoaderChoice> choices, boolean preferConnector) {
        if (preferConnector && choices.contains(FABRIC_VIA_CONNECTOR)) {
            return FABRIC_VIA_CONNECTOR;
        }

        return choices.isEmpty() ? null : choices.get(0);
    }

    /**
     * The files listed for a path, in their given order: Connector builds from all files, anything else from the
     * usual files the instance's loader accepts.
     */
    public static <T> List<T> filesFor(LoaderChoice choice, List<T> all, List<T> usual,
            Function<T, LoaderChoice> choiceOf, Predicate<T> isConnectorBuild) {
        if (choice == FABRIC_VIA_CONNECTOR) {
            return all.stream().filter(isConnectorBuild).collect(Collectors.toList());
        }

        return usual.stream().filter(f -> choiceOf.apply(f) == choice).collect(Collectors.toList());
    }

    /**
     * The file to select after switching path: the same one while it's still listed, else the newest listed one,
     * else none. A file the list no longer shows is never kept selected.
     */
    public static <T> T selectionAfterSwitch(List<T> listed, T selected) {
        if (selected != null && listed.contains(selected)) {
            return selected;
        }

        return listed.isEmpty() ? null : listed.get(0);
    }

    public static String label(LoaderChoice choice, LoaderVersion loader) {
        switch (choice) {
            case FORGE_COMPATIBLE:
                return GetText.tr("Forge — compatible");
            case FABRIC_VIA_QUILT:
                return GetText.tr("Fabric — via Quilt");
            case FABRIC_VIA_CONNECTOR:
                return GetText.tr("Fabric — via Connector");
            default:
                // #. {0} is the instance's own mod loader (e.g. NeoForge)
                return GetText.tr("{0} — native", loader == null ? GetText.tr("Vanilla") : loader.type);
        }
    }

    private static boolean hasTag(Collection<String> tags, String tag) {
        return tags != null && tags.stream().anyMatch(tag::equalsIgnoreCase);
    }
}
