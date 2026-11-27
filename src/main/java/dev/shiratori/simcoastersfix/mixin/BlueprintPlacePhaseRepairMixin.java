package dev.shiratori.simcoastersfix.mixin;

import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintPlacePhase;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintPlaceSession;
import dev.shiratori.simcoastersfix.blueprint.BlueprintPlaceRepair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Repairs unwritten blueprint blocks at the one moment it costs nothing.
 *
 * <p>Photomancy suspends a place into phases. Right after {@code PLACE_BLOCK_STATES} every block
 * state has been written, and right before {@code LOAD_BLOCK_ENTITIES} no block entity exists yet.
 * That is the only point where a cell the placer recorded without writing can be filled in and still
 * get its block entity - with the blueprint's own NBT and through the mapper hooks - from Photomancy's
 * own pass.</p>
 *
 * <p>See {@link BlueprintPlaceRepair} for what goes wrong before this hook runs. {@code require = 0}
 * plus a mixin config that is not required: if Photomancy renames the phase call, this quietly does
 * nothing and the late fallback in the place event still repairs the block states.</p>
 */
@Mixin(value = BlueprintPlaceSession.class, remap = false)
public abstract class BlueprintPlacePhaseRepairMixin {
    @Inject(method = "setPhase", at = @At("HEAD"), remap = false, require = 0)
    private void simcoastersfix$repairBeforeBlockEntities(BlueprintPlacePhase phase, CallbackInfo ci) {
        if (phase == BlueprintPlacePhase.LOAD_BLOCK_ENTITIES) {
            BlueprintPlaceRepair.beforeBlockEntities((BlueprintPlaceSession) (Object) this);
        }
    }
}
