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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.atlauncher.data.modrinth.ModrinthSearchHit;
import com.atlauncher.data.modrinth.ModrinthVersion;
import com.atlauncher.managers.LogManager;
import com.atlauncher.utils.ModrinthApi;

/**
 * Checks Modrinth search results for one dialog: what the result itself settles right away, the rest by fetching the
 * project's versions for this Minecraft version in the background, a few at a time. What's fetched is kept until the
 * dialog closes and judged again on each use, so installing Connector meanwhile is taken into account.
 */
public final class ModCompatibilityChecker {
    // 4 at a time like Edit Mods' update checks
    private static final int THREADS = 4;
    // ponytail: one request per 250 ms across every open checker (240 a minute), under Modrinth's ~300 a minute with
    // room for searches, so paging quickly doesn't turn results into "couldn't check". A 200-result page takes under a
    // minute; read X-Ratelimit-Remaining instead if that's too slow.
    private static final long REQUEST_SPACING_MS = 250;
    private static long nextRequestAt = 0;

    // fetched files per project and section; failures aren't kept, so they're tried again next time
    private final Map<String, List<ModCompatibility.FileFacts>> fetched = new ConcurrentHashMap<>();
    private ExecutorService executor;
    private boolean shutDown = false;

    /**
     * The verdict known now. When it's {@link ModCompatibility.State#CHECKING}, the versions are fetched in the
     * background and {@code later} gets the final verdict on a background thread, unless {@code wanted} says the
     * result is no longer shown by then. Null when nothing is checked for this section.
     */
    public ModCompatibility.Verdict modrinth(ModCompatibility.Target target, String section, ModrinthSearchHit hit,
            BooleanSupplier wanted, Consumer<ModCompatibility.Verdict> later) {
        ModCompatibility.Verdict known = ModCompatibility.modrinthProjectLevel(target, section, hit.versions,
                hit.categories);
        if (known == null || known.state != ModCompatibility.State.CHECKING || hit.projectId == null) {
            return known;
        }

        String key = hit.projectId + "|" + section;
        List<ModCompatibility.FileFacts> facts = fetched.get(key);
        if (facts != null) {
            return ModCompatibility.forModrinthFacts(target, section, facts);
        }

        try {
            executor().execute(() -> {
                if (!wanted.getAsBoolean()) {
                    return;
                }

                List<ModCompatibility.FileFacts> found = fetch(target, section, hit, key, wanted);
                if (found != null || wanted.getAsBoolean()) {
                    later.accept(ModCompatibility.forModrinthFacts(target, section, found));
                }
            });
        } catch (RejectedExecutionException e) {
            // the dialog closed
            return ModCompatibility.checkFailed(target, section);
        }

        return known;
    }

    private List<ModCompatibility.FileFacts> fetch(ModCompatibility.Target target, String section,
            ModrinthSearchHit hit, String key, BooleanSupplier wanted) {
        List<ModCompatibility.FileFacts> facts = fetched.get(key);
        if (facts != null) {
            return facts;
        }

        try {
            waitForTurn();
        } catch (InterruptedException e) {
            // the dialog closed
            Thread.currentThread().interrupt();
            return null;
        }
        // the page may have changed while waiting
        if (!wanted.getAsBoolean()) {
            return null;
        }

        try {
            List<ModrinthVersion> versions = ModrinthApi.getVersionsForLoaders(hit.projectId,
                    target.minecraftVersion, ModCompatibility.modrinthLoaderFilter(target, section));
            if (versions == null) {
                return null;
            }

            facts = ModCompatibility.modrinthFacts(versions);
            fetched.put(key, facts);
            return facts;
        } catch (RuntimeException e) {
            LogManager.logStackTrace("Error checking " + hit.projectId + " for compatible versions", e, false);
            return null;
        }
    }

    /**
     * Takes the next Modrinth request slot and sleeps until it comes round. Shared by everything that sends Modrinth
     * requests in bulk, so together they stay under its rate limit.
     */
    public static void waitForTurn() throws InterruptedException {
        long wait;
        synchronized (ModCompatibilityChecker.class) {
            long now = System.currentTimeMillis();
            long slot = Math.max(now, nextRequestAt);
            nextRequestAt = slot + REQUEST_SPACING_MS;
            wait = slot - now;
        }
        if (wait > 0) {
            Thread.sleep(wait);
        }
    }

    private synchronized ExecutorService executor() {
        if (shutDown) {
            throw new RejectedExecutionException();
        }

        if (executor == null) {
            executor = Executors.newFixedThreadPool(THREADS, r -> {
                Thread thread = new Thread(r, "Add Mods compatibility checks");
                thread.setDaemon(true);
                return thread;
            });
        }
        return executor;
    }

    /**
     * Stops checking, dropping whatever is still queued. Call it when the dialog closes.
     */
    public synchronized void shutdown() {
        shutDown = true;
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
