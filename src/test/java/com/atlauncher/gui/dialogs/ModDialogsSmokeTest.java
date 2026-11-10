/*
 * ATLauncher - https://github.com/ATLauncher/ATLauncher
 * Copyright (C) 2013-2022 ATLauncher
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
package com.atlauncher.gui.dialogs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.atlauncher.App;
import com.atlauncher.Launcher;
import com.atlauncher.Network;
import com.atlauncher.constants.Constants;
import com.atlauncher.data.DisableableMod;
import com.atlauncher.data.InstalledDependencies;
import com.atlauncher.data.Server;
import com.atlauncher.data.Settings;
import com.atlauncher.data.Type;
import com.atlauncher.data.curseforge.CurseForgeFile;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthDependency;
import com.atlauncher.data.modrinth.ModrinthDependencyType;
import com.atlauncher.data.modrinth.ModrinthFile;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthVersion;
import com.atlauncher.themes.ATLauncherLaf;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Builds Edit Mods, the mod removal dialog and both file selectors from fixture data (no network: every request gets
 * a canned answer or a 404) and drives them the way a user would. Needs a display, so it's skipped on headless
 * machines.
 */
public class ModDialogsSmokeTest {
    private static final MediaType JSON = MediaType.get("application/json");

    @TempDir
    Path root;

    private Settings previousSettings;
    private Launcher previousLauncher;
    private ATLauncherLaf previousTheme;
    private OkHttpClient previousClient, previousCachedClient;
    // canned answers by request path; anything else gets a 404
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Window> opened = new ArrayList<>();
    private EditModsDialog dialog;
    private boolean replaced = false;

    @BeforeEach
    public void setUp() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A display is required to build the dialog");
        previousSettings = App.settings;
        previousLauncher = App.launcher;
        previousTheme = App.THEME;
        App.settings = new Settings();
        App.launcher = new Launcher();
        // after the settings: the network setup reads them once
        previousClient = Network.CLIENT;
        previousCachedClient = Network.CACHED_CLIENT;
        replaced = true;

        OkHttpClient offline = new OkHttpClient.Builder().addInterceptor(chain -> {
            String path = chain.request().url().encodedPath();
            requests.add(chain.request().method() + " " + path);
            String answer = answers.get(path);
            Response.Builder response = new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1);
            return answer == null
                ? response.code(404).message("Not Found").body(ResponseBody.create("{}", JSON)).build()
                : response.code(200).message("OK").body(ResponseBody.create(answer, JSON)).build();
        }).build();
        Network.CLIENT = offline;
        Network.CACHED_CLIENT = offline;
    }

    @AfterEach
    public void tearDown() throws Exception {
        SwingUtilities.invokeAndWait(() -> opened.forEach(Window::dispose));
        if (replaced) {
            App.settings = previousSettings;
            App.launcher = previousLauncher;
            App.THEME = previousTheme;
            Network.CLIENT = previousClient;
            Network.CACHED_CLIENT = previousCachedClient;
        }
    }

    private Server server(LoaderVersion loader) {
        Server server = new Server();
        server.name = "Smoke";
        server.minecraftVersion = "1.20.1";
        server.loaderVersion = loader;
        server.ROOT = root;
        return server;
    }

    private static DisableableMod modrinth(String id, String sha1, String published, String... loaders) {
        DisableableMod mod = new DisableableMod(id, "1.0", true, id + ".jar", Type.mods, null, "About " + id, false,
            true);
        mod.modrinthProject = new ModrinthProject();
        mod.modrinthProject.id = id;
        mod.modrinthProject.slug = id;
        mod.modrinthProject.title = id;
        mod.modrinthProject.categories = new ArrayList<>();
        mod.modrinthVersion = version(id + "-old", sha1, published, loaders);
        return mod;
    }

    private static ModrinthVersion version(String id, String sha1, String published, String... loaders) {
        ModrinthVersion version = new ModrinthVersion();
        version.id = id;
        version.name = id;
        version.datePublished = published;
        version.loaders = Arrays.asList(loaders);
        version.gameVersions = Collections.singletonList("1.20.1");
        version.dependencies = new ArrayList<>();
        ModrinthFile file = new ModrinthFile();
        file.primary = true;
        file.filename = id + ".jar";
        file.hashes = Collections.singletonMap("sha1", sha1);
        version.files = Collections.singletonList(file);
        return version;
    }

    private EditModsDialog open(Server server) throws Exception {
        AtomicReference<EditModsDialog> created = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> created.set(new EditModsDialog(server)));
        dialog = created.get();
        opened.add(dialog);
        return dialog;
    }

    @SuppressWarnings("unchecked")
    private static <T> T find(Container container, Class<T> type) {
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                return (T) child;
            }
            if (child instanceof Container) {
                T found = find((Container) child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // the update check label once nothing is being checked any more
    private String settledUpdateLabel(JLabel label) throws Exception {
        AtomicReference<String> text = new AtomicReference<>();
        for (int i = 0; i < 100; i++) {
            SwingUtilities.invokeAndWait(() -> text.set(label.getText()));
            if (!text.get().startsWith("Checking")) {
                return text.get();
            }
            Thread.sleep(100);
        }
        return text.get();
    }

    @Test
    public void buildsSortsSearchesAndReloadsThreeHundredMods() throws Exception {
        Server server = server(null);
        Files.createDirectories(root.resolve("mods"));
        for (int i = 0; i < 300; i++) {
            String file = String.format("mod-%03d.jar", i);
            Files.write(root.resolve("mods").resolve(file), new byte[] { 1 });
            server.mods.add(new DisableableMod("Mod " + i, "1.0", true, file, Type.mods, null, "Mod number " + i,
                false, true));
        }

        long start = System.nanoTime();
        open(server);
        long opened = System.nanoTime();

        JComboBox<?> sort = find(dialog, JComboBox.class);
        JTextField search = find(dialog, JTextField.class);
        assertNotNull(sort);
        assertNotNull(search);

        List<Long> reloads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            DisableableMod mod = server.mods.get(i);
            long before = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(mod.disable(server));
                dialog.reloadPanels();
            });
            reloads.add((System.nanoTime() - before) / 1_000_000);
        }
        assertTrue(Files.exists(root.resolve("disabledmods").resolve("mod-000.jar")));

        SwingUtilities.invokeAndWait(() -> {
            sort.setSelectedIndex(1);
            search.setText("mod 12");
            sort.setSelectedIndex(0);
            search.setText("");
            dialog.removeMods(Collections.singletonList(server.mods.get(299)), false);
        });
        assertEquals(299, server.mods.size());
        assertFalse(Files.exists(root.resolve("mods").resolve("mod-299.jar")));

        System.out.println("Edit Mods, 300 mods: open " + (opened - start) / 1_000_000 + " ms, disable one + reload "
            + reloads + " ms");
    }

    @Test
    public void connectorInstallsAreCheckedAgainWhenConnectorIsTurnedOn() throws Exception {
        App.settings.showFabricModsWhenSinytraInstalled = false;
        Server server = server(new LoaderVersion("47.1.3", false, "NeoForge"));
        server.mods.add(modrinth(Constants.MODRINTH_SINYTRA_CONNECTOR_MOD_ID, "c0", "2024-01-01T00:00:00Z",
            "neoforge"));
        server.mods.add(modrinth("fabricmod", "f0", "2024-01-01T00:00:00Z", "fabric"));
        answers.put("/v2/version_files/update", "{\"f0\": {\"id\": \"fabricmod-new\", \"project_id\": \"fabricmod\", "
            + "\"date_published\": \"2024-06-01T00:00:00Z\", \"loaders\": [\"fabric\"], "
            + "\"game_versions\": [\"1.20.1\"]}}");
        // Edit Mods drops entries whose file is gone
        Files.createDirectories(root.resolve("mods"));
        for (DisableableMod mod : server.mods) {
            Files.write(root.resolve("mods").resolve(mod.file), new byte[] { 1 });
        }

        open(server);
        JLabel label = (JLabel) field(EditModsDialog.class, "updateCheckLabel").get(dialog);
        // Connector shown as off: a Connector install has no update, and the request for it is never made
        assertEquals("", settledUpdateLabel(label));

        App.settings.showFabricModsWhenSinytraInstalled = true;
        SwingUtilities.invokeAndWait(dialog::reloadPanels);

        assertEquals("Updates available: 1", settledUpdateLabel(label));
    }

    @Test
    public void escapeCancelsTheRemovalDialog() throws Exception {
        DisableableMod lib = modrinth("lib", "l0", "2024-01-01T00:00:00Z", "fabric");
        DisableableMod user = modrinth("user", "u0", "2024-01-01T00:00:00Z", "fabric");
        ModrinthDependency needsLib = new ModrinthDependency();
        needsLib.projectId = "lib";
        needsLib.dependencyType = ModrinthDependencyType.REQUIRED;
        user.modrinthVersion.dependencies.add(needsLib);
        InstalledDependencies deps = new InstalledDependencies(Arrays.asList(lib, user), null, false);
        List<DisableableMod> selected = Collections.singletonList(lib);

        AtomicReference<ModRemovalDialog> created = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                Constructor<ModRemovalDialog> constructor = ModRemovalDialog.class.getDeclaredConstructor(
                    Window.class, InstalledDependencies.class, List.class, List.class, List.class);
                constructor.setAccessible(true);
                created.set(constructor.newInstance(null, deps, selected, deps.brokenByAll(selected),
                    deps.withoutData(selected)));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        ModRemovalDialog removal = created.get();
        opened.add(removal);

        SwingUtilities.invokeAndWait(() -> {
            java.awt.event.ActionListener escape = removal.getRootPane()
                .getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
            assertNotNull(escape, "Escape does nothing");
            escape.actionPerformed(new ActionEvent(removal, ActionEvent.ACTION_PERFORMED, "escape"));
            assertFalse(removal.isDisplayable());
        });
        // what ask() returns: nothing, so nothing is removed
        assertNull(field(ModRemovalDialog.class, "result").get(removal));
    }

    // a NeoForge version needing the "lib" project
    private static String modrinthVersionJson(String id, String published) {
        return "{\"id\": \"" + id + "\", \"project_id\": \"xmod\", \"name\": \"" + id + "\", \"date_published\": \""
            + published + "\", \"loaders\": [\"neoforge\"], \"game_versions\": [\"1.20.1\"], "
            + "\"dependencies\": [{\"project_id\": \"lib\", \"dependency_type\": \"required\"}], "
            + "\"files\": [{\"filename\": \"" + id + ".jar\", \"primary\": true, \"hashes\": {}}]}";
    }

    // a NeoForge file needing CurseForge project 200
    private static String curseForgeFileJson(int id) {
        return "{\"id\": " + id + ", \"displayName\": \"cf-" + id + "\", \"fileName\": \"cf-" + id + ".jar\", "
            + "\"releaseType\": 1, \"gameVersions\": [\"1.20.1\", \"NeoForge\"], "
            + "\"dependencies\": [{\"modId\": 200, \"relationType\": 3}]}";
    }

    private long requested(String request) {
        synchronized (requests) {
            return requests.stream().filter(request::equals).count();
        }
    }

    // a selector loads in the background and enables its list when done
    private static void awaitLoaded(JComboBox<?> list) throws Exception {
        AtomicBoolean enabled = new AtomicBoolean();
        for (int i = 0; i < 100 && !enabled.get(); i++) {
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> enabled.set(list.isEnabled()));
        }
        assertTrue(enabled.get(), "the selector never finished loading");
    }

    // switching versions and pressing Add check the dependencies again on the UI thread, without asking Modrinth again
    @Test
    @SuppressWarnings("unchecked")
    public void modrinthSelectorLooksUpDependenciesOnceAndViewFileNeedsASelection() throws Exception {
        App.THEME = new ATLauncherLaf();
        Server server = server(new LoaderVersion("47.1.3", false, "NeoForge"));
        ModrinthProject project = new ModrinthProject();
        project.id = "xmod";
        project.slug = "xmod";
        project.title = "X Mod";
        project.loaders = Collections.singletonList("neoforge");
        answers.put("/v2/project/xmod/version", "[" + modrinthVersionJson("x2", "2024-06-01T00:00:00Z") + ", "
            + modrinthVersionJson("x1", "2024-01-01T00:00:00Z") + "]");
        answers.put("/v2/projects", "[{\"id\": \"lib\", \"slug\": \"lib\", \"title\": \"Lib\"}]");

        AtomicReference<ModrinthVersionSelectorDialog> created = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> created.set(new ModrinthVersionSelectorDialog(null, project, server)));
        ModrinthVersionSelectorDialog selector = created.get();
        opened.add(selector);
        JComboBox<ModrinthVersion> versions = (JComboBox<ModrinthVersion>) field(ModrinthVersionSelectorDialog.class,
            "versionsDropdown").get(selector);
        JButton viewFile = (JButton) field(ModrinthVersionSelectorDialog.class, "viewFileButton").get(selector);
        awaitLoaded(versions);

        SwingUtilities.invokeAndWait(() -> {
            assertEquals(2, versions.getItemCount());
            versions.setSelectedIndex(1);
            versions.setSelectedIndex(0);
            // as when a dependency's declared version isn't listed
            versions.setSelectedIndex(-1);
            viewFile.doClick();
        });

        assertEquals(1, requested("GET /v2/projects"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void curseForgeSelectorLooksUpDependenciesOnce() throws Exception {
        App.THEME = new ATLauncherLaf();
        Server server = server(new LoaderVersion("47.1.3", false, "NeoForge"));
        CurseForgeProject project = new CurseForgeProject();
        project.id = 100;
        project.name = "CF Mod";
        project.classId = Constants.CURSEFORGE_MODS_SECTION_ID;
        project.categories = new ArrayList<>();
        answers.put("/v1/mods/100/files", "{\"data\": [" + curseForgeFileJson(1002) + ", " + curseForgeFileJson(1001)
            + "]}");
        answers.put("/v1/mods", "{\"data\": [{\"id\": 200, \"name\": \"Lib\", \"classId\": 6, \"categories\": []}]}");
        // a file that doesn't fit, so no file is picked; an empty list would touch the launcher's HTTP cache folder
        answers.put("/v1/mods/200/files", "{\"data\": [{\"id\": 2001, \"displayName\": \"lib-old\", "
            + "\"fileName\": \"lib.jar\", \"gameVersions\": [\"1.12.2\", \"Forge\"]}]}");

        AtomicReference<CurseForgeProjectFileSelectorDialog> created = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> created.set(new CurseForgeProjectFileSelectorDialog(null, project,
            server)));
        CurseForgeProjectFileSelectorDialog selector = created.get();
        opened.add(selector);
        JComboBox<CurseForgeFile> files = (JComboBox<CurseForgeFile>) field(CurseForgeProjectFileSelectorDialog.class,
            "filesDropdown").get(selector);
        awaitLoaded(files);

        SwingUtilities.invokeAndWait(() -> {
            assertEquals(2, files.getItemCount());
            files.setSelectedIndex(1);
            files.setSelectedIndex(0);
        });

        assertEquals(1, requested("POST /v1/mods"));
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
