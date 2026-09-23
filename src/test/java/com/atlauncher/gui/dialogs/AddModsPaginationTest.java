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
package com.atlauncher.gui.dialogs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.atlauncher.utils.CurseForgeApi;
import com.atlauncher.utils.ModrinthApi;

public class AddModsPaginationTest {
    private static final int CF_MAX = CurseForgeApi.MAX_SEARCH_PAGE_SIZE;
    private static final int CF_INDEX = CurseForgeApi.MAX_SEARCH_INDEX;
    private static final int MR_MAX = ModrinthApi.MAX_SEARCH_LIMIT;

    // "offset:limit" for each planned request
    private static List<String> plan(int page, int pageSize, int providerMax, int maxIndex) {
        return AddModsPagination.planRequests(page, pageSize, providerMax, maxIndex).stream()
                .map(r -> r.offset + ":" + r.limit).collect(Collectors.toList());
    }

    @Test
    void curseForgeSplitsPagesIntoRequestsOfAtMost50() {
        assertEquals(Arrays.asList("0:25"), plan(0, 25, CF_MAX, CF_INDEX));
        assertEquals(Arrays.asList("0:50"), plan(0, 50, CF_MAX, CF_INDEX));
        assertEquals(Arrays.asList("0:50", "50:50"), plan(0, 100, CF_MAX, CF_INDEX));
        assertEquals(Arrays.asList("0:50", "50:50", "100:50", "150:50"), plan(0, 200, CF_MAX, CF_INDEX));

        assertEquals(Arrays.asList("75:25"), plan(3, 25, CF_MAX, CF_INDEX));
        assertEquals(Arrays.asList("150:50"), plan(3, 50, CF_MAX, CF_INDEX));
        assertEquals(Arrays.asList("300:50", "350:50"), plan(3, 100, CF_MAX, CF_INDEX));
        assertEquals(Arrays.asList("600:50", "650:50", "700:50", "750:50"), plan(3, 200, CF_MAX, CF_INDEX));
    }

    @Test
    void modrinthSplitsPagesIntoRequestsOfAtMost100() {
        assertEquals(Arrays.asList("0:25"), plan(0, 25, MR_MAX, Integer.MAX_VALUE));
        assertEquals(Arrays.asList("0:50"), plan(0, 50, MR_MAX, Integer.MAX_VALUE));
        assertEquals(Arrays.asList("0:100"), plan(0, 100, MR_MAX, Integer.MAX_VALUE));
        assertEquals(Arrays.asList("0:100", "100:100"), plan(0, 200, MR_MAX, Integer.MAX_VALUE));

        assertEquals(Arrays.asList("75:25"), plan(3, 25, MR_MAX, Integer.MAX_VALUE));
        assertEquals(Arrays.asList("150:50"), plan(3, 50, MR_MAX, Integer.MAX_VALUE));
        assertEquals(Arrays.asList("300:100"), plan(3, 100, MR_MAX, Integer.MAX_VALUE));
        assertEquals(Arrays.asList("600:100", "700:100"), plan(3, 200, MR_MAX, Integer.MAX_VALUE));
    }

    @Test
    void curseForgeNeverRequestsPastIndex10000() {
        // last full 200 page ends exactly at the boundary
        assertEquals(Arrays.asList("9800:50", "9850:50", "9900:50", "9950:50"), plan(49, 200, CF_MAX, CF_INDEX));
        // a page starting at the boundary has nothing to request
        assertTrue(plan(50, 200, CF_MAX, CF_INDEX).isEmpty());
        assertTrue(plan(400, 25, CF_MAX, CF_INDEX).isEmpty());
        // a page straddling a lower limit is trimmed to it
        assertEquals(Arrays.asList("0:50", "50:50", "100:20"), plan(0, 200, CF_MAX, 120));

        for (int pageSize : new int[] { 25, 50, 100, 200 }) {
            for (int page = 0; page * pageSize < CF_INDEX + pageSize; page++) {
                for (AddModsPagination.Request request : AddModsPagination.planRequests(page, pageSize, CF_MAX,
                        CF_INDEX)) {
                    assertTrue(request.offset + request.limit <= CF_INDEX);
                    assertTrue(request.limit <= CF_MAX);
                }
            }
        }
    }

    @Test
    void curseForgeNextPageUsesTotalCountAndTheIndexBoundary() {
        assertTrue(AddModsPagination.curseForgeHasNext(0, 25, 25, 26, CF_INDEX));
        assertFalse(AddModsPagination.curseForgeHasNext(0, 25, 25, 25, CF_INDEX));
        assertFalse(AddModsPagination.curseForgeHasNext(3, 50, 10, 160, CF_INDEX));
        assertTrue(AddModsPagination.curseForgeHasNext(3, 50, 50, 201, CF_INDEX));

        // total is larger, but the next page would start at 10000
        assertFalse(AddModsPagination.curseForgeHasNext(49, 200, 200, 50000, CF_INDEX));
        assertTrue(AddModsPagination.curseForgeHasNext(48, 200, 200, 50000, CF_INDEX));
        assertFalse(AddModsPagination.curseForgeHasNext(399, 25, 25, 50000, CF_INDEX));

        // no reported total: fall back to "a full page may have more"
        assertTrue(AddModsPagination.curseForgeHasNext(0, 100, 100, -1, CF_INDEX));
        assertFalse(AddModsPagination.curseForgeHasNext(0, 100, 60, -1, CF_INDEX));
    }

    @Test
    void modrinthNextPageUsesTotalHits() {
        assertTrue(AddModsPagination.modrinthHasNext(0, 25, 25, 26));
        assertFalse(AddModsPagination.modrinthHasNext(0, 25, 25, 25));
        assertFalse(AddModsPagination.modrinthHasNext(2, 200, 37, 437));
        assertTrue(AddModsPagination.modrinthHasNext(2, 200, 200, 601));
    }

    @Test
    void dedupeKeepsTheFirstOccurrenceInOrder() {
        List<String> ids = Arrays.asList("a", "b", "a", "c", "b", "d");

        assertEquals(Arrays.asList("a", "b", "c", "d"), AddModsPagination.dedupe(ids, Function.identity()));
    }

    @Test
    void dedupeKeepsItemsWithoutAnId() {
        List<String> items = Arrays.asList("x", "y", "z");

        assertEquals(items, AddModsPagination.dedupe(items, item -> null));
    }

    // fake provider: `available` results with ids 0..available-1, recording each request made
    private static BiFunction<Integer, Integer, List<Integer>> provider(int available, List<String> calls) {
        return (offset, limit) -> {
            calls.add(offset + ":" + limit);
            return IntStream.range(offset, Math.min(available, offset + limit)).boxed().collect(Collectors.toList());
        };
    }

    @Test
    void fetchPageCombinesRequestsAndStopsAtAShortOne() {
        List<String> calls = new ArrayList<>();

        AddModsPagination.Page<Integer> page = AddModsPagination.fetchPage(
                AddModsPagination.planRequests(0, 200, CF_MAX, CF_INDEX), provider(120, calls), r -> r,
                r -> 120, Function.identity());

        assertEquals(Arrays.asList("0:50", "50:50", "100:50"), calls);
        assertEquals(120, page.items.size());
        assertEquals(120, page.fetched);
        assertEquals(120, page.total);
        assertEquals(Integer.valueOf(0), page.items.get(0));
        assertEquals(Integer.valueOf(119), page.items.get(119));
    }

    @Test
    void fetchPageReturnsNullIfAnyRequestFails() {
        List<String> calls = new ArrayList<>();
        BiFunction<Integer, Integer, List<Integer>> failSecond = (offset, limit) -> {
            calls.add(offset + ":" + limit);
            return offset == 0 ? Collections.nCopies(limit, 1) : null;
        };

        assertNull(AddModsPagination.fetchPage(AddModsPagination.planRequests(0, 100, CF_MAX, CF_INDEX),
                failSecond, r -> r, r -> 1000, Function.identity()));
        assertEquals(Arrays.asList("0:50", "50:50"), calls);
    }

    @Test
    void fetchPageDedupesAcrossRequestsButCountsWhatWasFetched() {
        // the second request repeats the last result of the first, as can happen when results shift
        BiFunction<Integer, Integer, List<Integer>> overlapping = (offset, limit) -> IntStream
                .range(offset == 0 ? 0 : offset - 1, offset + limit - (offset == 0 ? 0 : 1)).boxed()
                .collect(Collectors.toList());

        AddModsPagination.Page<Integer> page = AddModsPagination.fetchPage(
                AddModsPagination.planRequests(0, 100, CF_MAX, CF_INDEX), overlapping, r -> r, r -> 1000,
                Function.identity());

        assertEquals(100, page.fetched);
        assertEquals(99, page.items.size());
        assertEquals(IntStream.range(0, 99).boxed().collect(Collectors.toList()), page.items);
    }
}
