package dev.shiratori.simcoastersfix.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.silvergold.simulatedcoasters.track.cart.CoasterCartPlotScan;
import dev.shiratori.simcoastersfix.FixLimits;
import dev.shiratori.simcoastersfix.util.CachedScan;
import dev.shiratori.simcoastersfix.util.CartMembership;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "dev.silvergold.simulatedcoasters.track.cart.CoasterCartBearingTickCache", remap = false)
public abstract class BearingScanThrottleMixin {
    @Unique
    private static final Map<ServerSubLevel, CachedScan> simc$scans =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Inject(method = "scan", at = @At("HEAD"), cancellable = true)
    private static void simc$reuseCachedScan(ServerSubLevel sub,
                                             CallbackInfoReturnable<CoasterCartPlotScan.BearingScanResult> cir) {
        ServerLevel level = sub.getLevel();
        if (level == null) {
            return;
        }
        CachedScan cached = simc$scans.get(sub);
        if (cached == null) {
            return;
        }
        long now = level.getGameTime();

        if (now >= cached.gameTime() && now - cached.gameTime() < cached.ttl()) {
            cir.setReturnValue(cached.result());
        }
    }
    @Inject(method = "scan", at = @At("RETURN"))
    private static void simc$rememberScan(ServerSubLevel sub,
                                          CallbackInfoReturnable<CoasterCartPlotScan.BearingScanResult> cir) {
        ServerLevel level = sub.getLevel();
        CoasterCartPlotScan.BearingScanResult result = cir.getReturnValue();
        if (level == null || result == null) {
            return;
        }
        if (result.bearingCount() == 0 && !CartMembership.plotChunksLoaded(sub)) {
            // A plot with no chunk object cannot answer "this sub-level carries no cart": the
            // sub-level is in the container, its plot data is not here yet. Caching that negative
            // would make the cart look removed for the whole TTL, and the train-link code deletes
            // the coupling (persisted NBT included) when it sees a cart disappear.
            simc$scans.remove(sub);
            return;
        }
        long ttl = result.bearingCount() > 0 ? FixLimits.bearingTtlWithCart : FixLimits.bearingTtlEmpty;
        simc$scans.put(sub, new CachedScan(level.getGameTime(), ttl, result));
    }
    @Inject(method = "invalidate", at = @At("HEAD"))
    private static void simc$dropOnInvalidate(ServerSubLevel sub, CallbackInfo ci) {
        simc$scans.remove(sub);
    }
    @Inject(method = "remove", at = @At("HEAD"))
    private static void simc$dropOnRemove(ServerSubLevel sub, CallbackInfo ci) {
        simc$scans.remove(sub);
    }
    @Inject(method = "clearDimension", at = @At("HEAD"))
    private static void simc$dropOnClearDimension(ServerLevel level, CallbackInfo ci) {
        simc$scans.keySet().removeIf(sub -> sub.getLevel() == level);
    }
}
