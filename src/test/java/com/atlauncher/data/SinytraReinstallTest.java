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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.atlauncher.App;
import com.atlauncher.constants.Constants;
import com.atlauncher.data.curseforge.CurseForgeFile;
import com.atlauncher.data.minecraft.MinecraftVersion;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthVersion;
import com.atlauncher.gui.dialogs.CurseForgeProjectFileSelectorDialog;
import com.atlauncher.gui.dialogs.ModrinthVersionSelectorDialog;

/**
 * Reinstall offers only files on the installed file's loader path, preselects the exact installed file, and never
 * falls back to another one. The selectors stop with a message when the installed file isn't offered, and Reinstall is
 * blocked while the loader is unknown or a Connector file's Connector is inactive. No network: everything the checks
 * need is built in memory.
 */
public class SinytraReinstallTest {
    private static final String MC = "1.21.1";
    private static final List<String> NO_FORGE_COMPAT = Collections.emptyList();
    private static final LoaderVersion NEOFORGE = new LoaderVersion("21.1.77", false, "NeoForge");
    private static final LoaderVersion FABRIC = new LoaderVersion("0.16.9", false, "Fabric");

    private Settings previousSettings;

    @BeforeEach
    public void setUp() {
        previousSettings = App.settings;
        App.settings = new Settings();
        App.settings.addModRestriction = AddModRestriction.STRICT;
        App.settings.showFabricModsWhenSinytraInstalled = true;
    }

    @AfterEach
    public void tearDown() {
        App.settings = previousSettings;
    }

    private static DisableableMod connector(boolean disabled) {
        DisableableMod mod = new DisableableMod();
        mod.curseForgeProjectId = Constants.CURSEFORGE_SINYTRA_CONNECTOR_MOD_ID;
        mod.curseForgeFileId = 5555555;
        mod.disabled = disabled;
        return mod;
    }

    private static Instance instance(LoaderVersion loader, DisableableMod... mods) {
        MinecraftVersion version = new MinecraftVersion();
        version.id = MC;
        Instance instance = new Instance(version);
        instance.launcher = new InstanceLauncher();
        instance.launcher.loaderVersion = loader;
        instance.launcher.mods = new ArrayList<>(Arrays.asList(mods));
        return instance;
    }

    private static Server server(LoaderVersion loader, DisableableMod... mods) {
        Server server = new Server();
        server.minecraftVersion = MC;
        server.loaderVersion = loader;
        server.mods = new ArrayList<>(Arrays.asList(mods));
        return server;
    }

    // --- CurseForge

    private static CurseForgeFile cf(int id, String... gameVersions) {
        CurseForgeFile file = new CurseForgeFile();
        file.id = id;
        file.gameVersions = new ArrayList<>(Arrays.asList(gameVersions));
        return file;
    }

    private static DisableableMod installedCurseForge(CurseForgeFile stored, int fileId) {
        DisableableMod mod = new DisableableMod();
        mod.name = "Some Fabric Mod";
        mod.file = "some-fabric-mod.jar";
        mod.curseForgeProjectId = 123;
        mod.curseForgeFileId = fileId;
        mod.curseForgeFile = stored;
        return mod;
    }

    // what Reinstall offers (the selector's loader filter is the update filter) and what it preselects
    private static List<CurseForgeFile> offered(DisableableMod installed, ModManagement target,
            List<CurseForgeFile> projectFiles) {
        CurseForgeProjectFileSelectorDialog.markMissingTagsAsUntagged(projectFiles);
        return projectFiles.stream().filter(installed.curseForgeUpdateCandidate(target, projectFiles,
                NO_FORGE_COMPAT)).collect(Collectors.toList());
    }

    private static String blocker(DisableableMod installed, ModManagement target, List<CurseForgeFile> files) {
        return DisableableMod.reinstallBlocker(installed.name,
                installed.curseForgeInstalledPath(target, files, NO_FORGE_COMPAT),
                target.isForgeLikeAndHasInstalledSinytraConnector());
    }

    @Test
    public void connectorFileIsPreselectedByExactIdWithNativeFilesPresent() {
        for (ModManagement target : Arrays.asList(instance(NEOFORGE, connector(false)),
                server(NEOFORGE, connector(false)))) {
            DisableableMod installed = installedCurseForge(cf(100, MC, "Fabric"), 100);
            List<CurseForgeFile> files = new ArrayList<>(Arrays.asList(cf(300, MC, "NeoForge"),
                    cf(250, MC, "Forge"), cf(200, MC, "Fabric"), cf(100, MC, "Fabric")));

            assertNull(blocker(installed, target, files));
            List<CurseForgeFile> offered = offered(installed, target, files);
            // only same-loader choices, and the installed file rather than the newest one
            assertEquals(Arrays.asList(200, 100), offered.stream().map(f -> f.id).collect(Collectors.toList()));
            assertSame(files.get(3),
                    CurseForgeProjectFileSelectorDialog.installedFileToReinstall(offered, installed.curseForgeFileId));
        }
    }

    @Test
    public void nativeFileIsPreselectedByExactIdAndNeverOfferedFabricOnlyFiles() {
        ModManagement target = instance(NEOFORGE, connector(false));
        DisableableMod installed = installedCurseForge(cf(100, MC, "NeoForge"), 100);
        List<CurseForgeFile> files = new ArrayList<>(Arrays.asList(cf(200, MC, "Fabric"), cf(150, MC, "NeoForge"),
                cf(100, MC, "NeoForge")));

        assertNull(blocker(installed, target, files));
        List<CurseForgeFile> offered = offered(installed, target, files);
        assertEquals(Arrays.asList(150, 100), offered.stream().map(f -> f.id).collect(Collectors.toList()));
        assertEquals(100, CurseForgeProjectFileSelectorDialog.installedFileToReinstall(offered, 100).id);
    }

    @Test
    public void ordinaryFabricInstanceReinstallStillWorks() {
        ModManagement target = server(FABRIC);
        DisableableMod installed = installedCurseForge(cf(100, MC, "Fabric"), 100);
        List<CurseForgeFile> files = new ArrayList<>(Arrays.asList(cf(200, MC, "Fabric"), cf(100, MC, "Fabric")));

        assertNull(blocker(installed, target, files));
        assertEquals(100, CurseForgeProjectFileSelectorDialog
                .installedFileToReinstall(offered(installed, target, files), 100).id);
    }

    @Test
    public void missingInstalledIdStopsInsteadOfPickingAnotherFile() {
        ModManagement target = instance(NEOFORGE, connector(false));
        DisableableMod installed = installedCurseForge(cf(100, MC, "Fabric"), 100);
        // the installed file was removed from CurseForge; newer Fabric and native files remain
        List<CurseForgeFile> files = new ArrayList<>(Arrays.asList(cf(300, MC, "NeoForge"),
                cf(200, MC, "Fabric")));

        assertNull(CurseForgeProjectFileSelectorDialog.installedFileToReinstall(offered(installed, target, files),
                100));
        assertNull(CurseForgeProjectFileSelectorDialog.installedFileToReinstall(files, null));
    }

    @Test
    public void connectorFileIsBlockedWhenConnectorAbsentDisabledOrHidden() {
        List<ModManagement> targets = Arrays.asList(instance(NEOFORGE), server(NEOFORGE),
                instance(NEOFORGE, connector(true)), server(NEOFORGE, connector(true)));

        for (ModManagement target : targets) {
            DisableableMod installed = installedCurseForge(cf(100, MC, "Fabric"), 100);
            List<CurseForgeFile> files = new ArrayList<>(Arrays.asList(cf(200, MC, "NeoForge"),
                    cf(100, MC, "Fabric")));

            assertNotNull(blocker(installed, target, files));
            // and even past the blocker, no file would be offered
            assertEquals(0, offered(installed, target, files).size());
        }

        App.settings.showFabricModsWhenSinytraInstalled = false;
        DisableableMod installed = installedCurseForge(cf(100, MC, "Fabric"), 100);
        assertNotNull(blocker(installed, instance(NEOFORGE, connector(false)),
                new ArrayList<>(Collections.singletonList(cf(100, MC, "Fabric")))));
    }

    @Test
    public void unknownInstalledLoaderIsBlocked() {
        ModManagement target = instance(NEOFORGE, connector(false));
        DisableableMod installed = installedCurseForge(null, 100);

        assertNotNull(blocker(installed, target, new ArrayList<>(Arrays.asList(cf(200, MC, "Fabric"),
                cf(300, MC, "NeoForge")))));
    }

    @Test
    public void checksLeaveTheInstalledModUnchanged() {
        CurseForgeFile stored = cf(100, MC, "Fabric");
        DisableableMod installed = installedCurseForge(stored, 100);
        List<CurseForgeFile> files = new ArrayList<>(Arrays.asList(cf(200, MC, "NeoForge"), cf(100, MC, "Fabric")));

        for (ModManagement target : Arrays.asList(instance(NEOFORGE), instance(NEOFORGE, connector(false)))) {
            blocker(installed, target, files);
            CurseForgeProjectFileSelectorDialog.installedFileToReinstall(offered(installed, target, files), 100);
        }

        assertEquals("some-fabric-mod.jar", installed.file);
        assertEquals(Integer.valueOf(123), installed.curseForgeProjectId);
        assertEquals(Integer.valueOf(100), installed.curseForgeFileId);
        assertSame(stored, installed.curseForgeFile);
        assertEquals(Arrays.asList(MC, "Fabric"), stored.gameVersions);
        assertEquals(false, installed.disabled);
    }

    // --- Modrinth

    private static ModrinthVersion mr(String id, List<String> loaders, String... gameVersions) {
        ModrinthVersion version = new ModrinthVersion();
        version.id = id;
        version.datePublished = "2024-01-01T00:00:00Z";
        version.loaders = loaders;
        version.gameVersions = Arrays.asList(gameVersions);
        return version;
    }

    private static DisableableMod installedModrinth(List<String> loaders) {
        DisableableMod mod = new DisableableMod();
        mod.name = "Some Fabric Mod";
        mod.file = "some-fabric-mod.jar";
        mod.type = Type.mods;
        mod.modrinthProject = new ModrinthProject();
        mod.modrinthProject.id = "abcd1234";
        mod.modrinthVersion = mr("installed", loaders, MC);
        return mod;
    }

    private static List<ModrinthVersion> offered(DisableableMod installed, ModManagement target,
            ModrinthVersion... projectVersions) {
        List<ModrinthVersion> all = Arrays.asList(projectVersions);
        return installed.modrinthUpdateCandidates(target, all,
                installed.modrinthInstalledPath(target, all, NO_FORGE_COMPAT), NO_FORGE_COMPAT);
    }

    @Test
    public void modrinthConnectorVersionIsPreselectedByExactIdWithNativeVersionsPresent() {
        for (ModManagement target : Arrays.asList(instance(NEOFORGE, connector(false)),
                server(NEOFORGE, connector(false)))) {
            DisableableMod installed = installedModrinth(Collections.singletonList("fabric"));
            List<ModrinthVersion> offered = offered(installed, target,
                    mr("neo-new", Collections.singletonList("neoforge"), MC),
                    mr("fabric-new", Collections.singletonList("fabric"), MC),
                    mr("installed", Collections.singletonList("fabric"), MC));

            assertEquals(Arrays.asList("fabric-new", "installed"),
                    offered.stream().map(v -> v.id).collect(Collectors.toList()));
            assertEquals("installed",
                    ModrinthVersionSelectorDialog.installedVersionToReinstall(offered, "installed").id);
        }
    }

    @Test
    public void modrinthMissingInstalledIdStopsInsteadOfPickingAnotherVersion() {
        DisableableMod installed = installedModrinth(Collections.singletonList("fabric"));
        List<ModrinthVersion> offered = offered(installed, instance(NEOFORGE, connector(false)),
                mr("fabric-new", Collections.singletonList("fabric"), MC),
                mr("neo-new", Collections.singletonList("neoforge"), MC));

        assertNull(ModrinthVersionSelectorDialog.installedVersionToReinstall(offered, "installed"));
        assertNull(ModrinthVersionSelectorDialog.installedVersionToReinstall(offered, null));
    }

    @Test
    public void modrinthConnectorVersionIsBlockedWhenConnectorDisabledAndUnknownLoadersAreBlocked() {
        for (ModManagement target : Arrays.asList(instance(NEOFORGE, connector(true)),
                server(NEOFORGE, connector(true)))) {
            DisableableMod installed = installedModrinth(Collections.singletonList("fabric"));

            assertNotNull(DisableableMod.reinstallBlocker(installed.name,
                    installed.modrinthInstalledPath(target, null, NO_FORGE_COMPAT),
                    target.isForgeLikeAndHasInstalledSinytraConnector()));
        }

        ModManagement target = instance(NEOFORGE, connector(false));
        DisableableMod unknown = installedModrinth(null);
        assertNotNull(DisableableMod.reinstallBlocker(unknown.name, unknown.modrinthInstalledPath(target,
                Collections.singletonList(mr("neighbour", Collections.singletonList("fabric"), MC)),
                NO_FORGE_COMPAT), true));

        // missing stored loaders recovered through the exact version ID aren't blocked
        assertNull(DisableableMod.reinstallBlocker(unknown.name, unknown.modrinthInstalledPath(target,
                Collections.singletonList(mr("installed", Collections.singletonList("fabric"), MC)),
                NO_FORGE_COMPAT), true));
        assertNull(unknown.modrinthVersion.loaders);
    }
}
