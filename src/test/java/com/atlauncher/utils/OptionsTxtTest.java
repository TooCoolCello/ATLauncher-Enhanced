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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.atlauncher.data.DefaultGameOptions;

public class OptionsTxtTest {
    private static final String MC_1_20_1 = "2023-06-12T13:25:51+00:00";
    private static final String MC_1_10_2 = "2016-06-23T09:17:32+00:00";
    private static final String MC_1_7_10 = "2014-05-14T17:29:23+00:00";
    private static final String MC_1_6_4 = "2013-09-19T15:52:37+00:00";

    @TempDir
    Path root;

    private static DefaultGameOptions all() {
        DefaultGameOptions options = new DefaultGameOptions();
        options.fov = 90;
        options.mouseSensitivity = 100;
        options.renderDistance = 24;
        options.guiScale = 3;
        options.autoJump = false;
        options.masterVolume = 50;
        return options;
    }

    @Test
    void encodesInTheUnitsOptionsTxtUses() {
        Map<String, String> values = OptionsTxt.encode(all(), MC_1_20_1);

        assertEquals("0.5", values.get("fov"));
        assertEquals("0.5", values.get("mouseSensitivity"));
        assertEquals("24", values.get("renderDistance"));
        assertEquals("3", values.get("guiScale"));
        assertEquals("false", values.get("autoJump"));
        assertEquals("0.5", values.get("soundCategory_master"));
        assertEquals(6, values.size());
    }

    @Test
    void encodesTheEndsOfEachRange() {
        DefaultGameOptions options = new DefaultGameOptions();
        options.fov = 30;
        options.mouseSensitivity = 0;
        options.guiScale = 0;
        options.masterVolume = 100;
        Map<String, String> low = OptionsTxt.encode(options, MC_1_20_1);
        assertEquals("-1.0", low.get("fov"));
        assertEquals("0.0", low.get("mouseSensitivity"));
        assertEquals("0", low.get("guiScale"));
        assertEquals("1.0", low.get("soundCategory_master"));

        options.fov = 110;
        options.mouseSensitivity = 200;
        Map<String, String> high = OptionsTxt.encode(options, MC_1_20_1);
        assertEquals("1.0", high.get("fov"));
        assertEquals("1.0", high.get("mouseSensitivity"));

        options.fov = 77;
        assertEquals("0.175", OptionsTxt.encode(options, MC_1_20_1).get("fov"));
    }

    @Test
    void leavesOutOptionsThatAreNotSet() {
        DefaultGameOptions options = new DefaultGameOptions();
        options.fov = 70;

        assertEquals(java.util.Collections.singletonMap("fov", "0.0"), OptionsTxt.encode(options, MC_1_20_1));
        assertTrue(OptionsTxt.encode(new DefaultGameOptions(), MC_1_20_1).isEmpty());
    }

    @Test
    void skipsAutoJumpBefore111() {
        Map<String, String> values = OptionsTxt.encode(all(), MC_1_10_2);

        assertFalse(values.containsKey("autoJump"));
        assertEquals("24", values.get("renderDistance"));
    }

    @Test
    void limitsFovAndRenderDistanceBefore18() {
        DefaultGameOptions options = all();
        options.fov = 50;

        Map<String, String> values = OptionsTxt.encode(options, MC_1_7_10);

        assertEquals("0.0", values.get("fov"));
        assertEquals("16", values.get("renderDistance"));
        assertEquals("0.5", values.get("soundCategory_master"));
    }

    @Test
    void skipsKeysThatHadOtherFormatsBefore17() {
        Map<String, String> values = OptionsTxt.encode(all(), MC_1_6_4);

        assertEquals("0.5", values.get("fov"));
        assertEquals("0.5", values.get("mouseSensitivity"));
        assertEquals("3", values.get("guiScale"));
        assertFalse(values.containsKey("renderDistance"));
        assertFalse(values.containsKey("soundCategory_master"));
        assertFalse(values.containsKey("autoJump"));
    }

    @Test
    void anUnknownReleaseTimeIsTreatedAsCurrent() {
        assertEquals(6, OptionsTxt.encode(all(), null).size());
        assertEquals(6, OptionsTxt.encode(all(), "not a date").size());
    }

    @Test
    void mergeReplacesValuesInPlaceAndKeepsEverythingElse() {
        String text = "version:3465\nfov:0.0\nlang:en_us\nkey_key.jump:key.keyboard.space\nrenderDistance:12\n";
        Map<String, String> values = new LinkedHashMap<>();
        values.put("fov", "0.5");
        values.put("renderDistance", "8");

        assertEquals("version:3465\nfov:0.5\nlang:en_us\nkey_key.jump:key.keyboard.space\nrenderDistance:8\n",
            OptionsTxt.merge(text, values));
    }

    @Test
    void mergeAppendsMissingKeysAndKeepsCrlf() {
        String text = "version:1343\r\nfov:0.0\r\n";
        Map<String, String> values = new LinkedHashMap<>();
        values.put("fov", "0.25");
        values.put("autoJump", "false");

        assertEquals("version:1343\r\nfov:0.25\r\nautoJump:false\r\n", OptionsTxt.merge(text, values));
    }

    @Test
    void mergeOnlyMatchesWholeKeys() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("fov", "0.5");

        // fovEffectScale and resourcePacks' values with colons must not be touched
        String text = "fovEffectScale:1.0\nresourcePacks:[\"vanilla\",\"file/a:b.zip\"]\nfov:0.0\nfov:0.1";
        assertEquals("fovEffectScale:1.0\nresourcePacks:[\"vanilla\",\"file/a:b.zip\"]\nfov:0.5\nfov:0.5\n",
            OptionsTxt.merge(text, values));
    }

    @Test
    void mergeIntoNothingWritesOnlyTheValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("guiScale", "2");

        assertEquals("guiScale:2\n", OptionsTxt.merge("", values));
    }

    @Test
    void applyKeepsThePacksOptionsAndTheirBytes() throws IOException {
        // "é" in a legacy charset byte that isn't valid UTF-8, plus a UTF-8 multi-byte name
        byte[] before = "lastServer:café\nfov:0.0\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] utf8 = "skin:中\n".getBytes(StandardCharsets.UTF_8);
        byte[] original = new byte[before.length + utf8.length];
        System.arraycopy(before, 0, original, 0, before.length);
        System.arraycopy(utf8, 0, original, before.length, utf8.length);
        Files.write(root.resolve("options.txt"), original);

        DefaultGameOptions options = new DefaultGameOptions();
        options.fov = 90;
        OptionsTxt.applyDefaults(root, options, MC_1_20_1);

        byte[] after = Files.readAllBytes(root.resolve("options.txt"));
        byte[] expectedStart = "lastServer:café\nfov:0.5\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] expected = new byte[expectedStart.length + utf8.length];
        System.arraycopy(expectedStart, 0, expected, 0, expectedStart.length);
        System.arraycopy(utf8, 0, expected, expectedStart.length, utf8.length);
        assertArrayEquals(expected, after);
        assertFalse(Files.exists(root.resolve("options.txt.atlauncher-tmp")));
    }

    @Test
    void applyCreatesOptionsTxtWhenThereIsNone() throws IOException {
        DefaultGameOptions options = new DefaultGameOptions();
        options.autoJump = false;

        OptionsTxt.applyDefaults(root, options, MC_1_20_1);

        assertEquals("autoJump:false\n", new String(Files.readAllBytes(root.resolve("options.txt")),
            StandardCharsets.UTF_8));
    }

    @Test
    void applyWritesNothingWhenNoOptionIsSet() throws IOException {
        assertTrue(OptionsTxt.applyDefaults(root, new DefaultGameOptions(), MC_1_20_1).isEmpty());
        assertFalse(Files.exists(root.resolve("options.txt")));
    }

    @Test
    void applyGoesIntoThePacksYosbrFileUntilOptionsTxtExists() throws IOException {
        Path yosbr = root.resolve("config/yosbr/options.txt");
        Files.createDirectories(yosbr.getParent());
        Files.write(yosbr, "key_key.jump:key.keyboard.j\n".getBytes(StandardCharsets.UTF_8));

        DefaultGameOptions options = new DefaultGameOptions();
        options.guiScale = 2;
        OptionsTxt.applyDefaults(root, options, MC_1_20_1);

        assertFalse(Files.exists(root.resolve("options.txt")));
        assertEquals("key_key.jump:key.keyboard.j\nguiScale:2\n",
            new String(Files.readAllBytes(yosbr), StandardCharsets.UTF_8));

        // once the game has made options.txt, that's the one to change
        Files.write(root.resolve("options.txt"), "guiScale:4\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(root.resolve("options.txt"), OptionsTxt.target(root));
    }

    @Test
    void defaultOptionsModFileIsUsedToo() throws IOException {
        Path defaultOptions = root.resolve("config/defaultoptions/options.txt");
        Files.createDirectories(defaultOptions.getParent());
        Files.write(defaultOptions, new byte[0]);

        assertEquals(defaultOptions, OptionsTxt.target(root));
    }
}
