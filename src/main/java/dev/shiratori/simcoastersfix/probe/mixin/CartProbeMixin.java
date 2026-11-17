package dev.shiratori.simcoastersfix.probe.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.shiratori.simcoastersfix.probe.Probes;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.silvergold.simulatedcoasters.track.cart.CoasterCartPlotScan;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Probes for {@code CoasterCartServerMembership} (new in simulatedcoasters 0.1.6).
 *
 * <p>Three questions, one per handler:
 *
 * <ul>
 *   <li>{@code reconcile} &mdash; how much of the tick does the whole membership pass take, and how
 *       often does it run at all ({@code entryFor} does not trigger it, {@code forEachCart} and
 *       {@code cartMap} do; the second caller usually hits the "already reconciled this tick"
 *       early-out).
 *   <li>{@code scan} &mdash; the per-cart lookup that every {@code representativeBearingPlotPos}
 *       call lands on. On a cache hit it still validates the representative block with a live
 *       {@code getBlockState}, so this is where N carts turn into N world reads per tick.
 *   <li>{@code classify} &mdash; count only. Each one is a full plot scan, so the count times the
 *       {@code plot.scanBearingCells} average is the re-classification cost. A count that does not
 *       fall to ~0 on a settled world means the dirty/ready-chunk logic is thrashing.
 * </ul>
 *
 * <p>All three are pure observation: no argument is inspected, no result is altered.
 */
@Mixin(targets = "dev.silvergold.simulatedcoasters.track.cart.CoasterCartServerMembership", remap = false)
public abstract class CartProbeMixin {

    /** Self-check anchor: presence means CartProbeMixin landed on CoasterCartServerMembership. */
    @Unique
    private static final boolean simc$cartMembershipProbe = true;
    @WrapMethod(method = "reconcile")
    private static void simc$probeReconcile(ServerLevel level, Operation<Void> original) {
        if (!Probes.enabled) {
            original.call(level);
            return;
        }
        long started = Probes.start();
        try {
            original.call(level);
        } finally {
            Probes.end(Probes.CART_RECONCILE, started);
        }
    }

    @WrapMethod(method = "scan")
    private static CoasterCartPlotScan.BearingScanResult simc$probeMembershipScan(
            ServerSubLevel sub, Operation<CoasterCartPlotScan.BearingScanResult> original) {
        if (!Probes.enabled) {
            return original.call(sub);
        }
        long started = Probes.start();
        try {
            return original.call(sub);
        } finally {
            Probes.end(Probes.CART_MEMBERSHIP_SCAN, started);
        }
    }

    @Inject(method = "classify", at = @At("HEAD"))
    private static void simc$countReclassify(
            ServerLevel level, ServerSubLevel sub, Block bearingBlock, CallbackInfoReturnable<Object> cir) {
        if (Probes.enabled) {
            Probes.count(Probes.CART_RECLASSIFY);
        }
    }
}
