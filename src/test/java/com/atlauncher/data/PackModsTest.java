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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.atlauncher.constants.Constants;
import com.atlauncher.data.PackMods.PackFile;
import com.atlauncher.data.PackMods.Row;
import com.atlauncher.data.PackMods.Status;
import com.atlauncher.data.curseforge.CurseForgeFile;
import com.atlauncher.data.curseforge.CurseForgeFileDependency;
import com.atlauncher.data.curseforge.CurseForgeGameVersionLatestFiles;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.curseforge.pack.CurseForgeManifest;
import com.atlauncher.data.curseforge.pack.CurseForgeManifestFile;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthDependency;
import com.atlauncher.data.modrinth.ModrinthDependencyType;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthProjectType;
import com.atlauncher.data.modrinth.ModrinthVersion;
import com.atlauncher.data.modrinth.pack.ModrinthModpackFile;
import com.atlauncher.data.modrinth.pack.ModrinthModpackManifest;

public class PackModsTest {
    private static final String MC = "1.20.1";
    private static final LoaderVersion NEOFORGE = new LoaderVersion("47.1.106", false, "NeoForge");
    private static final ModCompatibility.Target NEOFORGE_CONNECTOR = new ModCompatibility.Target(MC, NEOFORGE,
            false, true);
    private static final ModCompatibility.Target NEOFORGE_ONLY = new ModCompatibility.Target(MC, NEOFORGE, false,
            false);

    private static ModrinthModpackFile packFile(String path, String sha1, String client, String server) {
        ModrinthModpackFile file = new ModrinthModpackFile();
        file.path = path;
        file.hashes = new HashMap<>();
        file.hashes.put("sha1", sha1);
        if (client != null) {
            file.env = new HashMap<>();
            file.env.put("client", client);
            file.env.put("server", server);
        }
        return file;
    }

    private static ModrinthVersion version(String projectId, String loader, String... requires) {
        ModrinthVersion version = new ModrinthVersion();
        version.id = projectId + "-" + loader;
        version.projectId = projectId;
        version.name = projectId + " " + loader;
        version.gameVersions = Collections.singletonList(MC);
        version.loaders = Collections.singletonList(loader);
        version.dependencies = new ArrayList<>();
        for (String required : requires) {
            ModrinthDependency dependency = new ModrinthDependency();
            dependency.projectId = required;
            dependency.dependencyType = ModrinthDependencyType.REQUIRED;
            version.dependencies.add(dependency);
        }
        return version;
    }

    private static ModrinthProject project(String id, ModrinthProjectType type) {
        ModrinthProject project = new ModrinthProject();
        project.id = id;
        project.title = "Mod " + id;
        project.projectType = type;
        return project;
    }

    private static CurseForgeGameVersionLatestFiles index(int fileId, int modLoader, int releaseType) {
        CurseForgeGameVersionLatestFiles file = new CurseForgeGameVersionLatestFiles();
        file.gameVersion = MC;
        file.fileId = fileId;
        file.modLoader = modLoader;
        file.releaseType = releaseType;
        return file;
    }

    private static CurseForgeProject curseForge(int id, int classId, CurseForgeGameVersionLatestFiles... files) {
        CurseForgeProject project = new CurseForgeProject();
        project.id = id;
        project.name = "CF " + id;
        project.classId = classId;
        project.latestFilesIndexes = Arrays.asList(files);
        return project;
    }

    /**
     * Modrinth knows hashes a..f; "newest" answers by loader; one CurseForge project.
     */
    private static final class FakeSites implements PackMods.Api {
        final Map<String, ModrinthVersion> identity = new HashMap<>();
        final Map<String, Map<String, ModrinthVersion>> newestByLoader = new HashMap<>();
        final List<ModrinthProject> projects = new ArrayList<>();
        final List<CurseForgeProject> curseForge = new ArrayList<>();
        final List<String> newestCalls = new ArrayList<>();

        @Override
        public Map<String, ModrinthVersion> identify(List<String> sha1s) {
            Map<String, ModrinthVersion> found = new HashMap<>();
            sha1s.stream().filter(identity::containsKey).forEach(h -> found.put(h, identity.get(h)));
            return found;
        }

        @Override
        public Map<String, ModrinthVersion> newest(List<String> sha1s, List<String> loaders, String mc) {
            newestCalls.add(String.join(",", loaders) + ":" + String.join(",", sha1s));
            Map<String, ModrinthVersion> found = new HashMap<>();
            Map<String, ModrinthVersion> forLoader = newestByLoader.getOrDefault(String.join(",", loaders),
                    Collections.emptyMap());
            sha1s.stream().filter(forLoader::containsKey).forEach(h -> found.put(h, forLoader.get(h)));
            return found;
        }

        @Override
        public List<ModrinthProject> modrinthProjects(List<String> ids) {
            return projects.stream().filter(p -> ids.contains(p.id)).collect(Collectors.toList());
        }

        @Override
        public List<CurseForgeProject> curseForgeProjects(List<Integer> ids) {
            return curseForge.stream().filter(p -> ids.contains(p.id)).collect(Collectors.toList());
        }

        void modrinth(String hash, String projectId, ModrinthProjectType type) {
            identity.put(hash, version(projectId, "neoforge"));
            if (projects.stream().noneMatch(p -> p.id.equals(projectId))) {
                projects.add(project(projectId, type));
            }
        }

        void newest(String loader, String hash, ModrinthVersion version) {
            newestByLoader.computeIfAbsent(loader, l -> new HashMap<>()).put(hash, version);
        }
    }

    private static FakeSites sites() {
        FakeSites sites = new FakeSites();
        sites.modrinth("aa", "native", ModrinthProjectType.MOD);
        sites.modrinth("a2", "native", ModrinthProjectType.MOD);
        sites.modrinth("bb", "fabricOnly", ModrinthProjectType.MOD);
        sites.modrinth("cc", "nothing", ModrinthProjectType.MOD);
        sites.modrinth("ee", "installed", ModrinthProjectType.MOD);
        sites.modrinth("ff", "shaders", ModrinthProjectType.SHADER);
        sites.modrinth("fa", Constants.MODRINTH_FABRIC_MOD_ID, ModrinthProjectType.MOD);
        sites.newest("neoforge", "aa", version("native", "neoforge", "fabricOnly"));
        sites.newest("neoforge", "a2", version("native", "neoforge"));
        sites.newest("neoforge", "ee", version("installed", "neoforge"));
        sites.newest("fabric", "bb", version("fabricOnly", "fabric", "missing", "installed"));
        sites.newest("fabric", "fa", version(Constants.MODRINTH_FABRIC_MOD_ID, "fabric"));
        sites.curseForge.add(curseForge(10, Constants.CURSEFORGE_MODS_SECTION_ID,
                index(101, Constants.CURSEFORGE_NEOFORGE_MODLOADER_ID, 1)));
        sites.curseForge.add(curseForge(11, 12, index(111, 0, 1)));
        return sites;
    }

    private static PackMods.Installed installed() {
        DisableableMod mod = new DisableableMod();
        mod.modrinthProject = project("installed", ModrinthProjectType.MOD);
        mod.modrinthVersion = version("installed", "neoforge");
        return PackMods.Installed.of(Collections.singletonList(mod));
    }

    private static List<PackFile> files() {
        return Arrays.asList(PackFile.byHash("AA", "native.jar", null), PackFile.byHash("a2", "native-2.jar", null),
                PackFile.byHash("bb", "fabric.jar", ModSide.CLIENT), PackFile.byHash("cc", "nothing.jar", null),
                PackFile.byHash("dd", "unknown.jar", null), PackFile.byHash("ee", "installed.jar", null),
                PackFile.byHash("ff", "shader.zip", null), PackFile.byHash("fa", "fabric-api.jar", null),
                PackFile.curseForge(10, "cf mod", null), PackFile.curseForge(11, "cf resource pack", null),
                PackFile.curseForge(12, "cf gone", null));
    }

    private static Row row(List<Row> rows, String name) {
        return rows.stream().filter(r -> r.name.equals(name)).findFirst().orElse(null);
    }

    @Test
    public void modrinthIndexKeepsModsWithTheirSide() {
        ModrinthModpackManifest manifest = new ModrinthModpackManifest();
        manifest.files = Arrays.asList(packFile("mods/Sodium.jar", "ABC", "required", "unsupported"),
                packFile("mods\\lib.jar", "def", null, null),
                packFile("resourcepacks/pack.zip", "123", "required", "required"),
                packFile("config/thing.toml", "456", "required", "required"));

        List<PackFile> files = PackMods.fromModrinthIndex(manifest);

        assertEquals(2, files.size());
        assertEquals("abc", files.get(0).sha1);
        assertEquals("Sodium.jar", files.get(0).name);
        assertEquals(ModSide.CLIENT, files.get(0).side);
        assertEquals("lib.jar", files.get(1).name);
        assertNull(files.get(1).side);
    }

    @Test
    public void curseForgeManifestListsProjects() {
        CurseForgeManifest manifest = new CurseForgeManifest();
        CurseForgeManifestFile file = new CurseForgeManifestFile();
        file.projectID = 238222;
        file.fileID = 1;
        manifest.files = Collections.singletonList(file);

        List<PackFile> files = PackMods.fromCurseForgeManifest(manifest);

        assertEquals(1, files.size());
        assertEquals(Integer.valueOf(238222), files.get(0).curseForgeProjectId);
        assertNull(files.get(0).sha1);
    }

    @Test
    public void resolveChecksEachModAgainstTheInstance() {
        FakeSites sites = sites();
        PackMods.Result result = PackMods.resolve(files(), NEOFORGE_CONNECTOR, installed(), sites);
        List<Row> rows = result.rows;

        Row nativeMod = row(rows, "Mod native");
        assertEquals(Status.COMPATIBLE, nativeMod.status);
        assertTrue(nativeMod.installable());
        assertEquals("native-neoforge", nativeMod.modrinthVersion.id);
        // the same project from two files is one row
        assertEquals(1, rows.stream().filter(r -> "native".equals(r.modrinthId)).count());

        assertEquals(Status.VIA_CONNECTOR, row(rows, "Mod fabricOnly").status);
        assertEquals(Status.NOT_AVAILABLE, row(rows, "Mod nothing").status);
        assertFalse(row(rows, "Mod nothing").installable());
        assertEquals(Status.INSTALLED, row(rows, "Mod installed").status);
        assertFalse(row(rows, "Mod installed").installable());

        Row unknown = row(rows, "unknown.jar");
        assertEquals(Status.UNKNOWN, unknown.status);
        assertNull(unknown.platform);
        assertFalse(unknown.installable());

        // Connector takes Forgified Fabric API, never Fabric API
        assertEquals(Status.NOT_AVAILABLE, row(rows, "Mod " + Constants.MODRINTH_FABRIC_MOD_ID).status);

        Row curseForge = row(rows, "CF 10");
        assertEquals(Status.COMPATIBLE, curseForge.status);
        assertEquals(Integer.valueOf(101), curseForge.curseForgeFileId);
        assertEquals(Status.UNKNOWN, row(rows, "cf gone").status);

        // a shader on Modrinth and a resource pack on CurseForge aren't mods
        assertEquals(2, result.notMods);
        assertNull(result.problem);

        // addable first, installed last
        assertEquals(Status.COMPATIBLE, rows.get(0).status);
        assertEquals(Status.INSTALLED, rows.get(rows.size() - 1).status);

        // native files are asked for first, Fabric ones only for the rest
        assertTrue(sites.newestCalls.get(0).startsWith("neoforge:"));
        assertTrue(sites.newestCalls.get(1).startsWith("fabric:"));
        assertFalse(sites.newestCalls.get(1).contains("aa"));
    }

    @Test
    public void aModOfTheSameNameCountsAsInstalled() {
        // added from CurseForge and never matched on Modrinth, so only its name links it
        DisableableMod fromElsewhere = new DisableableMod();
        fromElsewhere.name = "mod NATIVE ";
        fromElsewhere.type = Type.mods;

        List<Row> rows = PackMods.resolve(files(), NEOFORGE_CONNECTOR,
                PackMods.Installed.of(Collections.singletonList(fromElsewhere)), sites()).rows;

        assertEquals(Status.INSTALLED, row(rows, "Mod native").status);
        assertFalse(row(rows, "Mod native").installable());
        // an unidentified file isn't matched by its file name
        assertEquals(Status.UNKNOWN, row(rows, "unknown.jar").status);
    }

    @Test
    public void withoutConnectorFabricOnlyModsAreNotAvailable() {
        FakeSites sites = sites();
        List<Row> rows = PackMods.resolve(files(), NEOFORGE_ONLY, installed(), sites).rows;

        assertEquals(Status.NOT_AVAILABLE, row(rows, "Mod fabricOnly").status);
        assertEquals(1, sites.newestCalls.size());
    }

    @Test
    public void unreachableModrinthIsSaid() {
        FakeSites sites = sites();
        sites.identity.clear();
        PackMods.Result result = PackMods.resolve(Collections.singletonList(PackFile.byHash("aa", "a.jar", null)),
                NEOFORGE_CONNECTOR, installed(), sites);

        assertNotNull(result.problem);
        assertEquals(Status.UNKNOWN, result.rows.get(0).status);
    }

    @Test
    public void clientOnlyModsAreFlaggedOnServers() {
        Row clientOnly = new Row("Minimap", ModPlatform.MODRINTH, project("m", ModrinthProjectType.MOD), "m",
                version("m", "neoforge"), null, null, null, Status.COMPATIBLE, "", ModSide.CLIENT);

        assertTrue(clientOnly.compatible(false));
        assertTrue(clientOnly.clientOnlyOnServer(true));
        assertFalse(clientOnly.compatible(true));
        // still addable by hand
        assertTrue(clientOnly.installable());
    }

    @Test
    public void bestCurseForgeFilePrefersNativeReleases() {
        List<CurseForgeGameVersionLatestFiles> index = Arrays.asList(
                index(1, Constants.CURSEFORGE_FABRIC_MODLOADER_ID, 1),
                index(2, Constants.CURSEFORGE_NEOFORGE_MODLOADER_ID, 2),
                index(3, Constants.CURSEFORGE_NEOFORGE_MODLOADER_ID, 1),
                index(4, 0, 1));

        assertEquals(Integer.valueOf(3), PackMods.bestCurseForgeFileId(NEOFORGE_ONLY, index));
        // only Fabric and untagged: Fabric with Connector, else the untagged file
        List<CurseForgeGameVersionLatestFiles> fabric = Arrays.asList(index(1, 4, 1), index(4, 0, 1));
        assertEquals(Integer.valueOf(1), PackMods.bestCurseForgeFileId(NEOFORGE_CONNECTOR, fabric));
        assertEquals(Integer.valueOf(4), PackMods.bestCurseForgeFileId(NEOFORGE_ONLY, fabric));
        assertNull(PackMods.bestCurseForgeFileId(NEOFORGE_ONLY, Collections.singletonList(index(1, 4, 1))));
    }

    @Test
    public void dependenciesAddWhatThePackHasAndNameTheRest() {
        FakeSites sites = sites();
        List<Row> rows = PackMods.resolve(files(), NEOFORGE_CONNECTOR, installed(), sites).rows;
        Row nativeMod = row(rows, "Mod native");

        PackMods.Dependencies needs = PackMods.dependencies(Collections.singletonList(nativeMod), rows, installed(),
                Collections.emptyMap());

        // native needs fabricOnly, from the pack; fabricOnly needs "installed" (here) and "missing" (nowhere)
        assertEquals(1, needs.alsoAdd.size());
        assertEquals("fabricOnly", needs.alsoAdd.get(0).modrinthId);
        assertEquals(Collections.singleton("missing"), needs.missingModrinth);
    }

    @Test
    public void curseForgeDependenciesNeedTheFile() {
        Row row = row(PackMods.resolve(files(), NEOFORGE_ONLY, installed(), sites()).rows, "CF 10");
        CurseForgeFile file = new CurseForgeFile();
        file.id = 101;
        CurseForgeFileDependency dependency = new CurseForgeFileDependency();
        dependency.modId = 99;
        dependency.relationType = 3;
        file.dependencies = Collections.singletonList(dependency);
        Map<Integer, CurseForgeFile> files = new HashMap<>();
        files.put(101, file);

        assertTrue(PackMods.dependencies(Collections.singletonList(row), Collections.singletonList(row), installed(),
                Collections.emptyMap()).missingCurseForge.isEmpty());
        assertEquals(Collections.singleton(99), PackMods.dependencies(Collections.singletonList(row),
                Collections.singletonList(row), installed(), files).missingCurseForge);
    }

    @Test
    public void linksName() {
        PackMods.Link modrinth = PackMods.parseLink(" https://modrinth.com/modpack/fabulously-optimized/version/6.4.0 ");
        assertEquals(ModPlatform.MODRINTH, modrinth.platform);
        assertEquals("fabulously-optimized", modrinth.slug);
        assertEquals("6.4.0", modrinth.version);

        PackMods.Link curseForge = PackMods.parseLink("https://www.curseforge.com/minecraft/modpacks/all-the-mods-9/files/5125809");
        assertEquals(ModPlatform.CURSEFORGE, curseForge.platform);
        assertEquals("all-the-mods-9", curseForge.slug);
        assertEquals("5125809", curseForge.version);

        // the slug is only ever letters, digits, _ and -, as it goes in a request
        assertNull(PackMods.parseLink("modrinth.com/modpack/../etc"));
        assertNull(PackMods.parseLink("https://example.com/modpack/x"));
        assertNull(PackMods.parseLink("https://modrinth.com/mod/sodium"));
    }

    @Test
    public void bestPackVersionMatchesMinecraftAndLoader() {
        ModrinthVersion other = version("pack", "fabric");
        other.gameVersions = Collections.singletonList("1.21.1");
        ModrinthVersion fabric = version("pack", "fabric");
        ModrinthVersion neoforge = version("pack", "neoforge");

        assertEquals(neoforge, PackMods.bestPackVersion(Arrays.asList(other, fabric, neoforge), NEOFORGE_ONLY));
        assertEquals(fabric, PackMods.bestPackVersion(Arrays.asList(other, fabric), NEOFORGE_ONLY));
        assertEquals(other, PackMods.bestPackVersion(Collections.singletonList(other), NEOFORGE_ONLY));
    }
}
