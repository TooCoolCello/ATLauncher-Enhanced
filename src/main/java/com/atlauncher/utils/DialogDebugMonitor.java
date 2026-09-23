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
package com.atlauncher.utils;

import java.awt.AWTEvent;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.atlauncher.managers.LogManager;

/**
 * Debug-only diagnostics for "invisible modal dialog" and "launcher froze" reports.
 *
 * <p>
 * Those reports look identical to a user: the launcher stops responding. The cause is either a modal dialog
 * that opened behind another window or off-screen (the EDT is fine, input is just blocked), or the Swing Event
 * Dispatch Thread itself being blocked (nothing repaints). This monitor tells the two apart by logging window
 * lifecycle events and by watching how long the EDT takes to run a trivial task.
 *
 * <p>
 * It is debug-only because it logs every window open/close/activation and runs an extra thread, which is noise
 * and overhead nobody needs during normal use. When disabled, {@link #startIfEnabled()} does nothing at all: no
 * AWT listener, no thread, no log output.
 *
 * <p>
 * Enable with {@code -Datlauncher.dialogDebug=true} or the launcher's {@code --debug} argument.
 */
public final class DialogDebugMonitor {
    public static final String PROPERTY = "atlauncher.dialogDebug";

    private static final String PREFIX = "[DialogDebug] ";
    static final long PROBE_INTERVAL_MS = 2000;
    static final long BLOCKED_THRESHOLD_MS = 5000;
    static final long REPORT_COOLDOWN_MS = 30000;
    private static final int MAX_TITLE_LENGTH = 80;
    private static final long NONE = Long.MIN_VALUE;

    private static boolean started = false;

    // nanoTime a probe was posted to the EDT, or NONE when no probe is outstanding
    private static final AtomicLong probePostedAt = new AtomicLong(NONE);
    private static long lastBlockedReportAt = NONE;
    // postedAt of the probe a blocked warning was logged for, or NONE, so recovery is only logged after a warning
    private static final AtomicLong reportedProbe = new AtomicLong(NONE);
    private static volatile Thread edtThread;

    private DialogDebugMonitor() {
    }

    /**
     * Must be called after command line arguments are parsed so {@code --debug} is known.
     */
    public static boolean isEnabled() {
        return Boolean.getBoolean(PROPERTY) || LogManager.showDebug;
    }

    public static synchronized void startIfEnabled() {
        if (started || !isEnabled() || GraphicsEnvironment.isHeadless()) {
            return;
        }
        started = true;

        Toolkit.getDefaultToolkit().addAWTEventListener(e -> logWindowEvent((WindowEvent) e),
                AWTEvent.WINDOW_EVENT_MASK);
        startEdtWatchdog();

        LogManager.info(PREFIX + "Dialog debug monitoring enabled (window events + EDT watchdog)");
    }

    private static void logWindowEvent(WindowEvent e) {
        String type;
        switch (e.getID()) {
            case WindowEvent.WINDOW_OPENED:
                type = "OPENED";
                break;
            case WindowEvent.WINDOW_CLOSING:
                type = "CLOSING";
                break;
            case WindowEvent.WINDOW_CLOSED:
                type = "CLOSED";
                break;
            case WindowEvent.WINDOW_ACTIVATED:
                type = "ACTIVATED";
                break;
            case WindowEvent.WINDOW_DEACTIVATED:
                type = "DEACTIVATED";
                break;
            case WindowEvent.WINDOW_ICONIFIED:
                type = "ICONIFIED";
                break;
            case WindowEvent.WINDOW_DEICONIFIED:
                type = "DEICONIFIED";
                break;
            default:
                // focus/state events duplicate activation/iconify and would only add noise
                return;
        }

        try {
            Window window = e.getWindow();
            Rectangle bounds = window.getBounds();
            Window owner = window.getOwner();

            StringBuilder sb = new StringBuilder(PREFIX).append(type)
                    .append(" window=").append(describe(window));

            if (window instanceof Dialog) {
                sb.append(" modality=").append(((Dialog) window).getModalityType());
            } else {
                sb.append(" modality=n/a");
            }

            sb.append(" owner=").append(owner == null ? "none" : describe(owner))
                    .append(String.format(Locale.ENGLISH, " bounds=[x=%d,y=%d,w=%d,h=%d]", bounds.x, bounds.y,
                            bounds.width, bounds.height))
                    .append(" onScreen=").append(intersectsAnyScreen(bounds, getScreenBounds()))
                    .append(" thread=").append(Thread.currentThread().getName())
                    .append(" edt=").append(EventQueue.isDispatchThread());

            LogManager.info(sb.toString());
        } catch (Throwable t) {
            // diagnostics must never break event dispatch
            LogManager.warn(PREFIX + "Failed to describe window event: " + t);
        }
    }

    private static String describe(Window window) {
        String title = null;
        if (window instanceof Dialog) {
            title = ((Dialog) window).getTitle();
        } else if (window instanceof Frame) {
            title = ((Frame) window).getTitle();
        }

        return window.getClass().getName() + " \"" + sanitizeTitle(title) + "\"";
    }

    /**
     * Titles are shown to users and occasionally contain file names. Anything that looks like a path or URL is
     * redacted, and long titles are truncated.
     */
    static String sanitizeTitle(String title) {
        if (title == null || title.isEmpty()) {
            return "";
        }

        if (title.contains("://") || title.contains("/") || title.contains("\\")) {
            return "<redacted>";
        }

        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) + "..." : title;
    }

    private static List<Rectangle> getScreenBounds() {
        List<Rectangle> screens = new ArrayList<>();
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            screens.add(device.getDefaultConfiguration().getBounds());
        }
        return screens;
    }

    static boolean intersectsAnyScreen(Rectangle bounds, List<Rectangle> screens) {
        for (Rectangle screen : screens) {
            if (screen.intersects(bounds)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The EDT can't report its own freeze, so a separate daemon thread posts a no-op probe every
     * {@link #PROBE_INTERVAL_MS} and checks how long it has been waiting. Only one probe is outstanding at a time
     * so a blocked EDT doesn't accumulate tasks. The watchdog only reads timestamps and thread stacks; it never
     * touches UI state or calls blocking GUI APIs.
     */
    private static void startEdtWatchdog() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "DialogDebug-EDT-Watchdog");
            thread.setDaemon(true);
            return thread;
        });

        executor.scheduleWithFixedDelay(() -> {
            try {
                checkEdt();
            } catch (Throwable t) {
                // an exception would silently cancel the scheduled task
                LogManager.warn(PREFIX + "EDT watchdog check failed: " + t);
            }
        }, PROBE_INTERVAL_MS, PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private static void checkEdt() {
        long now = System.nanoTime();
        long postedAt = probePostedAt.get();

        if (postedAt == NONE) {
            if (probePostedAt.compareAndSet(NONE, now)) {
                EventQueue.invokeLater(() -> {
                    edtThread = Thread.currentThread();
                    long delayMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - now);
                    probePostedAt.compareAndSet(now, NONE);

                    if (claimRecovery(now)) {
                        LogManager.warn(PREFIX + "EDT responsive again after " + delayMs + "ms");
                    }
                });
            }
            return;
        }

        long delayMs = TimeUnit.NANOSECONDS.toMillis(now - postedAt);
        if (delayMs <= BLOCKED_THRESHOLD_MS || !shouldReport(now, lastBlockedReportAt)) {
            return;
        }
        lastBlockedReportAt = now;

        StringBuilder sb = new StringBuilder(PREFIX).append("EDT may be blocked: probe delayed ")
                .append(delayMs).append("ms");

        Thread edt = findEdtThread();
        if (edt == null) {
            sb.append(" (EDT stack trace unavailable)");
        } else {
            sb.append(", EDT thread \"").append(edt.getName()).append("\" state=").append(edt.getState())
                    .append(" stack:");
            for (StackTraceElement element : edt.getStackTrace()) {
                sb.append("\n    at ").append(element);
            }
        }

        LogManager.warn(sb.toString());
        markBlockedReported(postedAt);
    }

    static void markBlockedReported(long postedAt) {
        reportedProbe.set(postedAt);
    }

    /**
     * True, once, if a blocked warning was logged for this probe. Clears the state so each blocked period gets at
     * most one recovery log. A warning for a probe that already completed leaves a stale timestamp that no later
     * probe can match, so it never causes a spurious recovery log.
     */
    static boolean claimRecovery(long postedAt) {
        return reportedProbe.compareAndSet(postedAt, NONE);
    }

    private static Thread findEdtThread() {
        Thread edt = edtThread;
        if (edt != null && edt.isAlive()) {
            return edt;
        }

        // no probe has run yet (or the EDT was replaced), so fall back to the standard EDT thread name
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("AWT-EventQueue")) {
                return thread;
            }
        }
        return null;
    }

    static boolean shouldReport(long nowNanos, long lastReportNanos) {
        return lastReportNanos == NONE
                || TimeUnit.NANOSECONDS.toMillis(nowNanos - lastReportNanos) >= REPORT_COOLDOWN_MS;
    }
}
