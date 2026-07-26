package co.fax.wang;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Shared client-side, multiplayer-safe block placement (the Litematica approach): select/swap the
 * block into the hand and send a normal "use item on the support block's face" interaction so the
 * server validates and places it. Used by both the gradient and noise placers.
 */
public final class BlockPlacement {

    private BlockPlacement() {}

    private static volatile boolean placing = false;

    /** True while we are issuing our own placement interaction (so the click-cancel callbacks pass). */
    public static boolean isPlacing() {
        return placing;
    }

    /** Place the block held in inventory {@code slot} against {@code support}'s {@code dir} face. */
    public static void place(Minecraft mc, LocalPlayer player, int slot, BlockPos support, Direction dir) {
        Inventory inv = player.getInventory();
        int selSlot = inv.getSelectedSlot();
        boolean fromInventory = slot >= 9; // main inventory needs swapping into the held hotbar slot
        int menuId = player.inventoryMenu.containerId;
        placing = true;
        try {
            if (fromInventory) {
                mc.gameMode.handleContainerInput(menuId, slot, selSlot, ContainerInput.SWAP, player);
            } else {
                inv.setSelectedSlot(slot);
            }
            Vec3 loc = new Vec3(
                    support.getX() + 0.5 + dir.getStepX() * 0.5,
                    support.getY() + 0.5 + dir.getStepY() * 0.5,
                    support.getZ() + 0.5 + dir.getStepZ() * 0.5);
            BlockHitResult hit = new BlockHitResult(loc, dir, support, false);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        } finally {
            if (fromInventory) {
                mc.gameMode.handleContainerInput(menuId, slot, selSlot, ContainerInput.SWAP, player);
            } else {
                inv.setSelectedSlot(selSlot);
            }
            placing = false;
        }
    }

}
