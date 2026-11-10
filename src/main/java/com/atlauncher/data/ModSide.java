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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.data.modrinth.ModrinthSide;
import com.google.gson.annotations.SerializedName;

/**
 * Where a mod is needed. CLIENT means a dedicated server doesn't need it (minimaps, shaders, Sodium); SERVER means
 * players joining a server don't need it. A client instance still runs SERVER mods in singleplayer.
 *
 * @see DisableableMod#getEffectiveSide()
 */
public enum ModSide {
    @SerializedName("both")
    BOTH,

    @SerializedName("client")
    CLIENT,

    @SerializedName("server")
    SERVER;

    @Override
    public String toString() {
        switch (this) {
            case CLIENT:
                return GetText.tr("Client Only");
            case SERVER:
                return GetText.tr("Server Only");
            default:
                return GetText.tr("Client And Server");
        }
    }

    /**
     * From a Modrinth project's client_side and server_side; null when they don't say (missing, "unknown", or
     * neither side supported).
     */
    public static ModSide fromModrinth(ModrinthSide client, ModrinthSide server) {
        if (client == ModrinthSide.UNSUPPORTED && server == ModrinthSide.UNSUPPORTED) {
            return null;
        }

        if (server == ModrinthSide.UNSUPPORTED) {
            return CLIENT;
        }

        if (client == ModrinthSide.UNSUPPORTED) {
            return SERVER;
        }

        return client != null && server != null ? BOTH : null;
    }

    /**
     * From a CurseForge file's game versions, where authors can tag the "Client" and "Server" environments; null when
     * the file has neither tag.
     */
    public static ModSide fromCurseForge(List<String> gameVersions) {
        if (gameVersions == null) {
            return null;
        }

        boolean client = gameVersions.stream().anyMatch("Client"::equalsIgnoreCase);
        boolean server = gameVersions.stream().anyMatch("Server"::equalsIgnoreCase);

        if (client && server) {
            return BOTH;
        }

        return client ? CLIENT : server ? SERVER : null;
    }

    /**
     * The env entry of a file in a Modrinth pack (modrinth.index.json) for a mod with this side (null: unknown).
     * Client-only mods are unsupported on servers. Server-only mods stay optional on clients, because singleplayer
     * runs them.
     */
    public static Map<String, String> modrinthEnv(ModSide side) {
        Map<String, String> env = new HashMap<>();
        env.put("client", side == SERVER ? "optional" : "required");
        env.put("server", side == CLIENT ? "unsupported" : "required");
        return env;
    }
}
