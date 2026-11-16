package dev.shiratori.simcoastersfix.mixin;

import dev.shiratori.simcoastersfix.FixLimits;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Throttles {@code BalloonTetherController.maybeRestoreTethers}.
 *
 * <p>Upstream guards that method with {@code last != gameTime} — the same "per-tick value treated
 * as a once-only flag" mistake this mod already fixes for
 * {@code CoasterCartTrainLinkConstraint.maybeRestorePersistedLinks}. Because {@code gameTime}
 * advances every tick, the guard is always true, so {@code restoreTethers(level)} walks
 * <em>every</em> sub-level on <em>every</em> physics tick (one {@code getBlockState(ZERO)} plus an
 * {@code ACTIVE.containsKey} each) to re-register balloons that are normally already registered
 * through the sub-level observer and the load reconciler.
 *
 * <p>Cancelling the call is safe: it only decides whether the safety-net scan runs this tick. The
 * upstream map is untouched, so when we do let the call through its own guard still fires. Worst
 * case a balloon that never went through the observer takes one interval longer to be tethered.
 */
@Mixin(targets = "dev.silvergold.simulatedcoasters.balloon.BalloonTetherController", remap = false)
public abstract class BalloonRestoreThrottleMixin {
    @Unique
    private static final Map<ResourceKey<Level>, Long> simc$balloonRestoreAt = new ConcurrentHashMap<>();

    @Inject(method = "maybeRestoreTethers", at = @At("HEAD"), cancellable = true)
    private static void simc$throttleRestoreTethers(ServerLevel level, CallbackInfo ci) {
        if (!FixLimits.balloonRestoreThrottle) {
            return;
        }
        int period = FixLimits.balloonRestoreIntervalTicks;
        if (period <= 1) {
            return;
        }
        long now = level.getGameTime();
        ResourceKey<Level> dimension = level.dimension();
        Long last = simc$balloonRestoreAt.get(dimension);
        if (last != null && now >= last && now - last < period) {
            ci.cancel();
            return;
        }
        simc$balloonRestoreAt.put(dimension, now);
    }
}
