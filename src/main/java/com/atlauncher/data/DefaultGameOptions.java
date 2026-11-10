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

/**
 * Game options the user wants every new client instance to start with, in the units the game's options screen shows.
 * Each one is null by default, which means "don't change": the pack's or the game's own value stays.
 *
 * @see com.atlauncher.utils.OptionsTxt
 */
public class DefaultGameOptions {
    public static final int MIN_FOV = 30, MAX_FOV = 110;
    public static final int MAX_MOUSE_SENSITIVITY = 200;
    public static final int MIN_RENDER_DISTANCE = 2, MAX_RENDER_DISTANCE = 32;
    public static final int MAX_GUI_SCALE = 8;
    public static final int MAX_MASTER_VOLUME = 100;

    // degrees, as the FOV slider shows them
    public Integer fov;
    // percent; the game's default is 100
    public Integer mouseSensitivity;
    // chunks
    public Integer renderDistance;
    // 0 is Auto
    public Integer guiScale;
    public Boolean autoJump;
    // percent
    public Integer masterVolume;

    public boolean isEmpty() {
        return fov == null && mouseSensitivity == null && renderDistance == null && guiScale == null
            && autoJump == null && masterVolume == null;
    }

    // a hand-edited value out of range goes back to "don't change" rather than reaching options.txt
    void validate() {
        fov = inRange(fov, MIN_FOV, MAX_FOV);
        mouseSensitivity = inRange(mouseSensitivity, 0, MAX_MOUSE_SENSITIVITY);
        renderDistance = inRange(renderDistance, MIN_RENDER_DISTANCE, MAX_RENDER_DISTANCE);
        guiScale = inRange(guiScale, 0, MAX_GUI_SCALE);
        masterVolume = inRange(masterVolume, 0, MAX_MASTER_VOLUME);
    }

    private static Integer inRange(Integer value, int min, int max) {
        return value == null || value < min || value > max ? null : value;
    }
}
