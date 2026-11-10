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
package com.atlauncher.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.atlauncher.data.DependencyChoice.Kind;
import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.ModManagement;
import com.atlauncher.data.RequiredDependency.State;
import com.atlauncher.data.Type;
import com.atlauncher.data.curseforge.CurseForgeFile;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthDownloadMetadata;
import com.atlauncher.data.modrinth.ModrinthFile;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthVersion;
import com.atlauncher.gui.dialogs.ProgressDialog;

/**
 * Carrying out the dependency choices after a mod is added: required ones are installed by default, optional ones
 * aren't, a conflicting mod is set aside until its replacement is in, and unmet required ones are warned about.
 */
public class DependencyChoicesPanelTest {
    // records what the panel asks of the instance, in order
    private static final class Recorder implements ModManagement {
        final List<String> calls = new ArrayList<>();
        final Path root;

        Recorder() {
            this(null);
        }

        Recorder(Path root) {
            this.root = root;
        }

        public Path getRoot() {
            return root;
        }

        public String getName() {
            return "test";
        }

        public String getMinecraftVersion() {
            return "1.20.1";
        }

        public LoaderVersion getLoaderVersion() {
            return null;
        }

        public boolean supportsPlugins() {
            return false;
        }

        public boolean isForgeLikeAndHasInstalledSinytraConnector() {
            return false;
        }

        public List<DisableableMod> getMods() {
            return new ArrayList<>();
        }

        public void addMod(DisableableMod mod) {
        }

        public void addMods(List<DisableableMod> modsToAdd) {
        }

        public void removeMod(DisableableMod mod) {
            calls.add("remove " + mod.name);
        }

        public void addFileFromCurseForge(CurseForgeProject mod, CurseForgeFile file, ProgressDialog<Void> dialog) {
        }

        public void addFileFromModrinth(ModrinthProject project, ModrinthVersion version, ModrinthFile file,
                Type installType, ModrinthDownloadMetadata.Reason downloadReason, String dependentVersionId,
                ProgressDialog<Void> dialog) {
        }

        public void scanMissingMods(Window parent) {
        }

        public void save() {
            calls.add("save");
        }
    }

    private static DependencyChoicesPanel.Item<String> item(String title, Kind kind, State state,
            DisableableMod installed, boolean replacesInstalled) {
        DependencyChoicesPanel.Item<String> item = new DependencyChoicesPanel.Item<>(title + state, title, kind,
                state, null, installed, replacesInstalled, title);
        item.picker = () -> new DependencyChoicesPanel.Pick<>(title + ".jar", title, null, null);
        return item;
    }

    // an enabled Fabric API, its jar in the instance's mods folder
    private static DisableableMod fabricApi(Path root) throws IOException {
        DisableableMod fabricApi = new DisableableMod();
        fabricApi.name = "Fabric API";
        fabricApi.file = "fabric-api.jar";
        fabricApi.type = Type.mods;
        Files.createDirectories(root.resolve("mods"));
        Files.write(root.resolve("mods/fabric-api.jar"), new byte[] { 1 });
        return fabricApi;
    }

    @Test
    public void defaultsInstallRequiredOnesAndReplaceAConflictOnceItsReplacementIsIn(@TempDir Path root)
            throws IOException {
        DisableableMod fabricApi = fabricApi(root);

        DependencyChoicesPanel<String> panel = new DependencyChoicesPanel<>();
        panel.show(Arrays.asList(
                item("lib", Kind.REQUIRED, State.MISSING, null, false),
                item("extra", Kind.OPTIONAL, State.MISSING, null, false),
                item("Forgified Fabric API", Kind.REQUIRED, State.FABRIC_API_CONFLICT, fabricApi, true)));

        assertTrue(panel.warnings().isEmpty());

        Recorder instance = new Recorder(root);
        List<String> problems = panel.apply(instance, null, (item, file) -> {
            // set aside by then, so the new file can't clash with it
            instance.calls.add("install " + file + (Files.exists(root.resolve("mods/fabric-api.jar")) ? "" : " aside"));
            return true;
        });

        assertTrue(problems.isEmpty());
        assertEquals(Arrays.asList("install lib.jar", "save", "install Forgified Fabric API.jar aside",
                "remove Fabric API", "save"), instance.calls);
    }

    @Test
    public void aConflictIsPutBackWhenItsReplacementFailsToInstall(@TempDir Path root) throws IOException {
        DisableableMod fabricApi = fabricApi(root);

        DependencyChoicesPanel<String> panel = new DependencyChoicesPanel<>();
        panel.show(Collections.singletonList(
                item("Forgified Fabric API", Kind.REQUIRED, State.FABRIC_API_CONFLICT, fabricApi, true)));

        Recorder instance = new Recorder(root);
        List<String> problems = panel.apply(instance, null, (item, file) -> false);

        assertEquals(1, problems.size());
        assertFalse(instance.calls.contains("remove Fabric API"));
        assertFalse(fabricApi.isDisabled());
        assertTrue(Files.exists(root.resolve("mods/fabric-api.jar")));
        assertFalse(Files.exists(root.resolve("disabledmods/fabric-api.jar")));
    }

    @Test
    public void aDisabledRequiredOneIsEnabledWithoutAProblem(@TempDir Path root) throws IOException {
        DisableableMod lib = new DisableableMod();
        lib.name = "lib";
        lib.file = "lib.jar";
        lib.type = Type.mods;
        lib.disabled = true;
        Files.createDirectories(root.resolve("disabledmods"));
        Files.write(root.resolve("disabledmods/lib.jar"), new byte[] { 1 });

        DependencyChoicesPanel<String> panel = new DependencyChoicesPanel<>();
        panel.show(Collections.singletonList(item("lib", Kind.REQUIRED, State.DISABLED, lib, false)));

        assertTrue(panel.apply(new Recorder(root), null, (item, file) -> true).isEmpty());
        assertFalse(lib.isDisabled());
        assertTrue(Files.exists(root.resolve("mods/lib.jar")));
    }

    @Test
    public void unmetRequiredDependenciesAreWarnedAboutAndFailuresReported() {
        DependencyChoicesPanel<String> panel = new DependencyChoicesPanel<>();
        DependencyChoicesPanel.Item<String> unresolved = item("gone", Kind.REQUIRED, State.UNRESOLVED, null, false);
        DependencyChoicesPanel.Item<String> noFile = item("nofile", Kind.REQUIRED, State.MISSING, null, false);
        noFile.picker = () -> null;
        panel.show(Arrays.asList(unresolved, noFile));

        // only Skip is on offer for one that can't be found
        assertEquals(1, panel.warnings().size());
        assertTrue(panel.warnings().get(0).startsWith("gone: "));

        List<String> problems = panel.apply(new Recorder(), null, (item, file) -> true);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).startsWith("nofile: "));
    }

    @Test
    public void choicesOnlyApplyWhileTheDependenciesAreAsListed() {
        DependencyChoicesPanel<String> panel = new DependencyChoicesPanel<>();
        List<DependencyChoicesPanel.Item<String>> listed = Collections
                .singletonList(item("lib", Kind.REQUIRED, State.MISSING, null, false));
        panel.show(listed);

        assertTrue(panel.matches(Collections.singletonList(item("lib", Kind.REQUIRED, State.MISSING, null, false))));
        assertFalse(panel.matches(Collections.singletonList(item("lib", Kind.REQUIRED, State.SATISFIED, null, false))));
        assertFalse(panel.matches(Collections.emptyList()));
    }
}
