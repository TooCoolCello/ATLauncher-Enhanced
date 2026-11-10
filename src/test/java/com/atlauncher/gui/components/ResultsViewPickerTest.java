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
package com.atlauncher.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JRadioButtonMenuItem;

import org.junit.jupiter.api.Test;

import com.atlauncher.data.ResultsView;
import com.formdev.flatlaf.extras.FlatSVGIcon;

/**
 * The one View button Add Mods and Collections both show, and its menu.
 */
public class ResultsViewPickerTest {
    @Test
    public void theMenuOffersEachViewByNameAndIconWithTheChosenOneChecked() {
        ResultsViewPicker picker = new ResultsViewPicker(ResultsView.COMPACT_LIST, view -> {
        });

        assertEquals(3, picker.menu().getComponentCount());
        String[] names = { "Cards", "List", "Compact List" };
        Set<String> icons = new HashSet<>();
        for (int i = 0; i < names.length; i++) {
            JRadioButtonMenuItem item = (JRadioButtonMenuItem) picker.menu().getComponent(i);
            assertEquals(names[i], item.getText());
            FlatSVGIcon icon = (FlatSVGIcon) item.getIcon();
            assertTrue(icon.hasFound(), icon.getName());
            assertTrue(icons.add(icon.getName()), "each view has its own icon");
        }
        assertTrue(picker.item(ResultsView.COMPACT_LIST).isSelected());
        assertFalse(picker.item(ResultsView.LIST).isSelected());
    }

    @Test
    public void theButtonShowsTheChosenViewAndIsReachableByKeyboard() {
        ResultsViewPicker picker = new ResultsViewPicker(ResultsView.LIST, view -> {
        });

        assertEquals("View: List", picker.getText());
        assertEquals("View: List", picker.getAccessibleContext().getAccessibleName());
        assertEquals("assets/icon/view-list.svg", ((FlatSVGIcon) picker.getIcon()).getName());
        assertNotNull(picker.getToolTipText());
        assertTrue(picker.isFocusable());
    }

    @Test
    public void pickingAViewSaysSoAndShowsIt() {
        List<ResultsView> picked = new ArrayList<>();
        ResultsViewPicker picker = new ResultsViewPicker(ResultsView.CARDS, picked::add);

        picker.item(ResultsView.COMPACT_LIST).doClick(0);
        assertEquals(Collections.singletonList(ResultsView.COMPACT_LIST), picked);
        assertEquals(ResultsView.COMPACT_LIST, picker.shown());
        assertEquals("View: Compact List", picker.getAccessibleContext().getAccessibleName());
        assertTrue(picker.item(ResultsView.COMPACT_LIST).isSelected());
    }

    @Test
    public void aViewChosenElsewhereIsShownWithoutSayingSo() {
        List<ResultsView> picked = new ArrayList<>();
        ResultsViewPicker picker = new ResultsViewPicker(ResultsView.CARDS, picked::add);

        picker.select(ResultsView.LIST);
        assertTrue(picker.item(ResultsView.LIST).isSelected());
        assertEquals("View: List", picker.getText());
        assertTrue(picked.isEmpty());

        // an unset view shows as Cards
        picker.select(null);
        assertTrue(picker.item(ResultsView.CARDS).isSelected());

        // the button itself only opens the menu, and not while it's off screen
        picker.doClick(0);
        assertFalse(picker.menu().isVisible());
        assertTrue(picked.isEmpty());
    }
}
