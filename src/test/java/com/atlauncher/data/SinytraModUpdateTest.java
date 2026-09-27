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
import java.util.function.Predicate;
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

/**
 * Update checks keep an installed file on its loader path: Connector installs update to Fabric files only while
 * Connector is active, native installs never update to Fabric-only files. No network: files are built in memory and
 * only the filtering and comparison the update check runs on fetched files is exercised.
 */
public class SinytraModUpdateTest {
    private static final String MC = "1.21.1";
    private static final List<String> NO_FORGE_COMPAT = Collections.emptyList();
    private static final LoaderVersion NEOFORGE = new LoaderVersion("21.1.77", false, "NeoForge");
    private static final LoaderVersion FORGE = new LoaderVersion("52.0.0", false, "Forge");
    private static final LoaderVersion FABRIC = new LoaderVersion("0.16.9", false, "Fabric");
    private static final LoaderVersion QUILT = new LoaderVersion("0.27.1", false, "Quilt");

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

    // --- targets

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

    private static List<ModManagement> connectorTargets() {
        return Arrays.asList(instance(NEOFORGE, connector(false)), server(NEOFORGE, connector(false)));
    }

    // --- CurseForge

    private static CurseForgeFile cf(int id, String... gameVersions) {
        CurseForgeFile file = new CurseForgeFile();
        file.id = id;
        file.gameVersions = new ArrayList<>(Arrays.asList(gameVersions));
        return file;
    }

    private static DisableableMod installedCurseForge(CurseForgeFile storedFile, int fileId) {
        DisableableMod mod = new DisableableMod();
        mod.curseForgeProjectId = 123;
        mod.curseForgeFileId = fileId;
        mod.curseForgeFile = storedFile;
        return mod;
    }

    private static DisableableMod installedCurseForge(CurseForgeFile storedFile) {
        return installedCurseForge(storedFile, storedFile.id);
    }

    private static List<Integer> offered(DisableableMod installed, ModManagement target, CurseForgeFile... fetched) {
        List<CurseForgeFile> files = new ArrayList<>(Arrays.asList(fetched));
        CurseForgeProjectFileSelectorDialog.markMissingTagsAsUntagged(files);
        Predicate<CurseForgeFile> candidate = installed.curseForgeUpdateCandidate(target, files, NO_FORGE_COMPAT);
        return files.stream().filter(candidate).map(f -> f.id).collect(Collectors.toList());
    }

    private static boolean hasUpdate(DisableableMod installed, ModManagement target, CurseForgeFile... fetched) {
        List<CurseForgeFile> files = new ArrayList<>(Arrays.asList(fetched));
        CurseForgeProjectFileSelectorDialog.markMissingTagsAsUntagged(files);
        return installed.hasCurseForgeUpdate(target, files,
                installed.curseForgeUpdateCandidate(target, files, NO_FORGE_COMPAT));
    }

    @Test
    public void connectorInstallUpdatesToNewerFabricFileOnInstancesAndServers() {
        for (ModManagement target : connectorTargets()) {
            DisableableMod installed = installedCurseForge(cf(100, MC, "Fabric"));

            assertTrue(hasUpdate(installed, target, cf(100, MC, "Fabric"), cf(200, MC, "Fabric")));
        }
    }

    @Test
    public void connectorInstallNeverUpdatesToNativeFileInMixedProject() {
        for (ModManagement target : connectorTargets()) {
            DisableableMod installed = installedCurseForge(cf(100, MC, "Fabric"));
            CurseForgeFile[] fetched = { cf(100, MC, "Fabric"), cf(200, MC, "NeoForge"), cf(300, MC, "Forge"),
                    cf(400, MC, "Fabric", "NeoForge") };

            assertFalse(hasUpdate(installed, target, fetched));
            assertEquals(Collections.singletonList(100), offered(installed, target, fetched));
        }
    }

    @Test
    public void connectorInstallGetsNoUpdateWhenConnectorAbsentDisabledOrHidden() {
        CurseForgeFile[] fetched = { cf(100, MC, "Fabric"), cf(200, MC, "Fabric"), cf(300, MC, "NeoForge") };

        for (ModManagement target : Arrays.asList(instance(NEOFORGE), server(NEOFORGE),
                instance(NEOFORGE, connector(true)), server(NEOFORGE, connector(true)))) {
            DisableableMod installed = installedCurseForge(cf(100, MC, "Fabric"));

            assertFalse(hasUpdate(installed, target, fetched));
            assertTrue(offered(installed, target, fetched).isEmpty());
            // the check never touches the installed mod
            assertEquals(Integer.valueOf(100), installed.curseForgeFileId);
            assertFalse(installed.disabled);
        }

        App.settings.showFabricModsWhenSinytraInstalled = false;
        for (ModManagement target : connectorTargets()) {
            assertFalse(hasUpdate(installedCurseForge(cf(100, MC, "Fabric")), target, fetched));
        }
    }

    @Test
    public void nativeInstallUpdatesToNativeFilesOnlyEvenWithConnectorActive() {
        for (ModManagement target : connectorTargets()) {
            DisableableMod installed = installedCurseForge(cf(100, MC, "NeoForge"));

            assertFalse(hasUpdate(installed, target, cf(100, MC, "NeoForge"), cf(200, MC, "Fabric")));
            assertTrue(hasUpdate(installed, target, cf(100, MC, "NeoForge"), cf(200, MC, "NeoForge")));
            assertEquals(Arrays.asList(100, 300, 400), offered(installed, target, cf(100, MC, "NeoForge"),
                    cf(200, MC, "Fabric"), cf(300, MC, "Fabric", "NeoForge"), cf(400, MC)));
        }
    }

    @Test
    public void forgeInstallKeepsForgeRules() {
        ModManagement target = instance(FORGE, connector(false));
        DisableableMod installed = installedCurseForge(cf(100, MC, "Forge"));

        assertTrue(hasUpdate(installed, target, cf(100, MC, "Forge"), cf(200, MC, "Forge")));
        assertFalse(hasUpdate(installed, target, cf(100, MC, "Forge"), cf(200, MC, "NeoForge")));
        assertFalse(hasUpdate(installed, target, cf(100, MC, "Forge"), cf(200, MC, "Fabric")));
    }

    @Test
    public void fabricAndQuiltLoadersKeepTheirUsualRules() {
        for (ModManagement target : Arrays.asList(instance(FABRIC), server(FABRIC))) {
            DisableableMod installed = installedCurseForge(cf(100, MC, "Fabric"));

            assertTrue(hasUpdate(installed, target, cf(100, MC, "Fabric"), cf(200, MC, "Fabric")));
            assertFalse(hasUpdate(installed, target, cf(100, MC, "Fabric"), cf(200, MC, "NeoForge")));
        }

        // Quilt keeps a Quilt file on Quilt files, rather than switching it to a Fabric-only build
        DisableableMod onQuilt = installedCurseForge(cf(100, MC, "Quilt"));
        assertTrue(hasUpdate(onQuilt, instance(QUILT), cf(100, MC, "Quilt"), cf(200, MC, "Quilt")));
        assertFalse(hasUpdate(onQuilt, instance(QUILT), cf(100, MC, "Quilt"), cf(200, MC, "Fabric")));
    }

    @Test
    public void newerFileForAnotherMinecraftVersionIsNotAnUpdate() {
        for (ModManagement target : connectorTargets()) {
            DisableableMod installed = installedCurseForge(cf(100, MC, "Fabric"));

            assertFalse(hasUpdate(installed, target, cf(100, MC, "Fabric"), cf(200, "1.20.1", "Fabric")));
        }
    }

    @Test
    public void missingOrEmptyStoredMetadataIsRecoveredByExactFileIdMatch() {
        for (CurseForgeFile stored : Arrays.asList(null, cf(100))) {
            for (ModManagement target : connectorTargets()) {
                DisableableMod installed = installedCurseForge(stored, 100);

                assertEquals(SinytraConnector.InstalledPath.CONNECTOR, installed.curseForgeInstalledPath(target,
                        Arrays.asList(cf(100, MC, "Fabric")), NO_FORGE_COMPAT));
                assertTrue(hasUpdate(installed, target, cf(100, MC, "Fabric"), cf(200, MC, "Fabric")));
                assertFalse(hasUpdate(installed, target, cf(100, MC, "Fabric"), cf(200, MC, "NeoForge")));
            }
        }
    }

    @Test
    public void fetchedExactIdMatchWinsOverStaleStoredMetadata() {
        ModManagement target = instance(NEOFORGE, connector(false));
        DisableableMod installed = installedCurseForge(cf(100, MC), 100);

        assertEquals(SinytraConnector.InstalledPath.CONNECTOR, installed.curseForgeInstalledPath(target,
                Arrays.asList(cf(100, MC, "Fabric")), NO_FORGE_COMPAT));
    }

    @Test
    public void unresolvedInstalledMetadataGetsNoUpdateOnForgeLikeLoaders() {
        List<ModManagement> targets = Arrays.asList(instance(NEOFORGE, connector(false)),
                server(NEOFORGE, connector(false)), instance(NEOFORGE), server(NEOFORGE),
                instance(FORGE, connector(false)));

        for (CurseForgeFile stored : Arrays.asList(null, cf(100))) {
            for (ModManagement target : targets) {
                DisableableMod installed = installedCurseForge(stored, 100);
                // a neighbouring file's tags are no evidence about the installed one
                CurseForgeFile[] fetched = { cf(101, MC, "Fabric"), cf(200, MC, "Fabric"), cf(300, MC, "NeoForge"),
                        cf(400, MC, "Forge"), cf(500, MC) };

                assertEquals(SinytraConnector.InstalledPath.UNKNOWN,
                        installed.curseForgeInstalledPath(target, Arrays.asList(fetched), NO_FORGE_COMPAT));
                assertFalse(hasUpdate(installed, target, fetched));
                assertTrue(offered(installed, target, fetched).isEmpty());
                assertEquals(Integer.valueOf(100), installed.curseForgeFileId);
                assertEquals(stored, installed.curseForgeFile);
            }
        }
    }

    @Test
    public void unresolvedInstalledMetadataKeepsUsualRulesOffForgeLikeLoaders() {
        ModManagement target = instance(FABRIC);
        DisableableMod installed = installedCurseForge(null, 100);

        assertTrue(hasUpdate(installed, target, cf(200, MC, "Fabric")));
        assertFalse(hasUpdate(installed, target, cf(200, MC, "NeoForge")));
    }

    @Test
    public void connectorUpdateNeedsThisMinecraftVersionEvenWithRestrictionNone() {
        App.settings.addModRestriction = AddModRestriction.NONE;

        for (ModManagement target : connectorTargets()) {
            DisableableMod installed = installedCurseForge(cf(100, MC, "Fabric"));
            CurseForgeFile[] fetched = { cf(100, MC, "Fabric"), cf(200, "1.20.1", "Fabric") };

            assertFalse(hasUpdate(installed, target, fetched));
            assertEquals(Collections.singletonList(100), offered(installed, target, fetched));
            assertTrue(hasUpdate(installed, target, cf(100, MC, "Fabric"), cf(200, "1.20.1", MC, "Fabric")));
        }
    }

    @Test
    public void nativeUpdatesKeepRestrictionNoneBehaviour() {
        App.settings.addModRestriction = AddModRestriction.NONE;
        ModManagement target = instance(NEOFORGE, connector(false));
        DisableableMod installed = installedCurseForge(cf(100, MC, "NeoForge"));

        assertTrue(hasUpdate(installed, target, cf(100, MC, "NeoForge"), cf(200, "1.20.1", "NeoForge")));

        // files with no tag list at all count as untagged, as elsewhere
        CurseForgeFile untagged = cf(300);
        untagged.gameVersions = null;
        assertTrue(hasUpdate(installed, target, cf(100, MC, "NeoForge"), untagged));
    }

    // --- Modrinth

    private static ModrinthVersion mr(String id, String published, List<String> loaders, String... gameVersions) {
        ModrinthVersion version = new ModrinthVersion();
        version.id = id;
        version.datePublished = published;
        version.loaders = loaders;
        version.gameVersions = Arrays.asList(gameVersions);
        return version;
    }

    private static final String OLD = "2024-01-01T00:00:00Z";
    private static final String NEW = "2024-06-01T00:00:00Z";

    private static DisableableMod installedModrinth(List<String> loaders) {
        DisableableMod mod = new DisableableMod();
        mod.modrinthProject = new ModrinthProject();
        mod.modrinthProject.id = "abcd1234";
        mod.modrinthVersion = mr("installed", OLD, loaders, MC);
        mod.type = Type.mods;
        return mod;
    }

    private static SinytraConnector.InstalledPath path(DisableableMod installed, ModManagement target,
            ModrinthVersion... projectVersions) {
        return installed.modrinthInstalledPath(target, projectVersions.length == 0 ? null
                : Arrays.asList(projectVersions), NO_FORGE_COMPAT);
    }

    private static List<ModrinthVersion> candidates(DisableableMod installed, ModManagement target,
            ModrinthVersion... fetched) {
        return installed.modrinthUpdateCandidates(target, Arrays.asList(fetched), path(installed, target),
                NO_FORGE_COMPAT);
    }

    private static List<String> candidateIds(DisableableMod installed, ModManagement target,
            ModrinthVersion... fetched) {
        return candidates(installed, target, fetched).stream().map(v -> v.id).collect(Collectors.toList());
    }

    @Test
    public void modrinthConnectorInstallUpdatesToFabricVersionsOnly() {
        for (ModManagement target : connectorTargets()) {
            DisableableMod installed = installedModrinth(Collections.singletonList("fabric"));
            ModrinthVersion[] fetched = { mr("fabric-new", NEW, Collections.singletonList("fabric"), MC),
                    mr("neo-new", NEW, Collections.singletonList("neoforge"), MC),
                    mr("both-new", NEW, Arrays.asList("fabric", "neoforge"), MC) };

            // the query skips the loader filter so Fabric versions come back at all
            assertEquals(SinytraConnector.InstalledPath.CONNECTOR, path(installed, target));
            assertEquals(Collections.singletonList("fabric-new"), candidateIds(installed, target, fetched));
            assertTrue(installed.hasModrinthUpdate(target, candidates(installed, target, fetched)));
        }
    }

    @Test
    public void modrinthConnectorInstallGetsNothingWhenConnectorDisabled() {
        for (ModManagement target : Arrays.asList(instance(NEOFORGE, connector(true)),
                server(NEOFORGE, connector(true)))) {
            DisableableMod installed = installedModrinth(Collections.singletonList("fabric"));

            assertTrue(candidateIds(installed, target, mr("fabric-new", NEW, Collections.singletonList("fabric"),
                    MC), mr("neo-new", NEW, Collections.singletonList("neoforge"), MC)).isEmpty());
        }
    }

    @Test
    public void modrinthNativeInstallNeverUpdatesToFabricOnlyVersion() {
        for (ModManagement target : connectorTargets()) {
            DisableableMod installed = installedModrinth(Collections.singletonList("neoforge"));

            assertEquals(SinytraConnector.InstalledPath.NATIVE, path(installed, target));
            assertEquals(Arrays.asList("neo-new", "both-new"), candidateIds(installed, target,
                    mr("fabric-new", NEW, Collections.singletonList("fabric"), MC),
                    mr("neo-new", NEW, Collections.singletonList("neoforge"), MC),
                    mr("both-new", NEW, Arrays.asList("fabric", "neoforge"), MC)));
        }
    }

    @Test
    public void modrinthFabricLoaderKeepsItsUsualRules() {
        ModManagement target = server(FABRIC);

        for (List<String> loaders : Arrays.asList(Collections.singletonList("fabric"), null)) {
            DisableableMod installed = installedModrinth(loaders);

            assertEquals(SinytraConnector.InstalledPath.NATIVE, path(installed, target));
            assertTrue(installed.hasModrinthUpdate(target, candidates(installed, target,
                    mr("fabric-new", NEW, Collections.singletonList("fabric"), MC))));
        }
    }

    @Test
    public void modrinthVersionForAnotherMinecraftVersionOrOlderIsNotAnUpdate() {
        ModManagement target = instance(NEOFORGE, connector(false));
        DisableableMod installed = installedModrinth(Collections.singletonList("fabric"));

        assertFalse(installed.hasModrinthUpdate(target, candidates(installed, target,
                mr("other-mc", NEW, Collections.singletonList("fabric"), "1.20.1"),
                mr("older", OLD, Collections.singletonList("fabric"), MC))));
    }

    @Test
    public void modrinthConnectorUpdateNeedsThisMinecraftVersionEvenWithRestrictionNone() {
        App.settings.addModRestriction = AddModRestriction.NONE;

        for (ModManagement target : connectorTargets()) {
            DisableableMod installed = installedModrinth(Collections.singletonList("fabric"));

            assertTrue(candidateIds(installed, target,
                    mr("other-mc", NEW, Collections.singletonList("fabric"), "1.20.1")).isEmpty());
            assertEquals(Collections.singletonList("both-mc"), candidateIds(installed, target,
                    mr("both-mc", NEW, Collections.singletonList("fabric"), "1.20.1", MC)));
        }
    }

    @Test
    public void modrinthMissingOrEmptyLoadersAreRecoveredByExactVersionIdMatch() {
        for (List<String> missing : Arrays.asList(null, Collections.<String>emptyList())) {
            for (ModManagement target : connectorTargets()) {
                DisableableMod installed = installedModrinth(missing);

                assertEquals(SinytraConnector.InstalledPath.CONNECTOR, path(installed, target,
                        mr("other", OLD, Collections.singletonList("neoforge"), MC),
                        mr("installed", OLD, Collections.singletonList("fabric"), MC)));
                assertEquals(SinytraConnector.InstalledPath.NATIVE, path(installed, target,
                        mr("installed", OLD, Collections.singletonList("neoforge"), MC)));
            }
        }
    }

    @Test
    public void modrinthUnresolvedLoadersGetNoUpdateOnForgeLikeLoaders() {
        for (List<String> missing : Arrays.asList(null, Collections.<String>emptyList())) {
            for (ModManagement target : Arrays.asList(instance(NEOFORGE, connector(false)), server(NEOFORGE),
                    instance(FORGE))) {
                DisableableMod installed = installedModrinth(missing);

                // no project versions, or none with the installed ID (a neighbour's loaders prove nothing)
                assertEquals(SinytraConnector.InstalledPath.UNKNOWN, path(installed, target));
                assertEquals(SinytraConnector.InstalledPath.UNKNOWN, path(installed, target,
                        mr("neighbour", OLD, Collections.singletonList("fabric"), MC),
                        mr("installed", OLD, Collections.<String>emptyList(), MC)));
                assertTrue(installed.modrinthUpdateCandidates(target, Arrays.asList(
                        mr("fabric-new", NEW, Collections.singletonList("fabric"), MC),
                        mr("neo-new", NEW, Collections.singletonList("neoforge"), MC),
                        mr("forge-new", NEW, Collections.singletonList("forge"), MC)),
                        SinytraConnector.InstalledPath.UNKNOWN, NO_FORGE_COMPAT).isEmpty());
                assertEquals(missing, installed.modrinthVersion.loaders);
            }
        }
    }
}
