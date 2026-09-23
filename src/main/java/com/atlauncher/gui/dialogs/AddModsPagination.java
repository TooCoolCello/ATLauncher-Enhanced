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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Paging for the Add Mods dialog. One logical page (the user's chosen page size) can be larger than a provider
 * allows in a single search request, so it is fetched as several sequential requests and combined. Pure logic with
 * no Swing or network access, so it can be unit tested.
 */
final class AddModsPagination {
    private AddModsPagination() {
    }

    static final class Request {
        final int offset;
        final int limit;

        Request(int offset, int limit) {
            this.offset = offset;
            this.limit = limit;
        }
    }

    static final class Page<T> {
        final List<T> items;
        // results returned by the provider, before de-duplication
        final int fetched;
        // provider's total result count, or -1 if it didn't report one
        final int total;

        Page(List<T> items, int fetched, int total) {
            this.items = items;
            this.fetched = fetched;
            this.total = total;
        }
    }

    /**
     * The requests needed to fetch one logical page, assuming each request is filled. No request goes past
     * maxIndex (offset + limit), so a page starting at or beyond it has no requests.
     */
    static List<Request> planRequests(int page, int pageSize, int providerMax, int maxIndex) {
        List<Request> requests = new ArrayList<>();
        int logicalStart = page * pageSize;

        int planned = 0;
        while (planned < pageSize) {
            int offset = logicalStart + planned;
            int limit = Math.min(providerMax, Math.min(pageSize - planned, maxIndex - offset));
            if (limit <= 0) {
                break;
            }

            requests.add(new Request(offset, limit));
            planned += limit;
        }

        return requests;
    }

    /**
     * Runs the planned requests in order, stopping early when one returns fewer results than asked for. Returns
     * null if any request fails, so a partial page is never shown as a complete result.
     */
    static <R, T, K> Page<T> fetchPage(List<Request> requests, BiFunction<Integer, Integer, R> search,
            Function<R, List<T>> itemsOf, ToIntFunction<R> totalOf, Function<T, K> idOf) {
        List<T> results = new ArrayList<>();
        int total = -1;

        for (Request request : requests) {
            R response = search.apply(request.offset, request.limit);
            List<T> items = response == null ? null : itemsOf.apply(response);
            if (items == null) {
                return null;
            }

            results.addAll(items);
            total = totalOf.applyAsInt(response);

            if (items.size() < request.limit) {
                break;
            }
        }

        return new Page<>(dedupe(results, idOf), results.size(), total);
    }

    /**
     * Drops later results with an id already seen (results can shift between requests), keeping provider order.
     * Results without an id are always kept.
     */
    static <T, K> List<T> dedupe(List<T> items, Function<T, K> idOf) {
        Set<K> seen = new HashSet<>();
        List<T> unique = new ArrayList<>();
        for (T item : items) {
            K id = idOf.apply(item);
            if (id == null || seen.add(id)) {
                unique.add(item);
            }
        }
        return unique;
    }

    /**
     * CurseForge can't search past maxIndex, so there's no next page once it would start there, whatever the
     * total says. Without a reported total, a full page is taken to mean there may be more.
     */
    static boolean curseForgeHasNext(int page, int pageSize, int fetched, int totalCount, int maxIndex) {
        int nextStart = (page + 1) * pageSize;
        if (nextStart >= maxIndex) {
            return false;
        }

        return totalCount >= 0 ? nextStart < totalCount : fetched == pageSize;
    }

    static boolean modrinthHasNext(int page, int pageSize, int fetched, int totalHits) {
        return page * pageSize + fetched < totalHits;
    }
}
