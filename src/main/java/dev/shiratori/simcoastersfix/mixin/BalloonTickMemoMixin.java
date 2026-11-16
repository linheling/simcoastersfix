package dev.shiratori.simcoastersfix.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.silvergold.simulatedcoasters.balloon.BalloonBlockEntity;
import dev.shiratori.simcoastersfix.FixLimits;
import dev.shiratori.simcoastersfix.util.TickEvalMemo;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Memoises the repeated balloon lookups inside {@code BalloonTetherController.onPrePhysicsTick}.
 *
 * <p>The method walks the active-balloon set twice: once to total up per-host support, once to apply
 * the forces. Both passes resolve the balloon sub-level by UUID
 * ({@code BalloonResolve.findBalloonById}), then the balloon's block entity, then that block
 * entity's host sub-level. With N balloons that is 2N + 2N + 2N lookups per physics tick where N + N
 * is enough.
 *
 * <p>Behaviour is identical: the memo lives exactly as long as one invocation of the hook
 * (see {@link TickEvalMemo}), and nothing inside the hook mutates the sub-level container or the
 * block entities, so a second lookup in the same invocation cannot have a different answer.
 *
 * <p>Not changed here on purpose: the two {@code level.clip()} raycasts per balloon
 * ({@code groundHoverFactor}) and the ~20 vector/quaternion allocations per balloon. Both could be
 * cut, but both feed the physics feedback loop, so they would change hover behaviour — they need
 * in-game validation, not a blind patch.
 */
@Mixin(targets = "dev.silvergold.simulatedcoasters.balloon.BalloonTetherController", remap = false)
public abstract class BalloonTickMemoMixin {
    /** Self-check anchor; the memo storage itself is shared, so there is no live field to look for. */
    @Unique
    private static final boolean simc$balloonTickMemo = true;

    /** Self-check anchor for the physics-pipeline part of the memo. */
    @Unique
    private static final boolean simc$balloonPhysicsMemo = true;

    @Inject(method = "onPrePhysicsTick", at = @At("HEAD"))
    private static void simc$memoBegin(ForgeSablePrePhysicsTickEvent event, CallbackInfo ci) {
        if (FixLimits.balloonTickMemo) {
            TickEvalMemo.begin();
        }
    }

    @Inject(method = "onPrePhysicsTick", at = @At("RETURN"))
    private static void simc$memoEnd(ForgeSablePrePhysicsTickEvent event, CallbackInfo ci) {
        if (FixLimits.balloonTickMemo) {
            TickEvalMemo.end();
        }
    }

    @WrapOperation(
            method = "onPrePhysicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/silvergold/simulatedcoasters/balloon/BalloonResolve;"
                            + "findBalloonById(Lnet/minecraft/server/level/ServerLevel;Ljava/util/UUID;)"
                            + "Ldev/ryanhcode/sable/sublevel/ServerSubLevel;"))
    private static ServerSubLevel simc$memoFindBalloonById(
            ServerLevel level, UUID balloonId, Operation<ServerSubLevel> original) {
        if (!FixLimits.balloonTickMemo) {
            return original.call(level, balloonId);
        }
        Object hit = TickEvalMemo.get(balloonId);
        if (hit != TickEvalMemo.MISS) {
            return (ServerSubLevel) TickEvalMemo.unwrap(hit);
        }
        ServerSubLevel resolved = original.call(level, balloonId);
        TickEvalMemo.put(balloonId, resolved);
        return resolved;
    }

    @WrapOperation(
            method = "onPrePhysicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/silvergold/simulatedcoasters/balloon/BalloonTetherController;"
                            + "balloonBlockEntity(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;)"
                            + "Ldev/silvergold/simulatedcoasters/balloon/BalloonBlockEntity;"))
    private static BalloonBlockEntity simc$memoBalloonBlockEntity(
            ServerSubLevel balloon, Operation<BalloonBlockEntity> original) {
        if (!FixLimits.balloonTickMemo) {
            return original.call(balloon);
        }
        Object hit = TickEvalMemo.get(balloon);
        if (hit != TickEvalMemo.MISS) {
            return (BalloonBlockEntity) TickEvalMemo.unwrap(hit);
        }
        BalloonBlockEntity resolved = original.call(balloon);
        TickEvalMemo.put(balloon, resolved);
        return resolved;
    }

    @WrapOperation(
            method = "onPrePhysicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/silvergold/simulatedcoasters/balloon/BalloonBlockEntity;"
                            + "resolveHostSubLevel(Lnet/minecraft/server/level/ServerLevel;)"
                            + "Ldev/ryanhcode/sable/sublevel/ServerSubLevel;"))
    private static ServerSubLevel simc$memoResolveHost(
            BalloonBlockEntity blockEntity, ServerLevel level, Operation<ServerSubLevel> original) {
        if (!FixLimits.balloonTickMemo) {
            return original.call(blockEntity, level);
        }
        Object hit = TickEvalMemo.get(blockEntity);
        if (hit != TickEvalMemo.MISS) {
            return (ServerSubLevel) TickEvalMemo.unwrap(hit);
        }
        ServerSubLevel resolved = original.call(blockEntity, level);
        TickEvalMemo.put(blockEntity, resolved);
        return resolved;
    }

    /**
     * Collapses the repeated physics-pipeline velocity reads inside one balloon hook.
     *
     * <p>The pipeline only <em>queues</em> the forces and impulses this hook applies; the body's
     * velocity changes at the step, not at the call. So every read of the same body inside one
     * {@code onPrePhysicsTick} returns the same value, and {@code applyTetherSpring} alone reads it
     * four times (balloon linear/angular, host linear/angular) while {@code dampHostSwing} and
     * {@code dampHostVerticalInBand} read the host again. Each of those is a pipeline round trip.
     *
     * <p>The memo hands back a <em>copy</em> into the caller's buffer rather than a shared instance:
     * callers do mutate the buffer, and aliasing a shared vector would be a physics bug. Only the
     * round trip is saved.
     *
     * <p>One annotation per (method, descriptor): MixinExtras wraps <em>every</em> matching
     * invocation inside the named method, so the balloon and host reads in {@code applyTetherSpring}
     * are covered by the same pair of handlers. Five call sites, five annotations — the duplicate
     * {@code wakeUp} calls are deliberately left alone, there is no evidence they matter and each
     * extra injector is another way for an upstream update to break the load.
     */
    @WrapOperation(
            method = "applyTetherSpring",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/PhysicsPipeline;"
                            + "getLinearVelocity(Ldev/ryanhcode/sable/api/physics/PhysicsPipelineBody;"
                            + "Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"))
    private static Vector3d simc$tetherSpringLinear(
            PhysicsPipeline pipeline, PhysicsPipelineBody body, Vector3d destination, Operation<Vector3d> original) {
        return simc$memoLinear(pipeline, body, destination, original);
    }

    @WrapOperation(
            method = "applyTetherSpring",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/PhysicsPipeline;"
                            + "getAngularVelocity(Ldev/ryanhcode/sable/api/physics/PhysicsPipelineBody;"
                            + "Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"))
    private static Vector3d simc$tetherSpringAngular(
            PhysicsPipeline pipeline, PhysicsPipelineBody body, Vector3d destination, Operation<Vector3d> original) {
        return simc$memoAngular(pipeline, body, destination, original);
    }

    @WrapOperation(
            method = "dampHostSwing",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/PhysicsPipeline;"
                            + "getLinearVelocity(Ldev/ryanhcode/sable/api/physics/PhysicsPipelineBody;"
                            + "Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"))
    private static Vector3d simc$dampSwingLinear(
            PhysicsPipeline pipeline, PhysicsPipelineBody body, Vector3d destination, Operation<Vector3d> original) {
        return simc$memoLinear(pipeline, body, destination, original);
    }

    @WrapOperation(
            method = "dampHostSwing",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/PhysicsPipeline;"
                            + "getAngularVelocity(Ldev/ryanhcode/sable/api/physics/PhysicsPipelineBody;"
                            + "Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"))
    private static Vector3d simc$dampSwingAngular(
            PhysicsPipeline pipeline, PhysicsPipelineBody body, Vector3d destination, Operation<Vector3d> original) {
        return simc$memoAngular(pipeline, body, destination, original);
    }

    @WrapOperation(
            method = "dampHostVerticalInBand",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/PhysicsPipeline;"
                            + "getLinearVelocity(Ldev/ryanhcode/sable/api/physics/PhysicsPipelineBody;"
                            + "Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"))
    private static Vector3d simc$dampVerticalLinear(
            PhysicsPipeline pipeline, PhysicsPipelineBody body, Vector3d destination, Operation<Vector3d> original) {
        return simc$memoLinear(pipeline, body, destination, original);
    }

    @Unique
    private static Vector3d simc$memoLinear(
            PhysicsPipeline pipeline, PhysicsPipelineBody body, Vector3d destination, Operation<Vector3d> original) {
        if (!FixLimits.balloonPhysicsMemo || !TickEvalMemo.active()) {
            return original.call(pipeline, body, destination);
        }
        Object hit = TickEvalMemo.getSlot(TickEvalMemo.SLOT_LINEAR_VELOCITY, body);
        if (hit != TickEvalMemo.MISS) {
            destination.set((Vector3d) TickEvalMemo.unwrap(hit));
            return destination;
        }
        Vector3d value = original.call(pipeline, body, destination);
        TickEvalMemo.putSlot(TickEvalMemo.SLOT_LINEAR_VELOCITY, body, new Vector3d(value));
        return value;
    }

    @Unique
    private static Vector3d simc$memoAngular(
            PhysicsPipeline pipeline, PhysicsPipelineBody body, Vector3d destination, Operation<Vector3d> original) {
        if (!FixLimits.balloonPhysicsMemo || !TickEvalMemo.active()) {
            return original.call(pipeline, body, destination);
        }
        Object hit = TickEvalMemo.getSlot(TickEvalMemo.SLOT_ANGULAR_VELOCITY, body);
        if (hit != TickEvalMemo.MISS) {
            destination.set((Vector3d) TickEvalMemo.unwrap(hit));
            return destination;
        }
        Vector3d value = original.call(pipeline, body, destination);
        TickEvalMemo.putSlot(TickEvalMemo.SLOT_ANGULAR_VELOCITY, body, new Vector3d(value));
        return value;
    }
}
