package co.fax.wang;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a block's actual texture pixels (via its particle sprite → the texture PNG in the resource
 * manager) and derives the colour/value used for gradients. Pixels and signatures are cached per
 * block. No mixin needed — the PNG is read straight from resources, which exposes RGBA pixels.
 *
 * <p>Falls back to the block's default {@code MapColor} when no texture can be read.
 */
public final class BlockTextures {

    private BlockTextures() {}

    private static final Logger LOG = LoggerFactory.getLogger("gradient/textures");
    private static final int SIGNATURE_GRID = 8;

    private record Tex(int[] argb, int w, int h) {}
    private static final Tex EMPTY = new Tex(new int[0], 0, 0);

    private static final Map<Block, Tex> TEX_CACHE = new HashMap<>();
    private static final Map<Block, int[]> SIG_CACHE = new HashMap<>();

    /**
     * The 0xRRGGBB value a block contributes to the gradient. For most modes this is a texture
     * colour; for the diff modes it's a grey encoding of how different the block's texture is from
     * {@code startBlock} (so the gradient sorts by similarity to the start).
     */
    public static int gradientValue(Block block, Block startBlock, GradientMode mode, double pixelFraction) {
        if (mode.isStartRelative() && startBlock != null) {
            int[] sig = signature(block), start = signature(startBlock);
            double d = (mode == GradientMode.COLOR_DIFF)
                    ? TextureStats.colorDiff(sig, start)
                    : TextureStats.bwDiff(sig, start);
            int grey = Math.max(0, Math.min(255, (int) Math.round(d)));
            return (grey << 16) | (grey << 8) | grey; // luminance == the diff
        }

        int[] px = tex(block).argb();
        if (px.length > 0) {
            if (mode.usesPixelPercent()) {
                return TextureStats.topColor(px, pixelFraction, mode.selectsLightest());
            }
            return TextureStats.averageColor(px);
        }
        var mapColor = block.defaultMapColor(); // fallback when no texture is available
        return mapColor == null ? 0 : mapColor.col;
    }

    /** Start-independent brightness of a block (its average texture colour) — used for default endpoints. */
    public static double baseLuminance(Block block) {
        return TextureStats.luminance(gradientValue(block, null, GradientMode.COLOR, 0.5));
    }

    /** Drop caches — call on a resource reload so re-read textures aren't stale. */
    public static void clearCache() {
        TEX_CACHE.clear();
        SIG_CACHE.clear();
    }

    private static int[] signature(Block block) {
        return SIG_CACHE.computeIfAbsent(block, b -> {
            Tex t = tex(b);
            if (t.argb().length == 0) {
                int[] empty = new int[SIGNATURE_GRID * SIGNATURE_GRID];
                Arrays.fill(empty, -1);
                return empty;
            }
            return TextureStats.downsample(t.argb(), t.w(), t.h(), SIGNATURE_GRID);
        });
    }

    private static Tex tex(Block block) {
        Tex cached = TEX_CACHE.get(block);
        if (cached != null) return cached;
        Tex loaded = load(block);
        TEX_CACHE.put(block, loaded);
        return loaded;
    }

    private static Tex load(Block block) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getModelManager() == null) return EMPTY;
            TextureAtlasSprite sprite = mc.getModelManager().getBlockStateModelSet()
                    .getParticleMaterial(block.defaultBlockState()).sprite();
            Identifier name = sprite.contents().name();
            Identifier png = Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + ".png");
            List<Resource> stack = mc.getResourceManager().getResourceStack(png);
            if (stack.isEmpty()) return EMPTY;
            try (InputStream in = stack.get(stack.size() - 1).open(); NativeImage img = NativeImage.read(in)) {
                int w = img.getWidth(), h = img.getHeight();
                int[] out = new int[w * h];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        out[y * w + x] = abgrToArgb(img.getPixel(x, y));
                    }
                }
                return new Tex(out, w, h);
            }
        } catch (Exception e) {
            LOG.warn("texture read failed for {}: {}", block, e.toString());
            return EMPTY;
        }
    }

    /** NativeImage stores pixels as ABGR (0xAABBGGRR); convert to ARGB (0xAARRGGBB). */
    private static int abgrToArgb(int abgr) {
        int a = (abgr >>> 24) & 0xFF, b = (abgr >> 16) & 0xFF, g = (abgr >> 8) & 0xFF, r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
