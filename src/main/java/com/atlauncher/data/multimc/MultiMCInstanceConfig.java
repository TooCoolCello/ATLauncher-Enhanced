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
package com.atlauncher.data.multimc;

import java.util.Properties;

import com.atlauncher.data.Settings;

public class MultiMCInstanceConfig {
    public String name;

    public String iconKey;
    public Integer maximumMemory;
    // MinMemAlloc, when MultiMC applied it to this instance; null otherwise
    public Integer initialMemory;
    public Integer permGen;
    public String javaPath;
    public String javaArguments;
    public String preLaunchCommand;
    public String postExitCommand;
    public String wrapperCommand;

    /**
     * Writes the memory settings for a MultiMC export, so importing it back gives the same minimum and maximum. The
     * instance's own values (null when it follows the launcher) make MultiMC apply them to this instance.
     */
    public static void writeMemory(Properties cfg, Integer instanceMaximum, Integer instanceInitial,
            int launcherMaximum, int launcherInitial) {
        int maximum = instanceMaximum == null ? launcherMaximum : instanceMaximum;
        cfg.setProperty("MaxMemAlloc", maximum + "");
        int initial = Settings.clampInitialMemory(instanceInitial == null ? launcherInitial : instanceInitial, maximum);
        if (initial > 0) {
            cfg.setProperty("MinMemAlloc", initial + "");
        }
        cfg.setProperty("OverrideMemory", instanceMaximum == null && instanceInitial == null ? "false" : "true");
    }

    public MultiMCInstanceConfig(Properties props) {
        name = props.getProperty("name");

        if (name == null) {
            name = "MultiMC Import";
        }

        if (props.getProperty("iconKey") != null) {
            iconKey = props.getProperty("iconKey");
        }

        if (props.getProperty("MaxMemAlloc") != null) {
            maximumMemory = Integer.parseInt(props.getProperty("MaxMemAlloc"));
        }

        // only a minimum MultiMC applies to this instance (OverrideMemory) is its own; without that, MultiMC's global
        // default is in play, so the instance follows the launcher's setting instead
        if (Boolean.parseBoolean(props.getProperty("OverrideMemory")) && props.getProperty("MinMemAlloc") != null) {
            try {
                initialMemory = Integer.parseInt(props.getProperty("MinMemAlloc").trim());
            } catch (NumberFormatException e) {
                initialMemory = null;
            }
        }

        if (props.getProperty("PermGen") != null) {
            permGen = Integer.parseInt(props.getProperty("PermGen"));
        }

        if (props.getProperty("JavaPath") != null) {
            javaPath = props.getProperty("JavaPath");
        }

        if (props.getProperty("JVMArgs") != null) {
            javaArguments = props.getProperty("JVMArgs");
        }

        if (props.getProperty("PreLaunchCommand") != null) {
            preLaunchCommand = props.getProperty("PreLaunchCommand");
        }

        if (props.getProperty("PostExitCommand") != null) {
            postExitCommand = props.getProperty("PostExitCommand");
        }
        if (props.getProperty("WrapperCommand") != null) {
            wrapperCommand = props.getProperty("WrapperCommand");
        }
    }

    /**
     * The minimum memory to give the imported instance, checked like any loaded minimum against the maximum it will
     * run with: never above it, and 0 or below leaves it to Java. Null to follow the launcher's setting.
     */
    public Integer initialMemoryWithin(int maximumMemory) {
        return initialMemory == null ? null : Settings.clampInitialMemory(initialMemory, maximumMemory);
    }
}
