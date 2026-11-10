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
 * How project results are shown, in Add Mods and Collections alike: each remembers its own.
 */
public enum ResultsView {
    // the cards Add Mods has always had
    CARDS,
    // one short row each, as Add Mods' earlier "List"
    COMPACT_LIST,
    // one comfortable row each, with room for the summary and details
    LIST;

    /**
     * What an Add Mods view saved before there were three means now: its "List" was the compact one.
     */
    public static ResultsView fromLegacy(AddModsViewMode legacy) {
        return legacy == AddModsViewMode.LIST ? COMPACT_LIST : CARDS;
    }

    public boolean isRow() {
        return this != CARDS;
    }
}
