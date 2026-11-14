package co.fax.wang.shape;

import co.fax.wang.Gradient;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A block's look for the marker block display ({@link MarkerStyle#block}): the sprite its model
 * shows on each of the six sides (so a log has its rings on top and bark round it) and that side's
 * tint. Marker faces are drawn with it as one textured, face-shaded square per block cell — a
 * picture of the block, not the block: nothing is placed and no model shape is kept (stairs show
 * as full cubes of their texture).
 *
 * <p>Drawn over the block atlas through the cutout block pipeline (solid) or the translucent beacon
 * beam pipeline (see-through): the textured, depth-tested channels custom geometry can use here.
 * Both cull back faces, so every square goes in both windings and reads from inside a hollow
 * marker too.
 */
final class BlockSkin {

    private BlockSkin() {}

    /** Most block squares one marker draws; bigger markers fall back to the flat tint. */
    static final int MAX_FACES = 60_000;

    private static final float[] SHADE = {0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f}; // by Direction.ordinal()

    /** Per side (Direction.ordinal()): sprite UV bounds {u0, u1, v0, v1} and the 0xRRGGBB tint × shade. */
    record Skin(RenderType solid, RenderType translucent, RenderType through, float[][] uv, int[] rgb) {

        /**
         * Fully solid faces write depth like placed blocks would. See-through ones must not, or
         * they would hide every marker drawn behind them: those go through the translucent beacon
         * beam pipeline (same vertex data, no depth write, sorted back to front).
         */
        RenderType type(int alpha) {
            return alpha >= 255 ? solid : translucent;
        }
    }

    private static final Map<String, Skin> CACHE = new HashMap<>();
    private static Object cacheModels; // the model set the cache was read from (a reload swaps it)

    /** The skin of the block with item id {@code id}; null when unset, unknown or unreadable. */
    static Skin of(String id) {
        if (id == null || id.isEmpty()) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getModelManager() == null) return null;
        Object models = mc.getModelManager().getBlockStateModelSet();
        if (models != cacheModels) {
            CACHE.clear();
            cacheModels = models;
        }
        if (CACHE.containsKey(id)) return CACHE.get(id);
        Skin skin = null;
        try {
            skin = load(mc, Gradient.blockOfItemId(id));
        } catch (RuntimeException e) {
            // an odd model: no skin, the marker keeps its flat tint
        }
        CACHE.put(id, skin);
        return skin;
    }

    private static Skin load(Minecraft mc, Block block) {
        if (block == null) return null;
        BlockState state = block.defaultBlockState();
        BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(42L), parts);
        TextureAtlasSprite fallback = model.particleMaterial().sprite();
        Identifier atlas = fallback.atlasLocation();
        float[][] uv = new float[6][];
        int[] rgb = new int[6];
        for (Direction d : Direction.values()) {
            BakedQuad quad = quadFacing(parts, d);
            TextureAtlasSprite sprite = fallback;
            int tint = 0xFFFFFF;
            if (quad != null && quad.materialInfo().sprite().atlasLocation().equals(atlas)) {
                sprite = quad.materialInfo().sprite();
                int index = quad.materialInfo().tintIndex();
                if (index >= 0) {
                    var source = mc.getBlockColors().getTintSource(state, index);
                    if (source != null) tint = source.color(state) & 0xFFFFFF;
                }
            }
            uv[d.ordinal()] = new float[]{sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1()};
            float s = SHADE[d.ordinal()];
            rgb[d.ordinal()] = (Math.round(((tint >> 16) & 0xFF) * s) << 16)
                    | (Math.round(((tint >> 8) & 0xFF) * s) << 8) | Math.round((tint & 0xFF) * s);
        }
        // Solid faces go through the real cutout block pipeline (opaque, depth-written, sorted
        // by depth like placed blocks). The text pipeline blends: its faces showed through each
        // other, so a tube's far side appeared through its top.
        RenderType solid = atlas.equals(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
                ? RenderTypes.cutoutMovingBlock() : RenderTypes.text(atlas);
        return new Skin(solid, RenderTypes.beaconBeam(atlas, true),
                RenderTypes.textSeeThrough(atlas), uv, rgb);
    }

    /** The model's first quad on side {@code d}: a culled face, else an unculled quad facing that way. */
    private static BakedQuad quadFacing(List<BlockStateModelPart> parts, Direction d) {
        for (BlockStateModelPart part : parts) {
            List<BakedQuad> quads = part.getQuads(d);
            if (!quads.isEmpty()) return quads.get(0);
        }
        for (BlockStateModelPart part : parts) {
            for (BakedQuad q : part.getQuads(null)) {
                if (q.direction() == d) return q;
            }
        }
        return null;
    }

    /** Where a marker reports its block faces: flat runs of cells, as {@link #emitRect} takes them. */
    interface RectSink {
        void rect(int face, float w, int a0, int a1, int b0, int b1);
    }

    /**
     * Submit a marker's block faces ({@code faces} reports them, in pose-local coordinates).
     * Normally one depth-tested pass. With {@code through} (faces shown when obscured) they go
     * through the depth-ignoring pipeline instead, which draws strictly in the order given — so
     * every block square is first sorted far to near from {@code camLocal} (the camera in the same local
     * coordinates), or far faces would paint over near ones.
     */
    static void submit(net.minecraft.client.renderer.SubmitNodeCollector col, PoseStack ps, Skin skin, int alpha,
                       boolean through, net.minecraft.world.phys.Vec3 camLocal,
                       java.util.function.Consumer<RectSink> faces) {
        if (!through) {
            col.submitCustomGeometry(ps, skin.type(alpha), (pose, vc) ->
                    faces.accept((f, w, a0, a1, b0, b1) -> emitRect(pose, vc, skin, alpha, f, w, a0, a1, b0, b1)));
            return;
        }
        // One entry per block square (not per run: a long run's middle says little about which of
        // its squares is in front of what). {face, w, a, b} plus the squared distance to its middle.
        it.unimi.dsi.fastutil.floats.FloatArrayList quads = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        it.unimi.dsi.fastutil.longs.LongArrayList keys = new it.unimi.dsi.fastutil.longs.LongArrayList();
        faces.accept((f, w, a0, a1, b0, b1) -> {
            int axis = TubeBuild.axisOf(f);
            int pa = axis == 0 ? 1 : 0, pb = axis == 2 ? 1 : 2;
            double[] c = new double[3];
            c[axis] = w;
            for (int i = a0; i < a1; i++) {
                for (int j = b0; j < b1; j++) {
                    c[pa] = i + 0.5;
                    c[pb] = j + 0.5;
                    double dx = c[0] - camLocal.x, dy = c[1] - camLocal.y, dz = c[2] - camLocal.z;
                    float d = (float) (dx * dx + dy * dy + dz * dz);
                    // Distances are never negative, so their float bits sort like the numbers.
                    keys.add(((long) Float.floatToIntBits(d) << 32) | (quads.size() / 4));
                    quads.add(f);
                    quads.add(w);
                    quads.add(i);
                    quads.add(j);
                }
            }
        });
        long[] order = keys.toLongArray();
        java.util.Arrays.sort(order);
        float[] q = quads.elements();
        col.submitCustomGeometry(ps, skin.through(), (pose, vc) -> {
            for (int k = order.length - 1; k >= 0; k--) { // far to near
                int at = (int) order[k] * 4;
                int a = (int) q[at + 2], b2 = (int) q[at + 3];
                emitRect(pose, vc, skin, alpha, (int) q[at], q[at + 1], a, a + 1, b2, b2 + 1);
            }
        });
        // Then the ordinary depth-tested picture over it, in a later order. Wherever the marker
        // can be seen directly this is what shows, so it looks exactly as it does with the option
        // off, in front of and behind the mod's other drawing alike; the pass above is left
        // showing only where real blocks are in the way.
        if (alpha >= 255) {
            col.order(1).submitCustomGeometry(ps, skin.solid(), (pose, vc) ->
                    faces.accept((f, w, a0, a1, b0, b1) -> emitRect(pose, vc, skin, alpha, f, w, a0, a1, b0, b1)));
        }
    }

    /** The faces of the block cell at (x, y, z) whose bit is set in {@code mask}, reported to {@code sink}. */
    static void cell(RectSink sink, int x, int y, int z, int mask) {
        int[] c = {x, y, z};
        for (int f = 0; f < 6; f++) {
            if ((mask & (1 << f)) == 0) continue;
            int axis = TubeBuild.axisOf(f);
            int a = axis == 0 ? 1 : 0, b = axis == 2 ? 1 : 2;
            float w = (f % 2 == 1) ? c[axis] + 1 + TubeBuild.EXPAND : c[axis] - TubeBuild.EXPAND;
            sink.rect(f, w, c[a], c[a] + 1, c[b], c[b] + 1);
        }
    }

    // ---- emitters (coordinates relative to the pose origin) -------------------------------------

    /**
     * A flat run of block faces on side {@code face} (Direction.ordinal()) at coordinate {@code w}
     * on that side's axis, covering cells [a0, a1) × [b0, b1) on the other two axes (in x, y, z
     * order): one textured square per cell, so the texture tiles like placed blocks would.
     */
    static void emitRect(PoseStack.Pose pose, VertexConsumer vc, Skin skin, int alpha,
                         int face, float w, int a0, int a1, int b0, int b1) {
        int axis = TubeBuild.axisOf(face);
        int pa = axis == 0 ? 1 : 0, pb = axis == 2 ? 1 : 2;
        float[] uv = skin.uv()[face];
        int argb = (alpha << 24) | skin.rgb()[face];
        float[] p = new float[3];
        p[axis] = w;
        float[][] quad = new float[4][5]; // x, y, z, u, v
        for (int i = a0; i < a1; i++) {
            for (int j = b0; j < b1; j++) {
                for (int k = 0; k < 4; k++) {
                    int da = (k == 1 || k == 2) ? 1 : 0, db = k >= 2 ? 1 : 0;
                    p[pa] = i + da;
                    p[pb] = j + db;
                    // Texture right way up on the sides: v runs down the block as y falls.
                    float s, t;
                    if (axis == 1) {
                        s = da;
                        t = db;
                    } else if (axis == 2) {
                        s = da;
                        t = 1 - db;
                    } else {
                        s = db;
                        t = 1 - da;
                    }
                    float[] q = quad[k];
                    q[0] = p[0];
                    q[1] = p[1];
                    q[2] = p[2];
                    q[3] = uv[0] + (uv[1] - uv[0]) * s;
                    q[4] = uv[2] + (uv[3] - uv[2]) * t;
                }
                for (int k = 0; k < 4; k++) vertex(pose, vc, quad[k], argb);
                for (int k = 3; k >= 0; k--) vertex(pose, vc, quad[k], argb);
            }
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, float[] q, int argb) {
        vc.addVertex(pose, q[0], q[1], q[2]).setUv(q[3], q[4]).setLight(LightCoordsUtil.FULL_BRIGHT).setColor(argb);
    }
}
