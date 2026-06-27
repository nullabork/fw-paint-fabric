package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entry point. Registers two keybinds (open settings, cycle mode), an in-game HUD status
 * line, and wires the shared {@link ToolMode} state. See fabric-26.2-mod-starter.md for API notes.
 */
public class Gradient implements ClientModInitializer {

    public static final String MOD_ID = "gradient";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        ConfigManager.load();

        // Keybinds (26.2: register via KeyMappingHelper, pass a KeyMapping.Category not a String).
        KeyMapping openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.gradient.open", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K, KeyMapping.Category.MISC));
        KeyMapping cycleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.gradient.cycle", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                // 26.2: open screens via setScreenAndShow (there is no setScreen).
                client.setScreenAndShow(new GradientScreen());
            }
            while (cycleKey.consumeClick()) {
                cycleMode();
            }
            MarkerManager.tick(client);
            GradientPlacer.tick(client);
        });

        // While the tool is engaged (Marker or Place mode), suppress vanilla left/right click so it
        // doesn't break/use the world — Marker mode marks, Place mode places. Cancel client-side
        // only (stops the action packet). The !isPlacing() guard lets our OWN synthesized placement
        // interaction through instead of cancelling it.
        AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) ->
                (level.isClientSide() && toolEngaged()) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                (level.isClientSide() && toolEngaged() && !GradientPlacer.isPlacing())
                        ? InteractionResult.FAIL : InteractionResult.PASS);

        // In-world marker rendering (filled committed faces + drag outline preview). Submit during
        // COLLECT_SUBMITS — the same submit-collection phase vanilla uses for entities + the block
        // outline — so our geometry is batched and drawn with the exact same camera (no frame drift).
        LevelRenderEvents.COLLECT_SUBMITS.register(MarkerManager::render);

        // In-game HUD status line (26.2: HudElementRegistry; HudRenderCallback is gone).
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MOD_ID, "mode_status"),
                (g, deltaTracker) -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null || mc.level == null) return; // only while in a world
                    GradientConfig cfg = ConfigManager.get();
                    String tool = toolDisplayName(cfg.selectedTool);
                    String text = "Gradient: " + cfg.mode.displayName()
                            + (tool.isEmpty() ? "" : "  [" + tool + "]");
                    g.text(mc.font, text, 4, 4, cfg.mode.color());
                });

        LOG.info("{} initialised", MOD_ID);
    }

    /** Advance to the next activation mode, persist it, and flash an action-bar message. */
    public static void cycleMode() {
        GradientConfig cfg = ConfigManager.get();
        cfg.mode = cfg.mode.next();
        ConfigManager.save();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendOverlayMessage(Component.literal("Gradient: " + cfg.mode.displayName()));
        }
    }

    /** True when the player's main hand holds the configured tool item. */
    public static boolean isHoldingTool(Minecraft mc) {
        if (mc.player == null) return false;
        String sel = ConfigManager.get().selectedTool;
        if (sel == null || sel.isEmpty()) return false;
        Identifier id = BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem());
        return id != null && id.toString().equals(sel);
    }

    /** True when the tool is held and the mod is active (Marker or Place — not Disabled). */
    public static boolean toolEngaged() {
        return ConfigManager.get().mode != ToolMode.DISABLED && isHoldingTool(Minecraft.getInstance());
    }

    /**
     * Human-readable name for a tool item id (e.g. {@code "minecraft:diamond_pickaxe"} →
     * {@code "Diamond Pickaxe"}). Returns {@code ""} for none, or the raw id if unresolvable.
     */
    public static String toolDisplayName(String id) {
        if (id == null || id.isEmpty()) return "";
        Identifier ident = Identifier.tryParse(id);
        if (ident == null) return id;
        return BuiltInRegistries.ITEM.getOptional(ident)
                .map(item -> new ItemStack((Item) item).getHoverName().getString())
                .orElse(id);
    }
}
