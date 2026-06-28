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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a block's actual texture pixels (via its particle sprite → the texture PNG in the resource
 * manager) and derives the colour used for gradients. Pixels are cached per block. No mixin needed —
 * the PNG is read straight from resources, which exposes RGBA pixels.
 *
 * <p>Falls back to the block's default {@code MapColor} when no texture can be read.
 */
public final class BlockTextures {

    private BlockTextures() {}

    private static final Logger LOG = LoggerFactory.getLogger("gradient/textures");
    private static final int[] EMPTY = new int[0];
    private static final Map<Block, int[]> CACHE = new HashMap<>();

    /** The 0xRRGGBB value a block contributes to the gradient under the given mode + pixel fraction. */
    public static int gradientRgb(Block block, GradientMode mode, double pixelFraction) {
        int[] px = pixels(block);
        if (px.length > 0) {
            if (mode.usesPixelPercent()) {
                return TextureStats.topColor(px, pixelFraction, mode.selectsLightest());
            }
            return TextureStats.averageColor(px);
        }
        var mapColor = block.defaultMapColor(); // fallback when no texture is available
        return mapColor == null ? 0 : mapColor.col;
    }

    /** Drop cached pixels — call on a resource reload so re-read textures aren't stale. */
    public static void clearCache() {
        CACHE.clear();
    }

    private static int[] pixels(Block block) {
        int[] cached = CACHE.get(block);
        if (cached != null) return cached;
        int[] loaded = load(block);
        CACHE.put(block, loaded);
        return loaded;
    }

    private static int[] load(Block block) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getModelManager() == null) return EMPTY;
            TextureAtlasSprite sprite = mc.getModelManager().getBlockStateModelSet()
                    .getParticleMaterial(block.defaultBlockState()).sprite();
            Identifier tex = sprite.contents().name();
            Identifier png = Identifier.fromNamespaceAndPath(tex.getNamespace(), "textures/" + tex.getPath() + ".png");
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
                return out;
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
