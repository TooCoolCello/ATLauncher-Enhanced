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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;

import com.atlauncher.data.DefaultGameOptions;
import com.atlauncher.data.minecraft.VersionManifestVersion;

/**
 * Reads and merges Minecraft's options.txt ("key:value" per line), and encodes {@link DefaultGameOptions} into it.
 * Only the chosen keys change; every other line is kept.
 */
public final class OptionsTxt {
    // read and written as Latin-1 so every byte round-trips, whatever charset the game wrote; our keys are ASCII
    private static final Charset CHARSET = StandardCharsets.ISO_8859_1;

    // FOV below 70 and render distance above 16 arrived in 1.8; auto-jump in 1.11
    static final DateTime RELEASE_1_8 = ISODateTimeFormat.dateTimeParser().parseDateTime("2014-09-02T08:24:35+00:00");
    static final DateTime RELEASE_1_11 = ISODateTimeFormat.dateTimeParser()
        .parseDateTime("2016-11-14T14:34:40+00:00");

    // where yosbr and Default Options keep a pack's options; they copy theirs into place only while options.txt is
    // missing, so creating options.txt first would stop the pack's own defaults (key binds and all) being applied
    private static final String[] PACK_DEFAULT_OPTIONS = { "config/yosbr/options.txt",
        "config/defaultoptions/options.txt" };

    private OptionsTxt() {
    }

    /**
     * The options.txt keys and values for these options on a Minecraft version released at releaseTime (ISO 8601, as
     * in the version manifest; null or unparseable means a current version). Options the version doesn't have are
     * left out.
     */
    public static Map<String, String> encode(DefaultGameOptions options, String releaseTime) {
        DateTime released = parse(releaseTime);
        boolean atLeast17 = released == null || !released.isBefore(VersionManifestVersion.release_1_7);
        boolean atLeast18 = released == null || !released.isBefore(RELEASE_1_8);
        boolean atLeast111 = released == null || !released.isBefore(RELEASE_1_11);

        Map<String, String> values = new LinkedHashMap<>();

        if (options.fov != null) {
            // stored as (fov - 70) / 40; before 1.8 the slider only went from 70 to 110
            int fov = atLeast18 ? options.fov : Math.max(70, options.fov);
            values.put("fov", Double.toString((fov - 70) / 40.0));
        }

        if (options.mouseSensitivity != null) {
            // 0.5 is 100%
            values.put("mouseSensitivity", Double.toString(options.mouseSensitivity / 200.0));
        }

        // before 1.7 render distance and volume were other keys with other scales (viewDistance, sound)
        if (options.renderDistance != null && atLeast17) {
            values.put("renderDistance", Integer.toString(atLeast18 ? options.renderDistance
                : Math.min(16, options.renderDistance)));
        }

        if (options.guiScale != null) {
            values.put("guiScale", Integer.toString(options.guiScale));
        }

        if (options.autoJump != null && atLeast111) {
            values.put("autoJump", Boolean.toString(options.autoJump));
        }

        if (options.masterVolume != null && atLeast17) {
            values.put("soundCategory_master", Double.toString(options.masterVolume / 100.0));
        }

        return values;
    }

    /**
     * The options.txt text with each key's value replaced (every line with that key) or, when missing, appended at
     * the end. Other lines stay as they were.
     */
    public static String merge(String text, Map<String, String> values) {
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = text.isEmpty() ? new ArrayList<>()
            : new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1); // the final newline; it's put back below
        }

        Set<String> replaced = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            int colon = lines.get(i).indexOf(':');
            String key = colon > 0 ? lines.get(i).substring(0, colon) : null;

            if (key != null && values.containsKey(key)) {
                lines.set(i, key + ":" + values.get(key));
                replaced.add(key);
            }
        }

        values.forEach((key, value) -> {
            if (!replaced.contains(key)) {
                lines.add(key + ":" + value);
            }
        });

        return lines.isEmpty() ? "" : String.join(newline, lines) + newline;
    }

    /**
     * Writes these options into the instance at instanceRoot: into options.txt, or, when there's none yet, into the
     * pack's yosbr/Default Options file so those mods still apply the pack's defaults. Returns the keys written,
     * empty (and nothing touched) when no option is set or none applies to this version.
     */
    public static Map<String, String> applyDefaults(Path instanceRoot, DefaultGameOptions options, String releaseTime)
        throws IOException {
        Map<String, String> values = encode(options, releaseTime);
        if (values.isEmpty()) {
            return values;
        }

        Path target = target(instanceRoot);
        String text = Files.exists(target) ? new String(Files.readAllBytes(target), CHARSET) : "";

        // written beside it then moved over, so a failed write can't leave a half-written options.txt
        Path temp = target.resolveSibling(target.getFileName() + ".atlauncher-tmp");
        Files.write(temp, merge(text, values).getBytes(CHARSET));
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);

        return values;
    }

    static Path target(Path instanceRoot) {
        Path options = instanceRoot.resolve("options.txt");
        if (Files.exists(options)) {
            return options;
        }

        for (String path : PACK_DEFAULT_OPTIONS) {
            if (Files.isRegularFile(instanceRoot.resolve(path))) {
                return instanceRoot.resolve(path);
            }
        }

        return options;
    }

    private static DateTime parse(String releaseTime) {
        if (releaseTime == null) {
            return null;
        }

        try {
            return ISODateTimeFormat.dateTimeParser().parseDateTime(releaseTime);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
