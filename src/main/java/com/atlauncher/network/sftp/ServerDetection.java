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
package com.atlauncher.network.sftp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.data.minecraft.loaders.LoaderType;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;

/**
 * Works out which Minecraft version and mod loader a server runs from the files the loaders' installers lay down, and
 * compares that with an instance. It only reports what the files show; anything else is left unknown with the reason.
 *
 * <p>
 * What it reads, most direct first:
 * <ul>
 * <li>Forge and NeoForge (1.17 and newer) start through an args file, such as
 * libraries/net/neoforged/neoforge/21.1.77/unix_args.txt, which run.sh/run.bat name. Pterodactyl's eggs link it into
 * the server folder as unix_args.txt instead. It holds --fml.mcVersion and --fml.forgeVersion or
 * --fml.neoForgeVersion.</li>
 * <li>Without those, a ServerPackCreator server pack's variables.txt (MINECRAFT_VERSION, MODLOADER,
 * MODLOADER_VERSION).</li>
 * <li>Library folders: net/neoforged/neoforge/&lt;v&gt;, net/neoforged/forge/&lt;mc&gt;-&lt;v&gt; (NeoForge for
 * 1.20.1), net/minecraftforge/forge/&lt;mc&gt;-&lt;v&gt;, net/fabricmc/fabric-loader/&lt;v&gt;,
 * org/quiltmc/quilt-loader/&lt;v&gt;, and for the Minecraft version net/fabricmc/intermediary/&lt;mc&gt;,
 * net/minecraft/server/&lt;mc&gt;[-&lt;mcp&gt;] and versions/&lt;mc&gt; (the vanilla 1.18+ server).</li>
 * <li>Jar names in the server folder: forge-&lt;mc&gt;-&lt;v&gt;.jar (older Forge), the Forge and NeoForge
 * installers (server packs that haven't been installed yet), fabric-server-mc.&lt;mc&gt;-loader.&lt;v&gt;-launcher.&lt;x&gt;.jar and minecraft_server.&lt;mc&gt;.jar.</li>
 * </ul>
 * Upgrading a loader often leaves the old version's libraries behind, so when the folders disagree and no start
 * script settles it, the answer is unknown rather than a guess.
 */
public final class ServerDetection {
    // text files read from the server are small; anything bigger isn't what we're looking for
    private static final long MAX_TEXT = 1024 * 1024;

    private static final Pattern ARGS_FILE = Pattern.compile("(libraries/\\S+?/(?:unix|win)_args\\.txt)");
    private static final Pattern MCP_SUFFIX = Pattern.compile("^(.+)-\\d{8}\\.\\d{6}$");
    private static final Pattern FORGE_JAR = Pattern
        .compile("^forge-([^-]+)-(.+?)(?:-(?:universal|server|shim|installer))?\\.jar$");
    private static final Pattern NEOFORGE_INSTALLER_JAR = Pattern.compile("^neoforge-(.+?)-installer\\.jar$");
    private static final Pattern FABRIC_LAUNCHER_JAR = Pattern
        .compile("^fabric-server-mc\\.(.+?)-loader\\.(.+?)-launcher\\..+\\.jar$");
    private static final Pattern NEOFORGE_VERSION = Pattern.compile("^(2[0-5])\\.(\\d+)\\.");
    private static final Pattern VANILLA_JAR = Pattern.compile("^minecraft_server\\.(.+)\\.jar$");

    /**
     * What a server runs. A null field is unknown, and its problem field says why.
     */
    public static final class Info {
        public String minecraftVersion;
        public LoaderType loader;
        public String loaderVersion;
        public String minecraftProblem;
        public String loaderProblem;
        public String loaderVersionProblem;

        /**
         * Such as "Minecraft 1.20.1 with Forge 47.2.0", with "unknown" for what isn't known.
         */
        public String describe() {
            String unknown = GetText.tr("unknown");
            String loaderText = loader == null ? GetText.tr("an unknown loader")
                : loader + " " + (loaderVersion == null ? unknown : loaderVersion);
            // #. {0} is the Minecraft version, {1} the loader and its version, such as Forge 47.2.0
            return GetText.tr("Minecraft {0} with {1}", minecraftVersion == null ? unknown : minecraftVersion,
                loaderText);
        }
    }

    /**
     * How a server compares with an instance.
     */
    public static final class Verdict {
        // a different Minecraft version or loader: syncing must stop
        public final List<String> mismatches = new ArrayList<>();
        // what couldn't be worked out: syncing needs an explicit go-ahead
        public final List<String> unknowns = new ArrayList<>();
        // shown in the preview, such as a different loader version
        public final List<String> warnings = new ArrayList<>();
    }

    private ServerDetection() {
    }

    public static Info detect(SftpClient client, String base) throws IOException {
        Info info = new Info();
        Set<String> root = names(client, base);

        Map<String, String> args = null;
        for (String script : Arrays.asList("run.sh", "run.bat")) {
            String text = root.contains(script) ? readText(client, SftpClient.join(base, script)) : null;
            if (text == null) {
                continue;
            }
            Matcher matcher = ARGS_FILE.matcher(text.replace('\\', '/'));
            while (args == null && matcher.find()) {
                String argsText = readText(client, SftpClient.join(base, matcher.group(1)));
                if (argsText != null) {
                    args = parseArgs(argsText);
                }
            }
        }
        for (String file : Arrays.asList("unix_args.txt", "win_args.txt")) {
            if (args == null && root.contains(file)) {
                String argsText = readText(client, SftpClient.join(base, file));
                if (argsText != null) {
                    args = parseArgs(argsText);
                }
            }
        }

        // loader -> version -> the Minecraft version it's for, when its folder or jar name says
        Map<LoaderType, Map<String, String>> found = new EnumMap<>(LoaderType.class);
        for (String version : folders(client, base, "libraries/net/neoforged/neoforge")) {
            add(found, LoaderType.NEOFORGE, version, neoForgeMinecraft(version));
        }
        for (String folder : folders(client, base, "libraries/net/neoforged/forge")) {
            String[] split = splitForge(folder);
            add(found, LoaderType.NEOFORGE, split[1], split[0]);
        }
        for (String folder : folders(client, base, "libraries/net/minecraftforge/forge")) {
            String[] split = splitForge(folder);
            add(found, LoaderType.FORGE, split[1], split[0]);
        }
        for (String version : folders(client, base, "libraries/net/fabricmc/fabric-loader")) {
            add(found, LoaderType.FABRIC, version, null);
        }
        for (String version : folders(client, base, "libraries/org/quiltmc/quilt-loader")) {
            add(found, LoaderType.QUILT, version, null);
        }

        Set<String> minecraft = new TreeSet<>();
        for (String name : root) {
            Matcher forge = FORGE_JAR.matcher(name);
            Matcher fabric = FABRIC_LAUNCHER_JAR.matcher(name);
            Matcher neoForge = NEOFORGE_INSTALLER_JAR.matcher(name);
            Matcher vanilla = VANILLA_JAR.matcher(name);
            if (forge.matches()) {
                add(found, LoaderType.FORGE, forge.group(2), forge.group(1));
            } else if (neoForge.matches()) {
                add(found, LoaderType.NEOFORGE, neoForge.group(1), neoForgeMinecraft(neoForge.group(1)));
            } else if (fabric.matches()) {
                add(found, LoaderType.FABRIC, fabric.group(2), fabric.group(1));
            } else if (vanilla.matches()) {
                minecraft.add(vanilla.group(1));
            }
        }
        for (String folder : Arrays.asList("libraries/net/fabricmc/intermediary", "libraries/org/quiltmc/hashed",
            "libraries/net/legacyfabric/intermediary", "versions")) {
            minecraft.addAll(folders(client, base, folder));
        }
        for (String folder : folders(client, base, "libraries/net/minecraft/server")) {
            Matcher mcp = MCP_SUFFIX.matcher(folder);
            minecraft.add(mcp.matches() ? mcp.group(1) : folder);
        }

        // what the server is set up to start, which settles any leftovers from upgrades
        String pinnedMinecraft = null;
        if (args != null) {
            if (args.containsKey("--fml.neoForgeVersion")) {
                info.loader = LoaderType.NEOFORGE;
                info.loaderVersion = args.get("--fml.neoForgeVersion");
            } else if (args.containsKey("--fml.forgeVersion")) {
                info.loader = "net.neoforged".equals(args.get("--fml.forgeGroup")) ? LoaderType.NEOFORGE
                    : LoaderType.FORGE;
                info.loaderVersion = args.get("--fml.forgeVersion");
            }
            pinnedMinecraft = args.get("--fml.mcVersion");
        } else if (root.contains("variables.txt")) {
            // server packs made with ServerPackCreator say what their scripts install
            Map<String, String> variables = parseVariables(readText(client, SftpClient.join(base, "variables.txt")));
            info.loader = loaderNamed(variables.get("MODLOADER"));
            info.loaderVersion = info.loader == null ? null : variables.get("MODLOADER_VERSION");
            pinnedMinecraft = variables.get("MINECRAFT_VERSION");
        }

        // the loader
        if (info.loader != null) {
            // already known
        } else if (found.size() == 1) {
            info.loader = found.keySet().iterator().next();
        } else if (found.isEmpty()) {
            info.loaderProblem = GetText.tr("No Forge, NeoForge, Fabric or Quilt files were found in its "
                + "libraries folder or start scripts. If the server hasn't been started yet, start it once.");
        } else {
            info.loaderProblem = GetText.tr("Its libraries folder has files for more than one loader ({0}), and "
                + "no start script says which one runs.", found.keySet().stream().map(LoaderType::toString)
                    .collect(Collectors.joining(", ")));
        }

        // its version
        Map<String, String> versions = info.loader == null ? Collections.emptyMap()
            : found.getOrDefault(info.loader, Collections.emptyMap());
        if (info.loader != null && info.loaderVersion == null) {
            if (versions.size() == 1) {
                info.loaderVersion = versions.keySet().iterator().next();
            } else if (versions.isEmpty()) {
                info.loaderVersionProblem = GetText.tr("No {0} version was found in its files.", info.loader);
            } else {
                info.loaderVersionProblem = GetText.tr("Its libraries folder has several {0} versions ({1}), and "
                    + "no start script says which one runs.", info.loader, String.join(", ", versions.keySet()));
            }
        }

        // the Minecraft version; with the loader version unknown, every version of it found is a candidate
        if (info.loaderVersion == null) {
            versions.values().stream().filter(Objects::nonNull).forEach(minecraft::add);
        }
        if (pinnedMinecraft != null) {
            info.minecraftVersion = pinnedMinecraft;
        } else if (info.loaderVersion != null && versions.get(info.loaderVersion) != null) {
            info.minecraftVersion = versions.get(info.loaderVersion);
        } else if (minecraft.size() == 1) {
            info.minecraftVersion = minecraft.iterator().next();
        } else if (minecraft.isEmpty()) {
            info.minecraftProblem = GetText.tr("No Minecraft version was found in its libraries or versions "
                + "folders. If the server hasn't been started yet, start it once.");
        } else {
            info.minecraftProblem = GetText.tr("Its files mention several Minecraft versions ({0}), and no start "
                + "script says which one runs.", String.join(", ", minecraft));
        }
        return info;
    }

    /**
     * Compares a server with an instance's Minecraft version and loader (null for none).
     */
    public static Verdict compare(Info server, String minecraftVersion, LoaderVersion loader) {
        Verdict verdict = new Verdict();
        if (server.minecraftVersion == null) {
            verdict.unknowns.add(GetText.tr("Minecraft version: {0}", server.minecraftProblem));
        } else if (!server.minecraftVersion.equalsIgnoreCase(minecraftVersion)) {
            verdict.mismatches.add(GetText.tr("The server runs Minecraft {0}, but this instance is Minecraft {1}. "
                + "Mods and configs for one version break the other. Change the server's Minecraft version in your "
                + "server panel first, or sync an instance made for Minecraft {0}.", server.minecraftVersion,
                minecraftVersion));
        }

        LoaderType instanceLoader = loader == null ? null : family(loader.getLoaderType());
        if (server.loader == null) {
            verdict.unknowns.add(GetText.tr("Mod loader: {0}", server.loaderProblem));
        } else if (instanceLoader == null) {
            verdict.mismatches.add(GetText.tr("The server runs {0}, but this instance has no mod loader.",
                server.loader));
        } else if (family(server.loader) != instanceLoader) {
            verdict.mismatches.add(GetText.tr("The server runs {0}, but this instance uses {1}. Mods for one loader "
                + "don't load on the other. Change the server's loader in your server panel first.", server.loader,
                loader.getLoaderType()));
        } else if (server.loaderVersion == null) {
            verdict.warnings.add(GetText.tr("The server's {0} version couldn't be worked out: {1}", server.loader,
                server.loaderVersionProblem));
        } else if (!sameVersion(server.loaderVersion, loader, server.minecraftVersion)) {
            verdict.warnings.add(GetText.tr("The server has {0} {1}, but this instance has {0} {2}. The launcher "
                + "can't change the server's loader: set {0} {2} in your server panel (on Pterodactyl, the version "
                + "under Startup, then Reinstall Server).", server.loader, server.loaderVersion, loader.version));
        }
        return verdict;
    }

    // Legacy Fabric servers run the same loader files as Fabric
    private static LoaderType family(LoaderType type) {
        return type == LoaderType.LEGACY_FABRIC ? LoaderType.FABRIC : type;
    }

    // Forge versions are written with and without the Minecraft version in front
    private static boolean sameVersion(String server, LoaderVersion loader, String minecraft) {
        String version = strip(server, minecraft);
        return version.equalsIgnoreCase(strip(loader.version, minecraft))
            || (loader.rawVersion != null && version.equalsIgnoreCase(strip(loader.rawVersion, minecraft)));
    }

    private static String strip(String version, String minecraft) {
        if (version == null) {
            return "";
        }
        if (minecraft != null && version.startsWith(minecraft + "-")) {
            version = version.substring(minecraft.length() + 1);
        }
        if (minecraft != null && version.endsWith("-" + minecraft)) {
            version = version.substring(0, version.length() - minecraft.length() - 1);
        }
        return version;
    }

    private static void add(Map<LoaderType, Map<String, String>> found, LoaderType type, String version,
            String minecraft) {
        Map<String, String> versions = found.computeIfAbsent(type, t -> new LinkedHashMap<>());
        if (minecraft != null || !versions.containsKey(version)) {
            versions.put(version, minecraft);
        }
    }

    /**
     * A Forge library folder such as 1.20.1-47.2.0, or 1.7.10-10.13.4.1614-1.7.10, as {minecraft, version}.
     */
    static String[] splitForge(String folder) {
        int dash = folder.indexOf('-');
        if (dash < 0) {
            return new String[] { null, folder };
        }
        String minecraft = folder.substring(0, dash);
        String version = folder.substring(dash + 1);
        if (version.endsWith("-" + minecraft)) {
            version = version.substring(0, version.length() - minecraft.length() - 1);
        }
        return new String[] { minecraft, version };
    }

    /**
     * NeoForge numbers its versions after Minecraft's: 21.1.x is for 1.21.1 and 21.0.x for 1.21. Null for versions
     * outside 1.20.2 to 1.25, where that scheme isn't known to hold.
     */
    static String neoForgeMinecraft(String version) {
        Matcher matcher = NEOFORGE_VERSION.matcher(version);
        if (!matcher.find()) {
            return null;
        }
        return "1." + matcher.group(1) + ("0".equals(matcher.group(2)) ? "" : "." + matcher.group(2));
    }

    // the values after --fml.* options, from an args file
    static Map<String, String> parseArgs(String text) {
        Map<String, String> args = new HashMap<>();
        String[] tokens = text.trim().split("\\s+");
        for (int i = 0; i + 1 < tokens.length; i++) {
            if (tokens[i].startsWith("--fml.")) {
                args.put(tokens[i], tokens[i + 1]);
            }
        }
        return args;
    }

    // NAME=value lines, as in a ServerPackCreator variables.txt
    static Map<String, String> parseVariables(String text) {
        Map<String, String> variables = new HashMap<>();
        for (String line : text == null ? new String[0] : text.split("\\R")) {
            int equals = line.indexOf('=');
            if (equals > 0 && !line.trim().startsWith("#")) {
                variables.put(line.substring(0, equals).trim(),
                    line.substring(equals + 1).trim().replaceAll("^[\"']|[\"']$", ""));
            }
        }
        return variables;
    }

    // a loader by name, such as NeoForge or LegacyFabric
    private static LoaderType loaderNamed(String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.replaceAll("[\\s_-]", "");
        return Arrays.stream(LoaderType.values()).filter(t -> t.name().replace("_", "").equalsIgnoreCase(wanted))
            .findFirst().orElse(null);
    }

    private static Set<String> names(SftpClient client, String folder) throws IOException {
        Set<String> names = new HashSet<>();
        for (SftpClient.RemoteFile file : client.list(folder)) {
            names.add(file.name);
        }
        return names;
    }

    // the names of the folders in a folder, or none if it isn't there
    private static Set<String> folders(SftpClient client, String base, String folder) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try {
            for (SftpClient.RemoteFile file : client.list(SftpClient.join(base, folder))) {
                if (file.directory && !file.name.startsWith(".")) {
                    names.add(file.name);
                }
            }
        } catch (NoSuchFileException e) {
            // not that loader
        }
        return names;
    }

    /**
     * A small text file's contents, or null if it's missing, too big or can't be read.
     */
    static String readText(SftpClient client, String path) throws IOException {
        SftpClient.RemoteFile file = client.stat(path);
        if (file == null || file.directory || file.size > MAX_TEXT) {
            return null;
        }
        Path temp = Files.createTempFile("atlauncher-server", ".txt");
        try {
            client.download(path, temp, null);
            return new String(Files.readAllBytes(temp), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
