package com.solidus.mixin;

import com.solidus.SolidusMod;
import com.solidus.networking.PacketHandler;
import com.solidus.shop.ShopScreenHandler;
import com.solidus.sell.SellScreenHandler;
import com.solidus.auction.AuctionScreenHandler;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ScreenHandler Mixin - Additional safety layer for virtual GUI protection.
 *
 * NOTE: We do NOT target the abstract quickMoveStack() method because
 * abstract methods have no body to inject into. Instead, this mixin
 * targets the clicked() method on AbstractContainerMenu as a safety net.
 *
 * The primary defense against unwanted item movement is already provided by:
 * 1. ShopScreenHandler.clicked() - Complete rewrite blocking all item movement
 * 2. ShopScreenHandler.quickMoveStack() - Returns ItemStack.EMPTY
 * 3. AuctionScreenHandler.clicked() - Same protection
 * 4. AuctionScreenHandler.quickMoveStack() - Returns ItemStack.EMPTY
 *
 * This mixin adds a final safety layer by ensuring that clicks on virtual
 * GUI containers are never processed by the default AbstractContainerMenu logic.
 *
 * IMPORTANT: SellScreenHandler is EXCLUDED from this safety net because it
 * implements its own cursor-based item movement in its clicked() override.
 * The sell GUI allows item placement, so its clicks must not be cancelled.
 * The SellScreenHandler.clicked() method does NOT call super.clicked(),
 * so this mixin's injection into AbstractContainerMenu.clicked() would not
 * fire for SellScreenHandler anyway. However, the check is explicit for clarity.
 *
 * Ghost Item Prevention:
 * If for any reason a click reaches this mixin and would result in item
 * movement in a Solidus virtual GUI, we cancel it AND force a container
 * resync via broadcastChanges() to prevent ghost items from appearing
 * on the client due to the server-client state mismatch.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class ScreenHandlerMixin {

    /**
     * Intercepts the clicked() method at the head to add a safety check.
     * If the container is a Solidus virtual GUI, the default implementation
     * is skipped entirely and we force a container resync to prevent ghost items.
     */
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    // TODO: 26.1.x - ClickType -> ContainerInput; method signature changed:
    //  clicked(Slot, int, ClickType, Player) -> clicked(Slot, int, ContainerInput, Player)
    //  The button parameter may have been removed (absorbed into ContainerInput).
    //  Verify the exact new signature at compile time.
    private void onClicked(net.minecraft.world.inventory.Slot slot, int slotIndex,
                            int button, net.minecraft.world.inventory.ContainerInput containerInput,
                            net.minecraft.world.entity.player.Player player, CallbackInfo ci) {
        // Only apply to Solidus virtual GUIs
        if (player instanceof ServerPlayer serverPlayer) {
            AbstractContainerMenu currentMenu = serverPlayer.containerMenu;
            if (currentMenu instanceof ShopScreenHandler || currentMenu instanceof AuctionScreenHandler) {
                // Cancel any default AbstractContainerMenu.clicked() processing
                // for Shop and Auction GUIs. Our custom ScreenHandler overrides
                // will handle the click through their own implementation.
                //
                // NOTE: SellScreenHandler is intentionally EXCLUDED here.
                // The sell GUI needs its own cursor-based item movement,
                // which is implemented in SellScreenHandler.clicked().
                // Since SellScreenHandler.clicked() does not call super.clicked(),
                // this mixin injection would not fire for it anyway, but we
                // explicitly exclude it for architectural clarity.
                ci.cancel();

                // FORCE RESYNC: Prevent ghost items by ensuring the client
                // receives the server's actual container state immediately
                // after the cancellation. Without this, the client may show
                // phantom items due to the click being canceled server-side
                // but already applied optimistically on the client.
                currentMenu.broadcastChanges();
            }
        }
    }
}
