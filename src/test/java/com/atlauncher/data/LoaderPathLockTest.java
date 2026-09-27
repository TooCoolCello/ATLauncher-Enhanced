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
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.atlauncher.App;
import com.atlauncher.data.SinytraConnector.InstalledPath;
import com.atlauncher.data.curseforge.CurseForgeFile;
import com.atlauncher.data.minecraft.MinecraftVersion;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthVersion;
import com.atlauncher.gui.dialogs.CurseForgeProjectFileSelectorDialog;
import com.atlauncher.gui.dialogs.ModrinthVersionSelectorDialog;

/**
 * Update and Reinstall keep an installed file on the loader path the Add Mods dropdown installed it through: a
 * Fabric-only file on Quilt stays on Fabric-only files, a Forge-only file on NeoForge on Forge-only files, and native
 * files on native ones. Mixed-tagged and untagged files are native, as the dropdown lists them, and a file with no
 * loader metadata on a loader with several paths gets nothing rather than a guess. Update and Reinstall both offer the
 * files these candidate rules allow; no network is used.
 */
public class LoaderPathLockTest {
    private static final String MC = "1.20.1";
    private static final List<String> FORGE_COMPATIBLE = Collections.singletonList(MC);
    private static final LoaderVersion QUILT = new LoaderVersion("0.27.1", false, "Quilt");
    private static final LoaderVersion NEOFORGE = new LoaderVersion("47.1.106", false, "NeoForge");
    private static final LoaderVersion FABRIC = new LoaderVersion("0.16.9", false, "Fabric");

    private Settings previousSettings;

    @BeforeEach
    public void setUp() {
        previousSettings = App.settings;
        App.settings = new Settings();
        App.settings.addModRestriction = AddModRestriction.STRICT;
    }

    @AfterEach
    public void tearDown() {
        App.settings = previousSettings;
    }

    private static Instance instance(LoaderVersion loader) {
        MinecraftVersion version = new MinecraftVersion();
        version.id = MC;
        Instance instance = new Instance(version);
        instance.launcher = new InstanceLauncher();
        instance.launcher.loaderVersion = loader;
        return instance;
    }

    private static Server server(LoaderVersion loader) {
        Server server = new Server();
        server.minecraftVersion = MC;
        server.loaderVersion = loader;
        return server;
    }

    private static List<ModManagement> targets(LoaderVersion loader) {
        return Arrays.asList(instance(loader), server(loader));
    }

    // --- CurseForge

    private static CurseForgeFile cf(int id, String... tags) {
        CurseForgeFile file = new CurseForgeFile();
        file.id = id;
        file.gameVersions = new ArrayList<>(Arrays.asList(tags));
        file.gameVersions.add(0, MC);
        return file;
    }

    private static DisableableMod installedCurseForge(CurseForgeFile stored, int fileId) {
        DisableableMod mod = new DisableableMod();
        mod.curseForgeProjectId = 42;
        mod.curseForgeFileId = fileId;
        mod.curseForgeFile = stored;
        return mod;
    }

    private static List<Integer> offered(DisableableMod installed, ModManagement target, CurseForgeFile... fetched) {
        List<CurseForgeFile> files = new ArrayList<>(Arrays.asList(fetched));
        return files.stream().filter(installed.curseForgeUpdateCandidate(target, files, FORGE_COMPATIBLE))
                .map(f -> f.id).collect(Collectors.toList());
    }

    private static boolean hasUpdate(DisableableMod installed, ModManagement target, CurseForgeFile... fetched) {
        List<CurseForgeFile> files = new ArrayList<>(Arrays.asList(fetched));
        return installed.hasCurseForgeUpdate(target, files,
                installed.curseForgeUpdateCandidate(target, files, FORGE_COMPATIBLE));
    }

    // every path's files, newest first
    private static CurseForgeFile[] quiltProject() {
        return new CurseForgeFile[] { cf(600, "Quilt"), cf(500, "Fabric"), cf(400, "Quilt", "Fabric"), cf(300),
                cf(200, "Fabric"), cf(100, "Quilt") };
    }

    private static CurseForgeFile[] neoForgeProject() {
        return new CurseForgeFile[] { cf(600, "NeoForge"), cf(500, "Forge"), cf(400, "NeoForge", "Forge"),
                cf(350, "Forge", "Fabric"), cf(300), cf(200, "Forge"), cf(100, "NeoForge") };
    }

    @Test
    public void fabricOnlyFileOnQuiltStaysOnFabricOnlyFiles() {
        for (ModManagement target : targets(QUILT)) {
            DisableableMod installed = installedCurseForge(cf(200, "Fabric"), 200);

            assertEquals(InstalledPath.FABRIC_VIA_QUILT,
                    installed.curseForgeInstalledPath(target, new ArrayList<>(), FORGE_COMPATIBLE));
            assertEquals(Arrays.asList(500, 200), offered(installed, target, quiltProject()));
            assertTrue(hasUpdate(installed, target, quiltProject()));
            // newer Quilt, mixed or untagged files aren't updates for it
            assertFalse(hasUpdate(installed, target, cf(200, "Fabric"), cf(600, "Quilt"), cf(400, "Quilt", "Fabric"),
                    cf(300)));
            // Reinstall offers the same list and keeps the exact installed file
            assertEquals(200, CurseForgeProjectFileSelectorDialog.installedFileToReinstall(
                    Arrays.asList(quiltProject()).stream().filter(f -> offered(installed, target, quiltProject())
                            .contains(f.id)).collect(Collectors.toList()), 200).id);
        }
    }

    @Test
    public void quiltFileStaysOnQuiltFiles() {
        for (ModManagement target : targets(QUILT)) {
            DisableableMod installed = installedCurseForge(cf(100, "Quilt"), 100);

            assertEquals(InstalledPath.NATIVE,
                    installed.curseForgeInstalledPath(target, new ArrayList<>(), FORGE_COMPATIBLE));
            // Quilt, Quilt-and-Fabric, and untagged files: never the Fabric-only ones
            assertEquals(Arrays.asList(600, 400, 300, 100), offered(installed, target, quiltProject()));
            assertFalse(hasUpdate(installed, target, cf(100, "Quilt"), cf(500, "Fabric")));
        }
    }

    @Test
    public void forgeOnlyFileOnNeoForgeStaysOnForgeCompatibleFiles() {
        for (ModManagement target : targets(NEOFORGE)) {
            DisableableMod installed = installedCurseForge(cf(200, "Forge"), 200);

            assertEquals(InstalledPath.FORGE_COMPATIBLE,
                    installed.curseForgeInstalledPath(target, new ArrayList<>(), FORGE_COMPATIBLE));
            // Forge-only files, including one also tagged Fabric (NeoForge loads it as a Forge file)
            assertEquals(Arrays.asList(500, 350, 200), offered(installed, target, neoForgeProject()));
            assertFalse(hasUpdate(installed, target, cf(200, "Forge"), cf(600, "NeoForge"),
                    cf(400, "NeoForge", "Forge")));
        }
    }

    @Test
    public void neoForgeFileStaysOnNeoForgeFiles() {
        for (ModManagement target : targets(NEOFORGE)) {
            DisableableMod installed = installedCurseForge(cf(100, "NeoForge"), 100);

            assertEquals(Arrays.asList(600, 400, 300, 100), offered(installed, target, neoForgeProject()));
            assertFalse(hasUpdate(installed, target, cf(100, "NeoForge"), cf(500, "Forge")));
        }
    }

    @Test
    public void mixedAndUntaggedInstalledFilesAreNativeAsTheDropdownListedThem() {
        ModManagement quilt = instance(QUILT);
        ModManagement neoForge = server(NEOFORGE);

        assertEquals(InstalledPath.NATIVE, installedCurseForge(cf(400, "Quilt", "Fabric"), 400)
                .curseForgeInstalledPath(quilt, new ArrayList<>(), FORGE_COMPATIBLE));
        assertEquals(InstalledPath.NATIVE, installedCurseForge(cf(400, "NeoForge", "Forge"), 400)
                .curseForgeInstalledPath(neoForge, new ArrayList<>(), FORGE_COMPATIBLE));

        // untagged: its Minecraft version but no loader, so native, and never moved to a Fabric- or Forge-only file
        DisableableMod untaggedOnQuilt = installedCurseForge(cf(300), 300);
        assertEquals(InstalledPath.NATIVE,
                untaggedOnQuilt.curseForgeInstalledPath(quilt, new ArrayList<>(), FORGE_COMPATIBLE));
        assertFalse(offered(untaggedOnQuilt, quilt, quiltProject()).contains(500));
        DisableableMod untaggedOnNeoForge = installedCurseForge(cf(300), 300);
        assertFalse(offered(untaggedOnNeoForge, neoForge, neoForgeProject()).contains(500));
    }

    @Test
    public void noLoaderMetadataGetsNothingRatherThanAGuess() {
        for (LoaderVersion loader : Arrays.asList(QUILT, NEOFORGE)) {
            for (ModManagement target : targets(loader)) {
                // no stored metadata and the installed file isn't in the fetched list
                DisableableMod installed = installedCurseForge(null, 999);
                CurseForgeFile[] fetched = loader == QUILT ? quiltProject() : neoForgeProject();

                assertEquals(InstalledPath.UNKNOWN, installed.curseForgeInstalledPath(target,
                        new ArrayList<>(Arrays.asList(fetched)), FORGE_COMPATIBLE));
                assertTrue(offered(installed, target, fetched).isEmpty());
            }
        }

        // a loader with one path has nothing to confuse it with
        DisableableMod onFabric = installedCurseForge(null, 999);
        assertEquals(InstalledPath.NATIVE,
                onFabric.curseForgeInstalledPath(instance(FABRIC), new ArrayList<>(), FORGE_COMPATIBLE));
    }

    // --- Modrinth

    private static ModrinthVersion mr(String id, String... loaders) {
        ModrinthVersion version = new ModrinthVersion();
        version.id = id;
        version.datePublished = "2024-01-01T00:00:00Z";
        version.loaders = Arrays.asList(loaders);
        version.gameVersions = Collections.singletonList(MC);
        return version;
    }

    private static DisableableMod installedModrinth(String id, String... loaders) {
        DisableableMod mod = new DisableableMod();
        mod.type = Type.mods;
        mod.modrinthProject = new ModrinthProject();
        mod.modrinthProject.id = "proj0042";
        mod.modrinthVersion = mr(id, loaders);
        return mod;
    }

    private static List<String> offered(DisableableMod installed, ModManagement target, ModrinthVersion... fetched) {
        List<ModrinthVersion> versions = Arrays.asList(fetched);
        return installed.modrinthUpdateCandidates(target, versions,
                installed.modrinthInstalledPath(target, versions, FORGE_COMPATIBLE), FORGE_COMPATIBLE).stream()
                .map(v -> v.id).collect(Collectors.toList());
    }

    @Test
    public void modrinthVersionsStayOnTheirLoaderPath() {
        ModrinthVersion[] quiltProject = { mr("quilt", "quilt"), mr("fabric", "fabric"),
                mr("both", "quilt", "fabric"), mr("installed-fabric", "fabric") };
        ModrinthVersion[] neoProject = { mr("neo", "neoforge"), mr("forge", "forge"),
                mr("neo-forge", "neoforge", "forge"), mr("installed-forge", "forge") };

        for (ModManagement target : targets(QUILT)) {
            DisableableMod fabricOnly = installedModrinth("installed-fabric", "fabric");
            assertEquals(Arrays.asList("fabric", "installed-fabric"), offered(fabricOnly, target, quiltProject));
            assertEquals("installed-fabric", ModrinthVersionSelectorDialog.installedVersionToReinstall(
                    fabricOnly.modrinthUpdateCandidates(target, Arrays.asList(quiltProject),
                            InstalledPath.FABRIC_VIA_QUILT, FORGE_COMPATIBLE), "installed-fabric").id);

            DisableableMod quiltNative = installedModrinth("quilt", "quilt");
            assertEquals(Arrays.asList("quilt", "both"), offered(quiltNative, target, quiltProject));
        }

        for (ModManagement target : targets(NEOFORGE)) {
            DisableableMod forgeOnly = installedModrinth("installed-forge", "forge");
            assertEquals(Arrays.asList("forge", "installed-forge"), offered(forgeOnly, target, neoProject));

            DisableableMod neoNative = installedModrinth("neo", "neoforge");
            assertEquals(Arrays.asList("neo", "neo-forge"), offered(neoNative, target, neoProject));
        }
    }

    @Test
    public void modrinthMissingLoadersAreRecoveredByExactIdOrLeftUnknown() {
        ModManagement target = server(QUILT);
        DisableableMod noLoaders = installedModrinth("installed-fabric");

        // recovered from the version with the installed ID
        assertEquals(InstalledPath.FABRIC_VIA_QUILT, noLoaders.modrinthInstalledPath(target,
                Arrays.asList(mr("quilt", "quilt"), mr("installed-fabric", "fabric")), FORGE_COMPATIBLE));
        // not recoverable: no guess, so nothing is offered
        assertEquals(InstalledPath.UNKNOWN, noLoaders.modrinthInstalledPath(target, null, FORGE_COMPATIBLE));
        assertTrue(noLoaders.modrinthUpdateCandidates(target, Arrays.asList(mr("quilt", "quilt"), mr("f", "fabric")),
                InstalledPath.UNKNOWN, FORGE_COMPATIBLE).isEmpty());
    }

    // --- what the lock doesn't change

    @Test
    public void dependenciesStillCountFilesTheLoaderRunsItself() {
        // a Forge-only library on NeoForge, or a Fabric-only one on Quilt, still satisfies a native file
        for (InstalledPath path : Arrays.asList(InstalledPath.NATIVE, InstalledPath.FORGE_COMPATIBLE,
                InstalledPath.FABRIC_VIA_QUILT)) {
            assertEquals(RequiredDependency.State.SATISFIED, RequiredDependency.state(path, null, false, null, null));
            assertEquals(RequiredDependency.State.OTHER_LOADER, RequiredDependency.state(path, null, true, null,
                    null));
        }
        assertEquals(RequiredDependency.State.SATISFIED,
                RequiredDependency.state(InstalledPath.CONNECTOR, null, true, null, null));
    }
}
