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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.atlauncher.Gsons;
import com.atlauncher.data.curseforge.CurseForgeFile;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthSide;

public class ModSideTest {
    private static final ModrinthSide REQUIRED = ModrinthSide.REQUIRED;
    private static final ModrinthSide OPTIONAL = ModrinthSide.OPTIONAL;
    private static final ModrinthSide UNSUPPORTED = ModrinthSide.UNSUPPORTED;

    private static DisableableMod modrinthMod(String id, ModrinthSide client, ModrinthSide server) {
        DisableableMod mod = new DisableableMod();
        mod.modrinthProject = new ModrinthProject();
        mod.modrinthProject.id = id;
        mod.modrinthProject.clientSide = client;
        mod.modrinthProject.serverSide = server;
        return mod;
    }

    private static DisableableMod curseForgeMod(int projectId, String... gameVersions) {
        DisableableMod mod = new DisableableMod();
        mod.curseForgeProjectId = projectId;
        mod.curseForgeFile = new CurseForgeFile();
        mod.curseForgeFile.gameVersions = Arrays.asList(gameVersions);
        return mod;
    }

    @Test
    void readsModrinthSides() {
        assertEquals(ModSide.CLIENT, ModSide.fromModrinth(REQUIRED, UNSUPPORTED));
        assertEquals(ModSide.CLIENT, ModSide.fromModrinth(OPTIONAL, UNSUPPORTED));
        assertEquals(ModSide.SERVER, ModSide.fromModrinth(UNSUPPORTED, REQUIRED));
        assertEquals(ModSide.BOTH, ModSide.fromModrinth(REQUIRED, OPTIONAL));
        assertEquals(ModSide.BOTH, ModSide.fromModrinth(REQUIRED, REQUIRED));
        // "unknown" (no matching enum value) only settles it when the other side is unsupported
        assertEquals(ModSide.CLIENT, ModSide.fromModrinth(null, UNSUPPORTED));
        assertNull(ModSide.fromModrinth(null, REQUIRED));
        assertNull(ModSide.fromModrinth(UNSUPPORTED, UNSUPPORTED));
    }

    @Test
    void readsCurseForgeEnvironmentTags() {
        assertEquals(ModSide.CLIENT, ModSide.fromCurseForge(Arrays.asList("1.20.1", "Forge", "Client")));
        assertEquals(ModSide.SERVER, ModSide.fromCurseForge(Arrays.asList("Server", "1.20.1")));
        assertEquals(ModSide.BOTH, ModSide.fromCurseForge(Arrays.asList("Client", "Server", "NeoForge")));
        assertNull(ModSide.fromCurseForge(Arrays.asList("1.20.1", "Fabric")));
        assertNull(ModSide.fromCurseForge(null));
    }

    @Test
    void effectiveSideIsManualThenModrinthThenCurseForge() {
        DisableableMod mod = curseForgeMod(1, "1.20.1", "Server");
        assertEquals(ModSide.SERVER, mod.getEffectiveSide());

        mod.modrinthProject = new ModrinthProject();
        mod.modrinthProject.clientSide = REQUIRED;
        mod.modrinthProject.serverSide = UNSUPPORTED;
        assertEquals(ModSide.CLIENT, mod.getEffectiveSide());

        mod.side = ModSide.BOTH;
        assertEquals(ModSide.BOTH, mod.getEffectiveSide());
        assertEquals(ModSide.CLIENT, mod.getPlatformSide());
    }

    @Test
    void modrinthWithoutAnAnswerFallsBackToCurseForge() {
        DisableableMod mod = curseForgeMod(1, "Client");
        mod.modrinthProject = new ModrinthProject();

        assertEquals(ModSide.CLIENT, mod.getEffectiveSide());
    }

    @Test
    void unknownWhenNothingSaysAndForPlainFiles() {
        assertNull(new DisableableMod().getEffectiveSide());
        assertNull(curseForgeMod(1, "1.20.1").getEffectiveSide());
    }

    @Test
    void manualSideIsSavedAndOlderInstanceJsonLoadsAsAuto() {
        DisableableMod mod = new DisableableMod();
        mod.side = ModSide.CLIENT;

        String json = Gsons.DEFAULT.toJson(mod);
        assertEquals(ModSide.CLIENT, Gsons.DEFAULT.fromJson(json, DisableableMod.class).side);
        assertNull(Gsons.DEFAULT.fromJson("{\"name\": \"x\"}", DisableableMod.class).side);
        assertNull(Gsons.DEFAULT.fromJson("{\"side\": \"sideways\"}", DisableableMod.class).side);
    }

    @Test
    void anUpdateKeepsTheManualSideOfTheSameProject() {
        DisableableMod curseForge = curseForgeMod(42);
        curseForge.side = ModSide.SERVER;
        DisableableMod modrinth = modrinthMod("AANobbMI", REQUIRED, REQUIRED);
        modrinth.side = ModSide.CLIENT;
        List<DisableableMod> mods = Arrays.asList(curseForgeMod(7), curseForge, modrinth);

        assertEquals(ModSide.SERVER, DisableableMod.manualSideOf(mods, 42, null));
        assertEquals(ModSide.CLIENT, DisableableMod.manualSideOf(mods, null, "aanobbmi"));
        assertNull(DisableableMod.manualSideOf(mods, 7, null));
        assertNull(DisableableMod.manualSideOf(Collections.emptyList(), 42, "AANobbMI"));
    }

    @Test
    void modrinthExportEnv() {
        assertEquals(env("required", "unsupported"), ModSide.modrinthEnv(ModSide.CLIENT));
        assertEquals(env("optional", "required"), ModSide.modrinthEnv(ModSide.SERVER));
        assertEquals(env("required", "required"), ModSide.modrinthEnv(ModSide.BOTH));
        assertEquals(env("required", "required"), ModSide.modrinthEnv(null));
    }

    private static Map<String, String> env(String client, String server) {
        Map<String, String> env = new HashMap<>();
        env.put("client", client);
        env.put("server", server);
        return env;
    }
}
