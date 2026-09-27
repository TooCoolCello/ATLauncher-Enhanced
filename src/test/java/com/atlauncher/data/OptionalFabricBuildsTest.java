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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

/**
 * The per-dialog "Mod loader" choice in the new-install selectors: the native loader first and by default, Forge on
 * NeoForge and Fabric on Quilt as their own paths over the same files, Fabric via
 * Connector only where it's verified and only with Fabric builds for this exact Minecraft version, Fabric as the
 * fallback when nothing native fits, no hidden file left selected on switching, a recheck before install, and no
 * choice where an installed project keeps its loader.
 */
public class OptionalFabricBuildsTest {
    private static final String MC = "1.21.1";
    private static final LoaderVersion NEOFORGE = new LoaderVersion("21.1.77", false, "NeoForge");

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

    // as the CurseForge selector judges its files (loader and Minecraft tags share one list)
    private static final Predicate<CurseForgeFile> CONNECTOR_BUILD = f -> SinytraConnector
            .isConnectorBuild(f.gameVersions, f.gameVersions, MC, NEOFORGE, false);
    private static final Predicate<CurseForgeFile> NEEDS_CONNECTOR = f -> SinytraConnector
            .isFabricFallbackFile(f.gameVersions, NEOFORGE, false);

    // newest first, as the selector sorts them; the usual list is what the loader shows without the opt-in
    private final CurseForgeFile fabricNew = cf(400, MC, "Fabric");
    private final CurseForgeFile nativeNew = cf(300, MC, "NeoForge");
    private final CurseForgeFile fabricOld = cf(200, MC, "Fabric");
    private final CurseForgeFile nativeOld = cf(100, MC, "NeoForge");
    private final List<CurseForgeFile> all = Arrays.asList(fabricNew, nativeNew, fabricOld, nativeOld);
    private final List<CurseForgeFile> usual = Arrays.asList(nativeNew, nativeOld);

    private static List<Integer> ids(List<CurseForgeFile> files) {
        return files.stream().map(f -> f.id).collect(Collectors.toList());
    }

    private static final Predicate<CurseForgeFile> NO_CONNECTOR = f -> false;
    private static final LoaderVersion FORGE_LOADER = new LoaderVersion("47.3.0", false, "Forge");
    private static final LoaderVersion FABRIC_LOADER = new LoaderVersion("0.16.9", false, "Fabric");
    private static final LoaderVersion QUILT_LOADER = new LoaderVersion("0.27.1", false, "Quilt");

    // as the selectors classify their listed files on this NeoForge instance (Minecraft version not Forge-compatible)
    private static LoaderChoice choiceOf(CurseForgeFile f) {
        return LoaderChoice.of(f.gameVersions, NEOFORGE, false);
    }

    private List<CurseForgeFile> filesFor(LoaderChoice choice) {
        return LoaderChoice.filesFor(choice, all, usual, OptionalFabricBuildsTest::choiceOf, CONNECTOR_BUILD);
    }

    private static List<Integer> listed(LoaderChoice choice, List<CurseForgeFile> usualFiles, LoaderVersion loader,
            boolean forgeCompatible) {
        return ids(LoaderChoice.filesFor(choice, usualFiles, usualFiles,
                f -> LoaderChoice.of(f.gameVersions, loader, forgeCompatible), NO_CONNECTOR));
    }

    private static List<LoaderChoice> choicesOn(List<CurseForgeFile> usualFiles, LoaderVersion loader,
            boolean forgeCompatible) {
        return LoaderChoice.choices(usualFiles, f -> LoaderChoice.of(f.gameVersions, loader, forgeCompatible),
                false);
    }

    @Test
    public void nativeLoaderIsListedFirstAndSelectedByDefault() {
        List<LoaderChoice> choices = LoaderChoice.choices(usual, OptionalFabricBuildsTest::choiceOf, true);

        assertEquals(Arrays.asList(LoaderChoice.NATIVE, LoaderChoice.FABRIC_VIA_CONNECTOR), choices);
        assertEquals(LoaderChoice.NATIVE, LoaderChoice.defaultChoice(choices, false));

        List<CurseForgeFile> listed = filesFor(LoaderChoice.NATIVE);
        assertEquals(Arrays.asList(300, 100), ids(listed));
        assertSame(nativeNew, RequiredDependency.initialSelection(listed, null, false, CONNECTOR_BUILD));
    }

    @Test
    public void choosingFabricViaConnectorListsOnlyFabricBuildsForThisMinecraftVersion() {
        List<CurseForgeFile> withOtherVersion = new ArrayList<>(all);
        withOtherVersion.add(0, cf(500, "1.20.1", "Fabric"));
        withOtherVersion.add(cf(50, MC, "NeoForge", "Fabric"));

        assertEquals(Arrays.asList(400, 200), ids(LoaderChoice.filesFor(LoaderChoice.FABRIC_VIA_CONNECTOR,
                withOtherVersion, usual, OptionalFabricBuildsTest::choiceOf, CONNECTOR_BUILD)));
    }

    @Test
    public void fabricViaConnectorIsOfferedOnlyWhereItIsVerified() {
        // Connector installed, enabled and allowed, on instances and servers
        for (ModManagement target : Arrays.asList(instance(connector(false)), server(connector(false)))) {
            assertTrue(LoaderChoice.choices(usual, OptionalFabricBuildsTest::choiceOf,
                    target.isForgeLikeAndHasInstalledSinytraConnector())
                    .contains(LoaderChoice.FABRIC_VIA_CONNECTOR));
        }

        // absent, disabled, or hidden by the existing setting: native only, so the dropdown stays hidden
        for (ModManagement target : Arrays.asList(instance(), server(), instance(connector(true)),
                server(connector(true)))) {
            assertEquals(Collections.singletonList(LoaderChoice.NATIVE), LoaderChoice.choices(usual,
                    OptionalFabricBuildsTest::choiceOf, target.isForgeLikeAndHasInstalledSinytraConnector()));
        }
        App.settings.showFabricModsWhenSinytraInstalled = false;
        assertFalse(LoaderChoice.choices(usual, OptionalFabricBuildsTest::choiceOf,
                instance(connector(false)).isForgeLikeAndHasInstalledSinytraConnector())
                .contains(LoaderChoice.FABRIC_VIA_CONNECTOR));
    }

    @Test
    public void noNativeFileFallsBackToFabricAsBefore() {
        // the automatic fallback's usual list holds only Connector builds
        List<CurseForgeFile> fallbackUsual = Arrays.asList(fabricNew, fabricOld);
        List<LoaderChoice> onlyFabric = LoaderChoice.choices(fallbackUsual, OptionalFabricBuildsTest::choiceOf,
                true);

        assertEquals(Collections.singletonList(LoaderChoice.FABRIC_VIA_CONNECTOR), onlyFabric);
        assertEquals(LoaderChoice.FABRIC_VIA_CONNECTOR, LoaderChoice.defaultChoice(onlyFabric, false));
        // untagged files alongside still leave Fabric first when the automatic fallback applies
        List<CurseForgeFile> withUntagged = Arrays.asList(fabricNew, cf(250, MC));
        assertEquals(LoaderChoice.FABRIC_VIA_CONNECTOR, LoaderChoice.defaultChoice(
                LoaderChoice.choices(withUntagged, OptionalFabricBuildsTest::choiceOf, true), true));
        // nothing at all: no loader path, and the selector reports no compatible files
        assertTrue(LoaderChoice.choices(Collections.<CurseForgeFile>emptyList(), OptionalFabricBuildsTest::choiceOf,
                false).isEmpty());
        assertNull(LoaderChoice.defaultChoice(Collections.emptyList(), true));
    }

    @Test
    public void connectorBuildDependencySuggestsFabricButAllowsNative() {
        List<LoaderChoice> choices = LoaderChoice.choices(usual, OptionalFabricBuildsTest::choiceOf, true);

        assertEquals(LoaderChoice.FABRIC_VIA_CONNECTOR, LoaderChoice.defaultChoice(choices, true));
        List<CurseForgeFile> fabric = filesFor(LoaderChoice.FABRIC_VIA_CONNECTOR);
        assertSame(fabricNew, RequiredDependency.initialSelection(fabric, null, true, CONNECTOR_BUILD));

        // the user may switch to native, and that choice is flagged as possibly not satisfying the Fabric parent
        CurseForgeFile picked = LoaderChoice.selectionAfterSwitch(filesFor(LoaderChoice.NATIVE), fabricNew);
        assertSame(nativeNew, picked);
        assertTrue(RequiredDependency.isOverride(picked, null, true, CONNECTOR_BUILD));
    }

    @Test
    public void mixedTaggedFilesStayNativeWithNoConnectorNote() {
        CurseForgeFile mixed = cf(600, MC, "NeoForge", "Fabric");

        assertEquals(LoaderChoice.NATIVE, choiceOf(mixed));
        assertFalse(CONNECTOR_BUILD.test(mixed));
        assertFalse(NEEDS_CONNECTOR.test(mixed));
        List<CurseForgeFile> usualWithMixed = Arrays.asList(mixed, nativeNew);
        assertEquals(Arrays.asList(600, 300), ids(LoaderChoice.filesFor(LoaderChoice.NATIVE, all, usualWithMixed,
                OptionalFabricBuildsTest::choiceOf, CONNECTOR_BUILD)));
    }

    @Test
    public void quiltSplitsNativeAndFabricViaQuiltWithoutChangingWhatItRuns() {
        CurseForgeFile quiltOnly = cf(700, MC, "Quilt");
        CurseForgeFile fabricOnly = cf(600, MC, "Fabric");
        CurseForgeFile both = cf(500, MC, "Quilt", "Fabric");
        CurseForgeFile untagged = cf(400, MC);
        List<CurseForgeFile> quiltUsual = Arrays.asList(quiltOnly, fabricOnly, both, untagged);

        assertEquals(Arrays.asList(LoaderChoice.NATIVE, LoaderChoice.FABRIC_VIA_QUILT),
                choicesOn(quiltUsual, QUILT_LOADER, false));
        assertEquals(LoaderChoice.NATIVE, LoaderChoice.defaultChoice(choicesOn(quiltUsual, QUILT_LOADER, false),
                false));
        // a file tagged for both appears once, as native
        assertEquals(Arrays.asList(700, 500, 400), listed(LoaderChoice.NATIVE, quiltUsual, QUILT_LOADER, false));
        assertEquals(Collections.singletonList(600), listed(LoaderChoice.FABRIC_VIA_QUILT, quiltUsual,
                QUILT_LOADER, false));
        // the split only divides the list: every file stays installable, none twice
        assertEquals(quiltUsual.size(), listed(LoaderChoice.NATIVE, quiltUsual, QUILT_LOADER, false).size()
                + listed(LoaderChoice.FABRIC_VIA_QUILT, quiltUsual, QUILT_LOADER, false).size());

        // Modrinth's lowercase loaders classify the same way
        assertEquals(LoaderChoice.FABRIC_VIA_QUILT,
                LoaderChoice.of(Collections.singletonList("fabric"), QUILT_LOADER, false));
        assertEquals(LoaderChoice.NATIVE, LoaderChoice.of(Arrays.asList("fabric", "quilt"), QUILT_LOADER, false));

        // only Fabric files: that's the one path, selected, and the dropdown stays hidden
        List<LoaderChoice> fabricOnlyChoices = choicesOn(Collections.singletonList(fabricOnly), QUILT_LOADER, false);
        assertEquals(Collections.singletonList(LoaderChoice.FABRIC_VIA_QUILT), fabricOnlyChoices);
        assertEquals(LoaderChoice.FABRIC_VIA_QUILT, LoaderChoice.defaultChoice(fabricOnlyChoices, false));
    }

    @Test
    public void neoForgeSplitsNativeAndForgeCompatibleOnlyWhereNeoForgeLoadsForgeFiles() {
        CurseForgeFile neoOnly = cf(700, MC, "NeoForge");
        CurseForgeFile forgeOnly = cf(600, MC, "Forge");
        CurseForgeFile both = cf(500, MC, "NeoForge", "Forge");
        CurseForgeFile forgeAndFabric = cf(400, MC, "Forge", "Fabric");
        List<CurseForgeFile> neoUsual = Arrays.asList(neoOnly, forgeOnly, both, forgeAndFabric);

        assertEquals(Arrays.asList(LoaderChoice.NATIVE, LoaderChoice.FORGE_COMPATIBLE),
                choicesOn(neoUsual, NEOFORGE, true));
        assertEquals(Arrays.asList(700, 500), listed(LoaderChoice.NATIVE, neoUsual, NEOFORGE, true));
        // Forge files NeoForge loads natively on this version, one of them also tagged Fabric but not a Connector build
        assertEquals(Arrays.asList(600, 400), listed(LoaderChoice.FORGE_COMPATIBLE, neoUsual, NEOFORGE, true));
        assertFalse(SinytraConnector.isFabricFallbackFile(forgeAndFabric.gameVersions, NEOFORGE, true));

        // where NeoForge doesn't load Forge files there's no Forge path, as there are no Forge files listed
        assertEquals(LoaderChoice.NATIVE, LoaderChoice.of(forgeOnly.gameVersions, NEOFORGE, false));
        assertEquals(Collections.singletonList(LoaderChoice.NATIVE),
                choicesOn(Collections.singletonList(neoOnly), NEOFORGE, false));
        // on Forge itself, Forge files are native
        assertEquals(LoaderChoice.NATIVE, LoaderChoice.of(forgeOnly.gameVersions, FORGE_LOADER, false));
    }

    @Test
    public void switchingLoaderNeverKeepsAHiddenSelection() {
        List<CurseForgeFile> fabric = filesFor(LoaderChoice.FABRIC_VIA_CONNECTOR);
        List<CurseForgeFile> nativeFiles = filesFor(LoaderChoice.NATIVE);

        // native to Fabric: the native file isn't listed there, so the newest Fabric build replaces it
        assertSame(fabricNew, LoaderChoice.selectionAfterSwitch(fabric, nativeOld));
        // back to native: the Fabric build isn't kept
        assertSame(nativeNew, LoaderChoice.selectionAfterSwitch(nativeFiles, fabricOld));
        assertSame(nativeOld, LoaderChoice.selectionAfterSwitch(nativeFiles, nativeOld));
        // nothing listed: nothing selected, so Add stays disabled
        assertNull(LoaderChoice.selectionAfterSwitch(Collections.<CurseForgeFile>emptyList(), fabricOld));
        // and a file the list no longer shows can't be installed
        assertFalse(SinytraConnector.mayInstall(nativeFiles, fabricOld, true, true));
    }

    @Test
    public void installIsRecheckedAgainstConnectorAtTheMoment() {
        List<CurseForgeFile> listed = filesFor(LoaderChoice.FABRIC_VIA_CONNECTOR);

        assertTrue(SinytraConnector.mayInstall(listed, fabricOld, true, true));
        // Connector disabled or removed while the dialog stayed open
        for (ModManagement target : Arrays.asList(instance(connector(true)), server())) {
            assertFalse(SinytraConnector.mayInstall(listed, fabricOld, true,
                    target.isForgeLikeAndHasInstalledSinytraConnector()));
        }
        // native files don't depend on Connector
        assertTrue(SinytraConnector.mayInstall(filesFor(LoaderChoice.NATIVE), nativeNew, false, false));
    }

    @Test
    public void fabricInstancesHaveOnlyTheirNativePath() {
        List<CurseForgeFile> fabricUsual = Arrays.asList(cf(400, MC, "Fabric"), cf(300, MC));

        assertEquals(Collections.singletonList(LoaderChoice.NATIVE), choicesOn(fabricUsual, FABRIC_LOADER, false));
        assertEquals(Arrays.asList(400, 300), listed(LoaderChoice.NATIVE, fabricUsual, FABRIC_LOADER, false));
    }

    @Test
    public void installedProjectsStayOnTheirLoaderWithNoChoiceOffered() {
        List<String> noCompat = Collections.emptyList();
        ModManagement target = instance(connector(false));

        // CurseForge: an installed native file only lists native files, even with Connector active
        DisableableMod installedNative = new DisableableMod();
        installedNative.curseForgeProjectId = 123;
        installedNative.curseForgeFileId = 100;
        installedNative.curseForgeFile = cf(100, MC, "NeoForge");
        List<CurseForgeFile> files = new ArrayList<>(all);
        assertEquals(Arrays.asList(300, 100), ids(files.stream()
                .filter(installedNative.curseForgeUpdateCandidate(target, files, noCompat))
                .collect(Collectors.toList())));

        // Modrinth: an installed Connector build only lists Connector builds for this Minecraft version
        DisableableMod installedFabric = new DisableableMod();
        installedFabric.type = Type.mods;
        installedFabric.modrinthProject = new ModrinthProject();
        installedFabric.modrinthProject.id = "abcd1234";
        installedFabric.modrinthVersion = mr("installed", Collections.singletonList("fabric"), MC);
        List<ModrinthVersion> versions = Arrays.asList(mr("neo", Collections.singletonList("neoforge"), MC),
                mr("fabric", Collections.singletonList("fabric"), MC),
                mr("fabric-old-mc", Collections.singletonList("fabric"), "1.20.1"));
        assertEquals(Collections.singletonList("fabric"), installedFabric.modrinthUpdateCandidates(target, versions,
                installedFabric.modrinthInstalledPath(target, versions, noCompat), noCompat).stream()
                .map(v -> v.id).collect(Collectors.toList()));
        // (the selectors show no dropdown for them: the loader choice is only built for new installs)
    }

    private static ModrinthVersion mr(String id, List<String> loaders, String... gameVersions) {
        ModrinthVersion version = new ModrinthVersion();
        version.id = id;
        version.datePublished = "2024-01-01T00:00:00Z";
        version.loaders = loaders;
        version.gameVersions = Arrays.asList(gameVersions);
        return version;
    }
}
