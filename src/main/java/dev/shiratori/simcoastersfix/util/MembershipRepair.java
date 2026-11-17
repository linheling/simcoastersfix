package dev.shiratori.simcoastersfix.util;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.shiratori.simcoastersfix.FixLimits;
import dev.shiratori.simcoastersfix.Simcoastersfix;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Repairs {@code CoasterCartServerMembership}'s sticky "this is not a cart" answer.
 *
 * <p>The upstream cache only re-classifies a sub-level when {@code readyPlotChunkCount} changes.
 * That number counts plot chunks that have an object, and a chunk object exists before its contents
 * are written, so a sub-level classified during world load stays cached as "not a cart" forever.
 * Measured in a live session: 1008 classifications in the load window, then <em>zero</em> for the
 * rest of the session, while 130 real carts were being reported as defunct link partners.
 *
 * <p>{@code CoasterCartServerMembership.invalidate(sub)} is the upstream answer to "forget what you
 * cached and look again"; every placement, removal and assembly move already calls it. It is
 * package-private, and the class is package-private too, so there is no public door. This class
 * opens it by reflection, once, and degrades to a no-op if the method ever moves:
 *
 * <ul>
 *   <li>the call only happens when a <em>fresh</em> plot scan found a bearing cell, i.e. when we
 *       know upstream's answer is wrong;
 *   <li>the handle is resolved lazily and cached, so the steady-state cost is one invoke per repair;
 *   <li>every failure mode is caught and logged once; the guard keeps working either way, it just
 *       stops correcting the index.
 * </ul>
 *
 * <p>Thread: server thread only (both call sites are the cart-link teardown path).
 */
public final class MembershipRepair {
    private static final String TARGET =
            "dev.silvergold.simulatedcoasters.track.cart.CoasterCartServerMembership";

    private static final AtomicLong REPAIRS = new AtomicLong();

    private static volatile MethodHandle invalidate;
    private static volatile boolean resolved;
    private static volatile boolean broken;

    private MembershipRepair() {
    }

    /** Forgets the cached membership of {@code sub}, so the next query re-scans its plot. */
    public static void heal(ServerSubLevel sub) {
        if (!FixLimits.repairCartMembershipIndex || sub == null || broken) {
            return;
        }
        MethodHandle handle = handle();
        if (handle == null) {
            return;
        }
        try {
            handle.invokeExact(sub);
        } catch (Throwable failure) {
            broken = true;
            Simcoastersfix.LOGGER.warn("[{}] could not invalidate a membership entry, the cart-link guard"
                    + " keeps working but the index stops self-correcting ({})",
                    Simcoastersfix.MODID, failure.getClass().getSimpleName());
            return;
        }
        long count = REPAIRS.incrementAndGet();
        if (count <= 8L || count % 128L == 0L) {
            Simcoastersfix.LOGGER.info("[{}] repaired the cart membership index for {} sub-levels"
                            + " (upstream had it cached as 'not a cart')",
                    Simcoastersfix.MODID, count);
        }
    }

    /** @return the invoker, or {@code null} when the target moved or access was refused. */
    private static MethodHandle handle() {
        MethodHandle cached = invalidate;
        if (resolved) {
            return cached;
        }
        synchronized (MembershipRepair.class) {
            if (resolved) {
                return invalidate;
            }
            resolved = true;
            try {
                Class<?> type = Class.forName(TARGET);
                MethodHandle found = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                        .findStatic(type, "invalidate", MethodType.methodType(void.class, ServerSubLevel.class));
                invalidate = found.asType(MethodType.methodType(void.class, ServerSubLevel.class));
            } catch (Throwable unsupported) {
                broken = true;
                Simcoastersfix.LOGGER.warn("[{}] {}#invalidate is not reachable ({}),"
                                + " the membership index will not self-correct",
                        Simcoastersfix.MODID, TARGET, unsupported.getClass().getSimpleName());
            }
            return invalidate;
        }
    }
}
