package dev.shiratori.simcoastersfix.util;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.silvergold.simulatedcoasters.SimulatedCoastersBlocks;
import dev.silvergold.simulatedcoasters.track.cart.CoasterCartPlotScan;
import dev.shiratori.simcoastersfix.Simcoastersfix;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

/**
 * Answers "is this sub-level a coaster cart right now" <em>from scratch</em>, so the destructive
 * half of the train-link code can tell a real answer apart from a cached one.
 *
 * <p>Why this exists: upstream resolves cart membership through
 * {@code CoasterCartTrainLinkConstraint.isDefunctLinkPartner} → {@code CoasterCartResolve
 * .isCartSubLevel} → {@code CoasterCartBearingTickCache.scan}. A {@code false} from that chain is
 * destructive — {@code pruneDefunctLinks} and {@code tryRestoreLink} call {@code unlinkPair}, which
 * deletes the coupling <em>from the cart's persisted NBT</em>. Upstream gets away with it because
 * its scan is fresh once per tick; this mod's TTL cache (and the load window, where a sub-level can
 * be in the container before its plot is populated) can hand back "no bearing cell" for a cart that
 * is merely not observable yet. That answer is indistinguishable from "this cart is gone", and the
 * coupling is gone for good.
 *
 * <p>So before destroying anything, ask this class instead of the cache. It re-scans, and when the
 * plot itself is empty it refuses to answer at all ({@link Verdict#UNKNOWN}) — an unobservable cart
 * is not a removed cart.
 *
 * <p>Only ever runs on the server thread, and only from code paths that are about to delete
 * something, so the extra scan is not on the hot path.
 */
public final class CartMembership {
    /** NBT keys upstream writes; a sub-level that carries either one is part of a train. */
    public static final String LINKS_TAG = "simulatedcoasters:cart_train_links";

    public static final String LEGACY_LINK_TAG = "simulatedcoasters:cart_train_link";

    /** How many refusals to spell out at INFO before switching to a periodic counter. */
    private static final long LOG_FIRST_REFUSALS = 12L;

    private static final AtomicLong REFUSALS = new AtomicLong();

    /** What a fresh look at one sub-level says about its cart membership. */
    public enum Verdict {
        /** A bearing cell is present in the plot: this is a cart. */
        CART,
        /** The plot is populated and holds no bearing cell: this really is not a cart. */
        NOT_CART,
        /** The sub-level or its plot cannot be observed right now — no answer, do not act on it. */
        UNKNOWN
    }

    private CartMembership() {
    }

    /** Fresh membership verdict; bypasses {@code CoasterCartBearingTickCache} entirely. */
    public static Verdict verdict(@Nullable SubLevel sub) {
        if (sub == null) {
            return Verdict.UNKNOWN;
        }
        if (sub.isRemoved()) {
            return Verdict.NOT_CART;
        }
        LevelPlot plot = sub.getPlot();
        if (plot == null) {
            return Verdict.UNKNOWN;
        }
        Block bearing = bearingBlock();
        if (bearing == null) {
            return Verdict.UNKNOWN;
        }
        try {
            if (CoasterCartPlotScan.scanBearingCells(plot, bearing).representative() != null) {
                return Verdict.CART;
            }
        } catch (RuntimeException e) {
            // A plot being rewritten under us is exactly the case that must not destroy links.
            return Verdict.UNKNOWN;
        }
        return plotHasContent(sub) ? Verdict.NOT_CART : Verdict.UNKNOWN;
    }

    /**
     * {@code true} when the cart carries persisted couplings, i.e. when destroying something is
     * even possible. Cheap: a single NBT key lookup.
     */
    public static boolean hasPersistedLinks(@Nullable SubLevel sub) {
        if (!(sub instanceof ServerSubLevel server)) {
            // Client sub-levels and unknown types: assume the expensive answer is needed.
            return true;
        }
        CompoundTag tag = server.getUserDataTag();
        return tag != null && (tag.contains(LINKS_TAG) || tag.contains(LEGACY_LINK_TAG));
    }

    /**
     * @return {@code true} when at least one block exists anywhere in the plot, i.e. when a
     *         "no bearing cell" answer means something. An empty plot is an unloaded plot.
     */
    public static boolean plotHasContent(@Nullable SubLevel sub) {
        LevelPlot plot = sub != null ? sub.getPlot() : null;
        if (plot == null) {
            return false;
        }
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            if (holder == null) {
                continue;
            }
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) {
                continue;
            }
            int sections = chunk.getSectionsCount();
            for (int i = 0; i < sections; i++) {
                LevelChunkSection section = chunk.getSection(i);
                if (section != null && !section.hasOnlyAir()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** @return {@code true} when the plot currently has at least one real chunk object. */
    public static boolean plotChunksLoaded(@Nullable SubLevel sub) {
        LevelPlot plot = sub != null ? sub.getPlot() : null;
        if (plot == null) {
            return false;
        }
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            if (holder != null && holder.getChunk() != null) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public static SubLevel find(ServerLevel level, UUID id) {
        SubLevelContainer raw = SubLevelContainer.getContainer(level);
        return raw instanceof ServerSubLevelContainer container ? container.getSubLevel(id) : null;
    }

    /**
     * Guard for {@code CoasterCartTrainLinkConstraint.isDefunctLinkPartner}.
     *
     * @return {@code true} when the pair must <em>not</em> be unlinked: the partner is still a cart
     *         per a fresh scan, or cannot be judged at all.
     */
    public static boolean partnerUnresolved(@Nullable ServerLevel level, UUID partnerId) {
        if (level == null) {
            return true;
        }
        SubLevel partner = find(level, partnerId);
        if (partner == null || partner.isRemoved()) {
            // Upstream semantics: absent partner is not "defunct", removed partner is.
            return false;
        }
        Verdict verdict = verdict(partner);
        if (verdict == Verdict.NOT_CART) {
            return false;
        }
        if (verdict == Verdict.CART && partner instanceof ServerSubLevel serverPartner) {
            // The block is physically there, so upstream's cached answer is wrong: make it look again.
            MembershipRepair.heal(serverPartner);
        }
        refuse("isDefunctLinkPartner", partner, verdict);
        return true;
    }

    /**
     * Guard for {@code CoasterCartTrainLinkConstraint.onCartRemoved}.
     *
     * @return {@code true} when the teardown must be skipped: the cart is unobservable or still
     *         shows a bearing cell, so removing its couplings would be a false positive.
     */
    public static boolean cartMembershipUnresolved(ServerSubLevel cart) {
        if (cart.isRemoved() || !hasPersistedLinks(cart)) {
            return false;
        }
        Verdict verdict = verdict(cart);
        if (verdict == Verdict.NOT_CART) {
            return false;
        }
        if (verdict == Verdict.CART) {
            MembershipRepair.heal(cart);
        }
        refuse("onCartRemoved", cart, verdict);
        return true;
    }

    @Nullable
    private static Block bearingBlock() {
        try {
            return SimulatedCoastersBlocks.COASTER_CART_BLOCK.get();
        } catch (Throwable registryNotReady) {
            return null;
        }
    }

    private static void refuse(String site, SubLevel sub, Verdict verdict) {
        long count = REFUSALS.incrementAndGet();
        if (count > LOG_FIRST_REFUSALS && count % 16L != 0L) {
            return;
        }
        Simcoastersfix.LOGGER.info(
                "[{}] kept cart-train couplings of {} at {} ({}): membership is {}, not 'gone' — "
                        + "persisted links left untouched ({})",
                Simcoastersfix.MODID,
                sub.getUniqueId(),
                site,
                verdict == Verdict.CART ? "fresh scan found the bearing cell" : "the plot is not populated yet",
                verdict,
                count);
    }
}
