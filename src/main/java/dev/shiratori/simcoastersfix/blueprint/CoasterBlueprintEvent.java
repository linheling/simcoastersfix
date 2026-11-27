package dev.shiratori.simcoastersfix.blueprint;

import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintPlaceSession;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintSaveSession;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintSubLevelRef;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintEvent;
import dev.rew1nd.sableschematicapi.api.blueprint.SubLevelSaveFrame;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.shiratori.simcoastersfix.Simcoastersfix;

import dev.silvergold.simulatedcoasters.balloon.BalloonLoadReconciler;
import dev.silvergold.simulatedcoasters.balloon.BalloonResolve;
import dev.silvergold.simulatedcoasters.rivet.RivetBlockEntity;
import dev.silvergold.simulatedcoasters.rivet.RivetHostIndex;
import dev.silvergold.simulatedcoasters.rivet.RivetHostKey;
import dev.silvergold.simulatedcoasters.rivet.RivetPoseAlignment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Blueprint side-car for the parts of a Create: Coasters Simulated build that live outside block
 * entities: cart train couplings, and the physical re-attachment of rivets and balloons.
 *
 * <p>Block entity NBT is handled by {@link RivetHostBlueprintMapper} and
 * {@link BalloonHostBlueprintMapper}. What is left here is:</p>
 * <ul>
 *   <li>couplings, which Coasters stores in {@code ServerSubLevel#getUserDataTag()} and Photomancy
 *       never exports;</li>
 *   <li>re-registering restored rivets/balloons in Coasters' {@code SavedData} indexes and rebuilding
 *       their physics locks, which has to happen after the placement has been finalised (poses are
 *       only teleported into place at the very end of {@code SableBlueprintPlacer.place}).</li>
 * </ul>
 */
final class CoasterBlueprintEvent implements SableBlueprintEvent {
    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(Simcoastersfix.MODID, "coasters_blueprint_bridge");

    private static final String CART_LINKS = "cart_links";
    private static final String SUB = "sub";
    private static final String LINKS = "links";
    private static final String PARTNER_SOURCE = "partner_source";

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void onSaveAfterBlocks(BlueprintSaveSession session, CompoundTag data) {
        ListTag cartLinks = new ListTag();
        int rivetSubLevels = 0;
        int balloonSubLevels = 0;

        for (SubLevelSaveFrame frame : session.frames()) {
            ServerSubLevel sub = frame.subLevel();
            if (RivetHostKey.isRivetSubLevel(sub)) {
                rivetSubLevels++;
            } else if (BalloonResolve.isBalloonSubLevel(sub)) {
                balloonSubLevels++;
            }

            ListTag stored = CartTrainLinks.read(sub);
            if (stored == null) {
                continue;
            }

            ListTag kept = new ListTag();
            for (Tag tag : stored) {
                UUID partner = CartTrainLinks.partnerOf(tag);
                if (partner == null) {
                    continue;
                }
                // A coupling to a cart that is not part of the blueprint cannot be restored.
                Optional<BlueprintSubLevelRef> ref = session.subLevelRef(partner);
                if (ref.isEmpty()) {
                    continue;
                }
                CompoundTag entry = new CompoundTag();
                entry.putUUID(PARTNER_SOURCE, ref.get().sourceUuid());
                entry.putDouble(CartTrainLinks.DISTANCE, CartTrainLinks.distanceOf(tag));
                kept.add(entry);
            }

            if (!kept.isEmpty()) {
                CompoundTag record = new CompoundTag();
                record.putUUID(SUB, frame.sourceUuid());
                record.put("links", kept);
                cartLinks.add(record);
            }
        }

        if (!cartLinks.isEmpty()) {
            data.put(CART_LINKS, cartLinks);
        }

        Simcoastersfix.LOGGER.info(
                "[{}] blueprint save: frames={} rivetSubLevels={} balloonSubLevels={} cartLinkSubLevels={}",
                Simcoastersfix.MODID, session.frames().size(), rivetSubLevels, balloonSubLevels, cartLinks.size());
    }

    @Override
    public void onPlaceAfterBlocks(BlueprintPlaceSession session, CompoundTag data) {
        // Normally a no-op: BlueprintPlaceRepair already ran at the LOAD_BLOCK_ENTITIES phase
        // boundary, where Photomancy's block entity pass can still attach the blueprint's NBT.
        // This covers a session that never reached that phase.
        BlueprintPlaceRepair.afterPlace(session);

        ServerLevel level = session.level();
        Map<UUID, UUID> uuidMap = session.subLevelUuidMap();
        int restoredLinks = 0;

        for (Tag tag : data.getList(CART_LINKS, 10)) {
            if (!(tag instanceof CompoundTag record) || !record.hasUUID(SUB)) {
                continue;
            }
            UUID placedId = uuidMap.get(record.getUUID(SUB));
            if (placedId == null) {
                continue;
            }
            ServerSubLevel placed = resolve(level, placedId);
            if (placed == null) {
                continue;
            }

            List<CompoundTag> links = new ArrayList<>();
            for (Tag linkTag : record.getList(LINKS, 10)) {
                if (!(linkTag instanceof CompoundTag link) || !link.hasUUID(PARTNER_SOURCE)) {
                    continue;
                }
                UUID placedPartner = uuidMap.get(link.getUUID(PARTNER_SOURCE));
                if (placedPartner != null) {
                    links.add(CartTrainLinks.entry(placedPartner, CartTrainLinks.distanceOf(link)));
                }
            }

            if (!links.isEmpty()) {
                CartTrainLinks.write(placed, links);
                restoredLinks += links.size();
            }
        }

        // Poses are only teleported into their final place at the very end of the placement, so the
        // physics side has to be rebuilt on the next tick rather than here.
        List<UUID> placedIds = new ArrayList<>(new LinkedHashMap<>(uuidMap).values());
        level.getServer().execute(() -> finishPlacement(level, placedIds));

        Simcoastersfix.LOGGER.info(
                "[{}] blueprint place: subLevels={} cartLinks={} (physics re-attach queued)",
                Simcoastersfix.MODID, placedIds.size(), restoredLinks);
    }

    private static void finishPlacement(ServerLevel level, List<UUID> placedIds) {
        // Runs as a queued server task, so nothing may escape: an exception here would land in the
        // server's task queue rather than in a blueprint diagnostic report.
        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return;
            }

            int rivets = 0;
            int balloons = 0;
            for (UUID id : placedIds) {
                try {
                    SubLevel raw = container.getSubLevel(id);
                    if (!(raw instanceof ServerSubLevel sub) || sub.isRemoved()) {
                        continue;
                    }
                    if (RivetHostKey.isRivetSubLevel(sub)) {
                        if (reattachRivet(level, container, sub)) {
                            rivets++;
                        }
                    } else if (BalloonResolve.isBalloonSubLevel(sub)) {
                        BalloonLoadReconciler.reconcileBalloon(level, sub, true);
                        balloons++;
                    }
                } catch (RuntimeException e) {
                    Simcoastersfix.LOGGER.warn(
                            "[{}] blueprint place: could not re-attach sub-level {}", Simcoastersfix.MODID, id, e);
                }
            }

            // Rebuild the couplings now that both ends exist with their final uuids and poses.
            try {
                CoasterCartTrainLinkBridge.restore(level);
            } catch (RuntimeException e) {
                Simcoastersfix.LOGGER.warn("[{}] blueprint place: cart link restore failed", Simcoastersfix.MODID, e);
            }

            Simcoastersfix.LOGGER.info(
                    "[{}] blueprint place: re-attached rivets={} balloons={}", Simcoastersfix.MODID, rivets, balloons);
        } catch (RuntimeException e) {
            Simcoastersfix.LOGGER.error("[{}] blueprint place: post-placement fixup failed", Simcoastersfix.MODID, e);
        }
    }

    private static boolean reattachRivet(ServerLevel level, ServerSubLevelContainer container, ServerSubLevel rivetSub) {
        BlockEntity blockEntity = rivetSub.getPlot().getEmbeddedLevelAccessor().getBlockEntity(BlockPos.ZERO);
        if (!(blockEntity instanceof RivetBlockEntity rivet)) {
            return false;
        }

        // The host uuid was dropped on save; resolve it again from the host cell we restored.
        RivetHostKey host = RivetHostKey.of(level, rivet.hostPos());
        rivet.rebindHost(host, rivet.face(), rivet.attachment());
        // registerLoadedRivet also drops any stale entry still pointing at this rivet sub-level.
        RivetHostIndex.get(level).registerLoadedRivet(rivet.anchorKey(), rivetSub.getUniqueId());

        if (!level.getBlockState(rivet.hostPos()).isAir()) {
            RivetPoseAlignment.alignToNominal(level, container, rivetSub, rivet);
        }
        // applyHostLock also schedules a teardown when the host really is gone, so it is safe to call
        // even for rivets whose host block was not part of the blueprint.
        rivet.applyHostLock(rivetSub);
        return true;
    }

    @Nullable
    private static ServerSubLevel resolve(ServerLevel level, UUID id) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        SubLevel sub = container.getSubLevel(id);
        return sub instanceof ServerSubLevel server && !server.isRemoved() ? server : null;
    }

    /** Keeps the Coasters coupling constraint class out of the main flow so its link is obvious. */
    private static final class CoasterCartTrainLinkBridge {
        private CoasterCartTrainLinkBridge() {
        }

        static void restore(ServerLevel level) {
            dev.silvergold.simulatedcoasters.track.cart.CoasterCartTrainLinkConstraint.restorePersistedLinks(level);
        }
    }
}
