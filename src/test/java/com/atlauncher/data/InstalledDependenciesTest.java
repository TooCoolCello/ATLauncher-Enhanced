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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.atlauncher.constants.Constants;
import com.atlauncher.data.InstalledDependencies.DependentsChoice;
import com.atlauncher.data.InstalledDependencies.Plan;
import com.atlauncher.data.curseforge.CurseForgeCategory;
import com.atlauncher.data.curseforge.CurseForgeFile;
import com.atlauncher.data.curseforge.CurseForgeFileDependency;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthDependency;
import com.atlauncher.data.modrinth.ModrinthDependencyType;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthVersion;

public class InstalledDependenciesTest {
    private static final LoaderVersion FABRIC = new LoaderVersion("0.16.9", false, "Fabric");
    private static final LoaderVersion QUILT = new LoaderVersion("0.26.0", false, "Quilt");

    // a Modrinth mod requiring the given Modrinth projects
    private static DisableableMod modrinth(String id, String... requires) {
        DisableableMod mod = new DisableableMod();
        mod.name = id;
        mod.modrinthProject = new ModrinthProject();
        mod.modrinthProject.id = id;
        mod.modrinthProject.categories = new ArrayList<>();
        mod.modrinthVersion = new ModrinthVersion();
        mod.modrinthVersion.id = id + "-v";
        mod.modrinthVersion.dependencies = new ArrayList<>();
        for (String required : requires) {
            ModrinthDependency dependency = new ModrinthDependency();
            dependency.projectId = required;
            dependency.dependencyType = ModrinthDependencyType.REQUIRED;
            mod.modrinthVersion.dependencies.add(dependency);
        }
        return mod;
    }

    private static DisableableMod curseForge(int id, int... requires) {
        DisableableMod mod = new DisableableMod();
        mod.name = "cf" + id;
        mod.curseForgeProjectId = id;
        mod.curseForgeFileId = id * 10;
        mod.curseForgeFile = new CurseForgeFile();
        mod.curseForgeFile.dependencies = new ArrayList<>();
        for (int required : requires) {
            CurseForgeFileDependency dependency = new CurseForgeFileDependency();
            dependency.modId = required;
            dependency.relationType = 3;
            mod.curseForgeFile.dependencies.add(dependency);
        }
        return mod;
    }

    private static DisableableMod noData(String name) {
        DisableableMod mod = new DisableableMod();
        mod.name = name;
        return mod;
    }

    private static InstalledDependencies of(LoaderVersion loader, boolean connector, DisableableMod... mods) {
        return new InstalledDependencies(Arrays.asList(mods), loader, connector);
    }

    @Test
    public void removingADependencyBreaksTheModsThatNeedItUnlessSomethingElseProvidesIt() {
        DisableableMod lib = modrinth("lib");
        DisableableMod user = modrinth("user", "lib");
        DisableableMod unrelated = modrinth("unrelated");
        InstalledDependencies deps = of(FABRIC, false, lib, user, unrelated);

        assertEquals(Collections.singletonList(user), deps.brokenBy(Collections.singletonList(lib)));
        assertTrue(deps.brokenBy(Collections.singletonList(unrelated)).isEmpty());
        // removing both together breaks nothing left behind
        assertTrue(deps.brokenBy(Arrays.asList(lib, user)).isEmpty());

        // Fabric API needed, but Quilted Fabric API stays
        DisableableMod fabricApi = modrinth(Constants.MODRINTH_FABRIC_MOD_ID);
        DisableableMod qfapi = modrinth(Constants.MODRINTH_QSL_MOD_ID);
        DisableableMod fabricMod = modrinth("fabricmod", Constants.MODRINTH_FABRIC_MOD_ID);
        assertTrue(of(QUILT, false, fabricApi, qfapi, fabricMod).brokenBy(Collections.singletonList(fabricApi))
                .isEmpty());
    }

    @Test
    public void disabledDependentsAreNotBrokenAndDisabledProvidersDoNotCount() {
        DisableableMod lib = modrinth("lib");
        DisableableMod user = modrinth("user", "lib");
        user.disabled = true;
        assertTrue(of(FABRIC, false, lib, user).brokenBy(Collections.singletonList(lib)).isEmpty());

        DisableableMod enabledUser = modrinth("user", "lib");
        DisableableMod disabledCopy = modrinth("lib");
        disabledCopy.disabled = true;
        assertEquals(Collections.singletonList(enabledUser),
                of(FABRIC, false, lib, disabledCopy, enabledUser).brokenBy(Collections.singletonList(lib)));
    }

    @Test
    public void crossPlatformAndConnectorEquivalentsAreMatched() {
        // a Modrinth mod needing Fabric API, provided by Fabric API from CurseForge
        DisableableMod cfFabricApi = curseForge(Constants.CURSEFORGE_FABRIC_MOD_ID);
        DisableableMod mrUser = modrinth("user", Constants.MODRINTH_FABRIC_MOD_ID);
        assertTrue(of(FABRIC, false, cfFabricApi, mrUser).needs(mrUser, cfFabricApi));

        // under Connector, Forgified Fabric API provides Fabric API for a CurseForge mod
        DisableableMod forgified = curseForge(Constants.CURSEFORGE_FORGIFIED_FABRIC_API_MOD_ID);
        DisableableMod cfUser = curseForge(1, Constants.CURSEFORGE_FABRIC_MOD_ID);
        assertTrue(of(null, true, forgified, cfUser).needs(cfUser, forgified));
        assertFalse(of(null, false, forgified, cfUser).needs(cfUser, forgified));
    }

    @Test
    public void chainsAreFollowedWhenDependentsGoToo() {
        DisableableMod lib = modrinth("lib");
        DisableableMod middle = modrinth("middle", "lib");
        DisableableMod top = modrinth("top", "middle");
        InstalledDependencies deps = of(FABRIC, false, lib, middle, top);

        assertEquals(Collections.singletonList(middle), deps.brokenBy(Collections.singletonList(lib)));
        assertEquals(Arrays.asList(middle, top), deps.brokenByAll(Collections.singletonList(lib)));

        Plan removeAll = deps.plan(Collections.singletonList(lib), DependentsChoice.REMOVE_DEPENDENTS,
                Collections.emptyList());
        assertEquals(new HashSet<>(Arrays.asList(lib, middle, top)), removeAll.remove);
        assertTrue(removeAll.disable.isEmpty());

        Plan disable = deps.plan(Collections.singletonList(lib), DependentsChoice.DISABLE_DEPENDENTS,
                Collections.emptyList());
        assertEquals(Collections.singleton(lib), disable.remove);
        assertEquals(new HashSet<>(Arrays.asList(middle, top)), disable.disable);

        Plan anyway = deps.plan(Collections.singletonList(lib), DependentsChoice.REMOVE_ANYWAY,
                Collections.emptyList());
        assertEquals(Collections.singleton(lib), anyway.remove);
        assertTrue(anyway.disable.isEmpty());
    }

    @Test
    public void keepingNeededModsAlsoKeepsWhatTheyNeed() {
        DisableableMod base = modrinth("base");
        DisableableMod lib = modrinth("lib", "base");
        DisableableMod user = modrinth("user", "lib");
        DisableableMod unrelated = modrinth("unrelated");
        InstalledDependencies deps = of(FABRIC, false, base, lib, user, unrelated);

        Plan plan = deps.plan(Arrays.asList(base, lib, unrelated), DependentsChoice.KEEP_NEEDED,
                Collections.emptyList());
        assertEquals(Collections.singleton(unrelated), plan.remove);
    }

    @Test
    public void dependenciesNothingElseNeedsAreOffered() {
        DisableableMod lib = modrinth("lib");
        DisableableMod shared = modrinth("shared");
        DisableableMod removed = modrinth("removed", "lib", "shared");
        DisableableMod other = modrinth("other", "shared");
        InstalledDependencies deps = of(FABRIC, false, lib, shared, removed, other);

        List<DisableableMod> removing = Collections.singletonList(removed);
        assertEquals(Collections.singletonList(lib), deps.unneededAfter(removing, removing));

        // a library ticked for removal stays on the list, and what only it needed joins it
        DisableableMod deep = modrinth("deep");
        DisableableMod lib2 = modrinth("lib2", "deep");
        DisableableMod removed2 = modrinth("removed2", "lib2");
        InstalledDependencies chain = of(FABRIC, false, deep, lib2, removed2);
        List<DisableableMod> asked = Collections.singletonList(removed2);
        assertEquals(Collections.singletonList(lib2), chain.unneededAfter(asked, asked));
        assertEquals(Arrays.asList(deep, lib2), chain.unneededAfter(asked, Arrays.asList(removed2, lib2)));
    }

    @Test
    public void disabledModsStillHoldOnToTheirDependencies() {
        DisableableMod lib = modrinth("lib");
        DisableableMod removed = modrinth("removed", "lib");
        DisableableMod disabledUser = modrinth("user", "lib");
        disabledUser.disabled = true;
        List<DisableableMod> removing = Collections.singletonList(removed);
        assertTrue(of(FABRIC, false, lib, removed, disabledUser).unneededAfter(removing, removing).isEmpty());
    }

    @Test
    public void modsWithoutStoredDataAreToldApart() {
        assertFalse(InstalledDependencies.hasData(noData("manual.jar")));
        assertTrue(InstalledDependencies.hasData(modrinth("x")));
        assertTrue(InstalledDependencies.hasData(curseForge(5)));

        // no data means it's never taken to need anything, and it's listed so the user is told
        DisableableMod lib = modrinth("lib");
        DisableableMod manual = noData("manual.jar");
        manual.type = Type.mods;
        DisableableMod pack = noData("pack.zip");
        pack.type = Type.resourcepack;
        InstalledDependencies deps = of(FABRIC, false, lib, manual, pack);
        assertTrue(deps.brokenBy(Collections.singletonList(lib)).isEmpty());
        assertEquals(Collections.singletonList(manual), deps.withoutData(Collections.singletonList(lib)));
        assertTrue(deps.withoutData(Collections.singletonList(manual)).isEmpty());
    }

    @Test
    public void librariesAreRecognisedFromPlatformCategories() {
        DisableableMod modrinthLib = modrinth("lib");
        modrinthLib.modrinthProject.categories.add("library");
        assertTrue(InstalledDependencies.isLibrary(modrinthLib));
        assertFalse(InstalledDependencies.isLibrary(modrinth("content")));

        DisableableMod cfLib = curseForge(9);
        cfLib.curseForgeProject = new CurseForgeProject();
        CurseForgeCategory category = new CurseForgeCategory();
        category.id = 421;
        cfLib.curseForgeProject.categories = Collections.singletonList(category);
        assertTrue(InstalledDependencies.isLibrary(cfLib));
    }
}
