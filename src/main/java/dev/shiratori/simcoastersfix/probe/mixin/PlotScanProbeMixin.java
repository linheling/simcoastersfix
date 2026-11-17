package dev.shiratori.simcoastersfix.probe.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.shiratori.simcoastersfix.probe.Probes;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.silvergold.simulatedcoasters.track.cart.CoasterCartPlotScan;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Probes for {@code CoasterCartPlotScan} (section pruning is upstream's now; the scan itself is the
 * thing worth measuring again).
 *
 * <ul>
 *   <li>{@code readyPlotChunkCount} &mdash; called once per live sub-level inside
 *       {@code CoasterCartServerMembership.reconcile}, <em>before</em> the "does this need
 *       re-classifying" test, so every sub-level pays for it every tick. It walks the plot's loaded
 *       chunks, which makes the cost O(sub-levels &times; plot chunks), not O(1). This is the most
 *       suspicious of the 0.1.6 additions.
 *   <li>{@code scanBearingCells} &mdash; the 16&times;16&times;16 walk that 1.1.0 pruned. Expected
 *       to be rare now (only on re-classification); a non-trivial per-tick count means the
 *       membership index is not holding.
 * </ul>
 */
@Mixin(targets = "dev.silvergold.simulatedcoasters.track.cart.CoasterCartPlotScan", remap = false)
public abstract class PlotScanProbeMixin {

    /** Self-check anchor: presence means PlotScanProbeMixin landed on CoasterCartPlotScan. */
    @Unique
    private static final boolean simc$plotScanProbe = true;
    @WrapMethod(method = "readyPlotChunkCount")
    private static int simc$probeReadyPlotChunks(LevelPlot plot, Operation<Integer> original) {
        if (!Probes.enabled) {
            return original.call(plot);
        }
        long started = Probes.start();
        try {
            return original.call(plot);
        } finally {
            Probes.end(Probes.PLOT_READY_CHUNKS, started);
        }
    }

    @WrapMethod(method = "scanBearingCells")
    private static CoasterCartPlotScan.BearingScanResult simc$probeScanBearingCells(
            LevelPlot plot, Block bearingBlock, Operation<CoasterCartPlotScan.BearingScanResult> original) {
        if (!Probes.enabled) {
            return original.call(plot, bearingBlock);
        }
        long started = Probes.start();
        try {
            return original.call(plot, bearingBlock);
        } finally {
            Probes.end(Probes.PLOT_SCAN_CELLS, started);
        }
    }
}
