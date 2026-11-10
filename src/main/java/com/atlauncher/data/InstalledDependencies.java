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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.atlauncher.constants.Constants;
import com.atlauncher.data.curseforge.CurseForgeFileDependency;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthDependency;
import com.atlauncher.data.modrinth.ModrinthDependencyType;

/**
 * Which installed mods need which, from the dependency lists the launcher stored with each mod when it was added or
 * matched on Modrinth (the version) or CurseForge (the file). Only required dependencies count. A mod with neither
 * stored has no data, and nothing here guesses for it: callers say so instead.
 */
public final class InstalledDependencies {
    /** What to do about installed mods that need something being removed. */
    public enum DependentsChoice {
        /** Don't remove the selected mods that something staying needs. */
        KEEP_NEEDED,
        /** Remove everything selected; the mods needing them stay as they are. */
        REMOVE_ANYWAY,
        /** Remove everything selected and disable the mods left without a dependency. */
        DISABLE_DEPENDENTS,
        /** Remove everything selected and the mods left without a dependency. */
        REMOVE_DEPENDENTS
    }

    /** What a removal does once decided. */
    public static final class Plan {
        public final Set<DisableableMod> remove;
        public final Set<DisableableMod> disable;

        Plan(Set<DisableableMod> remove, Set<DisableableMod> disable) {
            this.remove = remove;
            this.disable = disable;
        }
    }

    // one required dependency: a Modrinth project or version, or a CurseForge mod
    private static final class Need {
        final String modrinthProjectId;
        final String modrinthVersionId;
        final int curseForgeModId;

        Need(String modrinthProjectId, String modrinthVersionId, int curseForgeModId) {
            this.modrinthProjectId = modrinthProjectId;
            this.modrinthVersionId = modrinthVersionId;
            this.curseForgeModId = curseForgeModId;
        }
    }

    private final List<DisableableMod> installed;
    private final LoaderVersion loader;
    private final boolean connectorActive;
    // read once, as the checks below ask for them many times over
    private final Map<DisableableMod, List<Need>> needs = new HashMap<>();

    /**
     * @param connectorActive Sinytra Connector is enabled, so Forgified Fabric API provides Fabric API
     */
    public InstalledDependencies(List<DisableableMod> installed, LoaderVersion loader, boolean connectorActive) {
        this.installed = installed.stream().filter(m -> m != null).collect(Collectors.toList());
        this.loader = loader;
        this.connectorActive = connectorActive;
        this.installed.forEach(m -> needs.put(m, needsOf(m)));
    }

    public static InstalledDependencies of(ModManagement instanceOrServer) {
        return new InstalledDependencies(instanceOrServer.getMods(), instanceOrServer.getLoaderVersion(),
                instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector());
    }

    /**
     * Whether the launcher stored a dependency list for this mod, from Modrinth or CurseForge.
     */
    public static boolean hasData(DisableableMod mod) {
        return needsOf(mod) != null;
    }

    /**
     * The enabled mods staying with no stored dependency data, so nothing can be said about what they need. Resource
     * packs, shader packs and worlds are left out, as they don't need mods.
     */
    public List<DisableableMod> withoutData(Collection<DisableableMod> gone) {
        return installed.stream().filter(m -> !gone.contains(m) && active(m) && needs.get(m) == null
                && m.type != Type.resourcepack && m.type != Type.texturepack && m.type != Type.shaderpack
                && m.type != Type.worlds && m.type != Type.resourcepackextract
                && m.type != Type.texturepackextract)
                .collect(Collectors.toList());
    }

    /**
     * Whether a mod's platform marks it as a library, the kind of mod usually installed only for others.
     */
    public static boolean isLibrary(DisableableMod mod) {
        return (mod.modrinthProject != null && mod.modrinthProject.categories != null
                && mod.modrinthProject.categories.contains("library"))
                || (mod.curseForgeProject != null && mod.curseForgeProject.categories != null
                        && mod.curseForgeProject.categories.stream()
                                .anyMatch(c -> c != null && (c.id == 421 || "library-api".equals(c.slug))));
    }

    // null when nothing was stored
    private static List<Need> needsOf(DisableableMod mod) {
        List<ModrinthDependency> modrinth = mod.isFromModrinth() ? mod.modrinthVersion.dependencies : null;
        List<CurseForgeFileDependency> curseForge = mod.curseForgeFile == null ? null
                : mod.curseForgeFile.dependencies;

        if (modrinth == null && curseForge == null) {
            return null;
        }

        List<Need> needs = new ArrayList<>();
        if (modrinth != null) {
            modrinth.stream().filter(d -> d != null && d.dependencyType == ModrinthDependencyType.REQUIRED
                    && (d.projectId != null || d.versionId != null))
                    .forEach(d -> needs.add(new Need(d.projectId, d.versionId, 0)));
        }
        if (curseForge != null) {
            curseForge.stream().filter(d -> d != null && d.isRequired())
                    .forEach(d -> needs.add(new Need(null, null, d.modId)));
        }
        return needs;
    }

    private boolean provides(DisableableMod provider, Need need) {
        if (need.modrinthProjectId != null) {
            return RequiredDependency.providesModrinth(provider, need.modrinthProjectId)
                    || (connectorActive && Constants.MODRINTH_FABRIC_MOD_ID.equals(need.modrinthProjectId)
                            && RequiredDependency.isForgifiedFabricApi(provider));
        }

        if (need.modrinthVersionId != null) {
            return provider.isFromModrinth() && need.modrinthVersionId.equalsIgnoreCase(provider.modrinthVersion.id);
        }

        return (provider.isFromCurseForge() && provider.getCurseForgeModId() == need.curseForgeModId)
                || RequiredDependency.providesCurseForgeAsEquivalent(provider, need.curseForgeModId, loader)
                || (connectorActive && need.curseForgeModId == Constants.CURSEFORGE_FABRIC_MOD_ID
                        && RequiredDependency.isForgifiedFabricApi(provider));
    }

    /**
     * Whether a mod's stored data says it requires what the provider provides.
     */
    public boolean needs(DisableableMod mod, DisableableMod provider) {
        List<Need> modNeeds = needs.containsKey(mod) ? needs.get(mod) : needsOf(mod);
        return mod != provider && modNeeds != null && modNeeds.stream().anyMatch(n -> provides(provider, n));
    }

    // loaded right now: downloaded and enabled
    private static boolean active(DisableableMod mod) {
        return RequiredDependency.isPresent(mod) && !mod.disabled;
    }

    /**
     * The enabled mods staying that lose a required dependency: something in {@code gone} provided it and nothing
     * staying and enabled does.
     */
    public List<DisableableMod> brokenBy(Collection<DisableableMod> gone) {
        List<DisableableMod> staying = installed.stream().filter(m -> !gone.contains(m) && active(m))
                .collect(Collectors.toList());

        return staying.stream().filter(mod -> {
            List<Need> modNeeds = needs.get(mod);
            return modNeeds != null && modNeeds.stream()
                    .anyMatch(n -> gone.stream().anyMatch(g -> g != mod && provides(g, n))
                            && staying.stream().noneMatch(s -> s != mod && provides(s, n)));
        }).collect(Collectors.toList());
    }

    /**
     * {@link #brokenBy}, followed through: also the mods that lose a dependency once those are gone or disabled.
     */
    public List<DisableableMod> brokenByAll(Collection<DisableableMod> gone) {
        Set<DisableableMod> broken = new LinkedHashSet<>();
        while (true) {
            Set<DisableableMod> goneNow = new LinkedHashSet<>(gone);
            goneNow.addAll(broken);
            List<DisableableMod> more = brokenBy(goneNow);
            if (more.isEmpty()) {
                return new ArrayList<>(broken);
            }
            broken.addAll(more);
        }
    }

    /**
     * The mods staying that something in {@code gone} required and nothing staying requires any more, disabled mods
     * included, since they may be enabled again. Mods in {@code gone} that weren't asked for themselves (in
     * {@code asked}) are listed too, so an offer already ticked stays on the list.
     */
    public List<DisableableMod> unneededAfter(Collection<DisableableMod> asked, Collection<DisableableMod> gone) {
        List<DisableableMod> staying = installed.stream()
                .filter(m -> !gone.contains(m) && RequiredDependency.isPresent(m)).collect(Collectors.toList());

        return installed.stream()
                .filter(m -> !asked.contains(m) && RequiredDependency.isPresent(m)
                        && gone.stream().anyMatch(g -> needs(g, m))
                        && staying.stream().noneMatch(s -> needs(s, m)))
                .collect(Collectors.toList());
    }

    /**
     * What removing {@code selected} does under a choice, plus the unneeded dependencies the user also ticked.
     */
    public Plan plan(Collection<DisableableMod> selected, DependentsChoice choice,
            Collection<DisableableMod> alsoRemove) {
        Set<DisableableMod> remove = new LinkedHashSet<>(selected);
        Set<DisableableMod> disable = new LinkedHashSet<>();

        switch (choice) {
            case KEEP_NEEDED:
                remove = withoutNeeded(selected);
                break;
            case DISABLE_DEPENDENTS:
                disable.addAll(brokenByAll(remove));
                break;
            case REMOVE_DEPENDENTS:
                remove.addAll(brokenByAll(remove));
                break;
            default:
                break;
        }

        remove.addAll(alsoRemove);
        disable.removeAll(remove);
        return new Plan(remove, disable);
    }

    /**
     * The selected mods that can go without leaving anything staying short of a dependency: the ones something
     * staying needs are kept, and so is whatever those need in turn.
     */
    public Set<DisableableMod> withoutNeeded(Collection<DisableableMod> selected) {
        Set<DisableableMod> remove = new LinkedHashSet<>(selected);
        while (true) {
            List<DisableableMod> broken = brokenBy(remove);
            Set<DisableableMod> needed = remove.stream()
                    .filter(r -> broken.stream().anyMatch(b -> needs(b, r))).collect(Collectors.toSet());
            if (needed.isEmpty()) {
                return remove;
            }
            remove.removeAll(needed);
        }
    }
}
