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
package com.atlauncher.gui.dialogs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.atlauncher.data.Instance;
import com.atlauncher.data.InstanceLauncher;
import com.atlauncher.data.RemoteServer;
import com.atlauncher.data.minecraft.MinecraftVersion;

/**
 * Builds the remote server dialogs from fixture data, without showing them or touching the network.
 */
class RemoteServerDialogsSmokeTest {
    @TempDir
    Path temp;

    static RemoteServer server(String name) {
        RemoteServer server = new RemoteServer();
        server.name = name;
        server.host = name.toLowerCase() + ".example.com";
        server.port = 2022;
        server.username = "name.1a2b3c4d";
        return server;
    }

    static Window construct(String className, Class<?>[] types, Object... args) throws Exception {
        Constructor<?> constructor = Class.forName(className).getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return (Window) constructor.newInstance(args);
    }

    static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    @Test
    void buildsEachDialogAndSelectsTheRightServer() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A display is required to build dialogs");
        RemoteServer first = server("First");
        RemoteServer second = server("Second");
        List<RemoteServer> servers = Arrays.asList(first, second);
        Files.createDirectories(temp.resolve("mods"));
        Files.createDirectories(temp.resolve("config"));
        Files.write(temp.resolve("options.txt"), new byte[0]);
        Instance instance = new Instance(new MinecraftVersion());
        instance.launcher = new InstanceLauncher();
        instance.launcher.name = "Pack";
        instance.launcher.remoteServerId = second.id;
        instance.ROOT = temp;
        List<Window> built = new ArrayList<>();
        Exception[] failure = new Exception[1];

        SwingUtilities.invokeAndWait(() -> {
            try {
                built.add(construct(RemoteServersDialog.class.getName(), new Class<?>[] { Window.class },
                    (Object) null));
                built.add(construct(RemoteServersDialog.class.getName() + "$ServerForm",
                    new Class<?>[] { Window.class, RemoteServer.class }, null, first));
                built.add(construct(RemoteServerUploadDialog.class.getName(),
                    new Class<?>[] { Window.class, Instance.class, List.class }, null, instance, servers));
                built.add(construct(ServerSyncDialog.class.getName(),
                    new Class<?>[] { Window.class, Instance.class, RemoteServer.class }, null, instance, second));
                // Remote Servers > Make Instance... passes the server from its own copy of the list
                built.add(construct(ServerImportDialog.class.getName(),
                    new Class<?>[] { Window.class, List.class, RemoteServer.class }, null, servers, second.copy()));
            } catch (Exception e) {
                failure[0] = e;
            }
        });

        try {
            if (failure[0] != null) {
                throw failure[0];
            }
            RemoteServer uploadTo = (RemoteServer) ((JComboBox<?>) field(built.get(2), "server")).getSelectedItem();
            assertEquals(second.id, uploadTo.id);
            RemoteServer importFrom = (RemoteServer) ((JComboBox<?>) field(built.get(4), "server")).getSelectedItem();
            assertEquals(second.id, importFrom.id);
        } finally {
            SwingUtilities.invokeAndWait(() -> built.forEach(Window::dispose));
        }
    }
}
