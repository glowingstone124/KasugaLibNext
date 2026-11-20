package lib.kasuga.rendering.models.mc.scene;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Cached CPU vertices sourced from the native block atlas and grouped into moving-block layers. */
final class BlockPreviewGeometry {
    private final List<Batch> batches;

    private BlockPreviewGeometry(List<Batch> batches) {
        this.batches = List.copyOf(batches);
    }

    static BlockPreviewGeometry build(Minecraft client, BlockAndTintGetter visibility, BlockAndTintGetter appearance,
                                      BlockPos origin, List<LayeredModelSource.Element> elements) {
        BlockStateModelSet models = client.getModelManager().getBlockStateModelSet();
        FluidStateModelSet fluidModels = client.getModelManager().getFluidStateModelSet();
        ModelBlockRenderer blocks = new ModelBlockRenderer(true, true, client.getBlockColors());
        FluidRenderer fluids = new FluidRenderer(fluidModels);
        Map<BlockStateModel, BlockStateModel> fullViewModels = new IdentityHashMap<>();
        Map<ChunkSectionLayer, Vertices> output = new EnumMap<>(ChunkSectionLayer.class);
        for (LayeredModelSource.Element element : elements) {
            BlockPos pos = origin.offset(element.x(), element.y(), element.z());
            BlockState state = appearance.getBlockState(pos);
            if (state.getRenderShape() == RenderShape.MODEL) {
                BlockStateModel model = models.get(state);
                if (visibility != appearance) {
                    model = fullViewModels.computeIfAbsent(model, key -> new ConnectedModel(key, appearance));
                }
                blocks.tesselateBlock((x, y, z, quad, instance) ->
                                output.computeIfAbsent(quad.materialInfo().layer(), key -> new Vertices())
                                        .putBlockBakedQuad(x, y, z, quad, instance),
                        element.x(), element.y(), element.z(), visibility, pos, state, model, state.getSeed(pos));
            }

            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                FluidRenderer.Output region = layer -> {
                    Vertices vertices = output.computeIfAbsent(layer, key -> new Vertices());
                    // FluidRenderer emits section-relative coordinates.
                    vertices.offsetX = element.x() - (pos.getX() & 15);
                    vertices.offsetY = element.y() - (pos.getY() & 15);
                    vertices.offsetZ = element.z() - (pos.getZ() & 15);
                    return vertices;
                };
                FluidModel model = fluidModels.get(fluid);
                var custom = model.customRenderer();
                if (custom == null || !custom.renderFluid(fluids, fluid, visibility, pos, region, state)) {
                    fluids.tesselate(visibility, pos, region, state, fluid);
                }
                for (Vertices vertices : output.values()) {
                    vertices.offsetX = 0;
                    vertices.offsetY = 0;
                    vertices.offsetZ = 0;
                }
            }
        }
        List<Batch> batches = new ArrayList<>(output.size());
        for (Map.Entry<ChunkSectionLayer, Vertices> entry : output.entrySet()) {
            RenderType layer = switch (entry.getKey()) {
                case SOLID -> RenderTypes.solidMovingBlock();
                case CUTOUT -> RenderTypes.cutoutMovingBlock();
                case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
            };
            if (!entry.getValue().data.isEmpty()) batches.add(new Batch(layer, entry.getValue().data.toIntArray()));
        }
        return new BlockPreviewGeometry(batches);
    }

    void submit(MinecraftModelSource.Context context) {
        for (Batch batch : batches) context.submit(batch.layer(), (pose, vertices) -> batch.emit(pose, vertices, context.packedLight()));
    }

    /** Retains connectivity when the geometry renderer opens faces between exploded layers. */
    private record ConnectedModel(BlockStateModel delegate, BlockAndTintGetter appearance) implements BlockStateModel {
        @Override public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
                                           List<BlockStateModelPart> parts) {
            delegate.collectParts(appearance, pos, state, random, parts);
        }
        @Override public void collectParts(RandomSource random, List<BlockStateModelPart> parts) { delegate.collectParts(random, parts); }
        @Override public Material.Baked particleMaterial() { return delegate.particleMaterial(); }
        @Override public int materialFlags() { return delegate.materialFlags(); }
    }

    private record Batch(RenderType layer, int[] data) {
        void emit(PoseStack.Pose pose, VertexConsumer output, int light) {
            for (int i = 0; i < data.length; i += Vertices.STRIDE) {
                output.addVertex(pose, Float.intBitsToFloat(data[i]), Float.intBitsToFloat(data[i + 1]),
                                Float.intBitsToFloat(data[i + 2]))
                        .setColor(data[i + 3])
                        .setUv(Float.intBitsToFloat(data[i + 4]), Float.intBitsToFloat(data[i + 5]))
                        .setLight(LightCoordsUtil.max(light, data[i + 6]));
            }
        }
    }

    /** Captures the moving-block vertex format used by both the block and fluid tessellators. */
    private static final class Vertices implements VertexConsumer {
        private static final int STRIDE = 7;
        private final IntArrayList data = new IntArrayList();
        private float offsetX, offsetY, offsetZ;
        private int current;

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            current = data.size();
            data.add(Float.floatToRawIntBits(x + offsetX));
            data.add(Float.floatToRawIntBits(y + offsetY));
            data.add(Float.floatToRawIntBits(z + offsetZ));
            data.add(-1);
            data.add(0);
            data.add(0);
            data.add(0);
            return this;
        }

        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return setColor(ARGB.color(a, r, g, b)); }
        @Override public VertexConsumer setColor(int color) {
            data.set(current + 3, color);
            return this;
        }
        @Override public VertexConsumer setUv(float u, float v) {
            data.set(current + 4, Float.floatToRawIntBits(u));
            data.set(current + 5, Float.floatToRawIntBits(v));
            return this;
        }
        @Override public VertexConsumer setUv2(int u, int v) {
            data.set(current + 6, (v & 65535) << 16 | u & 65535);
            return this;
        }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
