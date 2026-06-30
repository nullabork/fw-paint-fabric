package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

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

    /** Inventory slot of a placeable, non-excluded {@code block} within the configured source, or -1. */
    public static int findSlot(LocalPlayer player, Block block) {
        NonNullList<ItemStack> items = player.getInventory().getNonEquipmentItems();
        List<String> excluded = ConfigManager.get().excludedBlocks;
        int from, to;
        switch (ConfigManager.get().source) {
            case HOTBAR -> { from = 0; to = 9; }
            case INVENTORY -> { from = 9; to = 36; }
            default -> { from = 0; to = 36; }
        }
        to = Math.min(to, items.size());
        for (int slot = from; slot < to; slot++) {
            ItemStack st = items.get(slot);
            if (!(st.getItem() instanceof BlockItem bi) || bi.getBlock() != block) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
            if (id != null && excluded.contains(id.toString())) continue;
            return slot;
        }
        return -1;
    }
}
