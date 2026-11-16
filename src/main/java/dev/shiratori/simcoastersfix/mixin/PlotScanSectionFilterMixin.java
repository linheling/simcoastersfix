package dev.shiratori.simcoastersfix.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.silvergold.simulatedcoasters.track.cart.CoasterCartPlotScan;
import dev.shiratori.simcoastersfix.util.SectionFilter;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Cuts {@code CoasterCartPlotScan}'s 4096 {@code getBlockState} calls per section down to a
 * palette lookup, without changing what the scan returns.
 *
 * <p>Both scan loops guard a section with {@code section != null && !section.hasOnlyAir()},
 * and {@code hasOnlyAir()} is {@code nonEmptyBlockCount == 0} — it only rejects sections that
 * are <em>entirely</em> air. Any section holding a single block (rail, stone, wool) still
 * pays the full 16x16x16 walk. Wrapping that single guard adds a second, stronger test: skip
 * the section when its palette proves it holds no cell of the wanted block.
 *
 * <p>Return value semantics are unchanged: {@code true} means "skip this section". When the
 * palette says the block may be present we return {@code false} and the original 4096-cell
 * loop runs exactly as before. A skipped section was going to contribute zero matches, so
 * {@code count}, {@code cells} and the {@code compareYXZ} representative are all preserved.
 *
 * <p>Handlers are {@code @WrapOperation} rather than {@code @Redirect} so they still chain
 * with other mods patching the same call.
 */
@Mixin(value = CoasterCartPlotScan.class, remap = false)
public abstract class PlotScanSectionFilterMixin {
    /**
     * Sections proven empty of the wanted block and skipped instead of walked.
     *
     * <p>Diagnostic only: plain {@code long}, never reset, races between the server thread and
     * the client boundary are harmless. Existence of this field is also what
     * {@code PatchSelfCheck} looks for to confirm the injection landed.
     */
    @Unique
    private static long simc$sectionsSkipped;

    @WrapOperation(
            method = "scanBearingCells",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;hasOnlyAir()Z"))
    private static boolean simc$skipEmptySectionsOnScan(
            LevelChunkSection section, Operation<Boolean> original,
            LevelPlot plot, Block bearingBlock) {
        return simc$skipSection(section, original, bearingBlock);
    }

    @WrapOperation(
            method = "forEachBearingWorldCell",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;hasOnlyAir()Z"))
    private static boolean simc$skipEmptySectionsOnForEach(
            LevelChunkSection section, Operation<Boolean> original,
            LevelPlot plot, Block bearingBlock, Consumer<MutableBlockPos> visitor) {
        return simc$skipSection(section, original, bearingBlock);
    }

    @Unique
    private static boolean simc$skipSection(
            LevelChunkSection section, Operation<Boolean> original, Block bearingBlock) {
        if (original.call(section)) {
            return true;
        }
        if (SectionFilter.mayContain(section, bearingBlock)) {
            return false;
        }
        simc$sectionsSkipped++;
        return true;
    }
}
