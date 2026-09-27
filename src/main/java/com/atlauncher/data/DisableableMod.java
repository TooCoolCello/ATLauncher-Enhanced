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
package com.atlauncher.data;

import java.awt.Color;
import java.awt.Window;
import java.io.File;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.joda.time.format.ISODateTimeFormat;
import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.data.curseforge.CurseForgeFile;
import com.atlauncher.data.curseforge.CurseForgeProject;
import com.atlauncher.data.minecraft.FabricMod;
import com.atlauncher.data.minecraft.MCMod;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.modrinth.ModrinthDownloadMetadata;
import com.atlauncher.data.modrinth.ModrinthProject;
import com.atlauncher.data.modrinth.ModrinthVersion;
import com.atlauncher.exceptions.InvalidMinecraftVersion;
import com.atlauncher.gui.dialogs.CurseForgeProjectFileSelectorDialog;
import com.atlauncher.gui.dialogs.ModrinthVersionSelectorDialog;
import com.atlauncher.gui.dialogs.ProgressDialog;
import com.atlauncher.managers.ConfigManager;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.managers.MinecraftManager;
import com.atlauncher.network.Analytics;
import com.atlauncher.network.analytics.AnalyticsEvent;
import com.atlauncher.utils.CurseForgeApi;
import com.atlauncher.utils.FileUtils;
import com.atlauncher.utils.ModrinthApi;
import com.atlauncher.utils.Pair;
import com.atlauncher.utils.Utils;
import com.google.gson.annotations.SerializedName;

public class DisableableMod implements Serializable {
    public String name;
    public String version;
    public boolean optional;
    public String file;
    public String path;
    public Type type;
    public Color colour;
    public String description;
    public boolean disabled;
    public boolean userAdded = false; // Default to not being user added
    public boolean wasSelected = true; // Default to it being selected on install
    public boolean skipped = false; // For browser download mods if they were skipped or not

    @SerializedName(value = "curseForgeProjectId", alternate = { "curseModId" })
    public Integer curseForgeProjectId;

    @SerializedName(value = "curseForgeFileId", alternate = { "curseFileId" })
    public Integer curseForgeFileId;

    @SerializedName(value = "curseForgeProject", alternate = { "curseMod" })
    public CurseForgeProject curseForgeProject;

    @SerializedName(value = "curseForgeFile", alternate = { "curseFile" })
    public CurseForgeFile curseForgeFile;

    @SerializedName(value = "modrinthProject", alternate = { "modrinthMod" })
    public ModrinthProject modrinthProject;
    public ModrinthVersion modrinthVersion;

    public DisableableMod(String name, String version, boolean optional, String file, String path, Type type,
            Color colour, String description, boolean disabled, boolean userAdded, boolean wasSelected, boolean skipped,
            Integer curseForgeModId, Integer curseForgeFileId, CurseForgeProject curseForgeProject,
            CurseForgeFile curseForgeFile, ModrinthProject modrinthProject, ModrinthVersion modrinthVersion) {
        this.name = name;
        this.version = version;
        this.optional = optional;
        this.file = file;
        this.path = path;
        this.type = type;
        this.colour = colour;
        this.description = description;
        this.disabled = disabled;
        this.userAdded = userAdded;
        this.wasSelected = wasSelected;
        this.skipped = skipped;
        this.curseForgeProjectId = curseForgeModId;
        this.curseForgeFileId = curseForgeFileId;
        this.curseForgeProject = curseForgeProject;
        this.curseForgeFile = curseForgeFile;
        this.modrinthProject = modrinthProject;
        this.modrinthVersion = modrinthVersion;
    }

    public DisableableMod(String name, String version, boolean optional, String file, String path, Type type,
            Color colour, String description, boolean disabled, boolean userAdded, boolean wasSelected, boolean skipped,
            Integer curseForgeModId, Integer curseForgeFileId, CurseForgeProject curseForgeProject,
            CurseForgeFile curseForgeFile) {
        this(name, version, optional, file, path, type, colour, description, disabled, userAdded, wasSelected, skipped,
                curseForgeModId, curseForgeFileId, curseForgeProject, curseForgeFile, null, null);
    }

    public DisableableMod(String name, String version, boolean optional, String file, Type type, Color colour,
            String description, boolean disabled, boolean userAdded, boolean wasSelected, boolean skipped,
            Integer curseForgeModId,
            Integer curseForgeFileId, CurseForgeProject curseForgeProject, CurseForgeFile curseForgeFile) {
        this(name, version, optional, file, null, type, colour, description, disabled, userAdded, wasSelected, skipped,
                curseForgeModId, curseForgeFileId, curseForgeProject, curseForgeFile, null, null);
    }

    public DisableableMod(String name, String version, boolean optional, String file, Type type, Color colour,
            String description, boolean disabled, boolean userAdded, boolean wasSelected, boolean skipped,
            CurseForgeProject curseForgeProject, CurseForgeFile curseForgeFile) {
        this(name, version, optional, file, null, type, colour, description, disabled, userAdded, wasSelected, skipped,
                curseForgeProject.id, curseForgeFile.id, curseForgeProject, curseForgeFile);
    }

    public DisableableMod(String name, String version, boolean optional, String file, Type type, Color colour,
            String description, boolean disabled, boolean userAdded, boolean wasSelected, boolean skipped,
            ModrinthProject modrinthProject,
            ModrinthVersion modrinthVersion) {
        this(name, version, optional, file, null, type, colour, description, disabled, userAdded, wasSelected, skipped,
                null,
                null, null, null, modrinthProject, modrinthVersion);
    }

    public DisableableMod(String name, String version, boolean optional, String file, Type type, Color colour,
            String description, boolean disabled, boolean userAdded, boolean wasSelected, boolean skipped,
            Integer curseForgeModId,
            Integer curseForgeFileId) {
        this(name, version, optional, file, null, type, colour, description, disabled, userAdded, wasSelected, skipped,
                curseForgeModId, curseForgeFileId, null, null);
    }

    public DisableableMod(String name, String version, boolean optional, String file, Type type, Color colour,
            String description, boolean disabled, boolean userAdded, boolean wasSelected, boolean skipped) {
        this(name, version, optional, file, null, type, colour, description, disabled, userAdded, wasSelected, skipped,
                null,
                null, null, null);
    }

    public DisableableMod(String name, String version, boolean optional, String file, Type type, Color colour,
            String description, boolean disabled, boolean userAdded) {
        this(name, version, optional, file, null, type, colour, description, disabled, userAdded, true, false, null,
                null,
                null, null);
    }

    public DisableableMod() {}

    public String getName() {
        return this.name;
    }

    public String getVersion() {
        return this.version;
    }

    public boolean isOptional() {
        return this.optional;
    }

    public boolean hasColour() {
        return this.colour != null;
    }

    public Color getColour() {
        return this.colour;
    }

    public String getDescription() {
        if (this.description == null) {
            return "";
        }
        return this.description;
    }

    public boolean isDisabled() {
        return this.disabled;
    }

    public boolean wasSelected() {
        return this.wasSelected;
    }

    public void setWasSelected(boolean wasSelected) {
        this.wasSelected = wasSelected;
    }

    public boolean isUserAdded() {
        return this.userAdded;
    }

    public boolean isUpdatable() {
        return this.isFromCurseForge() || this.isFromModrinth();
    }

    public boolean isFromCurseForge() {
        return this.curseForgeProjectId != null && this.curseForgeFileId != null;
    }

    public boolean hasFullCurseForgeInformation() {
        return this.curseForgeProject != null && this.curseForgeFile != null;
    }

    public Integer getCurseForgeModId() {
        return this.curseForgeProjectId;
    }

    public Integer getCurseForgeFileId() {
        return this.curseForgeFileId;
    }

    public boolean isFromModrinth() {
        return this.modrinthProject != null && this.modrinthVersion != null;
    }

    public String getFilename() {
        return this.file;
    }

    public boolean enable(ModManagement instanceOrServer) {
        if (this.disabled) {
            if (!getFile(instanceOrServer).getParentFile().exists()) {
                getFile(instanceOrServer).getParentFile().mkdir();
            }
            if (Utils.moveFile(getDisabledFile(instanceOrServer), getFile(instanceOrServer), true)) {
                this.disabled = false;
            }
        }
        return false;
    }

    public boolean disable(ModManagement instanceOrServer) {
        if (!this.disabled) {
            if (!Files.isDirectory(instanceOrServer.getRoot().resolve("disabledmods"))) {
                FileUtils.createDirectory(instanceOrServer.getRoot().resolve("disabledmods"));
            }

            if (Utils.moveFile(getFile(instanceOrServer), getDisabledFile(instanceOrServer), true)) {
                this.disabled = true;
                return true;
            }
        }
        return false;
    }

    public boolean doesFileExist(Instance instance) {
        if (isDisabled()) {
            return getDisabledFile(instance).exists();
        }

        return getFile(instance).exists();
    }

    public Path getPath(Instance instance) {
        if (isDisabled()) {
            return getDisabledFile(instance).toPath();
        }

        return getFile(instance).toPath();
    }

    public File getDisabledFile(ModManagement instanceOrServer) {
        try {
            return instanceOrServer.getRoot().resolve("disabledmods/" + this.file).toFile();
        } catch (InvalidPathException e) {
            LogManager.warn("Invalid path for mod " + this.name);
            return null;
        }
    }

    public File getFile(ModManagement instanceOrServer) {
        return getFile(instanceOrServer.getRoot(), instanceOrServer.getMinecraftVersion());
    }

    public File getFile(Server server) {
        return getFile(server.getRoot(), server.version);
    }

    public File getFile(Instance instance) {
        return getFile(instance.getRoot(), instance.id);
    }

    public File getFile(Instance instance, Path base) {
        return getFile(base, instance.id);
    }

    public File getFile(Path base) {
        return getFile(base, null);
    }

    public File getFile(Path base, String mcVersion) {
        try {
            File dir = null;
            if (path != null) {
                dir = base.resolve(path).toFile();
            } else {
                switch (type) {
                    case jar:
                    case forge:
                    case mcpc:
                        dir = base.resolve("jarmods").toFile();
                        break;
                    case texturepack:
                        dir = base.resolve("texturepacks").toFile();
                        break;
                    case resourcepack:
                        dir = base.resolve("resourcepacks").toFile();
                        break;
                    case mods:
                        dir = base.resolve("mods").toFile();
                        break;
                    case plugins:
                        dir = base.resolve("plugins").toFile();
                        break;
                    case ic2lib:
                        dir = base.resolve("mods/ic2").toFile();
                        break;
                    case denlib:
                        dir = base.resolve("mods/denlib").toFile();
                        break;
                    case coremods:
                        dir = base.resolve("coremods").toFile();
                        break;
                    case shaderpack:
                        dir = base.resolve("shaderpacks").toFile();
                        break;
                    case datapack:
                        dir = base.resolve("datapacks").toFile();
                        break;
                    case dependency:
                        if (mcVersion != null) {
                            dir = base.resolve("mods/" + mcVersion).toFile();
                        }
                        break;
                    default:
                        LogManager.warn("Unsupported mod for enabling/disabling " + this.name);
                        break;
                }
            }
            if (dir == null) {
                LogManager.warn("null path returned for mod " + this.name);
                return null;
            }
            return new File(dir, file);
        } catch (InvalidPathException e) {
            LogManager.warn("Invalid path for mod " + this.name);
            return null;
        }
    }

    public Type getType() {
        return this.type;
    }

    public boolean checkForUpdate(Window parent, ModManagement instanceOrServer) {
        return checkForUpdate(parent, instanceOrServer, null);
    }

    public boolean checkForUpdate(Window parent, ModManagement instanceOrServer, ModPlatform platform) {
        Analytics.trackEvent(AnalyticsEvent.simpleEvent("mod_update_check"));

        if (platform == ModPlatform.CURSEFORGE || (platform == null && isFromCurseForge()
                && (!isFromModrinth() || App.settings.defaultModPlatform == ModPlatform.CURSEFORGE))) {
            ProgressDialog<Object> dialog = new ProgressDialog<>(
                    // #. {0} is the platform were checking for updates (e.g. CurseForge/Modrinth)
                    GetText.tr("Checking For Update On {0}", "CurseForge"), 0,
                    // #. {0} is the platform were checking for updates (e.g. CurseForge/Modrinth)
                    GetText.tr("Checking For Update On {0}", "CurseForge"),
                    "Cancelled checking for update on CurseForge", parent);
            dialog.addThread(new Thread(() -> {
                List<CurseForgeFile> curseForgeFiles = CurseForgeApi.getFilesForProject(curseForgeProjectId);

                if (curseForgeFiles == null) {
                    dialog.setReturnValue(false);
                    dialog.close();
                    return;
                }

                CurseForgeProjectFileSelectorDialog.markMissingTagsAsUntagged(curseForgeFiles);

                List<String> neoForgeForgeCompatabilityVersions = ConfigManager
                        .getConfigItem("loaders.neoforge.forgeCompatibleMinecraftVersions", new ArrayList<>());

                if (curseForgeInstalledPath(instanceOrServer, curseForgeFiles,
                        neoForgeForgeCompatabilityVersions) == SinytraConnector.InstalledPath.UNKNOWN) {
                    LogManager.warn("Not checking " + name + " for updates: its installed file's loader is unknown");
                }

                Predicate<CurseForgeFile> updateCandidate = curseForgeUpdateCandidate(instanceOrServer,
                        curseForgeFiles, neoForgeForgeCompatabilityVersions);

                if (!hasCurseForgeUpdate(instanceOrServer, curseForgeFiles, updateCandidate)) {
                    dialog.setReturnValue(false);
                    dialog.close();
                    return;
                }

                CurseForgeProject project = CurseForgeApi.getProjectById(curseForgeProjectId);
                dialog.setReturnValue(project == null ? null : new Pair<>(project, updateCandidate));
                dialog.close();
            }));
            dialog.start();

            if (dialog.getReturnValue() == null) {
                return false;
            }

            if (dialog.getReturnValue() instanceof Boolean) {
                return ((Boolean) dialog.getReturnValue()) == true;
            }

            // the selector offers the same files the check counted, so it can't switch the loader path either
            @SuppressWarnings("unchecked")
            Pair<CurseForgeProject, Predicate<CurseForgeFile>> update =
                    (Pair<CurseForgeProject, Predicate<CurseForgeFile>>) dialog.getReturnValue();
            CurseForgeProjectFileSelectorDialog curseForgeProjectFileSelectorDialog = new CurseForgeProjectFileSelectorDialog(
                    parent, update.left(),
                    instanceOrServer,
                    curseForgeFileId, update.right());
            curseForgeProjectFileSelectorDialog.setVisible(true);
        } else if (platform == ModPlatform.MODRINTH || ((platform == null && isFromModrinth())
                && (!isFromCurseForge() || App.settings.defaultModPlatform == ModPlatform.MODRINTH))) {
            ProgressDialog<Pair<ModrinthProject, List<ModrinthVersion>>> dialog = new ProgressDialog<>(
                    // #. {0} is the platform were checking for updates (e.g. CurseForge/Modrinth)
                    GetText.tr("Checking For Update On {0}", "Modrinth"), 0,
                    // #. {0} is the platform were checking for updates (e.g. CurseForge/Modrinth)
                    GetText.tr("Checking For Update On {0}", "Modrinth"), "Cancelled checking for update on Modrinth",
                    parent);
            dialog.addThread(new Thread(() -> {
                List<String> neoForgeForgeCompatabilityVersions = ConfigManager
                        .getConfigItem("loaders.neoforge.forgeCompatibleMinecraftVersions", new ArrayList<>());

                // an installed version stored without loaders is looked up by its ID among the project's versions
                List<ModrinthVersion> projectVersions = hasTags(modrinthVersion.loaders) ? null
                        : ModrinthApi.getVersions(modrinthProject.id);
                SinytraConnector.InstalledPath installedPath = modrinthInstalledPath(instanceOrServer, projectVersions,
                        neoForgeForgeCompatabilityVersions);

                if (installedPath == SinytraConnector.InstalledPath.UNKNOWN) {
                    LogManager.warn("Not checking " + name + " for updates: its installed version's loader is unknown");
                    dialog.setReturnValue(null);
                    dialog.close();
                    return;
                }

                ModrinthProject mod = ModrinthApi.getProject(modrinthProject.id);
                // the loader's own query leaves out the Fabric files a Connector install updates to
                List<ModrinthVersion> versions = ModrinthApi.getVersions(modrinthProject.id,
                        instanceOrServer.getMinecraftVersion(),
                        installedPath == SinytraConnector.InstalledPath.CONNECTOR ? null
                                : instanceOrServer.getLoaderVersion());

                if (versions == null) {
                    dialog.setReturnValue(null);
                    dialog.close();
                    return;
                }

                // the selector gets only these, so it can't switch the loader path either
                versions = modrinthUpdateCandidates(instanceOrServer, versions, installedPath,
                        neoForgeForgeCompatabilityVersions);

                if (!hasModrinthUpdate(instanceOrServer, versions)) {
                    dialog.setReturnValue(null);
                    dialog.close();
                    return;
                }

                dialog.setReturnValue(new Pair<>(mod, versions));
                dialog.close();
            }));
            dialog.start();

            Pair<ModrinthProject, List<ModrinthVersion>> pair = dialog.getReturnValue();
            if (pair == null) {
                return false;
            }

            ModrinthVersionSelectorDialog modrinthVersionSelectorDialog = new ModrinthVersionSelectorDialog(parent,
                    pair.left(), pair.right(), instanceOrServer, modrinthVersion.id, this.type,
                    ModrinthDownloadMetadata.Reason.UPDATE);
            modrinthVersionSelectorDialog.setVisible(true);
        }

        return true;
    }

    /**
     * Which CurseForge files may update this one: files for the loader that keep the installed file's loader path (see
     * {@link SinytraConnector#keepsLoaderPath}). A Fabric file installed through Connector updates only to Fabric
     * files for this exact Minecraft version, whatever the Add Mod Restriction, and to none while Connector is
     * inactive. A Forge-only file on NeoForge stays on Forge-only files, and a Fabric-only file on Quilt on Fabric-only
     * files. On Forge, NeoForge or Quilt, a file whose loader can't be established gets no updates.
     */
    public Predicate<CurseForgeFile> curseForgeUpdateCandidate(ModManagement instanceOrServer,
            List<CurseForgeFile> projectFiles, List<String> neoForgeForgeCompatibleVersions) {
        LoaderVersion loader = instanceOrServer.getLoaderVersion();
        String minecraftVersion = instanceOrServer.getMinecraftVersion();
        boolean forgeCompatible = neoForgeForgeCompatibleVersions.contains(minecraftVersion);
        SinytraConnector.InstalledPath installedPath = curseForgeInstalledPath(instanceOrServer, projectFiles,
                neoForgeForgeCompatibleVersions);
        boolean connectorActive = instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector();

        return cf -> SinytraConnector.keepsLoaderPath(installedPath, connectorActive, cf.gameVersions, loader,
                forgeCompatible) && (installedPath == SinytraConnector.InstalledPath.CONNECTOR
                        ? cf.gameVersions.contains(minecraftVersion)
                        : isForLoader(cf.gameVersions, loader, forgeCompatible));
    }

    public SinytraConnector.InstalledPath curseForgeInstalledPath(ModManagement instanceOrServer,
            List<CurseForgeFile> projectFiles, List<String> neoForgeForgeCompatibleVersions) {
        return SinytraConnector.installedPath(installedCurseForgeTags(projectFiles),
                instanceOrServer.getLoaderVersion(),
                neoForgeForgeCompatibleVersions.contains(instanceOrServer.getMinecraftVersion()));
    }

    /**
     * The installed file's tags: the file with the installed file's exact ID in the project's file list, else its
     * stored metadata. Null when neither has any.
     */
    private List<String> installedCurseForgeTags(List<CurseForgeFile> projectFiles) {
        if (curseForgeFileId != null) {
            Optional<List<String>> fetched = projectFiles.stream()
                    .filter(f -> f != null && f.id == curseForgeFileId && hasTags(f.gameVersions))
                    .map(f -> f.gameVersions).findFirst();

            if (fetched.isPresent()) {
                return fetched.get();
            }
        }

        return curseForgeFile != null && hasTags(curseForgeFile.gameVersions) ? curseForgeFile.gameVersions : null;
    }

    private static boolean hasTags(List<String> tags) {
        return tags != null && !tags.isEmpty();
    }

    // CurseForge files the loader runs natively, or untagged ones
    private static boolean isForLoader(List<String> gameVersions, LoaderVersion loader, boolean forgeCompatible) {
        if (gameVersions.contains("Fabric") && loader != null
                && (loader.isFabric() || loader.isLegacyFabric() || loader.isQuilt())) {
            return true;
        }

        if (gameVersions.contains("NeoForge") && loader != null && loader.isNeoForge()) {
            return true;
        }

        if (gameVersions.contains("Forge") && loader != null
                && (loader.isForge() || (loader.isNeoForge() && forgeCompatible))) {
            return true;
        }

        if (gameVersions.contains("Quilt") && loader != null && loader.isQuilt()) {
            return true;
        }

        // if there's no loaders, assume the mod is untagged so we should show it
        return !gameVersions.contains("Fabric") && !gameVersions.contains("NeoForge")
                && !gameVersions.contains("Forge") && !gameVersions.contains("Quilt");
    }

    /**
     * Whether a newer CurseForge file than the installed one passes the Minecraft version restriction and the update
     * filter. Expects files whose missing tags were already marked untagged.
     */
    boolean hasCurseForgeUpdate(ModManagement instanceOrServer, List<CurseForgeFile> projectFiles,
            Predicate<CurseForgeFile> updateCandidate) {
        Stream<CurseForgeFile> curseForgeFilesStream = projectFiles.stream();

        if (App.settings.addModRestriction == AddModRestriction.STRICT) {
            curseForgeFilesStream = curseForgeFilesStream
                    .filter(f -> f.gameVersions.contains(instanceOrServer.getMinecraftVersion()));
        }

        if (App.settings.addModRestriction == AddModRestriction.LAX) {
            try {
                List<String> minecraftVersionsToSearch = MinecraftManager
                        .getMajorMinecraftVersions(instanceOrServer.getMinecraftVersion())
                        .stream().map(mv -> mv.id).collect(Collectors.toList());

                curseForgeFilesStream = curseForgeFilesStream.filter(
                        f -> f.gameVersions.stream()
                                .anyMatch(minecraftVersionsToSearch::contains));
            } catch (InvalidMinecraftVersion e) {
                LogManager.logStackTrace(e);
            }
        }

        return curseForgeFilesStream.filter(updateCandidate).anyMatch(f -> f.id > curseForgeFileId);
    }

    /**
     * Which loader path the installed Modrinth version runs on: from its stored loaders, else from the version with its
     * exact ID in {@code projectVersions}.
     *
     * @param projectVersions the project's versions, or null when the stored loaders are all there is
     */
    public SinytraConnector.InstalledPath modrinthInstalledPath(ModManagement instanceOrServer,
            List<ModrinthVersion> projectVersions, List<String> neoForgeForgeCompatibleVersions) {
        List<String> loaders = modrinthVersion.loaders;

        if (!hasTags(loaders) && projectVersions != null) {
            loaders = projectVersions.stream()
                    .filter(v -> v != null && modrinthVersion.id != null && modrinthVersion.id.equals(v.id))
                    .map(v -> v.loaders).filter(DisableableMod::hasTags).findFirst().orElse(null);
        }

        return SinytraConnector.installedPath(loaders, instanceOrServer.getLoaderVersion(),
                neoForgeForgeCompatibleVersions.contains(instanceOrServer.getMinecraftVersion()));
    }

    /**
     * The fetched Modrinth versions that keep the installed version's loader path (see
     * {@link SinytraConnector#keepsLoaderPath}). Connector updates must also be for this exact Minecraft version,
     * whatever the Add Mod Restriction.
     */
    public List<ModrinthVersion> modrinthUpdateCandidates(ModManagement instanceOrServer,
            List<ModrinthVersion> versions, SinytraConnector.InstalledPath installedPath,
            List<String> neoForgeForgeCompatibleVersions) {
        LoaderVersion loader = instanceOrServer.getLoaderVersion();
        String minecraftVersion = instanceOrServer.getMinecraftVersion();
        boolean forgeCompatible = neoForgeForgeCompatibleVersions.contains(minecraftVersion);
        boolean connectorActive = instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector();

        return versions.stream()
                .filter(v -> v != null && SinytraConnector.keepsLoaderPath(installedPath, connectorActive, v.loaders,
                        loader, forgeCompatible))
                .filter(v -> installedPath != SinytraConnector.InstalledPath.CONNECTOR
                        || (v.gameVersions != null && v.gameVersions.contains(minecraftVersion)))
                .collect(Collectors.toList());
    }

    // whether any version, after the datapack and Minecraft version restrictions, is newer than the installed one
    boolean hasModrinthUpdate(ModManagement instanceOrServer, List<ModrinthVersion> versions) {
        Stream<ModrinthVersion> versionsStream = versions.stream();

        if (this.type == Type.datapack) {
            versionsStream = versionsStream
                    .filter(v -> v.loaders != null && v.loaders.contains("datapack"));
        }

        if (App.settings.addModRestriction == AddModRestriction.STRICT) {
            versionsStream = versionsStream
                    .filter(v -> v.gameVersions.contains(instanceOrServer.getMinecraftVersion()));
        }

        return versionsStream.anyMatch(v -> ISODateTimeFormat.dateTimeParser().parseDateTime(v.datePublished)
                .minusSeconds(1)
                .isAfter(ISODateTimeFormat.dateTimeParser().parseDateTime(modrinthVersion.datePublished)));
    }

    public boolean reinstall(Window parent, ModManagement instanceOrServer) {
        return reinstall(parent, instanceOrServer, null);
    }

    public boolean reinstall(Window parent, ModManagement instanceOrServer, ModPlatform platform) {
        Analytics.trackEvent(AnalyticsEvent.simpleEvent("mod_reinstall"));

        if (platform == ModPlatform.CURSEFORGE || (platform == null && isFromCurseForge()
                && (!isFromModrinth() || App.settings.defaultModPlatform == ModPlatform.CURSEFORGE))) {
            ProgressDialog<Pair<CurseForgeProject, List<CurseForgeFile>>> dialog = new ProgressDialog<>(
                    // #. {0} is the platform were getting files from (e.g. CurseForge/Modrinth)
                    GetText.tr("Getting Files From {0}", "CurseForge"),
                    // #. {0} is the platform were getting files from (e.g. CurseForge/Modrinth)
                    0, GetText.tr("Getting Files From {0}", "CurseForge"), "Cancelled getting files from CurseForge",
                    parent);
            dialog.addThread(new Thread(() -> {
                dialog.setReturnValue(new Pair<>(CurseForgeApi.getProjectById(curseForgeProjectId),
                        CurseForgeApi.getFilesForProject(curseForgeProjectId)));
                dialog.close();
            }));
            dialog.start();

            Pair<CurseForgeProject, List<CurseForgeFile>> fetched = dialog.getReturnValue();
            if (fetched == null || fetched.left() == null || fetched.right() == null) {
                showCannotReinstall(parent, GetText.tr("Couldn't get the files for {0} from {1}. Nothing was changed.",
                        name, "CurseForge"));
                return false;
            }

            List<CurseForgeFile> projectFiles = fetched.right();
            CurseForgeProjectFileSelectorDialog.markMissingTagsAsUntagged(projectFiles);
            List<String> neoForgeForgeCompatabilityVersions = ConfigManager
                    .getConfigItem("loaders.neoforge.forgeCompatibleMinecraftVersions", new ArrayList<>());

            String blocker = reinstallBlocker(name,
                    curseForgeInstalledPath(instanceOrServer, projectFiles, neoForgeForgeCompatabilityVersions),
                    instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector());
            if (blocker != null) {
                showCannotReinstall(parent, blocker);
                return false;
            }

            // same files an update may offer, so no pick can switch the loader path; the selector preselects the
            // installed file and stops if it isn't among them
            CurseForgeProjectFileSelectorDialog curseForgeProjectFileSelectorDialog = new CurseForgeProjectFileSelectorDialog(
                    parent, fetched.left(), instanceOrServer, curseForgeFileId,
                    curseForgeUpdateCandidate(instanceOrServer, projectFiles, neoForgeForgeCompatabilityVersions), true);
            curseForgeProjectFileSelectorDialog.setVisible(true);
        } else if (platform == ModPlatform.MODRINTH || (platform == null && isFromModrinth()
                && (!isFromCurseForge() || App.settings.defaultModPlatform == ModPlatform.MODRINTH))) {
            ProgressDialog<Pair<ModrinthProject, List<ModrinthVersion>>> dialog = new ProgressDialog<>(
                    // #. {0} is the platform were getting files from (e.g. CurseForge/Modrinth)
                    GetText.tr("Getting Files From {0}", "Modrinth"), 0,
                    // #. {0} is the platform were getting files from (e.g. CurseForge/Modrinth)
                    GetText.tr("Getting Files From {0}", "Modrinth"), "Cancelled getting files from Modrinth", parent);
            dialog.addThread(new Thread(() -> {
                dialog.setReturnValue(new Pair<>(ModrinthApi.getProject(modrinthProject.id),
                        ModrinthApi.getVersions(modrinthProject.id)));
                dialog.close();
            }));
            dialog.start();

            Pair<ModrinthProject, List<ModrinthVersion>> fetched = dialog.getReturnValue();
            if (fetched == null || fetched.left() == null || fetched.right() == null) {
                showCannotReinstall(parent, GetText.tr("Couldn't get the files for {0} from {1}. Nothing was changed.",
                        name, "Modrinth"));
                return false;
            }

            List<String> neoForgeForgeCompatabilityVersions = ConfigManager
                    .getConfigItem("loaders.neoforge.forgeCompatibleMinecraftVersions", new ArrayList<>());
            SinytraConnector.InstalledPath installedPath = modrinthInstalledPath(instanceOrServer, fetched.right(),
                    neoForgeForgeCompatabilityVersions);

            String blocker = reinstallBlocker(name, installedPath,
                    instanceOrServer.isForgeLikeAndHasInstalledSinytraConnector());
            if (blocker != null) {
                showCannotReinstall(parent, blocker);
                return false;
            }

            // same versions an update may offer, so no pick can switch the loader path; the selector preselects the
            // installed version and stops if it isn't among them
            ModrinthVersionSelectorDialog modrinthVersionSelectorDialog = new ModrinthVersionSelectorDialog(parent,
                    fetched.left(), modrinthUpdateCandidates(instanceOrServer, fetched.right(), installedPath,
                            neoForgeForgeCompatabilityVersions),
                    instanceOrServer, modrinthVersion.id, this.type, true);
            modrinthVersionSelectorDialog.setVisible(true);
        }

        return true;
    }

    /**
     * Why Reinstall can't safely go ahead, or null when it can: the installed file's loader must be known, and a
     * Fabric file running through Connector needs Connector still active to be reinstalled as one.
     */
    static String reinstallBlocker(String name, SinytraConnector.InstalledPath installedPath,
            boolean connectorActive) {
        if (installedPath == SinytraConnector.InstalledPath.UNKNOWN) {
            return GetText.tr("Can't tell which loader the installed file for {0} is for, so it can't be reinstalled "
                    + "safely. Nothing was changed.", name);
        }

        if (installedPath == SinytraConnector.InstalledPath.CONNECTOR && !connectorActive) {
            return GetText.tr("{0} is a Fabric mod that runs through Sinytra Connector. To reinstall it, Sinytra "
                    + "Connector must be installed and enabled, with \"Show Fabric Mods When Sinytra Installed?\" "
                    + "on. Nothing was changed.", name);
        }

        return null;
    }

    private static void showCannotReinstall(Window parent, String message) {
        DialogManager.okDialog().setParent(parent).setTitle(GetText.tr("Can't Reinstall")).setContent(message)
                .setType(DialogManager.ERROR).show();
    }

    public static DisableableMod generateMod(File file, com.atlauncher.data.Type type, boolean enabled) {
        DisableableMod mod = new DisableableMod();
        mod.disabled = !enabled;
        mod.userAdded = true;
        mod.wasSelected = true;
        mod.file = file.getName();
        mod.type = type;
        mod.optional = true;
        mod.name = file.getName();
        mod.version = "Unknown";
        mod.description = null;

        MCMod mcMod = Utils.getMCModForFile(file);
        if (mcMod != null) {
            mod.name = Optional.ofNullable(mcMod.name).orElse(file.getName());
            mod.version = Optional.ofNullable(mcMod.version).orElse("Unknown");
            mod.description = mcMod.description;
        } else {
            FabricMod fabricMod = Utils.getFabricModForFile(file);
            if (fabricMod != null) {
                mod.name = Optional.ofNullable(fabricMod.name).orElse(file.getName());
                mod.version = Optional.ofNullable(fabricMod.version).orElse("Unknown");
                mod.description = fabricMod.description;
            }
        }
        return mod;
    }
}
