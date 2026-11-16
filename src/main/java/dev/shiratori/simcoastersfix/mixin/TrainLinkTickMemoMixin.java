package dev.shiratori.simcoastersfix.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.silvergold.simulatedcoasters.track.cart.CoasterCartResolve;
import dev.silvergold.simulatedcoasters.track.cart.CoasterCartTrainLinkConstraint;
import dev.shiratori.simcoastersfix.FixLimits;
import dev.shiratori.simcoastersfix.util.TickEvalMemo;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Memoises the two {@code CoasterCartResolve.findCartById} calls per active train link inside
 * {@code CoasterCartTrainLinkConstraint.tick}.
 *
 * <p>Per active link the method resolves cart A and cart B, and each resolution goes through the
 * bearing-scan cache plus a container lookup. The same pair is resolved again on the next tick —
 * that part is inherent — but never twice with a different answer inside one invocation, so the
 * second resolution of each UUID is free to drop.
 *
 * <p>The memo is cleared at {@code RETURN} of {@code tick}, so nothing can survive into the next
 * tick (see {@link TickEvalMemo}).
 *
 * <p>The {@code List.copyOf(ACTIVE_LINKS.values())} snapshot at the top of the loop is deliberately
 * left alone: it is what makes unlinking during the loop safe, and a stale snapshot could keep a
 * broken link alive for a tick.
 */
@Mixin(value = CoasterCartTrainLinkConstraint.class, remap = false)
public abstract class TrainLinkTickMemoMixin {
    /** Self-check anchor; the memo storage itself is shared, so there is no live field to look for. */
    @Unique
    private static final boolean simc$trainLinkTickMemo = true;

    @Inject(method = "tick", at = @At("HEAD"))
    private static void simc$memoBegin(SubLevelPhysicsSystem physics, ServerLevel level, CallbackInfo ci) {
        if (FixLimits.trainLinkTickMemo) {
            TickEvalMemo.begin();
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private static void simc$memoEnd(SubLevelPhysicsSystem physics, ServerLevel level, CallbackInfo ci) {
        if (FixLimits.trainLinkTickMemo) {
            TickEvalMemo.end();
        }
    }

    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/silvergold/simulatedcoasters/track/cart/CoasterCartResolve;"
                            + "findCartById(Lnet/minecraft/server/level/ServerLevel;Ljava/util/UUID;)"
                            + "Ldev/ryanhcode/sable/sublevel/ServerSubLevel;"))
    private static ServerSubLevel simc$memoFindCartById(
            ServerLevel level, UUID cartId, Operation<ServerSubLevel> original) {
        if (!FixLimits.trainLinkTickMemo) {
            return original.call(level, cartId);
        }
        Object hit = TickEvalMemo.get(cartId);
        if (hit != TickEvalMemo.MISS) {
            return (ServerSubLevel) TickEvalMemo.unwrap(hit);
        }
        ServerSubLevel resolved = original.call(level, cartId);
        TickEvalMemo.put(cartId, resolved);
        return resolved;
    }
}
