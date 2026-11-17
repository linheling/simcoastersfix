package dev.shiratori.simcoastersfix.blueprint;

import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintEventRegistry;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintMapperRegistry;
import dev.shiratori.simcoastersfix.Simcoastersfix;
import dev.silvergold.simulatedcoasters.SimulatedCoastersBlocks;

/**
 * Entry point for the Sable Photomancy bridge.
 *
 * <p>Everything in this package touches {@code sable_schematic_api} classes, so this class is only
 * ever loaded when that mod is present (see {@code Simcoastersfix#commonSetup}). It registers two
 * block mappers (rivet and balloon host references), the save/place event hook (cart train couplings)
 * and the block cost rules that make the film printer charge for Coasters blocks.
 */
public final class BlueprintCompat {
    private static boolean registered;

    private BlueprintCompat() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        try {
            SableBlueprintMapperRegistry.register(SimulatedCoastersBlocks.RIVET_BE.get(), new RivetHostBlueprintMapper());
            SableBlueprintMapperRegistry.register(SimulatedCoastersBlocks.BALLOON_BE.get(), new BalloonHostBlueprintMapper());
            SableBlueprintEventRegistry.register(new CoasterBlueprintEvent());
            CoasterBlockCostRules.register();
            Simcoastersfix.LOGGER.info(
                    "[{}] blueprint compatibility armed: rivet host, balloon host, cart train links",
                    Simcoastersfix.MODID);
        } catch (RuntimeException | LinkageError e) {
            // A Photomancy update that moves this API must not take the performance patch down with it.
            Simcoastersfix.LOGGER.error(
                    "[{}] blueprint compatibility NOT registered (Coasters/Photomancy version mismatch?)",
                    Simcoastersfix.MODID, e);
        }
    }
}
