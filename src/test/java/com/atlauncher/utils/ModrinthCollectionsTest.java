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
package com.atlauncher.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.atlauncher.data.Instance;
import com.atlauncher.data.InstanceLauncher;
import com.atlauncher.data.Server;
import com.atlauncher.data.minecraft.MinecraftVersion;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthCollection;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthProjectType;
import com.atlauncher.network.DownloadException;
import com.atlauncher.utils.ModrinthCollections.Handoff;
import com.atlauncher.utils.ModrinthCollections.Lookup;
import com.atlauncher.utils.ModrinthCollections.State;

import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Public Modrinth collections: parsing links, and looking them up and their projects' types, from a fake Modrinth,
 * never the network.
 */
public class ModrinthCollectionsTest {
    private static final String ID = "AbCd1234";

    // --- parsing

    private static Optional<String> parse(String input) {
        return ModrinthCollections.parseCollectionId(input);
    }

    @Test
    public void idsAndCollectionLinksGiveTheId() {
        assertEquals(Optional.of(ID), parse(ID));
        assertEquals(Optional.of(ID), parse("  " + ID + "  "));
        assertEquals(Optional.of(ID), parse("https://modrinth.com/collection/" + ID));
        assertEquals(Optional.of(ID), parse("https://modrinth.com/collection/" + ID + "/"));
        assertEquals(Optional.of(ID), parse("http://www.modrinth.com/collection/" + ID));
        assertEquals(Optional.of(ID), parse("HTTPS://Modrinth.com/collection/" + ID));
        assertEquals(Optional.of(ID), parse("modrinth.com/collection/" + ID));
        assertEquals(Optional.of(ID), parse("https://modrinth.com/collection/" + ID + "?tab=mods#top"));
    }

    @Test
    public void anythingElseIsRejectedAndNeverTreatedAsAnEndpoint() {
        for (String input : new String[] { null, "", "  ", "abc", "not an id!", "AbCd1234567890XYZ", "Ab%43d1234",
            "https://api.modrinth.com/v3/collection/" + ID, "https://modrinth.com/project/" + ID,
            "https://modrinth.com/user/someone/collections", "https://modrinth.com/collection/" + ID + "/extra",
            "https://modrinth.com/collection/../" + ID, "https://modrinth.com/collection/%2e%2e",
            "https://modrinth.com.evil.example/collection/" + ID, "https://evil.example/collection/" + ID,
            "https://evil.example/?next=https://modrinth.com/collection/" + ID,
            "https://user@modrinth.com/collection/" + ID, "https://modrinth.com:8443/collection/" + ID,
            "ftp://modrinth.com/collection/" + ID, "javascript:alert(1)", "file:///C:/collection/" + ID,
            "https://modrinth.com/collection/" }) {
            assertFalse(parse(input).isPresent(), String.valueOf(input));
        }
    }

    // --- looking one up

    private static ModrinthProject project(String id, String title, ModrinthProjectType type) {
        ModrinthProject project = new ModrinthProject();
        project.id = id;
        project.title = title;
        project.projectType = type;
        return project;
    }

    private static ModrinthCollection collection(String name, String... projects) {
        ModrinthCollection collection = new ModrinthCollection();
        collection.id = ID;
        collection.name = name;
        collection.description = "A description";
        collection.status = "listed";
        collection.projects = projects == null ? null : new ArrayList<>(Arrays.asList(projects));
        return collection;
    }

    private static DownloadException status(int code) {
        Response response = new Response.Builder()
            .request(new Request.Builder().url("https://api.modrinth.com/v3/collection/" + ID).build())
            .protocol(Protocol.HTTP_1_1).code(code).message("status " + code)
            .body(ResponseBody.create("", MediaType.get("text/plain"))).build();
        return new DownloadException(response);
    }

    /**
     * A Modrinth that answers from what it's given.
     */
    private static final class FakeModrinth implements ModrinthCollections.Source {
        ModrinthCollection collection;
        IOException collectionFails;
        final Map<String, ModrinthProject> projects = new HashMap<>();
        boolean projectsFail;
        final List<List<String>> batches = new ArrayList<>();
        List<ModrinthCollection> userCollections;
        IOException userCollectionsFail;
        String creatorAsked;

        FakeModrinth add(ModrinthProject project) {
            projects.put(project.id, project);
            return this;
        }

        @Override
        public ModrinthCollection collection(String id) throws IOException {
            if (collectionFails != null) {
                throw collectionFails;
            }
            return collection;
        }

        @Override
        public List<ModrinthProject> projects(List<String> ids) {
            batches.add(new ArrayList<>(ids));
            if (projectsFail) {
                return null;
            }
            return ids.stream().map(projects::get).filter(p -> p != null).collect(Collectors.toList());
        }

        @Override
        public List<ModrinthCollection> userCollections(String user) throws IOException {
            creatorAsked = user;
            if (userCollectionsFail != null) {
                throw userCollectionsFail;
            }
            return userCollections;
        }
    }

    @Test
    public void aCollectionsProjectsAreShownInItsOrderWithTheirTypes() {
        FakeModrinth modrinth = new FakeModrinth().add(project("P1", "Sodium", ModrinthProjectType.MOD))
            .add(project("P2", "Faithful", ModrinthProjectType.RESOURCEPACK))
            .add(project("P3", "Complementary", ModrinthProjectType.SHADER));
        modrinth.collection = collection("Performance", "P3", "P1", "gone", "P2", "P1");

        Lookup lookup = ModrinthCollections.lookUp(ID, modrinth);
        assertEquals(State.LOADED, lookup.state);
        assertTrue(lookup.canBeSaved());
        assertEquals("Performance", lookup.collection.name);
        assertEquals(Arrays.asList("Complementary", "Sodium", "Faithful"),
            lookup.projects.stream().map(p -> p.title).collect(Collectors.toList()));
        // "gone", and the repeated one
        assertEquals(2, lookup.missing);
        assertNull(lookup.problem);

        assertEquals("Mod", ModrinthCollections.typeName(ModrinthProjectType.MOD));
        assertEquals("Resource Pack", ModrinthCollections.typeName(ModrinthProjectType.RESOURCEPACK));
        assertEquals("Shader", ModrinthCollections.typeName(ModrinthProjectType.SHADER));
        assertEquals("Data Pack", ModrinthCollections.typeName(ModrinthProjectType.DATAPACK));
        assertEquals("Plugin", ModrinthCollections.typeName(ModrinthProjectType.PLUGIN));
        assertEquals("Modpack", ModrinthCollections.typeName(ModrinthProjectType.MODPACK));
        assertEquals("Mod", ModrinthCollections.typeName(null));
    }

    @Test
    public void largeCollectionsAreLookedUpInBatches() {
        FakeModrinth modrinth = new FakeModrinth();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            ids.add("Proj" + i);
            modrinth.add(project("Proj" + i, "Project " + i, ModrinthProjectType.MOD));
        }
        modrinth.collection = collection("Big", ids.toArray(new String[0]));

        Lookup lookup = ModrinthCollections.lookUp(ID, modrinth);
        assertEquals(State.LOADED, lookup.state);
        assertEquals(250, lookup.projects.size());
        assertEquals(Arrays.asList(100, 100, 50),
            modrinth.batches.stream().map(List::size).collect(Collectors.toList()));
    }

    @Test
    public void aCollectionWithNoProjectsIsEmptyButFound() {
        FakeModrinth modrinth = new FakeModrinth();
        modrinth.collection = collection("Nothing yet");
        Lookup lookup = ModrinthCollections.lookUp(ID, modrinth);
        assertEquals(State.EMPTY, lookup.state);
        assertTrue(lookup.canBeSaved());
        assertTrue(modrinth.batches.isEmpty());

        modrinth.collection = collection("No list", (String[]) null);
        assertEquals(State.EMPTY, ModrinthCollections.lookUp(ID, modrinth).state);
    }

    @Test
    public void unavailableAndFailedLookupsAreNeverAnEmptyCollection() {
        FakeModrinth modrinth = new FakeModrinth();

        for (int code : new int[] { 404, 401, 403, 410 }) {
            modrinth.collectionFails = status(code);
            Lookup lookup = ModrinthCollections.lookUp(ID, modrinth);
            assertEquals(State.UNAVAILABLE, lookup.state, String.valueOf(code));
            assertFalse(lookup.canBeSaved());
            assertTrue(lookup.problem.contains("private"));
            assertTrue(lookup.projects.isEmpty());
        }

        modrinth.collectionFails = status(503);
        Lookup unavailableServer = ModrinthCollections.lookUp(ID, modrinth);
        assertEquals(State.ERROR, unavailableServer.state);
        assertTrue(unavailableServer.problem.contains("503"));

        modrinth.collectionFails = new IOException("offline");
        Lookup offline = ModrinthCollections.lookUp(ID, modrinth);
        assertEquals(State.ERROR, offline.state);
        assertFalse(offline.canBeSaved());
        assertNotNull(offline.problem);

        // an answer that isn't a collection
        modrinth.collectionFails = null;
        modrinth.collection = null;
        assertEquals(State.ERROR, ModrinthCollections.lookUp(ID, modrinth).state);

        // found, but its projects couldn't be looked up
        modrinth.collection = collection("Performance", "P1");
        modrinth.projectsFail = true;
        Lookup noProjects = ModrinthCollections.lookUp(ID, modrinth);
        assertEquals(State.ERROR, noProjects.state);
        assertFalse(noProjects.canBeSaved());

        // a source that breaks
        ModrinthCollections.Source broken = new ModrinthCollections.Source() {
            @Override
            public ModrinthCollection collection(String id) {
                throw new IllegalStateException("bad json");
            }

            @Override
            public List<ModrinthProject> projects(List<String> ids) {
                return Collections.emptyList();
            }

            @Override
            public List<ModrinthCollection> userCollections(String user) {
                throw new IllegalStateException("bad json");
            }
        };
        assertEquals(State.ERROR, ModrinthCollections.lookUp(ID, broken).state);
        assertEquals(State.ERROR, ModrinthCollections.lookUpCreator("someone", broken).state);
    }

    // --- opening a project in the existing version selector

    private static Instance instance(LoaderVersion loader) {
        MinecraftVersion version = new MinecraftVersion();
        version.id = "1.20.1";
        Instance instance = new Instance(version);
        instance.launcher = new InstanceLauncher();
        instance.launcher.loaderVersion = loader;
        return instance;
    }

    private static Server server(LoaderVersion loader) {
        Server server = new Server();
        server.minecraftVersion = "1.20.1";
        server.loaderVersion = loader;
        return server;
    }

    private static Handoff handoff(ModrinthProjectType type, com.atlauncher.data.ModManagement target) {
        return ModrinthCollections.handoff(project("P", "Project", type), target);
    }

    @Test
    public void projectsGoToTheSelectorWhereAddModsWouldOfferThem() {
        Instance fabric = instance(new LoaderVersion("0.16.9", false, "Fabric"));
        assertEquals(Handoff.SELECTOR, handoff(ModrinthProjectType.MOD, fabric));
        assertEquals(Handoff.SELECTOR, handoff(null, fabric));
        assertEquals(Handoff.SELECTOR, handoff(ModrinthProjectType.RESOURCEPACK, fabric));
        assertEquals(Handoff.SELECTOR, handoff(ModrinthProjectType.SHADER, fabric));
        assertEquals(Handoff.SELECTOR_AS_DATAPACK, handoff(ModrinthProjectType.DATAPACK, fabric));
        assertEquals(Handoff.UNSUPPORTED, handoff(ModrinthProjectType.PLUGIN, fabric));
        assertEquals(Handoff.MODPACK, handoff(ModrinthProjectType.MODPACK, fabric));

        // an instance with no loader has no Mods section
        Instance vanilla = instance(null);
        assertEquals(Handoff.UNSUPPORTED, handoff(ModrinthProjectType.MOD, vanilla));
        assertEquals(Handoff.SELECTOR, handoff(ModrinthProjectType.RESOURCEPACK, vanilla));

        // a Paper server has plugins only
        Server paper = server(new LoaderVersion("1.20.1-196", false, "Paper"));
        assertEquals(Handoff.SELECTOR, handoff(ModrinthProjectType.PLUGIN, paper));
        assertEquals(Handoff.UNSUPPORTED, handoff(ModrinthProjectType.MOD, paper));
        assertEquals(Handoff.UNSUPPORTED, handoff(ModrinthProjectType.RESOURCEPACK, paper));
        assertEquals(Handoff.UNSUPPORTED, handoff(ModrinthProjectType.DATAPACK, paper));

        // a Forge server has mods
        Server forge = server(new LoaderVersion("47.3.0", false, "Forge"));
        assertEquals(Handoff.SELECTOR, handoff(ModrinthProjectType.MOD, forge));
        assertEquals(Handoff.UNSUPPORTED, handoff(ModrinthProjectType.PLUGIN, forge));

        assertTrue(ModrinthCollections.whyNot(Handoff.MODPACK).contains("instance"));
        assertNotNull(ModrinthCollections.whyNot(Handoff.UNSUPPORTED));
    }

    // --- browsing a creator's collections

    @Test
    public void creatorsAndProfileLinksGiveTheCreator() {
        assertEquals(Optional.of("Some_Creator-1"), ModrinthCollections.parseCreator(" Some_Creator-1 "));
        assertEquals(Optional.of("jellysquid3"),
            ModrinthCollections.parseCreator("https://modrinth.com/user/jellysquid3"));
        assertEquals(Optional.of("jellysquid3"),
            ModrinthCollections.parseCreator("modrinth.com/user/jellysquid3/collections/"));
        assertEquals(Optional.of("jellysquid3"),
            ModrinthCollections.parseCreator("http://www.modrinth.com/user/jellysquid3?x=1"));

        for (String input : new String[] { null, "", "has space", "a/b", "https://api.modrinth.com/v3/user/x",
            "https://modrinth.com/user/x/projects", "https://modrinth.com/user/../x", "https://modrinth.com/user/",
            "https://evil.example/user/x", "https://user@modrinth.com/user/x", "https://modrinth.com:8443/user/x",
            "https://modrinth.com/collection/" + ID, "javascript:alert(1)",
            "a234567890123456789012345678901234567890" }) {
            assertFalse(ModrinthCollections.parseCreator(input).isPresent(), String.valueOf(input));
        }
    }

    @Test
    public void aCreatorsPublicCollectionsAreListedByName() {
        FakeModrinth modrinth = new FakeModrinth();
        ModrinthCollection zeta = collection("zeta", "P1");
        ModrinthCollection alpha = collection("Alpha", "P1", "P2");
        alpha.id = "Alpha123";
        ModrinthCollection bad = collection("Bad", "P1");
        bad.id = "../../x";
        modrinth.userCollections = new ArrayList<>(Arrays.asList(zeta, null, bad, alpha));

        ModrinthCollections.CreatorLookup lookup = ModrinthCollections.lookUpCreator("someone", modrinth);
        assertEquals("someone", modrinth.creatorAsked);
        assertEquals(State.LOADED, lookup.state);
        assertEquals(Arrays.asList("Alpha", "zeta"),
            lookup.collections.stream().map(c -> c.name).collect(Collectors.toList()));
        assertNull(lookup.problem);
    }

    @Test
    public void aCreatorWithNoneOrThatIsntFoundOrFailsSaysSo() {
        FakeModrinth modrinth = new FakeModrinth();

        modrinth.userCollections = new ArrayList<>();
        assertEquals(State.EMPTY, ModrinthCollections.lookUpCreator("someone", modrinth).state);

        modrinth.userCollectionsFail = status(404);
        ModrinthCollections.CreatorLookup missing = ModrinthCollections.lookUpCreator("nobody", modrinth);
        assertEquals(State.UNAVAILABLE, missing.state);
        assertTrue(missing.collections.isEmpty());
        assertNotNull(missing.problem);

        modrinth.userCollectionsFail = status(500);
        assertEquals(State.ERROR, ModrinthCollections.lookUpCreator("someone", modrinth).state);
        modrinth.userCollectionsFail = new IOException("offline");
        assertEquals(State.ERROR, ModrinthCollections.lookUpCreator("someone", modrinth).state);
        modrinth.userCollectionsFail = null;
        modrinth.userCollections = null;
        assertEquals(State.ERROR, ModrinthCollections.lookUpCreator("someone", modrinth).state);
    }

    // --- shown with Add Mods' cards

    @Test
    public void aProjectBecomesTheSearchResultAddModsCardsShow() {
        ModrinthProject project = project("P1", "Sodium", ModrinthProjectType.MOD);
        project.slug = "sodium";
        project.description = "Fast";
        project.categories = new ArrayList<>(Arrays.asList("optimization", "fabric"));
        project.loaders = new ArrayList<>(Arrays.asList("fabric", "quilt"));
        project.downloads = 42;
        project.iconUrl = "https://cdn.modrinth.com/icon.png";

        com.atlauncher.data.modrinth.ModrinthSearchHit hit = ModrinthCollections.asSearchHit(project);
        assertEquals("P1", hit.projectId);
        assertEquals("sodium", hit.slug);
        assertEquals("Sodium", hit.title);
        assertEquals("Fast", hit.description);
        assertEquals(ModrinthProjectType.MOD, hit.projectType);
        assertEquals(Arrays.asList("optimization", "fabric", "quilt"), hit.categories);
        assertEquals(42, hit.downloads);
        assertEquals("https://cdn.modrinth.com/icon.png", hit.iconUrl);
        assertNull(hit.author);

        assertEquals(ModrinthProjectType.MOD, ModrinthCollections.asSearchHit(project("P2", "x", null)).projectType);
        assertTrue(
            ModrinthCollections.asSearchHit(project("P3", "x", ModrinthProjectType.SHADER)).categories.isEmpty());
    }

    @Test
    public void eachTypeIsShownInTheAddModsSectionForIt() {
        assertEquals("Mods", ModrinthCollections.sectionFor(ModrinthProjectType.MOD));
        assertEquals("Mods", ModrinthCollections.sectionFor(null));
        assertEquals("Plugins", ModrinthCollections.sectionFor(ModrinthProjectType.PLUGIN));
        assertEquals("Data Packs", ModrinthCollections.sectionFor(ModrinthProjectType.DATAPACK));
        assertEquals("Resource Packs", ModrinthCollections.sectionFor(ModrinthProjectType.RESOURCEPACK));
        assertEquals("Shaders", ModrinthCollections.sectionFor(ModrinthProjectType.SHADER));
    }
}
