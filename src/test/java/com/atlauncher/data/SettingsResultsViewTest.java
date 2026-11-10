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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.atlauncher.Gsons;

/**
 * Add Mods and Collections each keep their own view; the view Add Mods had before carries over as it was. Nothing here
 * is read from or written to disk.
 */
public class SettingsResultsViewTest {
    private static Settings read(String json) {
        Settings settings = Gsons.DEFAULT.fromJson(json, Settings.class);
        settings.validateAddModsViewMode();
        return settings;
    }

    @Test
    public void theEarlierAddModsViewMeansWhatItDid() {
        // its "List" was the compact one
        assertEquals(ResultsView.COMPACT_LIST, read("{\"addModsViewMode\": \"LIST\"}").addModsView());
        assertEquals(ResultsView.CARDS, read("{\"addModsViewMode\": \"CARDS\"}").addModsView());
        assertEquals(ResultsView.CARDS, read("{}").addModsView());
        assertEquals(ResultsView.CARDS, new Settings().addModsView());
    }

    @Test
    public void collectionsStartsAsAddModsWasSinceTheyWereShared() {
        assertEquals(ResultsView.COMPACT_LIST, read("{\"addModsViewMode\": \"LIST\"}").collectionsView());
        assertEquals(ResultsView.CARDS, read("{}").collectionsView());
    }

    @Test
    public void eachViewIsKeptApartOnceSet() {
        Settings settings = read("{\"addModsViewMode\": \"LIST\"}");

        settings.collectionsView = ResultsView.LIST;
        assertEquals(ResultsView.COMPACT_LIST, settings.addModsView());
        settings.addModsView = ResultsView.CARDS;
        assertEquals(ResultsView.LIST, settings.collectionsView());

        // and both are saved and read back as they are
        Settings reopened = read(Gsons.DEFAULT.toJson(settings));
        assertEquals(ResultsView.CARDS, reopened.addModsView());
        assertEquals(ResultsView.LIST, reopened.collectionsView());
    }

    @Test
    public void newViewsSavedWinOverTheEarlierOne() {
        Settings settings = read("{\"addModsViewMode\": \"LIST\", \"addModsView\": \"LIST\", "
            + "\"collectionsView\": \"CARDS\"}");
        assertEquals(ResultsView.LIST, settings.addModsView());
        assertEquals(ResultsView.CARDS, settings.collectionsView());
        // the earlier setting isn't touched
        assertEquals(AddModsViewMode.LIST, settings.addModsViewMode);
    }

    @Test
    public void anUnknownViewFallsBackToTheEarlierOne() {
        Settings settings = read("{\"addModsViewMode\": \"LIST\", \"addModsView\": \"GRID\"}");
        assertEquals(ResultsView.COMPACT_LIST, settings.addModsView());
        assertTrue(settings.collectionsView() == ResultsView.COMPACT_LIST);
    }
}
