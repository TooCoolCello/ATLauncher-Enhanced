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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FileUtilsWriteJsonTest {
    @TempDir
    Path dir;

    @Test
    public void replacesTheFileAndLeavesNoTemporaryFile() throws IOException {
        Path file = dir.resolve("instance.json");
        Files.write(file, "{\"old\":true}".getBytes(StandardCharsets.UTF_8));

        FileUtils.writeJsonAtomically(file, Collections.singletonMap("name", "Test"));

        assertEquals("{\"name\":\"Test\"}", new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replaceAll("\\s", ""));
        assertFalse(Files.exists(dir.resolve("instance.json.tmp")));
    }

    @Test
    public void aWriteThatFailsLeavesTheOldFileWhole() throws IOException {
        Path file = dir.resolve("instance.json");
        Files.write(file, "{\"old\":true}".getBytes(StandardCharsets.UTF_8));

        // Gson refuses NaN, after it has already written the start of the object
        Map<String, Object> unwritable = new LinkedHashMap<>();
        unwritable.put("name", "Test");
        unwritable.put("broken", Double.NaN);
        assertThrows(RuntimeException.class, () -> FileUtils.writeJsonAtomically(file, unwritable));

        assertEquals("{\"old\":true}", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertFalse(Files.exists(dir.resolve("instance.json.tmp")));
    }
}
