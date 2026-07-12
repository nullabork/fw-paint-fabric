package co.fax.wang;

import co.fax.wang.config.ConfigManager;
import co.fax.wang.config.GradientConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-independent core of the mod: the key mappings, the shared {@link PlacementMode}/
 * {@link PaintType} state, and the per-tick driving of markers + placement. The thin loader
 * entrypoints ({@code co.fax.wang.fabric} / {@code co.fax.wang.neoforge}) create this state via
 * {@link #createKeyMappings()}, register the mappings with their loader, and forward tick /
 * render / input events here. See fabric-26.2-mod-starter.md for API notes.
 */
public final class Gradient {

    public static final String MOD_ID = "gradient";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public static KeyMapping openKey;
    public static KeyMapping cycleKey;
    public static KeyMapping paintTypeKey;
    /** Held modifier (default L-Ctrl): a marker-removing click clears the whole connected plane. */
    public static KeyMapping clearConnectedKey;

    private Gradient() {}

    /** Build the (vanilla) key mappings; each loader entry registers them its own way. */
    public static void createKeyMappings() {
        openKey = new KeyMapping("key.gradient.open", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K, KeyMapping.Category.MISC);
        cycleKey = new KeyMapping("key.gradient.cycle", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G, KeyMapping.Category.MISC);
        paintTypeKey = new KeyMapping("key.gradient.paint_type", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V, KeyMapping.Category.MISC);
        clearConnectedKey = new KeyMapping("key.gradient.clear_connected", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_CONTROL, KeyMapping.Category.MISC);
    }

    /** Runs once at the end of every client tick (wired by the loader entrypoints). */
    public static void endClientTick(Minecraft client) {
        while (openKey.consumeClick()) {
            // 26.2: open screens via setScreenAndShow (there is no setScreen).
            client.setScreenAndShow(new GradientScreen());
        }
        while (cycleKey.consumeClick()) {
            cyclePlacement();
        }
        while (paintTypeKey.consumeClick()) {
            switchPaintType();
        }
        MarkerManager.tick(client);
        PaintPlacer.tick(client);
    }

    /**
     * While the tool is engaged, vanilla left/right clicks must be suppressed so they don't
     * break/use the world — the mod does the marking / placing itself. Our OWN synthesized
     * placement interaction (via {@link BlockPlacement}) must still pass, hence the isPlacing()
     * guard (only relevant on loaders whose hook wraps the interaction itself, like Fabric).
     */
    public static boolean shouldCancelClick() {
        return toolEngaged() && !BlockPlacement.isPlacing();
    }

    /** True when the configured paint tool item is in the player's main hand. */
    public static boolean holdingPaintTool(Minecraft mc) {
        if (mc.player == null) return false;
        GradientConfig cfg = ConfigManager.get();
        if (cfg.paintTool.isEmpty()) return false;
        Identifier id = BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem());
        return id != null && cfg.paintTool.equals(id.toString());
    }

    /** The global placement mode (DISABLED when the tool isn't held). */
    public static PlacementMode currentPlacement(Minecraft mc) {
        if (!holdingPaintTool(mc)) return PlacementMode.DISABLED;
        return ConfigManager.get().placementMode;
    }

    /** Advance the placement mode, persist it, and flash an action-bar message. */
    public static void cyclePlacement() {
        Minecraft mc = Minecraft.getInstance();
        if (!holdingPaintTool(mc)) {
            overlay(mc, "FW Paint: hold your paint tool to cycle placement");
            return;
        }
        GradientConfig cfg = ConfigManager.get();
        cfg.placementMode = cfg.placementMode.next();
        ConfigManager.save();
        overlay(mc, "FW Paint — Placement: " + cfg.placementMode.shortName());
    }

    /** Cycle Gradient → Noise → Solid paint. Only works while the paint tool is held; persisted. */
    public static void switchPaintType() {
        Minecraft mc = Minecraft.getInstance();
        if (!holdingPaintTool(mc)) {
            overlay(mc, "FW Paint: hold your paint tool to switch paint type");
            return;
        }
        GradientConfig cfg = ConfigManager.get();
        cfg.activePaintType = cfg.activePaintType.next();
        ConfigManager.save();
        overlay(mc, "FW Paint — Paint type: " + cfg.activePaintType.label());
    }

    private static void overlay(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.player.sendOverlayMessage(Component.literal(msg));
        }
    }

    /** True when a tool is held and the placement mode isn't Disabled. */
    public static boolean toolEngaged() {
        return currentPlacement(Minecraft.getInstance()) != PlacementMode.DISABLED;
    }

    /** True while the clear-connected modifier (default L-Ctrl) is held. */
    public static boolean clearConnectedDown() {
        return clearConnectedKey != null && clearConnectedKey.isDown();
    }

    /**
     * Display name of a mod keybind ("open" / "cycle" / "paint" / "clear") as currently bound —
     * UI text (e.g. the Help tab) uses this so rebinds always read correctly.
     */
    public static String boundKey(String id) {
        KeyMapping k = switch (id) {
            case "open" -> openKey;
            case "cycle" -> cycleKey;
            case "paint" -> paintTypeKey;
            case "clear" -> clearConnectedKey;
            default -> null;
        };
        return k == null ? "?" : k.getTranslatedKeyMessage().getString();
    }

    /** Resolve an item registry id (as stored in config) to its block, or null. */
    public static net.minecraft.world.level.block.Block blockOfItemId(String id) {
        if (id == null || id.isEmpty()) return null;
        Identifier ident = Identifier.tryParse(id);
        if (ident == null) return null;
        return BuiltInRegistries.ITEM.getOptional(ident)
                .map(item -> item instanceof net.minecraft.world.item.BlockItem bi ? bi.getBlock() : null)
                .orElse(null);
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
