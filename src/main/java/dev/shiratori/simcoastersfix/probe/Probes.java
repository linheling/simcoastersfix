package dev.shiratori.simcoastersfix.probe;

import dev.shiratori.simcoastersfix.Simcoastersfix;
import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * Tick-window counters for the hot spots {@code simulatedcoasters} 0.1.6 introduced.
 *
 * <p>Why this exists: 0.1.6 replaced "scan everything every tick" with event-driven member indexes.
 * That is the right fix, but it moves the remaining cost into a different shape &mdash; an
 * O(sub-levels) pass per tick that is cheap per element and invisible in a profiler that only
 * samples self-time. The candidate costs were identified by reading the 0.1.6 bytecode
 * (see {@code UPSTREAM-0.1.6.md} &sect;9) and are <em>not</em> proven; these probes are how they get
 * proven or dropped.
 *
 * <p>Design rules, in order of importance:
 *
 * <ul>
 *   <li><b>Never change behaviour.</b> Every probe only reads {@link System#nanoTime()} and bumps a
 *       counter. No ordering, no short-circuit, no early return.
 *   <li><b>Cheap enough to leave on.</b> Two {@code nanoTime} calls per invocation are ~40 ns; the
 *       counters are plain {@code long}s (a race between the server thread and the client boundary
 *       loses a tick of accuracy, never correctness).
 *   <li><b>Quiet.</b> One log line per window, and nothing at all when no probe fired, so an idle
 *       server stays silent.
 * </ul>
 *
 * <p>Reading the numbers: {@code x} is the call count in the window, the first duration is the
 * total, the second is per call. {@code alloc} is what the whole server thread allocated in the
 * window (via {@code com.sun.management.ThreadMXBean}), which is the only way to see the
 * per-tick {@code new DimensionState(...)} / {@code new HashSet(...)} churn without a GC log.
 */
public final class Probes {
    /** {@code CoasterCartPlotScan.readyPlotChunkCount} — called for every live sub-level, every reconcile. */
    public static final int PLOT_READY_CHUNKS = 0;

    /** {@code CoasterCartServerMembership.reconcile} — the whole per-tick membership pass. */
    public static final int CART_RECONCILE = 1;

    /** {@code CoasterCartServerMembership.scan} — one lookup; hit path does a {@code getBlockState}. */
    public static final int CART_MEMBERSHIP_SCAN = 2;

    /** {@code CoasterCartServerMembership.classify} — a full plot scan; count only. */
    public static final int CART_RECLASSIFY = 3;

    /** {@code RivetPlotMembership.reconcileLive} — the per-tick live-set pass. */
    public static final int RIVET_RECONCILE_LIVE = 4;

    /** {@code RivetPlotMembership.rivetsAndDirty} — per-tick id snapshot + container lookups. */
    public static final int RIVET_RIVETS_AND_DIRTY = 5;

    /** {@code CoasterCartPlotScan.scanBearingCells} — the block-level scan, now expected to be rare. */
    public static final int PLOT_SCAN_CELLS = 6;

    private static final int SLOTS = 7;

    private static final String[] NAMES = {
            "cart.readyChunks",
            "cart.reconcile",
            "cart.membershipScan",
            "cart.reclassify",
            "rivet.reconcileLive",
            "rivet.rivetsAndDirty",
            "plot.scanBearingCells",
    };

    /** Live switch; the config binds it, so it can be turned off without restarting. */
    public static volatile boolean enabled = true;

    /** Ticks per log window; 600 = 30 s at 20 TPS. */
    public static volatile int flushIntervalTicks = 600;

    private static final long[] calls = new long[SLOTS];
    private static final long[] nanos = new long[SLOTS];

    private static long windowTicks;

    private static Object allocBean;
    private static boolean allocBroken;
    private static long allocLastBytes = -1L;
    private static long allocLastNanos;

    private Probes() {
    }

    /** @return the timestamp to hand back to {@link #end(int, long)}. */
    public static long start() {
        return System.nanoTime();
    }

    /** Records one timed invocation. */
    public static void end(int slot, long startNanos) {
        calls[slot]++;
        nanos[slot] += System.nanoTime() - startNanos;
    }

    /** Records one untimed invocation. */
    public static void count(int slot) {
        calls[slot]++;
    }

    /** Called once per server tick, on the server thread. */
    public static void tick() {
        if (!enabled) {
            if (windowTicks != 0L) {
                reset();
            }
            return;
        }
        if (++windowTicks < flushIntervalTicks) {
            return;
        }
        flush();
    }

    private static void flush() {
        long ticks = windowTicks;
        long totalNanos = 0L;
        StringBuilder line = new StringBuilder(192);
        for (int slot = 0; slot < SLOTS; slot++) {
            long count = calls[slot];
            if (count == 0L) {
                continue;
            }
            long spent = nanos[slot];
            totalNanos += spent;
            if (line.length() > 0) {
                line.append(" | ");
            }
            line.append(NAMES[slot]).append(' ').append(count).append("x ").append(millis(spent)).append("ms");
            if (spent > 0L && count > 1L) {
                line.append(' ').append(microsPerCall(spent, count));
            }
        }
        double allocMbPerSec = sampleAllocation();
        if (allocMbPerSec >= 0.0) {
            if (line.length() > 0) {
                line.append(" | ");
            }
            line.append("alloc ").append(decimal(allocMbPerSec)).append("MB/s");
        }
        if (line.length() > 0) {
            if (ticks > 0L && totalNanos > 0L) {
                line.append(" || total ").append(microsPerTick(totalNanos, ticks));
            }
            Simcoastersfix.LOGGER.info("[{}] probes[{}t]: {}", Simcoastersfix.MODID, ticks, line);
        }
        reset();
    }

    private static void reset() {
        for (int slot = 0; slot < SLOTS; slot++) {
            calls[slot] = 0L;
            nanos[slot] = 0L;
        }
        windowTicks = 0L;
    }

    /**
     * @return the server thread's allocation rate in the last window, or {@code -1} when the JVM
     *         cannot answer (non-HotSpot, or the management interface was removed).
     */
    private static double sampleAllocation() {
        if (allocBroken) {
            return -1.0;
        }
        try {
            if (allocBean == null) {
                allocBean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            }
            long bytes = ((com.sun.management.ThreadMXBean) allocBean).getCurrentThreadAllocatedBytes();
            long now = System.nanoTime();
            if (allocLastBytes < 0L) {
                allocLastBytes = bytes;
                allocLastNanos = now;
                return -1.0;
            }
            long deltaBytes = bytes - allocLastBytes;
            long deltaNanos = now - allocLastNanos;
            allocLastBytes = bytes;
            allocLastNanos = now;
            if (deltaNanos <= 0L || deltaBytes < 0L) {
                return -1.0;
            }
            return deltaBytes / (deltaNanos / 1.0e9) / (1024.0 * 1024.0);
        } catch (Throwable unavailable) {
            allocBroken = true;
            allocBean = null;
            return -1.0;
        }
    }

    private static String millis(long nanos) {
        return decimal(nanos / 1.0e6);
    }

    private static String microsPerCall(long nanos, long count) {
        return decimal(nanos / 1000.0 / count) + "us/call";
    }

    private static String microsPerTick(long nanos, long ticks) {
        return decimal(nanos / 1000.0 / ticks) + "us/tick";
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
