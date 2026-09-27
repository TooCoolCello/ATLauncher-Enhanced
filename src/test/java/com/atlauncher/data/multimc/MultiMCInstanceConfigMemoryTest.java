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
package com.atlauncher.data.multimc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * MinMemAlloc from a MultiMC instance.cfg: imported as the instance's own minimum only when MultiMC applied its memory
 * settings to that instance (OverrideMemory), checked against the maximum like any loaded minimum, and otherwise left
 * to follow the launcher's setting.
 */
public class MultiMCInstanceConfigMemoryTest {
    private static MultiMCInstanceConfig config(String cfg) throws IOException {
        Properties props = new Properties();
        props.load(new StringReader(cfg));
        return new MultiMCInstanceConfig(props);
    }

    @Test
    public void olderFilesWithoutAMinimumFollowTheLauncher() throws IOException {
        MultiMCInstanceConfig config = config("name=Old\nOverrideMemory=true\nMaxMemAlloc=4096\n");

        assertNull(config.initialMemory);
        assertNull(config.initialMemoryWithin(4096));
        // the maximum is imported as before
        assertEquals(Integer.valueOf(4096), config.maximumMemory);
    }

    @Test
    public void minimumMultiMcDoesNotApplyToTheInstanceIsNotImported() throws IOException {
        for (String cfg : new String[] { "OverrideMemory=false\nMinMemAlloc=512\nMaxMemAlloc=4096\n",
                "MinMemAlloc=512\nMaxMemAlloc=4096\n" }) {
            assertNull(config(cfg).initialMemoryWithin(4096), cfg);
        }
    }

    @Test
    public void appliedMinimumIsImportedAsTheInstancesOwn() throws IOException {
        assertEquals(Integer.valueOf(1024),
                config("OverrideMemory=true\nMinMemAlloc=1024\nMaxMemAlloc=4096\n").initialMemoryWithin(4096));
        // an explicit 0 stays explicit: the instance leaves it to Java even if the launcher sets a minimum
        assertEquals(Integer.valueOf(0),
                config("OverrideMemory=true\nMinMemAlloc=0\nMaxMemAlloc=4096\n").initialMemoryWithin(4096));
    }

    @Test
    public void importedMinimumIsValidatedAgainstTheMaximum() throws IOException {
        MultiMCInstanceConfig tooLarge = config("OverrideMemory=true\nMinMemAlloc=8192\nMaxMemAlloc=4096\n");
        assertEquals(Integer.valueOf(4096), tooLarge.initialMemoryWithin(tooLarge.maximumMemory));

        assertEquals(Integer.valueOf(0),
                config("OverrideMemory=true\nMinMemAlloc=-512\n").initialMemoryWithin(4096));
        // unreadable values are ignored rather than failing the import
        assertNull(config("OverrideMemory=true\nMinMemAlloc=lots\n").initialMemoryWithin(4096));
        assertEquals(Integer.valueOf(2048),
                config("OverrideMemory=true\nMinMemAlloc= 2048 \n").initialMemoryWithin(4096));
    }
}
