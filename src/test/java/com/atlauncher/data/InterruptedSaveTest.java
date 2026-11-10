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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.atlauncher.data.minecraft.MinecraftVersion;

/**
 * Stopping an install interrupts its thread, which then goes on to save: that save still has to write the whole
 * file, not leave it emptied. Only Java 8 empties it (Files.newOutputStream there is interruptible), so these only
 * fail when the tests run on Java 8.
 */
public class InterruptedSaveTest {
    private static void saveInterrupted(Runnable save) {
        Thread.currentThread().interrupt();
        try {
            save.run();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void anInstanceSavedByAnInterruptedThreadIsWrittenInFull(@TempDir Path root) throws IOException {
        Instance instance = new Instance(new MinecraftVersion());
        instance.launcher = new InstanceLauncher();
        instance.launcher.name = "Pack";
        instance.ROOT = root;

        saveInterrupted(instance::save);

        assertTrue(new String(Files.readAllBytes(root.resolve("instance.json")), "UTF-8").contains("\"Pack\""));
    }

    @Test
    public void aServerSavedByAnInterruptedThreadIsWrittenInFull(@TempDir Path root) throws IOException {
        Server server = new Server();
        server.name = "World";
        server.ROOT = root;

        saveInterrupted(server::save);

        assertTrue(new String(Files.readAllBytes(root.resolve("server.json")), "UTF-8").contains("\"World\""));
    }
}
