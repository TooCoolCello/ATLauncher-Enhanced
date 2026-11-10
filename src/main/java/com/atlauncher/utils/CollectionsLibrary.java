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

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.Gsons;
import com.atlauncher.constants.Constants;
import com.atlauncher.data.Instance;
import com.atlauncher.data.ModManagement;
import com.atlauncher.data.ModPlatform;
import com.atlauncher.data.curseforge.CurseForgeCategory;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.managers.LogManager;
import com.atlauncher.utils.ModrinthCollections.Lookup;
import com.atlauncher.utils.ModrinthCollections.Saved;
import com.google.gson.JsonParseException;

/**
 * The launcher's own collections library, kept on this computer: Favorites and the user's own collections, each
 * holding projects from CurseForge and Modrinth, and saved references to Modrinth collections. The three are kept
 * apart; a project is always known by its provider and that provider's ID, so the same ID on both never collides.
 *
 * <p>
 * Only Modrinth has collections of its own, which are only referenced here. CurseForge has none; its projects are only
 * ever in the launcher's own collections.
 */
public final class CollectionsLibrary {
    public static final String FAVORITES_ID = "favorites";
    // the Add Mods sections a project can be recorded as added from
    static final List<String> SECTIONS = Collections.unmodifiableList(Arrays.asList("Mods", "Plugins", "Data Packs",
        "Resource Packs", "Shaders", "Worlds"));
    private static final Pattern CURSEFORGE_ID = Pattern.compile("[1-9][0-9]{0,8}");
    private static final Pattern LOCAL_ID = Pattern.compile("[0-9A-Za-z-]{1,64}");
    // how many projects to look up at once
    static final int BATCH = 100;

    /**
     * A project in a collection: which provider it's from and its ID there, and what's shown for it until it's looked
     * up.
     */
    public static final class Item {
        public ModPlatform provider;
        public String projectId;
        // the Add Mods section it was added from; null to go by the project's own type
        public String section;
        public String name;

        public Item() {
        }

        public Item(ModPlatform provider, String projectId, String section, String name) {
            this.provider = provider;
            this.projectId = projectId;
            this.section = section;
            this.name = name;
        }

        // unique across providers
        public String key() {
            return CollectionsLibrary.key(provider, projectId);
        }

        public boolean isValid() {
            if (provider == ModPlatform.MODRINTH) {
                return ModrinthCollections.isModrinthId(projectId);
            }
            return provider == ModPlatform.CURSEFORGE && projectId != null
                && CURSEFORGE_ID.matcher(projectId).matches();
        }
    }

    /**
     * Favorites, or a collection of the user's own.
     */
    /**
     * A project's key: its provider's constant name and its ID there, such as CURSEFORGE:238222.
     */
    static String key(ModPlatform provider, String projectId) {
        return (provider == null ? "?" : provider.name()) + ":" + projectId;
    }

    public static final class LocalCollection {
        public String id;
        public String name;
        public List<Item> items = new ArrayList<>();

        @Override
        public String toString() {
            return name;
        }
    }

    private static final class Contents {
        // written so a later format can tell this one apart
        @SuppressWarnings("UnusedVariable")
        int version = 1;
        LocalCollection favorites;
        List<LocalCollection> collections = new ArrayList<>();
        List<Saved> modrinthCollections = new ArrayList<>();
    }

    private static final class LegacyContents {
        List<Saved> collections = new ArrayList<>();
    }

    private final Path file;
    private final LocalCollection favorites = newFavorites();
    private final List<LocalCollection> collections = new ArrayList<>();
    private final List<Saved> modrinthCollections = new ArrayList<>();
    private final boolean readable;

    /**
     * @param legacyModrinthFile where saved Modrinth collections were kept before this library: read when there's no
     *                           library yet, and never changed
     */
    public CollectionsLibrary(Path file, Path legacyModrinthFile) {
        this.file = file;
        boolean readable = true;

        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    Contents contents = Gsons.DEFAULT.fromJson(reader, Contents.class);
                    if (contents != null) {
                        if (contents.favorites != null) {
                            favorites.items.addAll(validItems(contents.favorites.items));
                        }
                        readCollections(contents.collections);
                        readModrinth(contents.modrinthCollections);
                    }
                }
            } else if (legacyModrinthFile != null && Files.exists(legacyModrinthFile)) {
                try (Reader reader = Files.newBufferedReader(legacyModrinthFile, StandardCharsets.UTF_8)) {
                    LegacyContents contents = Gsons.DEFAULT.fromJson(reader, LegacyContents.class);
                    if (contents != null) {
                        readModrinth(contents.collections);
                    }
                }
            }
        } catch (IOException | JsonParseException e) {
            LogManager.logStackTrace("Couldn't read the collections library", e);
            readable = false;
        }
        this.readable = readable;
    }

    private static LocalCollection newFavorites() {
        LocalCollection favorites = new LocalCollection();
        favorites.id = FAVORITES_ID;
        favorites.name = GetText.tr("Favorites");
        return favorites;
    }

    // the valid ones, each once, in order
    private static List<Item> validItems(List<Item> items) {
        List<Item> valid = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (items != null) {
            for (Item item : items) {
                if (item != null && item.isValid() && seen.add(item.key())) {
                    if (item.section != null && !SECTIONS.contains(item.section)) {
                        item.section = null;
                    }
                    valid.add(item);
                }
            }
        }
        return valid;
    }

    private void readCollections(List<LocalCollection> read) {
        Set<String> ids = new HashSet<>();
        ids.add(FAVORITES_ID);
        if (read != null) {
            for (LocalCollection collection : read) {
                if (collection != null && collection.id != null && LOCAL_ID.matcher(collection.id).matches()
                    && ids.add(collection.id)) {
                    collection.items = validItems(collection.items);
                    collections.add(collection);
                }
            }
        }
    }

    private void readModrinth(List<Saved> read) {
        if (read != null) {
            for (Saved collection : read) {
                if (collection != null && ModrinthCollections.isModrinthId(collection.id)
                    && findModrinth(collection.id) == null) {
                    modrinthCollections.add(collection);
                }
            }
        }
    }

    public boolean isReadable() {
        return readable;
    }

    public LocalCollection favorites() {
        return favorites;
    }

    public List<LocalCollection> localCollections() {
        return Collections.unmodifiableList(collections);
    }

    public List<Saved> modrinthCollections() {
        return Collections.unmodifiableList(modrinthCollections);
    }

    // --- the launcher's own collections: groundwork, not yet offered in the launcher

    public boolean isFavorite(ModPlatform provider, String projectId) {
        String key = key(provider, projectId);
        return favorites.items.stream().anyMatch(item -> item.key().equals(key));
    }

    /**
     * Adds a project to Favorites, once.
     *
     * @return false when it's already there
     */
    public boolean addFavorite(Item item) throws IOException {
        if (item == null || !item.isValid()) {
            throw new IllegalArgumentException("not a project");
        }
        if (isFavorite(item.provider, item.projectId)) {
            return false;
        }

        favorites.items.add(item);
        write();
        return true;
    }

    public boolean removeFavorite(ModPlatform provider, String projectId) throws IOException {
        String key = key(provider, projectId);
        if (!favorites.items.removeIf(item -> item.key().equals(key))) {
            return false;
        }

        write();
        return true;
    }

    public LocalCollection createCollection(String name) throws IOException {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("no name");
        }

        LocalCollection collection = new LocalCollection();
        collection.id = UUID.randomUUID().toString();
        collection.name = name.trim();
        collections.add(collection);
        write();
        return collection;
    }

    // --- saved Modrinth collections

    public Saved findModrinth(String id) {
        return modrinthCollections.stream().filter(collection -> collection.id.equals(id)).findFirst().orElse(null);
    }

    /**
     * Saves a Modrinth collection that was found, or updates what's shown for one already saved: never two of the
     * same ID.
     *
     * @throws IllegalArgumentException for a lookup that failed, which isn't a collection to save
     */
    public Saved saveModrinth(Lookup lookup) throws IOException {
        if (!lookup.canBeSaved()) {
            throw new IllegalArgumentException("collection " + lookup.id + " wasn't found");
        }

        Saved collection = findModrinth(lookup.id);
        boolean added = collection == null;
        if (added) {
            collection = new Saved();
            collection.id = lookup.id;
        }
        collection.describe(lookup.collection);

        if (added) {
            modrinthCollections.add(collection);
        }
        write();
        return collection;
    }

    /**
     * What's shown for a saved Modrinth collection, brought up to date by a lookup that found it. Nothing is written
     * for one that isn't saved, or a lookup that failed.
     */
    public boolean refreshModrinth(Lookup lookup) throws IOException {
        Saved collection = findModrinth(lookup.id);
        if (collection == null || !lookup.canBeSaved()) {
            return false;
        }

        collection.describe(lookup.collection);
        write();
        return true;
    }

    /**
     * Removes a saved Modrinth collection from this library only; the collection on Modrinth isn't changed.
     */
    public boolean removeModrinth(String id) throws IOException {
        Saved collection = findModrinth(id);
        if (collection == null) {
            return false;
        }

        modrinthCollections.remove(collection);
        write();
        return true;
    }

    private void write() throws IOException {
        if (!readable) {
            throw new IOException(file + " couldn't be read, so it isn't overwritten");
        }

        Contents contents = new Contents();
        contents.favorites = favorites;
        contents.collections = new ArrayList<>(collections);
        contents.modrinthCollections = new ArrayList<>(modrinthCollections);
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            Gsons.DEFAULT.toJson(contents, writer);
        }

        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // --- opening a local collection: each project from its own provider

    /**
     * Where projects are looked up: CurseForge and Modrinth, or anything else in tests. Each returns those it found,
     * or null when it couldn't look them up at all.
     */
    public interface Providers {
        List<ModrinthProject> modrinth(List<String> ids) throws IOException;

        List<CurseForgeProject> curseForge(List<Integer> ids) throws IOException;
    }

    public static final Providers PROVIDERS = new Providers() {
        @Override
        public List<ModrinthProject> modrinth(List<String> ids) {
            return ModrinthApi.getProjects(ids.toArray(new String[0]));
        }

        @Override
        public List<CurseForgeProject> curseForge(List<Integer> ids) {
            return CurseForgeApi.getProjects(ids.stream().mapToInt(Integer::intValue).toArray());
        }
    };

    /**
     * A project in a collection, as its own provider returned it.
     */
    public static final class Resolved {
        public final Item item;
        public final ModrinthProject modrinth;
        public final CurseForgeProject curseForge;

        Resolved(Item item, ModrinthProject modrinth, CurseForgeProject curseForge) {
            this.item = item;
            this.modrinth = modrinth;
            this.curseForge = curseForge;
        }
    }

    public static final class Resolution {
        public final ModrinthCollections.State state;
        public final List<Resolved> found;
        // projects their provider didn't return, such as deleted ones
        public final int missing;
        public final String problem;

        Resolution(ModrinthCollections.State state, List<Resolved> found, int missing, String problem) {
            this.state = state;
            this.found = found;
            this.missing = missing;
            this.problem = problem;
        }
    }

    /**
     * Looks each project up from the provider it was recorded with, never from whichever Add Mods tab is showing. A
     * failed lookup is never an empty collection.
     */
    public static Resolution resolve(List<Item> items, Providers providers) {
        if (items.isEmpty()) {
            return new Resolution(ModrinthCollections.State.EMPTY, Collections.emptyList(), 0, null);
        }

        List<String> modrinthIds = new ArrayList<>();
        List<Integer> curseForgeIds = new ArrayList<>();
        for (Item item : items) {
            if (item.provider == ModPlatform.MODRINTH) {
                modrinthIds.add(item.projectId);
            } else {
                curseForgeIds.add(Integer.valueOf(item.projectId));
            }
        }

        Map<String, ModrinthProject> modrinth = new HashMap<>();
        Map<Integer, CurseForgeProject> curseForge = new HashMap<>();
        try {
            for (int from = 0; from < modrinthIds.size(); from += BATCH) {
                List<ModrinthProject> batch = providers.modrinth(
                    modrinthIds.subList(from, Math.min(modrinthIds.size(), from + BATCH)));
                if (batch == null) {
                    return failed(GetText.tr("The Modrinth projects in this collection couldn't be looked up. Try "
                        + "again later."));
                }
                batch.stream().filter(p -> p != null && p.id != null).forEach(p -> modrinth.put(p.id, p));
            }
            for (int from = 0; from < curseForgeIds.size(); from += BATCH) {
                List<CurseForgeProject> batch = providers.curseForge(
                    curseForgeIds.subList(from, Math.min(curseForgeIds.size(), from + BATCH)));
                if (batch == null) {
                    return failed(GetText.tr("The CurseForge projects in this collection couldn't be looked up. Try "
                        + "again later."));
                }
                batch.stream().filter(p -> p != null).forEach(p -> curseForge.put(p.id, p));
            }
        } catch (IOException | RuntimeException e) {
            LogManager.warn("Couldn't look up the projects in a collection: " + e.getMessage());
            return failed(GetText.tr("The projects in this collection couldn't be looked up. Try again later."));
        }

        List<Resolved> found = new ArrayList<>();
        for (Item item : items) {
            if (item.provider == ModPlatform.MODRINTH && modrinth.containsKey(item.projectId)) {
                found.add(new Resolved(item, modrinth.get(item.projectId), null));
            } else if (item.provider == ModPlatform.CURSEFORGE
                && curseForge.containsKey(Integer.valueOf(item.projectId))) {
                found.add(new Resolved(item, null, curseForge.get(Integer.valueOf(item.projectId))));
            }
        }
        return new Resolution(ModrinthCollections.State.LOADED, found, items.size() - found.size(), null);
    }

    private static Resolution failed(String problem) {
        return new Resolution(ModrinthCollections.State.ERROR, Collections.emptyList(), 0, problem);
    }

    // --- CurseForge projects, as Add Mods offers them

    /**
     * The Add Mods section a CurseForge project is shown in, by its class; null for a modpack, which isn't added to an
     * instance.
     */
    public static String curseForgeSection(CurseForgeProject project) {
        int classId = project.classId;
        if (project.categories != null) {
            for (CurseForgeCategory category : project.categories) {
                if (category != null && category.id == project.primaryCategoryId && category.classId != 0) {
                    classId = category.classId;
                }
            }
        }

        if (classId == Constants.CURSEFORGE_MODPACKS_SECTION_ID) {
            return null;
        }
        if (classId == Constants.CURSEFORGE_PLUGINS_SECTION_ID) {
            return "Plugins";
        }
        if (classId == Constants.CURSEFORGE_DATA_PACKS_SECTION_ID) {
            return "Data Packs";
        }
        if (classId == Constants.CURSEFORGE_RESOURCE_PACKS_SECTION_ID) {
            return "Resource Packs";
        }
        if (classId == Constants.CURSEFORGE_SHADER_PACKS_SECTION_ID) {
            return "Shaders";
        }
        if (classId == Constants.CURSEFORGE_WORLDS_SECTION_ID) {
            return "Worlds";
        }
        return "Mods";
    }

    /**
     * Whether Add Mods offers this section for this instance or server, on CurseForge.
     */
    public static boolean curseForgeOffers(String section, ModManagement target) {
        if (section == null) {
            return false;
        }

        switch (section) {
            case "Plugins":
                return target.supportsPlugins();
            case "Mods":
                return target.getLoaderVersion() != null && !target.getLoaderVersion().isPaper()
                    && !target.getLoaderVersion().isPurpur();
            default:
                return target instanceof Instance;
        }
    }
}
