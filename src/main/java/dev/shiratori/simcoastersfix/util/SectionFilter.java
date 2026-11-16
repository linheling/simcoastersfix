package dev.shiratori.simcoastersfix.util;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Section-level pruning for block scans.
 *
 * <p>{@link LevelChunkSection#maybeHas} delegates to the section's palette, so it answers
 * "could this section contain the block at all" in palette-entry time instead of 4096
 * {@code getBlockState} calls. The answer is conservative by construction:
 * {@code GlobalPalette.maybeHas} returns {@code true} unconditionally, so a {@code false}
 * result is a proof of absence, never a guess.
 *
 * <p>The predicate lives here rather than inside the mixin so that the merged target class
 * stays free of synthetic lambda methods, and so this rule can be unit-checked on its own.
 */
public final class SectionFilter {
    private SectionFilter() {
    }

    /**
     * @return {@code false} only when the section's palette proves it holds no state of {@code block}.
     */
    public static boolean mayContain(LevelChunkSection section, Block block) {
        return section.maybeHas(state -> state.is(block));
    }
}
