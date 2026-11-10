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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.atlauncher.data.DependencyChoice.Action;
import com.atlauncher.data.DependencyChoice.Kind;
import com.atlauncher.data.RequiredDependency.State;
import com.atlauncher.data.curseforge.CurseForgeFile;
import com.atlauncher.data.curseforge.CurseForgeFileDependency;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthChannel;
import com.atlauncher.data.modrinth.ModrinthDependencyType;
import com.atlauncher.data.modrinth.ModrinthVersion;

public class DependencyChoiceTest {
    private static final String MC = "1.20.1";
    private static final LoaderVersion FABRIC = new LoaderVersion("0.16.9", false, "Fabric");
    private static final LoaderVersion NEOFORGE = new LoaderVersion("47.1.106", false, "NeoForge");

    private static ModrinthVersion mr(String id, ModrinthChannel channel, String gameVersion, String... loaders) {
        ModrinthVersion version = new ModrinthVersion();
        version.id = id;
        version.versionType = channel;
        version.gameVersions = Collections.singletonList(gameVersion);
        version.loaders = Arrays.asList(loaders);
        return version;
    }

    private static ModrinthVersion pick(List<ModrinthVersion> newestFirst, String declared,
            ModCompatibility.Target target, boolean preferConnector) {
        Predicate<ModrinthVersion> isDeclared = declared == null ? null : v -> declared.equals(v.id);
        return DependencyChoice.pickFile(newestFirst, v -> v.loaders, v -> v.gameVersions,
                v -> v.versionType == ModrinthChannel.RELEASE, isDeclared, target, preferConnector);
    }

    @Test
    public void requiredDependenciesAreFixedByDefaultAndOptionalOnesLeftAlone() {
        assertEquals(Action.INSTALL, DependencyChoice.defaultAction(Kind.REQUIRED, State.MISSING, null));
        assertEquals(Action.SKIP, DependencyChoice.defaultAction(Kind.OPTIONAL, State.MISSING, null));
        // optional ones are still listed with Install on offer
        assertTrue(DependencyChoice.actions(Kind.OPTIONAL, State.MISSING).contains(Action.INSTALL));

        assertEquals(Action.ENABLE, DependencyChoice.defaultAction(Kind.REQUIRED, State.DISABLED, null));
        assertEquals(Action.KEEP, DependencyChoice.defaultAction(Kind.OPTIONAL, State.DISABLED, null));
        assertEquals(Action.INSTALL, DependencyChoice.defaultAction(Kind.REQUIRED, State.FILE_MISSING, null));
        assertEquals(Action.INSTALL,
                DependencyChoice.defaultAction(Kind.REQUIRED, State.USE_FORGIFIED_FABRIC_API, null));
        assertEquals(Action.REPLACE, DependencyChoice.defaultAction(Kind.REQUIRED, State.FABRIC_API_CONFLICT, null));
        assertEquals(Collections.singletonList(Action.SKIP), DependencyChoice.actions(Kind.REQUIRED, State.UNRESOLVED));
    }

    @Test
    public void satisfiedDependenciesOnlyOfferKeep() {
        assertEquals(Collections.singletonList(Action.KEEP), DependencyChoice.actions(Kind.REQUIRED, State.SATISFIED));
        assertEquals(Action.KEEP, DependencyChoice.defaultAction(Kind.REQUIRED, State.SATISFIED, null));
        assertNull(DependencyChoice.warning(Kind.REQUIRED, State.SATISFIED, Action.KEEP, null));
        assertTrue(DependencyChoice.satisfiedStatus("Quilted Fabric API", "7.0", true).contains("can't verify"));
    }

    @Test
    public void anOlderInstalledVersionIsReplacedButANewerOneKept() {
        assertEquals(Action.REPLACE, DependencyChoice.defaultAction(Kind.REQUIRED, State.OTHER_VERSION, true));
        assertEquals(Action.KEEP, DependencyChoice.defaultAction(Kind.REQUIRED, State.OTHER_VERSION, false));
        assertEquals(Action.KEEP, DependencyChoice.defaultAction(Kind.REQUIRED, State.OTHER_VERSION, null));
        // another loader path often still works, so it's never replaced unasked
        assertEquals(Action.KEEP, DependencyChoice.defaultAction(Kind.REQUIRED, State.OTHER_LOADER, null));
        assertTrue(DependencyChoice.actions(Kind.REQUIRED, State.OTHER_LOADER).contains(Action.REPLACE));

        assertTrue(DependencyChoice.isNewer("2024-03-01T00:00:00Z", "2023-12-31T23:59:59Z"));
        assertFalse(DependencyChoice.isNewer("2023-01-01T00:00:00Z", "2023-12-31T23:59:59Z"));
        assertNull(DependencyChoice.isNewer(null, "2023-12-31T23:59:59Z"));
    }

    @Test
    public void incompatibleModsAreDisabledByDefault() {
        assertEquals(Action.DISABLE, DependencyChoice.defaultAction(Kind.INCOMPATIBLE, State.SATISFIED, null));
        assertEquals(Arrays.asList(Action.DISABLE, Action.REMOVE, Action.KEEP),
                DependencyChoice.actions(Kind.INCOMPATIBLE, State.MISSING));
        assertNotNull(DependencyChoice.warning(Kind.INCOMPATIBLE, State.SATISFIED, Action.KEEP, null));
        assertNull(DependencyChoice.warning(Kind.INCOMPATIBLE, State.SATISFIED, Action.DISABLE, null));
    }

    @Test
    public void skippingOrKeepingAnUnmetRequiredDependencyWarns() {
        assertNotNull(DependencyChoice.warning(Kind.REQUIRED, State.MISSING, Action.SKIP, null));
        assertTrue(DependencyChoice.warning(Kind.REQUIRED, State.OTHER_LOADER, Action.KEEP, "Other loader.")
                .contains("Other loader."));
        assertNull(DependencyChoice.warning(Kind.OPTIONAL, State.MISSING, Action.SKIP, null));
        assertNull(DependencyChoice.warning(Kind.REQUIRED, State.MISSING, Action.INSTALL, null));
    }

    @Test
    public void platformDependencyTypesMapToKinds() {
        assertEquals(Kind.INCOMPATIBLE, DependencyChoice.kindOf(ModrinthDependencyType.INCOMPATIBLE));
        assertNull(DependencyChoice.kindOf(ModrinthDependencyType.EMBEDDED));
        assertNull(DependencyChoice.kindOf((ModrinthDependencyType) null));

        CurseForgeFileDependency incompatible = new CurseForgeFileDependency();
        incompatible.relationType = 5;
        assertEquals(Kind.INCOMPATIBLE, DependencyChoice.kindOf(incompatible));
        CurseForgeFileDependency tool = new CurseForgeFileDependency();
        tool.relationType = 4;
        assertNull(DependencyChoice.kindOf(tool));
    }

    @Test
    public void defaultFileIsTheNewestReleaseForThisMinecraftVersionAndLoader() {
        ModCompatibility.Target fabric = new ModCompatibility.Target(MC, FABRIC, false, false);
        ModrinthVersion otherMc = mr("other-mc", ModrinthChannel.RELEASE, "1.21", "fabric");
        ModrinthVersion forge = mr("forge", ModrinthChannel.RELEASE, MC, "forge");
        ModrinthVersion beta = mr("beta", ModrinthChannel.BETA, MC, "fabric");
        ModrinthVersion release = mr("release", ModrinthChannel.RELEASE, MC, "fabric");
        ModrinthVersion olderRelease = mr("older", ModrinthChannel.RELEASE, MC, "fabric");

        assertSame(release, pick(Arrays.asList(otherMc, forge, beta, release, olderRelease), null, fabric, false));
        // a beta only when there's no release
        assertSame(beta, pick(Arrays.asList(otherMc, forge, beta), null, fabric, false));
        assertNull(pick(Arrays.asList(otherMc, forge), null, fabric, false));
        // the declared version wins when it runs here, and only then
        assertSame(olderRelease, pick(Arrays.asList(beta, release, olderRelease), "older", fabric, false));
        assertSame(release, pick(Arrays.asList(otherMc, release), "other-mc", fabric, false));
    }

    @Test
    public void connectorBuildsArePickedOnlyWhenConnectorIsActiveAndNativeFirstUnlessTheParentNeedsConnector() {
        ModrinthVersion fabricBuild = mr("fabric", ModrinthChannel.RELEASE, MC, "fabric");
        ModrinthVersion nativeBuild = mr("neo", ModrinthChannel.RELEASE, MC, "neoforge");
        List<ModrinthVersion> both = Arrays.asList(fabricBuild, nativeBuild);

        ModCompatibility.Target withConnector = new ModCompatibility.Target(MC, NEOFORGE, false, true);
        ModCompatibility.Target withoutConnector = new ModCompatibility.Target(MC, NEOFORGE, false, false);

        assertSame(nativeBuild, pick(both, null, withConnector, false));
        assertSame(fabricBuild, pick(both, null, withConnector, true));
        assertSame(fabricBuild, pick(Collections.singletonList(fabricBuild), null, withConnector, false));
        assertNull(pick(Collections.singletonList(fabricBuild), null, withoutConnector, false));
    }

    @Test
    public void curseForgeFilesWithNoLoaderTagComeLast() {
        ModCompatibility.Target fabric = new ModCompatibility.Target(MC, FABRIC, false, false);
        CurseForgeFile untagged = new CurseForgeFile();
        untagged.gameVersions = Arrays.asList(MC);
        untagged.releaseType = 1;
        CurseForgeFile tagged = new CurseForgeFile();
        tagged.gameVersions = Arrays.asList(MC, "Fabric");
        tagged.releaseType = 2;

        assertSame(tagged, DependencyChoice.pickFile(Arrays.asList(untagged, tagged), f -> f.gameVersions,
                f -> f.gameVersions, f -> f.releaseType != null && f.releaseType == 1, null, fabric, false));
        assertSame(untagged, DependencyChoice.pickFile(Collections.singletonList(untagged), f -> f.gameVersions,
                f -> f.gameVersions, f -> f.releaseType != null && f.releaseType == 1, null, fabric, false));
    }
}
