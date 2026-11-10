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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.atlauncher.constants.Constants;
import com.atlauncher.data.Instance;
import com.atlauncher.data.InstanceLauncher;
import com.atlauncher.data.ModPlatform;
import com.atlauncher.data.Server;
import com.atlauncher.data.curseforge.CurseForgeCategory;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.minecraft.MinecraftVersion;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthCollection;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.utils.CollectionsLibrary.Item;
import com.atlauncher.utils.CollectionsLibrary.Resolution;
import com.atlauncher.utils.ModrinthCollections.Lookup;
import com.atlauncher.utils.ModrinthCollections.Saved;
import com.atlauncher.utils.ModrinthCollections.State;

/**
 * The launcher's collections library, in a temporary folder: projects known by provider and ID, Favorites and local
 * collections apart from saved Modrinth collections, bookmarks carried over from before, and each project looked up
 * from its own provider.
 */
public class CollectionsLibraryTest {
    private static final String ID = "AbCd1234";

    @TempDir
    Path temp;

    private Path file() {
        return temp.resolve("configs/collections.json");
    }

    private Path legacy() {
        return temp.resolve("configs/modrinth_collections.json");
    }

    private CollectionsLibrary library() {
        return new CollectionsLibrary(file(), legacy());
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    // a Modrinth collection that was found
    private static Lookup found(String name, String... projects) {
        ModrinthCollections.Source modrinth = new ModrinthCollections.Source() {
            @Override
            public ModrinthCollection collection(String id) {
                ModrinthCollection collection = new ModrinthCollection();
                collection.id = ID;
                collection.name = name;
                collection.description = "A description";
                collection.projects = new ArrayList<>(Arrays.asList(projects));
                return collection;
            }

            @Override
            public List<ModrinthProject> projects(List<String> ids) {
                return ids.stream().map(id -> {
                    ModrinthProject project = new ModrinthProject();
                    project.id = id;
                    project.title = id;
                    return project;
                }).collect(Collectors.toList());
            }

            @Override
            public List<ModrinthCollection> userCollections(String user) {
                return Collections.emptyList();
            }
        };
        return ModrinthCollections.lookUp(ID, modrinth);
    }

    // --- projects by provider and ID

    @Test
    public void aProjectIsKnownByItsProviderAndIdSoTheSameIdNeverCollides() throws IOException {
        Item curseForge = new Item(ModPlatform.CURSEFORGE, "238222", "Mods", "JEI");
        Item modrinth = new Item(ModPlatform.MODRINTH, "23822200", "Mods", "Something else");
        assertEquals("CURSEFORGE:238222", curseForge.key());
        assertEquals("MODRINTH:23822200", modrinth.key());

        CollectionsLibrary library = library();
        assertTrue(library.addFavorite(curseForge));
        assertTrue(library.addFavorite(new Item(ModPlatform.MODRINTH, "238222AB", "Mods", "Other")));
        assertFalse(library.addFavorite(new Item(ModPlatform.CURSEFORGE, "238222", "Mods", "JEI again")));
        assertTrue(library.isFavorite(ModPlatform.CURSEFORGE, "238222"));
        assertFalse(library.isFavorite(ModPlatform.MODRINTH, "238222"));

        CollectionsLibrary reopened = library();
        assertEquals(Arrays.asList("CURSEFORGE:238222", "MODRINTH:238222AB"),
            reopened.favorites().items.stream().map(Item::key).collect(Collectors.toList()));
        assertTrue(reopened.removeFavorite(ModPlatform.CURSEFORGE, "238222"));
        assertEquals(1, library().favorites().items.size());
    }

    @Test
    public void onlyValidProjectsAreKept() {
        assertTrue(new Item(ModPlatform.CURSEFORGE, "1", null, null).isValid());
        assertTrue(new Item(ModPlatform.MODRINTH, "AANobbMI", null, null).isValid());
        for (Item item : new Item[] { new Item(ModPlatform.CURSEFORGE, "0", null, null),
            new Item(ModPlatform.CURSEFORGE, "12a", null, null), new Item(ModPlatform.CURSEFORGE, "99999999999", null,
                null),
            new Item(ModPlatform.MODRINTH, "../x", null, null), new Item(ModPlatform.MODRINTH, null, null, null),
            new Item(null, "238222", null, null) }) {
            assertFalse(item.isValid(), item.provider + " " + item.projectId);
        }
        assertThrows(IllegalArgumentException.class,
            () -> library().addFavorite(new Item(ModPlatform.MODRINTH, "../x", null, null)));
    }

    @Test
    public void favoritesLocalCollectionsAndModrinthCollectionsAreKeptApart() throws IOException {
        CollectionsLibrary library = library();
        assertEquals(CollectionsLibrary.FAVORITES_ID, library.favorites().id);
        assertTrue(library.favorites().items.isEmpty());

        CollectionsLibrary.LocalCollection mine = library.createCollection("  Building  ");
        library.saveModrinth(found("Performance", "P1"));
        library.addFavorite(new Item(ModPlatform.MODRINTH, "AANobbMI", "Mods", "Sodium"));

        CollectionsLibrary reopened = library();
        assertEquals(1, reopened.localCollections().size());
        assertEquals("Building", reopened.localCollections().get(0).name);
        assertEquals(mine.id, reopened.localCollections().get(0).id);
        assertEquals(1, reopened.modrinthCollections().size());
        assertEquals(ID, reopened.modrinthCollections().get(0).id);
        assertEquals(1, reopened.favorites().items.size());
        assertTrue(reopened.localCollections().get(0).items.isEmpty());
    }

    @Test
    public void badEntriesAreDroppedWhenRead() throws IOException {
        write(file(), "{\"version\": 1,"
            + " \"favorites\": {\"id\": \"favorites\", \"items\": ["
            + "{\"provider\": \"CurseForge\", \"projectId\": \"238222\", \"section\": \"Nonsense\"},"
            + "{\"provider\": \"CurseForge\", \"projectId\": \"238222\"},"
            + "{\"provider\": \"Modrinth\", \"projectId\": \"../x\"}, null]},"
            + " \"collections\": [{\"id\": \"favorites\", \"name\": \"Clash\"}, {\"id\": \"a b\", \"name\": \"Bad\"},"
            + " {\"id\": \"ok-1\", \"name\": \"Good\"}, {\"id\": \"ok-1\", \"name\": \"Again\"}],"
            + " \"modrinthCollections\": [{\"id\": \"" + ID + "\", \"name\": \"First\"}, {\"id\": \"" + ID
            + "\", \"name\": \"Again\"}, {\"id\": \"../x\"}]}");

        CollectionsLibrary library = library();
        assertTrue(library.isReadable());
        assertEquals(1, library.favorites().items.size());
        assertNull(library.favorites().items.get(0).section);
        assertEquals(Collections.singletonList("Good"),
            library.localCollections().stream().map(c -> c.name).collect(Collectors.toList()));
        assertEquals(1, library.modrinthCollections().size());
        assertEquals("First", library.findModrinth(ID).name);
    }

    // --- saved Modrinth collections, and those saved before

    @Test
    public void earlierSavedModrinthCollectionsAreCarriedOverAndTheirFileLeftAlone() throws IOException {
        String before = "{\"collections\": [{\"id\": \"" + ID + "\", \"name\": \"Performance\", \"projectCount\": 2}]}";
        write(legacy(), before);

        CollectionsLibrary library = library();
        assertEquals(1, library.modrinthCollections().size());
        assertEquals("Performance", library.findModrinth(ID).name);
        assertEquals(2, library.findModrinth(ID).projectCount);
        // nothing's written until something changes
        assertFalse(Files.exists(file()));

        library.saveModrinth(found("Performance", "P1", "P2", "P3"));
        assertEquals(before, read(legacy()));
        assertTrue(Files.exists(file()));

        // from now on the library is read, not the file from before
        write(legacy(), "{\"collections\": []}");
        assertEquals(1, library().modrinthCollections().size());
        assertEquals(3, library().findModrinth(ID).projectCount);
    }

    @Test
    public void anUnreadableEarlierFileMakesTheLibraryReadOnly() throws IOException {
        write(legacy(), "{ not json");
        CollectionsLibrary library = library();

        assertFalse(library.isReadable());
        assertThrows(IOException.class, () -> library.saveModrinth(found("Performance", "P1")));
        assertFalse(Files.exists(file()));
        assertEquals("{ not json", read(legacy()));
    }

    @Test
    public void anUnreadableLibraryIsLeftAsItIs() throws IOException {
        write(file(), "{ not json");
        CollectionsLibrary library = library();

        assertFalse(library.isReadable());
        assertThrows(IOException.class,
            () -> library.addFavorite(new Item(ModPlatform.MODRINTH, "AANobbMI", null, null)));
        assertEquals("{ not json", read(file()));
    }

    @Test
    public void aSavedModrinthCollectionIsOnlyAReferenceNeverDuplicated() throws IOException {
        CollectionsLibrary library = library();
        library.saveModrinth(found("Performance", "P1"));
        library.saveModrinth(found("Performance, renamed", "P1", "P2"));

        assertEquals(1, library().modrinthCollections().size());
        Saved saved = library().findModrinth(ID);
        assertEquals("Performance, renamed", saved.name);
        assertEquals(2, saved.projectCount);
        String json = read(file());
        assertFalse(json.contains("\"P1\""), json);
        assertFalse(Files.exists(file().resolveSibling("collections.json.tmp")));
    }

    @Test
    public void aFailedLookupIsNeverSavedAndDoesntChangeWhatWas() throws IOException {
        CollectionsLibrary library = library();
        ModrinthCollections.Source offline = new ModrinthCollections.Source() {
            @Override
            public ModrinthCollection collection(String id) throws IOException {
                throw new IOException("offline");
            }

            @Override
            public List<ModrinthProject> projects(List<String> ids) {
                return null;
            }

            @Override
            public List<ModrinthCollection> userCollections(String user) {
                return null;
            }
        };
        Lookup failed = ModrinthCollections.lookUp(ID, offline);

        assertThrows(IllegalArgumentException.class, () -> library.saveModrinth(failed));
        assertFalse(Files.exists(file()));

        library.saveModrinth(found("Performance", "P1"));
        assertFalse(library.refreshModrinth(failed));
        assertEquals("Performance", library().findModrinth(ID).name);
        assertTrue(library.refreshModrinth(found("Performance v2", "P1")));
        assertEquals("Performance v2", library().findModrinth(ID).name);
    }

    @Test
    public void removingASavedModrinthCollectionOnlyRemovesItsReference() throws IOException {
        CollectionsLibrary library = library();
        library.saveModrinth(found("Performance", "P1"));
        library.addFavorite(new Item(ModPlatform.MODRINTH, "AANobbMI", null, null));

        assertTrue(library.removeModrinth(ID));
        assertFalse(library.removeModrinth(ID));
        assertTrue(library().modrinthCollections().isEmpty());
        assertEquals(1, library().favorites().items.size());
    }

    @Test
    public void longDescriptionsAreShortenedWhenSaved() throws IOException {
        Lookup lookup = found("Long", "P1");
        StringBuilder description = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            description.append("0123456789");
        }
        lookup.collection.description = description.toString();

        assertEquals(ModrinthCollections.SAVED_DESCRIPTION_LENGTH,
            library().saveModrinth(lookup).description.length());
    }

    // --- opening a local collection

    private static CurseForgeProject curseForgeProject(int id, int classId) {
        CurseForgeProject project = new CurseForgeProject();
        project.id = id;
        project.name = "CurseForge " + id;
        project.classId = classId;
        project.categories = new ArrayList<>();
        return project;
    }

    private static ModrinthProject modrinth(String id) {
        ModrinthProject project = new ModrinthProject();
        project.id = id;
        project.title = "Modrinth " + id;
        return project;
    }

    /**
     * Answers from what it's given, and remembers what it was asked.
     */
    private static final class FakeProviders implements CollectionsLibrary.Providers {
        final List<String> modrinthAsked = new ArrayList<>();
        final List<Integer> curseForgeAsked = new ArrayList<>();
        boolean curseForgeFails;

        @Override
        public List<ModrinthProject> modrinth(List<String> ids) {
            modrinthAsked.addAll(ids);
            return ids.stream().filter(id -> !id.equals("Gone0000")).map(CollectionsLibraryTest::modrinth)
                .collect(Collectors.toList());
        }

        @Override
        public List<CurseForgeProject> curseForge(List<Integer> ids) {
            curseForgeAsked.addAll(ids);
            if (curseForgeFails) {
                return null;
            }
            return ids.stream().map(id -> curseForgeProject(id, Constants.CURSEFORGE_MODS_SECTION_ID))
                .collect(Collectors.toList());
        }
    }

    @Test
    public void eachProjectIsLookedUpFromTheProviderItWasRecordedWith() {
        List<Item> items = Arrays.asList(new Item(ModPlatform.CURSEFORGE, "238222", "Mods", null),
            new Item(ModPlatform.MODRINTH, "AANobbMI", "Mods", null),
            new Item(ModPlatform.MODRINTH, "Gone0000", "Mods", null),
            new Item(ModPlatform.CURSEFORGE, "306612", "Mods", null));
        FakeProviders providers = new FakeProviders();

        Resolution resolution = CollectionsLibrary.resolve(items, providers);
        assertEquals(Arrays.asList(238222, 306612), providers.curseForgeAsked);
        assertEquals(Arrays.asList("AANobbMI", "Gone0000"), providers.modrinthAsked);
        assertEquals(State.LOADED, resolution.state);
        assertEquals(1, resolution.missing);

        // in the collection's order, each from its own provider
        assertEquals(238222, resolution.found.get(0).curseForge.id);
        assertNull(resolution.found.get(0).modrinth);
        assertEquals("AANobbMI", resolution.found.get(1).modrinth.id);
        assertNull(resolution.found.get(1).curseForge);
        assertEquals(306612, resolution.found.get(2).curseForge.id);
    }

    @Test
    public void aLocalCollectionThatCantBeLookedUpIsNeverEmpty() {
        FakeProviders providers = new FakeProviders();
        providers.curseForgeFails = true;

        Resolution failed = CollectionsLibrary.resolve(
            Collections.singletonList(new Item(ModPlatform.CURSEFORGE, "238222", null, null)), providers);
        assertEquals(State.ERROR, failed.state);
        assertTrue(failed.found.isEmpty());
        assertTrue(failed.problem.contains("CurseForge"));

        assertEquals(State.EMPTY, CollectionsLibrary.resolve(Collections.emptyList(), providers).state);
    }

    @Test
    public void curseForgeProjectsAreOfferedWhereAddModsOffersTheirSection() {
        assertEquals("Mods",
            CollectionsLibrary.curseForgeSection(curseForgeProject(1, Constants.CURSEFORGE_MODS_SECTION_ID)));
        assertEquals("Resource Packs",
            CollectionsLibrary.curseForgeSection(curseForgeProject(1, Constants.CURSEFORGE_RESOURCE_PACKS_SECTION_ID)));
        assertEquals("Shaders",
            CollectionsLibrary.curseForgeSection(curseForgeProject(1, Constants.CURSEFORGE_SHADER_PACKS_SECTION_ID)));
        assertEquals("Data Packs",
            CollectionsLibrary.curseForgeSection(curseForgeProject(1, Constants.CURSEFORGE_DATA_PACKS_SECTION_ID)));
        assertEquals("Worlds",
            CollectionsLibrary.curseForgeSection(curseForgeProject(1, Constants.CURSEFORGE_WORLDS_SECTION_ID)));
        assertEquals("Plugins",
            CollectionsLibrary.curseForgeSection(curseForgeProject(1, Constants.CURSEFORGE_PLUGINS_SECTION_ID)));
        assertNull(
            CollectionsLibrary.curseForgeSection(curseForgeProject(1, Constants.CURSEFORGE_MODPACKS_SECTION_ID)));

        // the primary category's class counts first, as elsewhere
        CurseForgeProject categorised = curseForgeProject(1, Constants.CURSEFORGE_MODS_SECTION_ID);
        CurseForgeCategory category = new CurseForgeCategory();
        category.id = 7;
        category.classId = Constants.CURSEFORGE_RESOURCE_PACKS_SECTION_ID;
        categorised.categories.add(category);
        categorised.primaryCategoryId = 7;
        assertEquals("Resource Packs", CollectionsLibrary.curseForgeSection(categorised));

        MinecraftVersion version = new MinecraftVersion();
        version.id = "1.20.1";
        Instance forge = new Instance(version);
        forge.launcher = new InstanceLauncher();
        forge.launcher.loaderVersion = new LoaderVersion("47.3.0", false, "Forge");
        assertTrue(CollectionsLibrary.curseForgeOffers("Mods", forge));
        assertTrue(CollectionsLibrary.curseForgeOffers("Worlds", forge));
        assertFalse(CollectionsLibrary.curseForgeOffers("Plugins", forge));
        assertFalse(CollectionsLibrary.curseForgeOffers(null, forge));

        Server paper = new Server();
        paper.minecraftVersion = "1.20.1";
        paper.loaderVersion = new LoaderVersion("1.20.1-196", false, "Paper");
        assertTrue(CollectionsLibrary.curseForgeOffers("Plugins", paper));
        assertFalse(CollectionsLibrary.curseForgeOffers("Mods", paper));
        assertFalse(CollectionsLibrary.curseForgeOffers("Resource Packs", paper));
    }
}
