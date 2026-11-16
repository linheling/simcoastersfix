package dev.shiratori.simcoastersfix.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.trains.track.BezierConnection;
import dev.silvergold.simulatedcoasters.track.CoasterPeerCurveHandleLengths;
import dev.shiratori.simcoastersfix.FixLimits;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Caches {@code CoasterPeerCurveHandleLengths.getLengthAtEndpoint} per connection and endpoint.
 *
 * <p>Why this is the hot path: every curve sample needs both control points, so a build with a
 * coaster track calls this twice per sample, and each call resolves the canonical lengths twice —
 * once directly and once through {@code fallbackSingleHandleLength} → {@code BezierConnection
 * .getHandleLength}, which upstream itself wraps with a mixin that re-enters
 * {@code getCanonicalLengths}. In a measured profile the canonical lookup was the single hottest
 * frame on the server tick thread (21.6% self plus 7.6% building the string key), ahead of the
 * physics step.
 *
 * <p>Each of those lookups costs three map operations: two {@code synchronizedMap(WeakHashMap)} gets
 * plus a freshly built {@code "<long>:<long>"} string key into {@code BY_EDGE}. The value itself only
 * changes when canonical lengths are written, and upstream tracks exactly that with an
 * {@code AtomicLong} revision bumped by every writer. So keying the cache on the revision introduces
 * no time-based staleness — a write is visible on the very next call.
 *
 * <p>Caching here rather than in {@code getCanonicalLengths} keeps the mixin free of
 * {@code net.createmod.catnip.data.Couple}: the wrapped value is a {@code double}, so there is no
 * shared-instance aliasing question at all.
 */
@Mixin(targets = "dev.silvergold.simulatedcoasters.track.CoasterPeerCurveHandleLengths", remap = false)
public abstract class CurveHandleLengthCacheMixin {
    /** Corner handle length per connection, indexed by endpoint; null slot = not computed yet. */
    @Unique
    private static final Map<BezierConnection, Double[]> simc$handleLengths =
            Collections.synchronizedMap(new HashMap<>());

    @Unique
    private static long simc$handleLengthsRevision = Long.MIN_VALUE;

    @WrapMethod(method = "getLengthAtEndpoint")
    private static double simc$cachedLengthAtEndpoint(
            BezierConnection connection, int endpointIndex, Operation<Double> original) {
        if (!FixLimits.curveHandleLengthCache || connection == null || endpointIndex < 0 || endpointIndex > 1) {
            return original.call(connection, endpointIndex);
        }
        // Revision read, invalidation and lookup share one lock, so a value cached under an older
        // revision can never be served after a write bumped it.
        synchronized (simc$handleLengths) {
            long revision = CoasterPeerCurveHandleLengths.revision();
            if (revision != simc$handleLengthsRevision) {
                simc$handleLengths.clear();
                simc$handleLengthsRevision = revision;
            } else {
                Double[] pair = simc$handleLengths.get(connection);
                if (pair != null && pair[endpointIndex] != null) {
                    return pair[endpointIndex];
                }
            }
        }
        double value = original.call(connection, endpointIndex);
        if (Double.isNaN(value)) {
            return value;
        }
        synchronized (simc$handleLengths) {
            // Re-check the revision: a bump between the miss and this store must not mix revisions.
            if (CoasterPeerCurveHandleLengths.revision() != simc$handleLengthsRevision) {
                simc$handleLengths.clear();
                simc$handleLengthsRevision = CoasterPeerCurveHandleLengths.revision();
            }
            simc$handleLengths.computeIfAbsent(connection, k -> new Double[2])[endpointIndex] = value;
        }
        return value;
    }
}
