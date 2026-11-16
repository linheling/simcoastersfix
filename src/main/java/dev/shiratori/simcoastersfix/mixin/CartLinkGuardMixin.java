package dev.shiratori.simcoastersfix.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.silvergold.simulatedcoasters.track.cart.CoasterCartTrainLinkConstraint;
import dev.shiratori.simcoastersfix.FixLimits;
import dev.shiratori.simcoastersfix.util.CartMembership;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops cart couplings from being destroyed on the strength of an unobservable membership answer.
 *
 * <p>Every destructive path in {@code CoasterCartTrainLinkConstraint} funnels through
 * {@code unlinkPair}, which not only drops the runtime constraint but also erases the coupling from
 * the cart's persisted NBT — unrecoverable. Two predicates decide whether that happens, and both
 * read cart membership through the very cache this mod made time-lagged:
 *
 * <ul>
 *   <li>{@code isDefunctLinkPartner(level, partnerId)} — used by {@code pruneDefunctLinks},
 *       {@code tryRestoreLink} and the per-tick sweep. Returns "defunct" for a partner that exists,
 *       is not removed, yet fails the bearing-cell test.
 *   <li>{@code onCartRemoved(cart)} — reads every persisted link and unlinks it. Called from
 *       {@code CoasterCartTrackSnap.onPrePhysicsTick} whenever the bearing scan comes back empty.
 * </ul>
 *
 * <p>Both are correct as long as "no bearing cell" means "there is no cart". They are wrong while a
 * cart is merely not observable — the load window, when a sub-level sits in the container before its
 * plot is populated, and the tail of a cached negative. Observed in the wild: loading a world with
 * 40 coupled carts unlinked every pair within a few seconds of the join, which is the reported bug.
 *
 * <p>So both predicates re-check membership through {@link CartMembership} before answering
 * "destroy": a fresh scan that finds the bearing cell keeps the link, and a plot with no content at
 * all is treated as "no answer" rather than "gone". A partner that was genuinely removed, or whose
 * populated plot really has no bearing cell, still unlinks exactly as upstream intends.
 *
 * <p>Cost is confined to teardown attempts: the {@code onCartRemoved} side short-circuits on a
 * single NBT key when the cart carries no couplings at all, which is the case for every non-cart
 * sub-level in the world.
 */
@Mixin(value = CoasterCartTrainLinkConstraint.class, remap = false)
public abstract class CartLinkGuardMixin {
    /** Self-check anchor; the guard itself is stateless beyond this flag. */
    @Unique
    private static final boolean simc$cartLinkGuard = true;

    @Inject(method = "isDefunctLinkPartner", at = @At("HEAD"), cancellable = true)
    private static void simc$keepLinkOnUnresolvedPartner(
            ServerLevel level, UUID partnerId, CallbackInfoReturnable<Boolean> cir) {
        if (!FixLimits.protectCartLinks) {
            return;
        }
        if (CartMembership.partnerUnresolved(level, partnerId)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "onCartRemoved", at = @At("HEAD"), cancellable = true)
    private static void simc$keepLinkOnUnresolvedCart(ServerSubLevel cart, CallbackInfo ci) {
        if (!FixLimits.protectCartLinks) {
            return;
        }
        if (CartMembership.cartMembershipUnresolved(cart)) {
            ci.cancel();
        }
    }
}
