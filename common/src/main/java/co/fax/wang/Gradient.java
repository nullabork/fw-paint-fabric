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
    /** Next item for the current paint type: palette, pattern or Solid block (default B). */
    public static KeyMapping cyclePaletteKey;
    /**
     * The one marker modifier (default L-Ctrl): "the other way / both ends", never an action of
     * its own — reverse an extrude, mirror curve handles, set or reset every line node at once,
     * and with middle-click delete everything connected (a whole block-marker group or line).
     */
    public static KeyMapping modifierKey;
    /** Held while dragging any marker control (default L-Alt): lock the drag to one axis. */
    public static KeyMapping axisLockKey;

    private Gradient() {}

    /** The mod's own keybind category (Options → Controls → Key Binds → FW Paint). */
    private static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

    /** Build the (vanilla) key mappings; each loader entry registers them its own way. */
    public static void createKeyMappings() {
        // 26.3 dropped GLFW for SDL — key codes come from vanilla's own InputConstants now,
        // and Type.KEYSYM/SCANCODE collapsed into Type.KEYBOARD.
        openKey = new KeyMapping("key.gradient.open", InputConstants.Type.KEYBOARD,
                InputConstants.KEY_K, KEY_CATEGORY);
        wheelKey = new KeyMapping("key.gradient.wheel", InputConstants.Type.KEYBOARD,
                InputConstants.KEY_G, KEY_CATEGORY);
        cyclePaletteKey = new KeyMapping("key.gradient.cycle_palette", InputConstants.Type.KEYBOARD,
                InputConstants.KEY_B, KEY_CATEGORY);
        modifierKey = new KeyMapping("key.gradient.modifier", InputConstants.Type.KEYBOARD,
                InputConstants.KEY_LCONTROL, KEY_CATEGORY);
        axisLockKey = new KeyMapping("key.gradient.axis_lock", InputConstants.Type.KEYBOARD,
                InputConstants.KEY_LALT, KEY_CATEGORY);
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
                overlay(client, "FW Paint: hold your paint tool to use the wheel");
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

    /**
     * True when a cell counts as empty space for painting: air or a fluid (water, lava, bubble
     * column). Replaceable plants — hanging roots, grass, vines, snow layers — are NOT empty:
     * painting keeps them and works around them. Waterlogged solids are NOT empty. Columns grow
     * through it, fills flood it, scans see past it.
     */
    public static boolean emptyCell(net.minecraft.world.level.block.state.BlockState state) {
        if (state.isAir()) return true;
        var block = state.getBlock();
        return state.canBeReplaced() && (block instanceof net.minecraft.world.level.block.LiquidBlock
                || block instanceof net.minecraft.world.level.block.BubbleColumnBlock);
    }

    /** True when the configured paint tool item is in the player's main hand. */
    public static boolean holdingPaintTool(Minecraft mc) {
        if (mc.player == null) return false;
        if (DebugRender.FORCE_TOOL) return true;
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

    /**
     * Don't match palette: face selection skips blocks of the active palette / Solid block /
     * pattern, so a re-click doesn't spread over the layer just placed. Session-only on
     * purpose — a forgotten persisted toggle would look like a bug.
     */
    public static boolean dontMatchPalette = false;

    public static void toggleDontMatchPalette() {
        dontMatchPalette = !dontMatchPalette;
        overlay(Minecraft.getInstance(),
                message("Place", "Don't match palette " + (dontMatchPalette ? "on" : "off"), null));
    }

    /** Set the placement mode (wheel selection), persist it, and flash an action-bar message. */
    public static void setPlacementMode(PlacementMode mode) {
        GradientConfig cfg = ConfigManager.get();
        cfg.placementMode = mode;
        ConfigManager.save();
        overlay(Minecraft.getInstance(), message("Place", mode.shortName(), null));
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
            overlay(mc, message(cfg.activePaintType.label(),
                    "no " + kind.label().toLowerCase(java.util.Locale.ROOT) + "s yet", "Paint"));
        } else {
            overlay(mc, message("Paint", cfg.activePaintType.label(), null));
        }
    }

    /** Solid paint via the wheel: switch how the placed block is chosen. */
    public static void setSolidMatch(SolidMatch match) {
        GradientConfig cfg = ConfigManager.get();
        cfg.solidMatch = match;
        ConfigManager.save();
        overlay(Minecraft.getInstance(), message("Solid", match.displayName(), null));
    }

    /** Solid paint via the wheel: place exactly this block (selects it, like the Paint tab's Solid list). */
    public static void setSolidBlock(String id) {
        GradientConfig cfg = ConfigManager.get();
        cfg.solidMatch = SolidMatch.SELECTED;
        cfg.solidExcludedBlocks.remove(id); // selecting an excluded block un-excludes it
        cfg.solidBlock = id;
        ConfigManager.save();
        overlay(Minecraft.getInstance(), message("Solid", toolDisplayName(id), null));
    }

    /** Make a palette/pattern the active one of its kind (wheel selection). */
    public static void setActivePalette(co.fax.wang.palette.Palette p) {
        co.fax.wang.palette.PaletteStore.setActive(p.id);
        overlay(Minecraft.getInstance(), message(p.kind.label(), p.name, null));
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
     * The B key: step to the next item for the current paint type — the next palette (Gradient,
     * Noise), the next pattern (Pattern), or the next block from the Solid source (Solid; makes
     * "This block" the match mode, like picking it on the Paint tab).
     */
    public static void cyclePalette() {
        Minecraft mc = Minecraft.getInstance();
        if (!holdingPaintTool(mc)) {
            overlay(mc, "FW Paint: hold your paint tool to switch with " + boundKey("palette"));
            return;
        }
        GradientConfig cfg = ConfigManager.get();
        if (cfg.activePaintType == PaintType.SOLID) {
            cycleSolidBlock(mc, cfg);
            return;
        }
        co.fax.wang.palette.PaletteKind kind = kindFor(cfg.activePaintType);
        co.fax.wang.palette.Palette next = co.fax.wang.palette.PaletteStore.cycleActive(1, kind);
        overlay(mc, next == null
                ? message(kind.label(), "no " + kind.label().toLowerCase(java.util.Locale.ROOT) + "s yet", "Paint")
                : message(kind.label(), next.name, null));
    }

    /**
     * Solid's B: the block after the current one among the source's non-excluded blocks. From
     * another match mode, the first press switches back to the current block (or the first).
     */
    private static void cycleSolidBlock(Minecraft mc, GradientConfig cfg) {
        java.util.List<String> ids = solidCandidates(mc, cfg);
        if (ids.isEmpty()) {
            overlay(mc, message("Solid", "no blocks in " + cfg.source.displayName(), "Paint"));
            return;
        }
        int idx = ids.indexOf(cfg.solidBlock);
        int next = cfg.solidMatch == SolidMatch.SELECTED || idx < 0 ? Math.floorMod(idx + 1, ids.size()) : idx;
        setSolidBlock(ids.get(next));
    }

    /**
     * Item ids of the unique placeable blocks in the Solid source range, in slot order, without
     * the excluded ones — the Solid list's order, so B walks it top to bottom.
     */
    public static java.util.List<String> solidCandidates(Minecraft mc, GradientConfig cfg) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (mc.player == null) return out;
        var items = mc.player.getInventory().getNonEquipmentItems();
        int from = cfg.source == GradientSource.INVENTORY ? 9 : 0;
        int to = Math.min(cfg.source == GradientSource.HOTBAR ? 9 : 36, items.size());
        for (int slot = from; slot < to; slot++) {
            ItemStack st = items.get(slot);
            if (!(st.getItem() instanceof net.minecraft.world.item.BlockItem bi)) continue;
            if (bi.getBlock().defaultBlockState().isAir()) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(st.getItem());
            if (id == null) continue;
            String s = id.toString();
            if (!out.contains(s) && !cfg.solidExcludedBlocks.contains(s)) out.add(s);
        }
        return out;
    }

    /**
     * An action-bar message in the one format every FW Paint message uses:
     * {@code FW Paint · <area>: <what happened> — <open key> › <tab>}. {@code fixTab} (the
     * screen tab where it gets fixed) is null when there's nothing to fix.
     */
    public static String message(String area, String what, String fixTab) {
        String msg = "FW Paint · " + area + ": " + what;
        return fixTab == null ? msg : msg + " — " + fixIn(fixTab);
    }

    /** "K › Paint": where something is fixed, with the open key's live name. */
    public static String fixIn(String tab) {
        return boundKey("open") + " › " + tab;
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

    /** True while the marker modifier (default L-Ctrl) is held. */
    public static boolean modifierDown() {
        return modifierKey != null && modifierKey.isDown();
    }

    /** Old name of {@link #modifierDown()} (clear-connected and line-link were merged into it). */
    public static boolean clearConnectedDown() {
        return modifierDown();
    }

    /** Old name of {@link #modifierDown()} (clear-connected and line-link were merged into it). */
    public static boolean lineLinkDown() {
        return modifierDown();
    }

    /** True while the axis-lock modifier (default L-Alt) is held. */
    public static boolean axisLockDown() {
        return axisLockKey != null && axisLockKey.isDown();
    }

    /**
     * Display name of a mod keybind ("open" / "wheel" / "palette" / "modifier" / "axis"; the
     * retired "clear" and "link" name the modifier too) as currently bound —
     * UI text (e.g. the Help tab) uses this so rebinds always read correctly.
     */
    public static String boundKey(String id) {
        KeyMapping k = switch (id) {
            case "open" -> openKey;
            case "wheel" -> wheelKey;
            case "palette" -> cyclePaletteKey;
            case "modifier", "clear", "link" -> modifierKey;
            case "axis" -> axisLockKey;
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
