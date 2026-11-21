package lib.kasuga.rendering.models.mc.scene;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Render-thread-owned model, backed by native Minecraft/mod renderers or UML geometry.
 * Snapshot once per draw; neither the preview nor an adapter may tick or modify a live world.
 * A preview owns the source passed to it. Borrowed game entities/templates remain caller-owned.
 */
public interface MinecraftModelSource extends AutoCloseable {
    Snapshot snapshot(LayerLayout layout, float partialTick);

    /** Keeps per-layer parts even when the model is fully assembled, for display animation. */
    default Snapshot snapshotLayers(LayerLayout layout, float partialTick) { return snapshot(layout, partialTick); }

    /** Nonempty source-local layers. Adapters may override without building a render snapshot. */
    default List<Integer> layerCoordinates(LayerLayout.Axis axis) {
        var frame = snapshot(LayerLayout.ASSEMBLED.axis(axis).separation(.000001), 0);
        return frame == null ? List.of() : frame.parts().stream().map(Part::layer).distinct().sorted().toList();
    }

    /** Counts source blocks, never rendered faces; models without block data return an empty map. */
    default java.util.Map<Integer, java.util.Map<Identifier, Integer>> blockStatistics(LayerLayout.Axis axis) {
        return java.util.Map.of();
    }

    @Override default void close() {}

    /** A view of another source whose lifecycle belongs to its existing owner. */
    static MinecraftModelSource borrowed(MinecraftModelSource source) {
        Objects.requireNonNull(source, "source");
        return new MinecraftModelSource() {
            @Override public Snapshot snapshot(LayerLayout layout, float partialTick) {
                return source.snapshot(layout, partialTick);
            }

            @Override public List<Integer> layerCoordinates(LayerLayout.Axis axis) {
                return source.layerCoordinates(axis);
            }

            @Override public java.util.Map<Integer, java.util.Map<Identifier, Integer>>
                    blockStatistics(LayerLayout.Axis axis) {
                return source.blockStatistics(axis);
            }
        };
    }

    @FunctionalInterface
    interface Renderer {
        void render(Context context);
    }

    /** Source block coordinates are stable; location is in the caller's display coordinate system. */
    record BlockHit(BlockPos position, BlockState state, Vec3 location, Direction face) {}
    @FunctionalInterface interface Picker { Optional<BlockHit> pick(Vec3 from, Vec3 to); }

    /** Applies exactly the same affine placement used to draw a source or exploded layer. */
    static Picker placedPicker(Matrix4fc placement, Picker picker) {
        var forward = new Matrix4f(placement); var inverse = new Matrix4f(forward).invert();
        if (!forward.isFinite() || !forward.isAffine() || !inverse.isFinite()) throw new IllegalArgumentException("Invalid pick placement");
        return (from, to) -> picker.pick(point(inverse, from), point(inverse, to))
                .map(hit -> new BlockHit(hit.position(), hit.state(), point(forward, hit.location()), hit.face()));
    }
    private static Vec3 point(Matrix4fc matrix, Vec3 point) {
        var result = matrix.transformPosition(new Vector3f((float) point.x, (float) point.y, (float) point.z));
        return new Vec3(result.x, result.y, result.z);
    }

    @FunctionalInterface
    interface Submission {
        void submit(PoseStack pose, SubmitNodeCollector nodes);
    }

    /** Camera position and basis in the same coordinates as the initial drawing pose. */
    record Viewpoint(Vec3 position, Vector3fc forward, Vector3fc up) {
        public CameraRenderState inSource(PoseStack pose, BlockPos origin) {
            Matrix4f inverse = new Matrix4f(pose.last().pose()).invertAffine();
            Vector3f localPosition = inverse.transformPosition(new Vector3f((float) position.x, (float) position.y, (float) position.z));
            Vector3f localForward = inverse.transformDirection(forward, new Vector3f()).normalize();
            Vector3f localUp = inverse.transformDirection(up, new Vector3f()).normalize();
            CameraRenderState camera = new CameraRenderState();
            camera.initialized = true;
            camera.pos = new Vec3(localPosition).add(origin.getX(), origin.getY(), origin.getZ());
            camera.blockPos = BlockPos.containing(camera.pos);
            camera.xRot = (float) Math.toDegrees(Math.asin(Math.clamp(-localForward.y, -1f, 1f)));
            camera.yRot = (float) Math.toDegrees(Math.atan2(-localForward.x, localForward.z));
            camera.orientation.set(new Quaternionf().lookAlong(localForward, localUp).invert());
            camera.viewRotationMatrix.rotation(new Quaternionf(camera.orientation).conjugate());
            return camera;
        }
    }

    /** Captures commands during level extraction or submits them to the active GUI preview. */
    record Context(PoseStack pose, @Nullable ExtractLevelRenderStateEvent extraction, @Nullable SubmitNodeCollector nodes,
                   Object frameToken, float partialTick, int packedLight, Viewpoint viewpoint) {
        public Context(PoseStack pose, ExtractLevelRenderStateEvent extraction, float partialTick, int packedLight) {
            this(pose, extraction, null, extraction, partialTick, packedLight,
                    new Viewpoint(Vec3.ZERO, extraction.getCamera().forwardVector(), extraction.getCamera().upVector()));
        }

        public Context(PoseStack pose, SubmitNodeCollector nodes, Object frameToken, float partialTick, int packedLight,
                       Viewpoint viewpoint) {
            this(pose, null, nodes, frameToken, partialTick, packedLight, viewpoint);
        }

        public void submit(RenderType layer, SubmitNodeCollector.CustomGeometryRenderer geometry) {
            submit((stack, collector) -> collector.submitCustomGeometry(stack, layer, geometry));
        }

        /** Uses the live biome and lightmap with a stable model seed for a moving block. */
        public void submitMovingBlock(ClientLevel level, BlockState block, BlockPos position, BlockPos seedPosition) {
            if (block.getRenderShape() != RenderShape.MODEL) return;
            MovingBlockRenderState state = new MovingBlockRenderState();
            state.blockState = block;
            state.randomSeedPos = seedPosition;
            state.blockPos = position;
            state.biome = level.getBiome(position);
            state.cardinalLighting = level.cardinalLighting();
            state.lightEngine = level.getLightEngine();
            submit((stack, nodes) -> nodes.submitMovingBlock(stack, state, 0));
        }

        /** Submit the extracted state of a native renderer; the world collector may consume it later in the frame. */
        public void submit(Submission submission) {
            if (nodes != null) submission.submit(pose, nodes);
            else SceneGeometry.add(Objects.requireNonNull(extraction), pose, submission);
        }
    }

    /** Renderer and bounds describe one frame. Borrowed callbacks are valid until the next snapshot or close. */
    record Part(String id, int layer, ModelBounds bounds, Renderer renderer, Picker picker) {
        public Part(String id, int layer, ModelBounds bounds, Renderer renderer) {
            this(id, layer, bounds, renderer, (from, to) -> Optional.empty());
        }
        public Part {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(renderer, "renderer");
            Objects.requireNonNull(picker, "picker");
        }
    }

    /** Empty parts mean no visible layers; bounds still describe the full exploded model for stable framing. */
    record Snapshot(ModelBounds bounds, List<Part> parts) {
        public Snapshot {
            Objects.requireNonNull(bounds, "bounds");
            parts = List.copyOf(parts);
        }
        public void render(Context context) {
            for (Part part : parts) part.renderer().render(context);
        }
        public Optional<BlockHit> pick(Vec3 from, Vec3 to) {
            return parts.stream().map(part -> part.picker().pick(from, to)).flatMap(Optional::stream)
                    .min(java.util.Comparator.comparingDouble(hit -> hit.location().distanceToSqr(from)));
        }
    }
}
