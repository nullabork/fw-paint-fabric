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
    /** Held (default G): shows the radial selector wheel for paint type + placement mode. */
    public static KeyMapping wheelKey;
    /** Cycle the active palette through the saved list (default B). */
    public static KeyMapping cyclePaletteKey;
    /** Held modifier (default L-Ctrl): a marker-removing click clears the whole connected plane. */
    public static KeyMapping clearConnectedKey;

    private Gradient() {}

    /** Build the (vanilla) key mappings; each loader entry registers them its own way. */
    public static void createKeyMappings() {
        openKey = new KeyMapping("key.gradient.open", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K, KeyMapping.Category.MISC);
        wheelKey = new KeyMapping("key.gradient.wheel", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G, KeyMapping.Category.MISC);
        cyclePaletteKey = new KeyMapping("key.gradient.cycle_palette", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B, KeyMapping.Category.MISC);
        clearConnectedKey = new KeyMapping("key.gradient.clear_connected", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_CONTROL, KeyMapping.Category.MISC);
    }

    /** Physical state of {@link #wheelKey} last tick (edge detection for hold-to-open). */
    private static boolean wheelWasDown;

    /** Runs once at the end of every client tick (wired by the loader entrypoints). */
    public static void endClientTick(Minecraft client) {
        while (openKey.consumeClick()) {
            // 26.2: open screens via setScreenAndShow (there is no setScreen).
            client.setScreenAndShow(new GradientScreen());
        }
        // Hold-to-open selector wheel. Open only on a fresh down-transition rather than
        // consumeClick: when a screen closes while the key is still physically held, the game
        // re-syncs key state and queues a phantom click, which would instantly reopen it.
        while (wheelKey.consumeClick()) {} // drain the click queue either way
        boolean down = wheelKey.isDown();
        if (down && !wheelWasDown && client.gui.screen() == null) {
            if (holdingPaintTool(client)) {
                client.setScreenAndShow(new co.fax.wang.wheel.WheelScreen());
            } else {
                overlay(client, "FW Paint: hold your paint tool to use the selector wheel");
            }
        }
        wheelWasDown = down;
        while (cyclePaletteKey.consumeClick()) {
            cyclePalette();
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

    /** Set the placement mode (wheel selection), persist it, and flash an action-bar message. */
    public static void setPlacementMode(PlacementMode mode) {
        GradientConfig cfg = ConfigManager.get();
        cfg.placementMode = mode;
        ConfigManager.save();
        overlay(Minecraft.getInstance(), "FW Paint — Placement: " + mode.shortName());
    }

    /** Set the paint type (wheel selection), persist it, and flash an action-bar message. */
    public static void setPaintType(PaintType type) {
        Minecraft mc = Minecraft.getInstance();
        GradientConfig cfg = ConfigManager.get();
        cfg.activePaintType = type;
        ConfigManager.save();
        // Landing on a palette-driven paint with nothing of its kind set up → guide the user.
        co.fax.wang.palette.PaletteKind kind = kindFor(cfg.activePaintType);
        if (kind != null && co.fax.wang.palette.PaletteStore.allOf(kind).isEmpty()) {
            overlay(mc, "FW Paint — " + cfg.activePaintType.label() + ": press "
                    + boundKey("open") + " to set up a " + kind.label().toLowerCase(java.util.Locale.ROOT));
        } else {
            overlay(mc, "FW Paint — Paint type: " + cfg.activePaintType.label());
        }
    }

    /** Solid paint via the wheel: place the closest colour match instead of a picked block. */
    public static void setSolidClosestColour() {
        GradientConfig cfg = ConfigManager.get();
        cfg.solidMatch = SolidMatch.CLOSEST_COLOR;
        ConfigManager.save();
        overlay(Minecraft.getInstance(), "FW Paint — Solid: closest colour match");
    }

    /** Solid paint via the wheel: place exactly this block (selects it, like the Solid tab). */
    public static void setSolidBlock(String id) {
        GradientConfig cfg = ConfigManager.get();
        cfg.solidMatch = SolidMatch.SELECTED;
        cfg.solidExcludedBlocks.remove(id); // selecting an excluded block un-excludes it
        cfg.solidBlock = id;
        ConfigManager.save();
        overlay(Minecraft.getInstance(), "FW Paint — Solid: " + toolDisplayName(id));
    }

    /** Make a palette/pattern the active one of its kind (wheel selection). */
    public static void setActivePalette(co.fax.wang.palette.Palette p) {
        co.fax.wang.palette.PaletteStore.setActive(p.id);
        overlay(Minecraft.getInstance(), "FW Paint — " + p.kind.label() + ": " + p.name);
    }

    /** The palette kind a paint type consumes (null for Solid). */
    public static co.fax.wang.palette.PaletteKind kindFor(PaintType type) {
        return switch (type) {
            case GRADIENT, NOISE -> co.fax.wang.palette.PaletteKind.GRADIENT;
            case PATTERN -> co.fax.wang.palette.PaletteKind.PATTERN;
            case SOLID -> null;
        };
    }

    /**
     * Step the active item through the saved list (the palette keybind) — filtered to the kind
     * the current paint type consumes (patterns in Pattern paint, gradients otherwise).
     */
    public static void cyclePalette() {
        Minecraft mc = Minecraft.getInstance();
        if (!holdingPaintTool(mc)) {
            overlay(mc, "FW Paint: hold your paint tool to cycle palettes");
            return;
        }
        co.fax.wang.palette.PaletteKind kind = kindFor(ConfigManager.get().activePaintType);
        if (kind == null) kind = co.fax.wang.palette.PaletteKind.GRADIENT; // Solid: cycle gradients
        co.fax.wang.palette.Palette next = co.fax.wang.palette.PaletteStore.cycleActive(1, kind);
        overlay(mc, next == null
                ? "FW Paint: no " + kind.label().toLowerCase(java.util.Locale.ROOT) + "s — press "
                        + boundKey("open") + " to set one up"
                : "FW Paint — " + kind.label() + ": " + next.name);
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
     * Display name of a mod keybind ("open" / "wheel" / "palette" / "clear") as currently bound —
     * UI text (e.g. the Help tab) uses this so rebinds always read correctly.
     */
    public static String boundKey(String id) {
        KeyMapping k = switch (id) {
            case "open" -> openKey;
            case "wheel" -> wheelKey;
            case "palette" -> cyclePaletteKey;
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
