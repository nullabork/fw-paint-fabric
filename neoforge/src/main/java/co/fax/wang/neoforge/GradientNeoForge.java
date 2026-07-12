package co.fax.wang.neoforge;

import co.fax.wang.Gradient;
import co.fax.wang.HudOverlay;
import co.fax.wang.MarkerManager;
import co.fax.wang.PaintPlacer;
import co.fax.wang.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * NeoForge entrypoint: everything real lives in the loader-free {@link Gradient} core — this
 * class only registers the key mappings and forwards NeoForge's tick, render, input, and HUD
 * events into it.
 */
@Mod(value = Gradient.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Gradient.MOD_ID, value = Dist.CLIENT)
public class GradientNeoForge {

    public GradientNeoForge() {
        ConfigManager.init(FMLPaths.CONFIGDIR.get());
        Gradient.createKeyMappings();
        Gradient.LOG.info("{} initialised (neoforge)", Gradient.MOD_ID);
    }

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(Gradient.openKey);
        event.register(Gradient.cycleKey);
        event.register(Gradient.paintTypeKey);
        event.register(Gradient.clearConnectedKey);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Gradient.endClientTick(Minecraft.getInstance());
    }

    /**
     * While the tool is engaged, suppress the vanilla attack/use interaction the click would
     * trigger — the mod does the marking/placing itself. This event fires at the input layer, so
     * our own synthesized placements (which call the game mode directly) never re-enter it.
     */
    @SubscribeEvent
    static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && Gradient.toolEngaged()) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /**
     * In-world marker + placement-preview rendering, submitted through the same
     * {@code SubmitNodeCollector} phase vanilla uses for entities + the block outline. Our
     * geometry builds its own fresh identity PoseStack (camera-relative), so only the collector
     * and camera position are taken from the event.
     */
    @SubscribeEvent
    static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        var col = event.getSubmitNodeCollector();
        var cam = event.getLevelRenderState().cameraRenderState.pos;
        MarkerManager.render(col, cam);
        PaintPlacer.renderPreview(col, cam);
    }

    @SubscribeEvent
    static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                Identifier.fromNamespaceAndPath(Gradient.MOD_ID, "mode_status"),
                (g, deltaTracker) -> HudOverlay.render(g));
    }
}
