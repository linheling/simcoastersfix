package dev.shiratori.simcoastersfix.probe.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.shiratori.simcoastersfix.probe.Probes;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Probes for {@code RivetPlotMembership} (new in simulatedcoasters 0.1.6).
 *
 * <p>The rivet pass stopped scanning blocks, which was the real win, but it still visits every
 * sub-level once per tick:
 *
 * <ul>
 *   <li>{@code reconcileLive} walks the container, keeps a {@code HashSet} of live ids, prunes three
 *       sets and builds a fresh {@code DimensionState} &mdash; every tick, whether or not anything
 *       changed.
 *   <li>{@code rivetsAndDirty} then snapshots ids into a {@code HashSet} and an {@code ArrayList}
 *       and resolves each one back through the container.
 * </ul>
 *
 * <p>Both are also where the per-tick allocation churn lives; {@code alloc} in the probe line is the
 * cross-check (if the two rivet costs are flat but allocation scales with sub-level count, the
 * records/sets are the cost, not the map lookups).
 */
@Mixin(targets = "dev.silvergold.simulatedcoasters.rivet.RivetPlotMembership", remap = false)
public abstract class RivetProbeMixin {

    /** Self-check anchor: presence means RivetProbeMixin landed on RivetPlotMembership. */
    @Unique
    private static final boolean simc$rivetMembershipProbe = true;
    @WrapMethod(method = "reconcileLive")
    private static void simc$probeReconcileLive(ServerLevel level, Operation<Void> original) {
        if (!Probes.enabled) {
            original.call(level);
            return;
        }
        long started = Probes.start();
        try {
            original.call(level);
        } finally {
            Probes.end(Probes.RIVET_RECONCILE_LIVE, started);
        }
    }

    @WrapMethod(method = "rivetsAndDirty")
    private static List<ServerSubLevel> simc$probeRivetsAndDirty(
            ServerLevel level, Operation<List<ServerSubLevel>> original) {
        if (!Probes.enabled) {
            return original.call(level);
        }
        long started = Probes.start();
        try {
            return original.call(level);
        } finally {
            Probes.end(Probes.RIVET_RIVETS_AND_DIRTY, started);
        }
    }
}
