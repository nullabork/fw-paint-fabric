package co.fax.wang.fabric;

import co.fax.wang.Gradient;
import co.fax.wang.HudOverlay;
import co.fax.wang.MarkerManager;
import co.fax.wang.PaintPlacer;
import co.fax.wang.config.ConfigManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;

/**
 * Fabric entrypoint: everything real lives in the loader-free {@link Gradient} core — this class
 * only creates/registers the key mappings and forwards Fabric's tick, render, input, and HUD
 * events into it. See fabric-26.2-mod-starter.md for the 26.2 API notes.
 */
public class GradientFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ConfigManager.init(FabricLoader.getInstance().getConfigDir());

        // Keybinds (26.2: register via KeyMappingHelper, pass a KeyMapping.Category not a String).
        Gradient.createKeyMappings();
        KeyMappingHelper.registerKeyMapping(Gradient.openKey);
        KeyMappingHelper.registerKeyMapping(Gradient.cycleKey);
        KeyMappingHelper.registerKeyMapping(Gradient.paintTypeKey);
        KeyMappingHelper.registerKeyMapping(Gradient.clearConnectedKey);

        ClientTickEvents.END_CLIENT_TICK.register(Gradient::endClientTick);

        // While the tool is engaged, suppress vanilla left/right click so it doesn't break/use the
        // world. Cancel client-side only (stops the action packet). Fabric's UseBlockCallback also
        // wraps our OWN synthesized placement interaction — shouldCancelClick()'s isPlacing()
        // guard lets that one through instead of cancelling it.
        AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) ->
                (level.isClientSide() && Gradient.toolEngaged()) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                (level.isClientSide() && Gradient.shouldCancelClick())
                        ? InteractionResult.FAIL : InteractionResult.PASS);

        // In-world marker + placement-preview rendering. Submit during COLLECT_SUBMITS — the same
        // submit-collection phase vanilla uses for entities + the block outline — so our geometry
        // is batched and drawn with the exact same camera (no frame drift).
        LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
            var col = ctx.submitNodeCollector();
            var cam = ctx.levelState().cameraRenderState.pos;
            MarkerManager.render(col, cam);
            PaintPlacer.renderPreview(col, cam);
        });

        // In-game HUD helper text (26.2: HudElementRegistry; HudRenderCallback is gone).
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(Gradient.MOD_ID, "mode_status"),
                (g, deltaTracker) -> HudOverlay.render(g));

        Gradient.LOG.info("{} initialised (fabric)", Gradient.MOD_ID);
    }
}
