package dev.shiratori.simcoastersfix.mixin;

import dev.silvergold.simulatedcoasters.track.cart.CoasterCartTrainLinkConstraint;
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

@Mixin(value = CoasterCartTrainLinkConstraint.class, remap = false)
public abstract class TrainLinkRestoreThrottleMixin {
    @Unique
    private static final Map<ResourceKey<Level>, Long> simc$lastRestore = new ConcurrentHashMap<>();

    @Inject(method = "maybeRestorePersistedLinks", at = @At("HEAD"), cancellable = true)
    private static void simc$throttleRestore(ServerLevel level, CallbackInfo ci) {
        int period = FixLimits.linkRestoreIntervalTicks;
        if (period <= 1) {
            return;
        }
        long now = level.getGameTime();
        ResourceKey<Level> dimension = level.dimension();
        Long last = simc$lastRestore.get(dimension);
        if (last != null && now >= last && now - last < period) {
            ci.cancel();
            return;
        }
        simc$lastRestore.put(dimension, now);
    }
}
