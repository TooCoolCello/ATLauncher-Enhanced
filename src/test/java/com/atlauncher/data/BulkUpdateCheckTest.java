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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.atlauncher.App;
import com.atlauncher.data.DisableableMod.UpdateStatus;
import com.atlauncher.data.curseforge.CurseForgeGameVersionLatestFiles;
import com.atlauncher.data.minecraft.MinecraftVersion;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthVersion;

/**
 * The batch update check may only settle a mod when the per-mod check would agree; anything it can't be sure of must
 * be left for the full check (null), never reported as up to date.
 */
public class BulkUpdateCheckTest {
    private static final String MC = "1.21.1";
    private static final String OLD = "2024-01-01T00:00:00Z";
    private static final String NEW = "2024-06-01T00:00:00Z";
    private static final List<String> NO_FORGE_COMPAT = Collections.emptyList();
    private static final LoaderVersion NEOFORGE = new LoaderVersion("21.1.77", false, "NeoForge");

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

    // --- CurseForge

    private static CurseForgeGameVersionLatestFiles latest(String gameVersion, int fileId) {
        CurseForgeGameVersionLatestFiles file = new CurseForgeGameVersionLatestFiles();
        file.gameVersion = gameVersion;
        file.fileId = fileId;
        return file;
    }

    private static final Predicate<String> STRICT = MC::equals;

    @Test
    public void curseForgeNoNewerFileForTheVersionMeansNoUpdate() {
        assertFalse(BulkUpdateCheck.mayHaveNewerFile(500, Arrays.asList(latest(MC, 500), latest(MC, 420)), STRICT));
    }

    @Test
    public void curseForgeNewerFileForAnotherVersionIsOutsideTheRestriction() {
        assertFalse(BulkUpdateCheck.mayHaveNewerFile(500, Arrays.asList(latest(MC, 500), latest("1.21.4", 900)),
                STRICT));
        assertTrue(BulkUpdateCheck.mayHaveNewerFile(500, Arrays.asList(latest(MC, 500), latest("1.21.4", 900)),
                v -> true));
    }

    @Test
    public void curseForgeNewerFileForTheVersionNeedsTheFullCheck() {
        assertTrue(BulkUpdateCheck.mayHaveNewerFile(500, Arrays.asList(latest(MC, 501)), STRICT));
    }

    @Test
    public void curseForgeEntryWithoutAVersionCountsAsPossible() {
        assertTrue(BulkUpdateCheck.mayHaveNewerFile(500, Arrays.asList(latest(null, 600)), STRICT));
    }

    // --- Modrinth

    private static ModrinthVersion mr(String id, String published, String... loaders) {
        ModrinthVersion version = new ModrinthVersion();
        version.id = id;
        version.datePublished = published;
        version.loaders = Arrays.asList(loaders);
        version.gameVersions = Collections.singletonList(MC);
        return version;
    }

    private static Instance neoForgeInstance(DisableableMod mod) {
        MinecraftVersion version = new MinecraftVersion();
        version.id = MC;
        Instance instance = new Instance(version);
        instance.launcher = new InstanceLauncher();
        instance.launcher.loaderVersion = NEOFORGE;
        instance.launcher.mods = new ArrayList<>(Collections.singletonList(mod));
        return instance;
    }

    private static DisableableMod installed() {
        DisableableMod mod = new DisableableMod();
        mod.modrinthProject = new ModrinthProject();
        mod.modrinthProject.id = "abcd1234";
        mod.modrinthVersion = mr("installed", OLD, "neoforge");
        mod.type = Type.mods;
        return mod;
    }

    private static UpdateStatus verdict(DisableableMod mod, ModrinthVersion newest) {
        Instance instance = neoForgeInstance(mod);
        return mod.modrinthBatchVerdict(instance, newest,
                mod.modrinthInstalledPath(instance, null, NO_FORGE_COMPAT), NO_FORGE_COMPAT);
    }

    @Test
    public void modrinthNewestIsTheInstalledVersion() {
        DisableableMod mod = installed();
        assertEquals(UpdateStatus.UP_TO_DATE, verdict(mod, mr("installed", OLD, "neoforge")));
    }

    @Test
    public void modrinthNewestIsNotNewer() {
        assertEquals(UpdateStatus.UP_TO_DATE, verdict(installed(), mr("other", OLD, "neoforge")));
    }

    @Test
    public void modrinthNewerOnTheSameLoaderPathIsAnUpdate() {
        assertEquals(UpdateStatus.AVAILABLE, verdict(installed(), mr("new", NEW, "neoforge")));
    }

    @Test
    public void modrinthNewerOnAnotherLoaderPathIsLeftToTheFullCheck() {
        // a newer Fabric-only build says nothing about older NeoForge builds that are still newer than the installed one
        assertNull(verdict(installed(), mr("fabric-new", NEW, "fabric")));
    }

    @Test
    public void modrinthNoAnswerIsLeftToTheFullCheck() {
        assertNull(verdict(installed(), null));
    }
}
