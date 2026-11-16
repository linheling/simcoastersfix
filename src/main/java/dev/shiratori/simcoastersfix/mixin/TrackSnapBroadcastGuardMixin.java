package dev.shiratori.simcoastersfix.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.silvergold.simulatedcoasters.track.cart.CoasterCartTrackSnap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CoasterCartTrackSnap.class, remap = false)
public abstract class TrackSnapBroadcastGuardMixin {
    @Unique
    private static final Set<UUID> simc$teardownDone = ConcurrentHashMap.newKeySet();

    @WrapOperation(
            method = "onPrePhysicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/silvergold/simulatedcoasters/track/cart/CoasterCartTrackSnap;"
                            + "representativeBearingPlotPos(Ldev/ryanhcode/sable/sublevel/SubLevel;)Lnet/minecraft/core/BlockPos;"))
    private static BlockPos simc$trackCartMembership(SubLevel sub, Operation<BlockPos> original) {
        BlockPos bearing = original.call(sub);
        if (bearing != null) {
            simc$teardownDone.remove(sub.getUniqueId());
        }
        return bearing;
    }
    @WrapOperation(
            method = "onPrePhysicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/silvergold/simulatedcoasters/track/cart/CoasterCartTrainLinkConstraint;"
                            + "onCartRemoved(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;)V"))
    private static void simc$oncePerTransition(ServerSubLevel sub, Operation<Void> original) {
        if (simc$teardownDone.add(sub.getUniqueId())) {
            original.call(sub);
        }
    }
}
