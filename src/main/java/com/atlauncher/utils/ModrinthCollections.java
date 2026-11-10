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
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.data.Instance;
import com.atlauncher.data.ModManagement;
import com.atlauncher.data.modrinth.ModrinthCollection;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthProjectType;
import com.atlauncher.data.modrinth.ModrinthSearchHit;
import com.atlauncher.managers.LogManager;
import com.atlauncher.network.DownloadException;

/**
 * Modrinth collections: looked up by their link or ID, or among the ones of a creator the launcher can see, shown
 * with their projects, and bookmarked in a local library. Modrinth has no way to search all collections, so there's
 * no browsing of them beyond a creator's. The library only holds references and what's shown for them; each is looked
 * up again when opened, and nothing on Modrinth is ever changed.
 */
public final class ModrinthCollections {
    // Modrinth IDs are letters and digits, so they're safe in a URL path as they are
    private static final Pattern ID = Pattern.compile("[0-9A-Za-z]{4,16}");
    // Modrinth usernames, or user IDs: letters, digits, _ and -, so safe in a URL path as they are
    private static final Pattern CREATOR = Pattern.compile("[0-9A-Za-z_-]{1,39}");
    // how many projects to look up in one request
    static final int BATCH = 100;
    // the most of a description kept in the library
    static final int SAVED_DESCRIPTION_LENGTH = 300;

    private ModrinthCollections() {
    }

    /**
     * The collection ID in a Modrinth collection link (https://modrinth.com/collection/ID, with or without the
     * https:// or www.), or an ID on its own. Anything else, including other Modrinth pages and API URLs, is empty.
     * The input is only read, never requested.
     */
    // a Modrinth ID as it's safe to use: only letters and digits
    public static boolean isModrinthId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public static Optional<String> parseCollectionId(String input) {
        if (input == null) {
            return Optional.empty();
        }

        String text = input.trim();
        if (ID.matcher(text).matches()) {
            return Optional.of(text);
        }

        try {
            URI uri = new URI(text.contains("://") ? text : "https://" + text);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http")) || uri.getUserInfo() != null
                || uri.getPort() != -1 || !(host.equals("modrinth.com") || host.equals("www.modrinth.com"))
                || uri.getRawPath() == null) {
                return Optional.empty();
            }

            String path = uri.getRawPath().endsWith("/") ? uri.getRawPath().substring(0, uri.getRawPath().length() - 1)
                : uri.getRawPath();
            String[] segments = path.split("/", -1);
            // "", "collection", ID
            if (segments.length == 3 && segments[0].isEmpty() && segments[1].equals("collection")
                && ID.matcher(segments[2]).matches()) {
                return Optional.of(segments[2]);
            }
        } catch (URISyntaxException e) {
            // not a link
        }
        return Optional.empty();
    }

    /**
     * The creator in a Modrinth profile link (https://modrinth.com/user/NAME, or its collections page), or a username
     * or user ID on its own. Anything else is empty. The input is only read, never requested.
     */
    public static Optional<String> parseCreator(String input) {
        if (input == null) {
            return Optional.empty();
        }

        String text = input.trim();
        if (CREATOR.matcher(text).matches()) {
            return Optional.of(text);
        }

        try {
            URI uri = new URI(text.contains("://") ? text : "https://" + text);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http")) || uri.getUserInfo() != null
                || uri.getPort() != -1 || !(host.equals("modrinth.com") || host.equals("www.modrinth.com"))
                || uri.getRawPath() == null) {
                return Optional.empty();
            }

            String path = uri.getRawPath().endsWith("/") ? uri.getRawPath().substring(0, uri.getRawPath().length() - 1)
                : uri.getRawPath();
            String[] segments = path.split("/", -1);
            // "", "user", NAME, and optionally "collections"
            boolean profile = segments.length == 3
                || (segments.length == 4 && segments[3].equals("collections"));
            if (profile && segments[0].isEmpty() && segments[1].equals("user")
                && CREATOR.matcher(segments[2]).matches()) {
                return Optional.of(segments[2]);
            }
        } catch (URISyntaxException e) {
            // not a link
        }
        return Optional.empty();
    }

    // --- looking one up

    /**
     * Where collections and projects come from: Modrinth, or anything else in tests.
     */
    public interface Source {
        // throws a DownloadException with its status code when Modrinth answers with an error
        ModrinthCollection collection(String id) throws IOException;

        // those that could be found; null when they couldn't be looked up at all
        List<ModrinthProject> projects(List<String> ids) throws IOException;

        // a creator's collections Modrinth lets this requester see; a DownloadException with its status code on an
        // error
        List<ModrinthCollection> userCollections(String user) throws IOException;
    }

    /**
     * Modrinth itself, through the launcher's API client.
     */
    public static final Source MODRINTH = new Source() {
        @Override
        public ModrinthCollection collection(String id) throws IOException {
            return ModrinthApi.getCollection(id);
        }

        @Override
        public List<ModrinthProject> projects(List<String> ids) {
            return ModrinthApi.getProjects(ids.toArray(new String[0]));
        }

        @Override
        public List<ModrinthCollection> userCollections(String user) throws IOException {
            return ModrinthApi.getUserCollections(user);
        }
    };

    public enum State {
        LOADED, EMPTY, UNAVAILABLE, ERROR
    }

    /**
     * A collection looked up: its projects in the collection's order, or why it couldn't be. Only a collection that
     * was found (loaded, or empty) can be saved.
     */
    public static final class Lookup {
        public final String id;
        public final State state;
        public final ModrinthCollection collection;
        public final List<ModrinthProject> projects;
        // projects in the collection that Modrinth didn't return, such as deleted or private ones
        public final int missing;
        public final String problem;

        private Lookup(String id, State state, ModrinthCollection collection, List<ModrinthProject> projects,
            int missing, String problem) {
            this.id = id;
            this.state = state;
            this.collection = collection;
            this.projects = projects;
            this.missing = missing;
            this.problem = problem;
        }

        public boolean canBeSaved() {
            return state == State.LOADED || state == State.EMPTY;
        }

        static Lookup failed(String id, State state, String problem) {
            return new Lookup(id, state, null, Collections.emptyList(), 0, problem);
        }
    }

    /**
     * Looks a collection up, then its projects. A lookup that fails is never an empty collection: it says what went
     * wrong.
     */
    public static Lookup lookUp(String id, Source source) {
        ModrinthCollection collection;
        try {
            collection = source.collection(id);
        } catch (DownloadException e) {
            Integer status = e.statusCode;
            if (status != null && (status == 404 || status == 401 || status == 403 || status == 410)) {
                return Lookup.failed(id, State.UNAVAILABLE, GetText.tr("This collection couldn't be found. It may be "
                    + "private or deleted, or the link may be wrong. Private collections can't be viewed here, as "
                    + "the launcher doesn't sign in to Modrinth."));
            }
            LogManager.warn("Modrinth answered " + status + " for collection " + id);
            return Lookup.failed(id, State.ERROR, GetText.tr("Modrinth couldn't return this collection right now "
                + "({0}). Try again later.", status == null ? "?" : String.valueOf(status)));
        } catch (IOException e) {
            LogManager.warn("Couldn't reach Modrinth for collection " + id + ": " + e.getMessage());
            return Lookup.failed(id, State.ERROR, GetText.tr("Modrinth couldn't be reached. Check your connection "
                + "and try again."));
        } catch (RuntimeException e) {
            LogManager.logStackTrace("Failed to read collection " + id, e);
            return Lookup.failed(id, State.ERROR, GetText.tr("Modrinth's answer for this collection couldn't be "
                + "read. Try again later."));
        }

        if (collection == null) {
            return Lookup.failed(id, State.ERROR, GetText.tr("Modrinth's answer for this collection couldn't be "
                + "read. Try again later."));
        }

        List<String> ids = collection.projects == null ? Collections.emptyList() : collection.projects;
        if (ids.isEmpty()) {
            return new Lookup(id, State.EMPTY, collection, Collections.emptyList(), 0, null);
        }

        Map<String, ModrinthProject> found = new HashMap<>();
        try {
            for (int from = 0; from < ids.size(); from += BATCH) {
                List<ModrinthProject> batch = source.projects(ids.subList(from, Math.min(ids.size(), from + BATCH)));
                if (batch == null) {
                    return Lookup.failed(id, State.ERROR, GetText.tr("The projects in this collection couldn't be "
                        + "looked up. Try again later."));
                }
                for (ModrinthProject project : batch) {
                    if (project != null && project.id != null) {
                        found.put(project.id, project);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LogManager.warn("Couldn't look up the projects in collection " + id + ": " + e.getMessage());
            return Lookup.failed(id, State.ERROR, GetText.tr("The projects in this collection couldn't be looked up. "
                + "Try again later."));
        }

        List<ModrinthProject> projects = new ArrayList<>();
        for (String projectId : ids) {
            ModrinthProject project = found.get(projectId);
            if (project != null && !projects.contains(project)) {
                projects.add(project);
            }
        }
        return new Lookup(id, State.LOADED, collection, projects, ids.size() - projects.size(), null);
    }

    // --- browsing a creator's collections

    /**
     * A creator's collections, as Modrinth lets this requester see them: their listed ones with projects in them,
     * and, with an API key set, the key owner's own unlisted and private ones when they look themselves up. It's not
     * all of Modrinth's collections; there's no way to search those.
     */
    public static final class CreatorLookup {
        public final String creator;
        public final State state;
        public final List<ModrinthCollection> collections;
        public final String problem;

        private CreatorLookup(String creator, State state, List<ModrinthCollection> collections, String problem) {
            this.creator = creator;
            this.state = state;
            this.collections = collections;
            this.problem = problem;
        }

        static CreatorLookup failed(String creator, State state, String problem) {
            return new CreatorLookup(creator, state, Collections.emptyList(), problem);
        }
    }

    public static CreatorLookup lookUpCreator(String creator, Source source) {
        List<ModrinthCollection> found;
        try {
            found = source.userCollections(creator);
        } catch (DownloadException e) {
            Integer status = e.statusCode;
            if (status != null && (status == 404 || status == 410)) {
                return CreatorLookup.failed(creator, State.UNAVAILABLE, GetText.tr("No Modrinth creator was found "
                    + "with that name or ID."));
            }
            LogManager.warn("Modrinth answered " + status + " for the collections of " + creator);
            return CreatorLookup.failed(creator, State.ERROR, GetText.tr("Modrinth couldn't return this creator's "
                + "collections right now ({0}). Try again later.", status == null ? "?" : String.valueOf(status)));
        } catch (IOException e) {
            LogManager.warn("Couldn't reach Modrinth for the collections of " + creator + ": " + e.getMessage());
            return CreatorLookup.failed(creator, State.ERROR, GetText.tr("Modrinth couldn't be reached. Check your "
                + "connection and try again."));
        } catch (RuntimeException e) {
            LogManager.logStackTrace("Failed to read the collections of " + creator, e);
            return CreatorLookup.failed(creator, State.ERROR, GetText.tr("Modrinth's answer for this creator couldn't "
                + "be read. Try again later."));
        }

        if (found == null) {
            return CreatorLookup.failed(creator, State.ERROR, GetText.tr("Modrinth's answer for this creator couldn't "
                + "be read. Try again later."));
        }

        List<ModrinthCollection> collections = new ArrayList<>();
        for (ModrinthCollection collection : found) {
            if (collection != null && collection.id != null && ID.matcher(collection.id).matches()) {
                collections.add(collection);
            }
        }
        if (collections.isEmpty()) {
            return new CreatorLookup(creator, State.EMPTY, collections, null);
        }

        collections.sort(Comparator.comparing((ModrinthCollection collection) -> collection.name == null ? ""
            : collection.name.toLowerCase(Locale.ROOT)));
        return new CreatorLookup(creator, State.LOADED, collections, null);
    }

    // --- project types, and handing one to the version selector

    public static String typeName(ModrinthProjectType type) {
        if (type == null) {
            return GetText.tr("Mod");
        }

        switch (type) {
            case MODPACK:
                return GetText.tr("Modpack");
            case PLUGIN:
                return GetText.tr("Plugin");
            case RESOURCEPACK:
                return GetText.tr("Resource Pack");
            case SHADER:
                return GetText.tr("Shader");
            case DATAPACK:
                return GetText.tr("Data Pack");
            default:
                return GetText.tr("Mod");
        }
    }

    /**
     * How a project can be opened from a collection: in the version selector, as Add Mods opens it, or not here.
     */
    public enum Handoff {
        // the selector, as for a search result
        SELECTOR,
        // the selector, installing it as a data pack, as Add Mods' Data Packs section does
        SELECTOR_AS_DATAPACK,
        // modpacks are installed as new instances, not added to one
        MODPACK,
        // not something this instance or server can have added, as Add Mods doesn't offer it there
        UNSUPPORTED
    }

    /**
     * The same sections Add Mods offers this instance or server decide it.
     */
    public static Handoff handoff(ModrinthProject project, ModManagement target) {
        ModrinthProjectType type = project.projectType == null ? ModrinthProjectType.MOD : project.projectType;
        boolean serverSoftware = target.getLoaderVersion() != null
            && (target.getLoaderVersion().isPaper() || target.getLoaderVersion().isPurpur());

        switch (type) {
            case MODPACK:
                return Handoff.MODPACK;
            case PLUGIN:
                return target.supportsPlugins() ? Handoff.SELECTOR : Handoff.UNSUPPORTED;
            case DATAPACK:
                return target instanceof Instance ? Handoff.SELECTOR_AS_DATAPACK : Handoff.UNSUPPORTED;
            case RESOURCEPACK:
            case SHADER:
                return target instanceof Instance ? Handoff.SELECTOR : Handoff.UNSUPPORTED;
            default:
                return target.getLoaderVersion() != null && !serverSoftware ? Handoff.SELECTOR : Handoff.UNSUPPORTED;
        }
    }

    /**
     * The Add Mods section a project of this type is shown in, and so how its card adds it.
     */
    public static String sectionFor(ModrinthProjectType type) {
        if (type == null) {
            return "Mods";
        }

        switch (type) {
            case PLUGIN:
                return "Plugins";
            case DATAPACK:
                return "Data Packs";
            case RESOURCEPACK:
                return "Resource Packs";
            case SHADER:
                return "Shaders";
            default:
                return "Mods";
        }
    }

    /**
     * A project as a search result, so it's shown by the same card as Add Mods' results. Its loaders go with its
     * categories, as they do in search results. Projects don't say who made them by name, so there's no author.
     */
    public static ModrinthSearchHit asSearchHit(ModrinthProject project) {
        ModrinthSearchHit hit = new ModrinthSearchHit();
        hit.projectId = project.id;
        hit.projectType = project.projectType == null ? ModrinthProjectType.MOD : project.projectType;
        hit.slug = project.slug;
        hit.title = project.title;
        hit.description = project.description;
        List<String> categories = new ArrayList<>();
        if (project.categories != null) {
            categories.addAll(project.categories);
        }
        if (project.loaders != null) {
            for (String loader : project.loaders) {
                if (!categories.contains(loader)) {
                    categories.add(loader);
                }
            }
        }
        hit.categories = categories;
        hit.downloads = project.downloads;
        hit.follows = project.followers;
        hit.iconUrl = project.iconUrl;
        hit.dateModified = project.updated;
        hit.clientSide = project.clientSide;
        hit.serverSide = project.serverSide;
        hit.gallery = project.gallery;
        return hit;
    }

    public static String whyNot(Handoff handoff) {
        if (handoff == Handoff.MODPACK) {
            return GetText.tr("Modpacks are installed as their own instance, from the Packs tab.");
        }
        return GetText.tr("This can't be added here, as Add Mods doesn't offer this type for this instance or server.");
    }

    // --- the local library

    /**
     * A saved Modrinth collection: only its ID and what's shown for it. Its projects are looked up again when it's
     * opened.
     */
    public static final class Saved {
        public String id;
        public String name;
        public String description;
        public int projectCount;

        // what's shown for it, from a collection Modrinth returned
        void describe(ModrinthCollection found) {
            name = found.name;
            description = found.description == null || found.description.length() <= SAVED_DESCRIPTION_LENGTH
                ? found.description
                : found.description.substring(0, SAVED_DESCRIPTION_LENGTH);
            projectCount = found.projects == null ? 0 : found.projects.size();
        }

        @Override
        public String toString() {
            return name == null || name.isEmpty() ? id : name;
        }
    }
}
