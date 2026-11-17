package dev.shiratori.simcoastersfix.blueprint;

import dev.rew1nd.sableschematicapi.api.blueprint.survival.BlueprintBlockCostContext;
import dev.rew1nd.sableschematicapi.api.blueprint.survival.BlueprintBlockCostRule;
import dev.rew1nd.sableschematicapi.api.blueprint.survival.BlueprintBuildBlockPayload;
import dev.rew1nd.sableschematicapi.api.blueprint.survival.CostLine;
import dev.rew1nd.sableschematicapi.api.blueprint.survival.CostQuote;
import dev.rew1nd.sableschematicapi.api.blueprint.survival.CostTiming;
import dev.rew1nd.sableschematicapi.survival.BlueprintBlockCostRules;
import dev.shiratori.simcoastersfix.Simcoastersfix;
import dev.silvergold.simulatedcoasters.SimulatedCoasters;
import dev.silvergold.simulatedcoasters.SimulatedCoastersBlocks;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * Teaches the film printer what a Coasters block costs.
 *
 * <p>Photomancy's default cost rule is one line long: charge the block's item, and charge nothing
 * when {@code block.asItem()} is air. Coasters registers three of its blocks without a {@code BlockItem}
 * — the item is a plain {@code Item} because the block is never placed from the inventory, it is
 * spawned through a sub-level:
 *
 * <ul>
 *   <li>{@code simulatedcoasters:coaster_cart} — placed by the {@code coaster_cart} item, which shrinks
 *       by one per cart ({@code CoasterCartItem}, both placement paths);
 *   <li>{@code simulatedcoasters:rivet} — placed by the {@code rivet} item, one per rivet
 *       ({@code RivetPlacePayload});
 *   <li>{@code simulatedcoasters:red_balloon} — attached by the {@code red_balloon} item, one per balloon
 *       ({@code BalloonAttachInteraction}).
 * </ul>
 *
 * <p>So a blueprint containing a coaster quoted only the vanilla blocks in it, while the printer happily
 * placed the carts, rivets and balloons for free. This rule maps each block back to the item the player
 * would have paid, which is the same one-to-one relationship the mod's own placement code uses.
 *
 * <p>Not covered on purpose: {@code simulatedcoasters:coaster_track_material}. It has no item at all, and
 * one {@code coaster_track} item places a whole bezier of track blocks — charging per block would
 * overcharge by the curve resolution (tens of blocks). That one needs a piece-level rule upstream.
 *
 * <p>Photomancy's cost rules are a public, ordered registry ({@code BlueprintBlockCostRules.register}),
 * and the two built-in compat modules (Create contraptions, Copycats) use exactly this door.
 */
public final class CoasterBlockCostRules {
    private static boolean registered;

    private CoasterBlockCostRules() {
    }

    /** Called once from {@link BlueprintCompat#register()}, after the Photomancy API is known to exist. */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        int applied = 0;
        applied += register(SimulatedCoastersBlocks.COASTER_CART_BLOCK.get(), SimulatedCoasters.COASTER_CART.get(),
                "coaster_cart_block", "simulatedcoasters:coaster_cart");
        applied += register(SimulatedCoastersBlocks.RIVET.get(), SimulatedCoasters.RIVET.get(),
                "rivet_block", "simulatedcoasters:rivet");
        applied += register(SimulatedCoastersBlocks.RED_BALLOON.get(), SimulatedCoasters.RED_BALLOON.get(),
                "red_balloon_block", "simulatedcoasters:red_balloon");
        Simcoastersfix.LOGGER.info("[{}] blueprint cost rules armed: {}/3 Coasters blocks now charge their item",
                Simcoastersfix.MODID, applied);
    }

    private static int register(Block block, Item item, String ruleName, String itemName) {
        if (block == null || item == null || item == Items.AIR) {
            Simcoastersfix.LOGGER.warn("[{}] blueprint cost rule {} skipped: block or item missing ({} -> {})",
                    Simcoastersfix.MODID, ruleName, block, item);
            return 0;
        }
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Simcoastersfix.MODID, ruleName);
        BlueprintBlockCostRules.register(block, new SingleItemRule(id, item));
        Simcoastersfix.LOGGER.debug("[{}] blueprint cost: {} -> {} (block item is {}, so the default rule charged nothing)",
                Simcoastersfix.MODID, blockName(block), itemName, block.asItem() == Items.AIR ? "air" : "present");
        return 1;
    }

    private static String blockName(Block block) {
        return block.builtInRegistryHolder().key().location().toString();
    }

    /** Charges one item per block, matching how Coasters itself consumes the item when placing it. */
    private record SingleItemRule(ResourceLocation id, Item item) implements BlueprintBlockCostRule {
        @Override
        public CostQuote quotePlacement(BlueprintBuildBlockPayload payload, BlueprintBlockCostContext context) {
            if (this.item == Items.AIR) {
                return CostQuote.empty(CostTiming.IMMEDIATE_BLOCK);
            }
            return CostQuote.of(
                    new CostLine(this.id, new ItemStack(this.item), Component.translatable(this.item.getDescriptionId())),
                    CostTiming.IMMEDIATE_BLOCK);
        }

        @Override
        public CostQuote quoteNbtLoad(BlueprintBuildBlockPayload payload, BlueprintBlockCostContext context) {
            return CostQuote.empty(CostTiming.COMMIT);
        }
    }
}
