package dev.shiratori.simcoastersfix.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.silvergold.simulatedcoasters.rivet.RivetDisassemblyEnforcer;
import dev.shiratori.simcoastersfix.FixLimits;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = RivetDisassemblyEnforcer.class, remap = false)
public abstract class RivetScanThrottleMixin {
    @Unique
    private static final Map<ServerSubLevel, Long> simc$lastRivetCheck =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Unique
    private static final Map<ServerSubLevel, Long> simc$lastSelfHostCheck =
            Collections.synchronizedMap(new WeakHashMap<>());

    @WrapOperation(
            method = "onPrePhysicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/silvergold/simulatedcoasters/rivet/RivetDisassemblyEnforcer;"
                            + "enforceSingleRivet(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;)Z"))
    private static boolean simc$throttleEnforceSingleRivet(ServerSubLevel sub, Operation<Boolean> original) {
        return simc$due(simc$lastRivetCheck, sub, FixLimits.rivetIntervalTicks) ? original.call(sub) : false;
    }
    @WrapOperation(
            method = "onPrePhysicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/silvergold/simulatedcoasters/rivet/RivetDisassemblyEnforcer;"
                            + "popIfSelfHosted(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;)V"))
    private static void simc$throttlePopIfSelfHosted(ServerSubLevel sub, Operation<Void> original) {
        if (simc$due(simc$lastSelfHostCheck, sub, FixLimits.selfHostIntervalTicks)) {
            original.call(sub);
        }
    }
    @Unique
    private static boolean simc$due(Map<ServerSubLevel, Long> lastSeen, ServerSubLevel sub, int period) {
        if (period <= 1) {
            return true;
        }
        ServerLevel level = sub.getLevel();
        if (level == null) {
            return true;
        }
        long now = level.getGameTime();
        Long last = lastSeen.get(sub);
        if (last != null && now >= last && now - last < period) {
            return false;
        }
        lastSeen.put(sub, now);
        return true;
    }
}
