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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.constants.Constants;
import com.atlauncher.data.RequiredDependency.State;
import com.atlauncher.data.curseforge.CurseForgeFile;
import com.atlauncher.data.minecraft.MinecraftVersion;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthVersion;

/**
 * Required dependencies of a file being added: they come from that exact file, a Connector parent gets a Fabric build
 * suggested but may deliberately pick another, a same-project install on another loader or version isn't taken as
 * satisfying it, Fabric API goes through Forgified Fabric API, and unresolved or doubtful ones are flagged before
 * adding. Nothing is installed by any of it.
 */
public class RequiredDependencyTest {
    private static final String MC = "1.21.1";
    private static final List<String> NO_COMPAT = Collections.emptyList();
    private static final LoaderVersion NEOFORGE = new LoaderVersion("21.1.77", false, "NeoForge");
    private static final LoaderVersion FABRIC = new LoaderVersion("0.16.9", false, "Fabric");

    private Settings previousSettings;

    @BeforeEach
    public void setUp() {
        previousSettings = App.settings;
        App.settings = new Settings();
        App.settings.showFabricModsWhenSinytraInstalled = true;
    }

    @AfterEach
    public void tearDown() {
        App.settings = previousSettings;
    }

    private static DisableableMod connector() {
        DisableableMod mod = new DisableableMod();
        mod.curseForgeProjectId = Constants.CURSEFORGE_SINYTRA_CONNECTOR_MOD_ID;
        mod.curseForgeFileId = 5555555;
        return mod;
    }

    private static Instance instance(DisableableMod... mods) {
        MinecraftVersion version = new MinecraftVersion();
        version.id = MC;
        Instance instance = new Instance(version);
        instance.launcher = new InstanceLauncher();
        instance.launcher.loaderVersion = NEOFORGE;
        instance.launcher.mods = new ArrayList<>(Arrays.asList(mods));
        return instance;
    }

    private static Server server(DisableableMod... mods) {
        Server server = new Server();
        server.minecraftVersion = MC;
        server.loaderVersion = NEOFORGE;
        server.mods = new ArrayList<>(Arrays.asList(mods));
        return server;
    }

    private static CurseForgeFile cf(int id, String... gameVersions) {
        CurseForgeFile file = new CurseForgeFile();
        file.id = id;
        file.gameVersions = new ArrayList<>(Arrays.asList(gameVersions));
        return file;
    }

    private static DisableableMod installedCurseForge(CurseForgeFile file) {
        DisableableMod mod = new DisableableMod();
        mod.curseForgeProjectId = 777;
        mod.curseForgeFileId = file == null ? 1 : file.id;
        mod.curseForgeFile = file;
        mod.version = "2.0.0";
        return mod;
    }

    private static ModrinthVersion mr(String id, String... loaders) {
        ModrinthVersion version = new ModrinthVersion();
        version.id = id;
        version.name = id;
        version.loaders = Arrays.asList(loaders);
        version.gameVersions = Collections.singletonList(MC);
        return version;
    }

    private static DisableableMod installedModrinth(String versionId, String... loaders) {
        DisableableMod mod = new DisableableMod();
        mod.modrinthProject = new ModrinthProject();
        mod.modrinthProject.id = "lib12345";
        mod.modrinthVersion = mr(versionId, loaders);
        return mod;
    }

    private static final Predicate<ModrinthVersion> CONNECTOR_BUILD = v -> SinytraConnector
            .isConnectorBuild(v.loaders, v.gameVersions, MC, NEOFORGE, false);

    // --- the dependency's own selector

    @Test
    public void connectorParentGetsAFabricBuildSuggested() {
        ModrinthVersion nativeNew = mr("neo-new", "neoforge");
        ModrinthVersion fabric = mr("fabric", "fabric");
        List<ModrinthVersion> listed = Arrays.asList(nativeNew, fabric, mr("neo-old", "neoforge"));

        assertSame(fabric, RequiredDependency.initialSelection(listed, null, true, CONNECTOR_BUILD));
        // native-first everywhere else, and when there's no Fabric build to suggest
        assertSame(nativeNew, RequiredDependency.initialSelection(listed, null, false, CONNECTOR_BUILD));
        assertSame(nativeNew, RequiredDependency.initialSelection(Collections.singletonList(nativeNew), null, true,
                CONNECTOR_BUILD));
    }

    @Test
    public void choosingANativeBuildForAConnectorParentIsAllowedButFlagged() {
        ModrinthVersion nativeBuild = mr("neo", "neoforge");
        ModrinthVersion mixed = mr("both", "fabric", "neoforge");

        assertTrue(RequiredDependency.isOverride(nativeBuild, null, true, CONNECTOR_BUILD));
        // a build tagged both ways loads natively, so it's flagged too
        assertTrue(RequiredDependency.isOverride(mixed, null, true, CONNECTOR_BUILD));
        assertFalse(RequiredDependency.isOverride(mr("fabric", "fabric"), null, true, CONNECTOR_BUILD));
        assertFalse(RequiredDependency.isOverride(nativeBuild, null, false, CONNECTOR_BUILD));
        assertFalse(RequiredDependency.isOverride(null, null, true, CONNECTOR_BUILD));
    }

    @Test
    public void declaredModrinthVersionIsSelectedOrItsAbsenceShown() {
        Predicate<ModrinthVersion> pinned = v -> "v2".equalsIgnoreCase(v.id);
        ModrinthVersion v2 = mr("v2", "fabric");
        List<ModrinthVersion> listed = Arrays.asList(mr("v3", "fabric"), v2, mr("v1", "fabric"));

        assertSame(v2, RequiredDependency.initialSelection(listed, pinned, true, CONNECTOR_BUILD));
        // not listed: nothing is selected rather than another version
        assertNull(RequiredDependency.initialSelection(Arrays.asList(mr("v3", "fabric")), pinned, true,
                CONNECTOR_BUILD));
        // the user may still pick another, flagged
        assertTrue(RequiredDependency.isOverride(mr("v3", "fabric"), pinned, false, CONNECTOR_BUILD));
        assertFalse(RequiredDependency.isOverride(v2, pinned, true, CONNECTOR_BUILD));
    }

    // --- dependency cards

    @Test
    public void sameProjectOnAnotherLoaderDoesNotCountAsSatisfied() {
        for (ModManagement target : Arrays.asList(instance(connector()), server(connector()))) {
            // a Fabric parent through Connector, the library installed as its NeoForge build
            DisableableMod nativeLib = installedCurseForge(cf(100, MC, "NeoForge"));
            State state = RequiredDependency.state(nativeLib.curseForgeInstalledPath(target, new ArrayList<>(),
                    NO_COMPAT), null, true, null, null);

            assertEquals(State.OTHER_LOADER, state);
            assertFalse(RequiredDependency.offersAdd(state));
            assertTrue(RequiredDependency.needsAcknowledgement(state));
            assertTrue(RequiredDependency.status(state, true, nativeLib.version).contains("2.0.0"));

            // a native parent, the library installed as a Fabric build through Connector
            DisableableMod fabricLib = installedCurseForge(cf(100, MC, "Fabric"));
            assertEquals(State.OTHER_LOADER, RequiredDependency.state(fabricLib.curseForgeInstalledPath(target,
                    new ArrayList<>(), NO_COMPAT), null, false, null, null));

            // matching paths still count
            assertEquals(State.SATISFIED, RequiredDependency.state(fabricLib.curseForgeInstalledPath(target,
                    new ArrayList<>(), NO_COMPAT), null, true, null, null));
            assertEquals(State.SATISFIED, RequiredDependency.state(nativeLib.curseForgeInstalledPath(target,
                    new ArrayList<>(), NO_COMPAT), null, false, null, null));
        }
    }

    @Test
    public void unknownInstalledLoaderIsNeverConfirmed() {
        ModManagement target = instance(connector());
        DisableableMod noMetadata = installedCurseForge(null);
        DisableableMod noLoaders = installedModrinth("v1");

        for (boolean parentNeedsConnector : new boolean[] { true, false }) {
            assertEquals(State.UNKNOWN_LOADER, RequiredDependency.state(noMetadata.curseForgeInstalledPath(target,
                    new ArrayList<>(), NO_COMPAT), null, parentNeedsConnector, null, null));
            assertEquals(State.UNKNOWN_LOADER, RequiredDependency.state(noLoaders.modrinthInstalledPath(target, null,
                    NO_COMPAT), null, parentNeedsConnector, null, "v1"));
        }
        assertFalse(RequiredDependency.offersAdd(State.UNKNOWN_LOADER));
        assertTrue(RequiredDependency.needsAcknowledgement(State.UNKNOWN_LOADER));

        // on loaders without Connector there's only one path, so it's satisfied as before
        Instance fabricInstance = instance();
        fabricInstance.launcher.loaderVersion = FABRIC;
        assertEquals(State.SATISFIED, RequiredDependency.state(noMetadata.curseForgeInstalledPath(fabricInstance,
                new ArrayList<>(), NO_COMPAT), null, false, null, null));
    }

    @Test
    public void installedAtAnotherVersionThanDeclaredIsFlagged() {
        ModManagement target = server(connector());
        DisableableMod lib = installedModrinth("v1", "fabric");
        SinytraConnector.InstalledPath path = lib.modrinthInstalledPath(target, null, NO_COMPAT);

        assertEquals(State.OTHER_VERSION, RequiredDependency.state(path, null, true, "v2", lib.modrinthVersion.id));
        // Add stays offered: its selector is locked to the installed loader and preselects the declared version
        assertTrue(RequiredDependency.offersAdd(State.OTHER_VERSION));
        assertTrue(RequiredDependency.needsAcknowledgement(State.OTHER_VERSION));
        assertEquals(State.SATISFIED, RequiredDependency.state(path, null, true, "V1", lib.modrinthVersion.id));
        assertEquals(State.SATISFIED, RequiredDependency.state(path, null, true, null, lib.modrinthVersion.id));
        assertEquals(State.MISSING, RequiredDependency.state(null, null, true, "v2", null));
    }

    @Test
    public void missingCurseForgeProjectIsUnresolvedNotSatisfied() {
        for (State state : State.values()) {
            assertEquals(State.UNRESOLVED, RequiredDependency.withProject(state, null));
        }
        assertEquals(State.MISSING, RequiredDependency.withProject(State.MISSING, new Object()));

        assertFalse(RequiredDependency.offersAdd(State.UNRESOLVED));
        assertTrue(RequiredDependency.needsAcknowledgement(State.UNRESOLVED));
        assertNotNull(RequiredDependency.status(State.UNRESOLVED, true, null));
    }

    @Test
    public void fabricApiGoesThroughForgifiedFabricApiAndNeverBoth() {
        assertEquals(State.USE_FORGIFIED_FABRIC_API, RequiredDependency.fabricApiUnderConnector(false, false, null));
        assertTrue(RequiredDependency.offersAdd(State.USE_FORGIFIED_FABRIC_API));
        assertEquals(State.SATISFIED, RequiredDependency.fabricApiUnderConnector(false, true, null));
        assertEquals(State.FABRIC_API_CONFLICT, RequiredDependency.fabricApiUnderConnector(true, false, null));
        assertEquals(State.FABRIC_API_CONFLICT, RequiredDependency.fabricApiUnderConnector(true, true, null));
        assertFalse(RequiredDependency.offersAdd(State.FABRIC_API_CONFLICT));

        // an installed Forgified Fabric API only provides it while usable, and isn't replaced from the card
        for (State inactive : Arrays.asList(State.DISABLED, State.NOT_DOWNLOADED, State.FILE_MISSING)) {
            assertEquals(inactive, RequiredDependency.fabricApiUnderConnector(false, true, inactive));
            assertFalse(RequiredDependency.offersAdd(inactive));
        }

        // ordinary Fabric API isn't added under Connector, nor Forgified next to it
        assertNotNull(RequiredDependency.fabricApiInstallBlocker(true, false, true, true, false));
        assertNotNull(RequiredDependency.fabricApiInstallBlocker(false, true, true, false, true));
        assertNull(RequiredDependency.fabricApiInstallBlocker(false, true, true, true, false));
        // Fabric API on a Fabric instance, or anything else, isn't stopped
        assertNull(RequiredDependency.fabricApiInstallBlocker(true, false, false, false, false));
        assertNull(RequiredDependency.fabricApiInstallBlocker(false, false, true, true, true));

        DisableableMod curseForgeFabricApi = new DisableableMod();
        curseForgeFabricApi.curseForgeProjectId = Constants.CURSEFORGE_FABRIC_MOD_ID;
        curseForgeFabricApi.curseForgeFileId = 1;
        DisableableMod modrinthForgified = installedModrinth("f1", "neoforge");
        modrinthForgified.modrinthProject.id = Constants.MODRINTH_FORGIFIED_FABRIC_API_MOD_ID;
        List<DisableableMod> mods = Arrays.asList(curseForgeFabricApi, modrinthForgified);

        assertTrue(RequiredDependency.anyInstalled(mods, RequiredDependency::isFabricApi));
        assertTrue(RequiredDependency.anyInstalled(mods, RequiredDependency::isForgifiedFabricApi));
        assertFalse(RequiredDependency.anyInstalled(Collections.singletonList(modrinthForgified),
                RequiredDependency::isFabricApi));

        // a disabled ordinary Fabric API is still on disk, so it still conflicts; a never-downloaded one doesn't
        curseForgeFabricApi.disabled = true;
        assertTrue(RequiredDependency.isPresent(curseForgeFabricApi));
        curseForgeFabricApi.skipped = true;
        assertFalse(RequiredDependency.isPresent(curseForgeFabricApi));
    }

    @Test
    public void disabledUndownloadedOrMissingDependenciesDoNotSatisfy() {
        for (ModManagement target : Arrays.asList(instance(connector()), server(connector()))) {
            DisableableMod lib = installedCurseForge(cf(100, MC, "Fabric"));
            SinytraConnector.InstalledPath path = lib.curseForgeInstalledPath(target, new ArrayList<>(), NO_COMPAT);

            assertNull(RequiredDependency.activity(lib, true));
            assertEquals(State.SATISFIED, RequiredDependency.state(path, null, true, null, null));

            assertEquals(State.FILE_MISSING, RequiredDependency.activity(lib, false));

            lib.disabled = true;
            assertEquals(State.DISABLED, RequiredDependency.activity(lib, true));

            lib.disabled = false;
            lib.skipped = true;
            assertEquals(State.NOT_DOWNLOADED, RequiredDependency.activity(lib, true));

            lib.skipped = false;
            lib.wasSelected = false;
            assertEquals(State.NOT_DOWNLOADED, RequiredDependency.activity(lib, true));

            for (State inactive : Arrays.asList(State.DISABLED, State.NOT_DOWNLOADED, State.FILE_MISSING)) {
                // on the right loader and version, but still not satisfying it
                assertEquals(inactive, RequiredDependency.state(path, inactive, true, null, null));
                assertFalse(RequiredDependency.offersAdd(inactive));
                assertTrue(RequiredDependency.needsAcknowledgement(inactive));
                assertNotNull(RequiredDependency.status(inactive, true, lib.version));
            }

            // shown, never changed
            assertFalse(lib.disabled);
            assertEquals(Integer.valueOf(100), lib.curseForgeFileId);
        }
    }

    @Test
    public void stateIsReadFromTheModAsItIsNow() {
        // a card rendered while the library was enabled doesn't vouch for it after it's disabled
        ModManagement target = instance(connector());
        DisableableMod lib = installedModrinth("v1", "fabric");
        SinytraConnector.InstalledPath path = lib.modrinthInstalledPath(target, null, NO_COMPAT);

        assertEquals(State.SATISFIED, RequiredDependency.state(path, RequiredDependency.activity(lib, true), true,
                null, "v1"));
        lib.disabled = true;
        assertEquals(State.DISABLED, RequiredDependency.state(path, RequiredDependency.activity(lib, true), true,
                null, "v1"));
    }

    @Test
    public void everyUnsatisfiedDependencyIsAcknowledgedBeforeAdding() {
        for (State state : State.values()) {
            assertEquals(state != State.SATISFIED, RequiredDependency.needsAcknowledgement(state), state.name());
        }

        // including one that's simply not installed yet, and the Forgified Fabric API suggestion
        assertEquals(GetText.tr("Not installed."), RequiredDependency.warning(State.MISSING, true, null));
        assertNull(RequiredDependency.status(State.MISSING, true, null));
        assertNotNull(RequiredDependency.warning(State.USE_FORGIFIED_FABRIC_API, true, null));
        for (State state : State.values()) {
            if (state != State.SATISFIED) {
                assertNotNull(RequiredDependency.warning(state, true, "1.0"), state.name());
            }
        }
    }
}
