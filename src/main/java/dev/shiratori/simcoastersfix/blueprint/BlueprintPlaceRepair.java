package dev.shiratori.simcoastersfix.blueprint;

import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintPlaceSession;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintPlacedBlock;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.shiratori.simcoastersfix.Simcoastersfix;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Repairs cells a blueprint place recorded but never wrote, as early as possible.
 *
 * <p>Phenomenon (Sable Photomancy 1.0.1, Sable 2.0.6, Drydock 4.288, measured 2026-11-27): a
 * 1887-block blueprint lands 1686 blocks. 201 cells stay air - 133 of them own a block entity and
 * raise {@code "state minecraft:air does not allow it"}, the other 68 (plain blocks) vanish without a
 * single log line. The set is deterministic in blueprint-local coordinates and does not move when the
 * plot is allocated somewhere else.</p>
 *
 * <p>Cause: for those cells the plot knows the chunk ({@code ensureChunk} creates nothing because
 * {@code plot.getChunkHolder(localChunk) != null}) while the level serves a placeholder
 * {@code EmptyLevelChunk}. Its {@code setBlockState} returns {@code null} and writes nothing, and
 * {@code SableBlueprintPlacer} does not check the return value before calling
 * {@code recordPlacedBlock}. Reading the same position from the level right then answers
 * {@code minecraft:void_air}.</p>
 *
 * <p>Next time: repair at the {@code PLACE_BLOCK_STATES -> LOAD_BLOCK_ENTITIES} transition, not after
 * the place. At that point every block state is written and Photomancy has not yet run its block
 * entity pass, so the repaired cells get their block entity - with the blueprint's own NBT and
 * through the mapper hooks - without this class touching NBT at all. The after-place pass is only a
 * fallback for a session that never reached that phase; it can restore the block but not the block
 * entity data, because that NBT lives in the blueprint, which the place session does not expose.</p>
 */
public final class BlueprintPlaceRepair {
    /** Guards against a repair that somehow triggers another place on the same thread. */
    private static boolean repairing;

    private BlueprintPlaceRepair() {
    }

    /**
     * Primary path, called from the {@code setPhase} hook when Photomancy switches to
     * {@code LOAD_BLOCK_ENTITIES}: all block states are written, no block entity has been created yet.
     */
    public static void beforeBlockEntities(BlueprintPlaceSession session) {
        repair(session, true);
    }

    /** Fallback, called at the start of {@code onPlaceAfterBlocks} if the phase hook never ran. */
    public static void afterPlace(BlueprintPlaceSession session) {
        repair(session, false);
    }

    private static void repair(BlueprintPlaceSession session, boolean blockEntitiesStillPending) {
        if (repairing) {
            return;
        }
        repairing = true;
        try {
            List<BlueprintPlacedBlock> blocks;
            try {
                blocks = session.placedBlocks().blocks();
            } catch (RuntimeException | LinkageError error) {
                Simcoastersfix.LOGGER.warn("[{}] blueprint place repair: cannot read the placed block list",
                        Simcoastersfix.MODID, error);
                return;
            }
            if (blocks.isEmpty()) {
                return;
            }

            ServerLevel level = session.level();
            int missing = 0;
            int repaired = 0;
            int skipped = 0;
            int withoutBlockEntity = 0;

            for (BlueprintPlacedBlock block : blocks) {
                BlockState expected = block.state();
                BlockPos storagePos = block.storagePos();
                if (level.getBlockState(storagePos).is(expected.getBlock())) {
                    continue;
                }
                missing++;

                ServerSubLevel subLevel = block.placedSubLevel();
                if (subLevel == null || subLevel.isRemoved()) {
                    skipped++;
                    continue;
                }

                // The embedded accessor addresses blocks by plot-local coordinates, whose origin is
                // the plot centre block - it never goes through the level's (placeholder) chunk.
                BlockPos localPos = storagePos.subtract(subLevel.getPlot().getCenterBlock());
                try {
                    // Flags 2 = send to clients only. Photomancy's own notifyPlacedBlocks pass runs
                    // later over the full placed list, so neighbour updates stay its business.
                    if (!subLevel.getPlot().getEmbeddedLevelAccessor().setBlock(localPos, expected, 2)) {
                        skipped++;
                        continue;
                    }
                } catch (RuntimeException error) {
                    skipped++;
                    Simcoastersfix.LOGGER.debug("[{}] blueprint place repair: {} failed",
                            Simcoastersfix.MODID, storagePos.toShortString(), error);
                    continue;
                }

                repaired++;
                if (!blockEntitiesStillPending && expected.hasBlockEntity()) {
                    withoutBlockEntity++;
                }
            }

            if (missing == 0) {
                return;
            }
            if (blockEntitiesStillPending) {
                Simcoastersfix.LOGGER.info(
                        "[{}] blueprint place repair: recorded={} missing={} repaired={} skipped={} "
                                + "(block entity data still restored by Photomancy)",
                        Simcoastersfix.MODID, blocks.size(), missing, repaired, skipped);
            } else {
                Simcoastersfix.LOGGER.warn(
                        "[{}] blueprint place repair (late): recorded={} missing={} repaired={} skipped={} "
                                + "blockEntitiesNotRestored={}",
                        Simcoastersfix.MODID, blocks.size(), missing, repaired, skipped, withoutBlockEntity);
            }
        } catch (RuntimeException | LinkageError error) {
            Simcoastersfix.LOGGER.warn("[{}] blueprint place repair failed", Simcoastersfix.MODID, error);
        } finally {
            repairing = false;
        }
    }
}
