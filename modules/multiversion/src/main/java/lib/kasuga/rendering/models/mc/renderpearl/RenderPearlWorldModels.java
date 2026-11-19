package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Renders portable Kasuga models through the active Minecraft graphics backend. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class RenderPearlWorldModels {
    private static final ContextKey<List<Draw>> DRAW_DATA =
            new ContextKey<>(Identifier.fromNamespaceAndPath(KasugaLib.MODID, "world_models"));
    private static final Map<ClientLevel, Map<ModelInstance, Mount>> LEVELS = new IdentityHashMap<>();

    private RenderPearlWorldModels() {}

    /**
     * Mounts a model in the current client level. The caller owns the instance and texture IDs.
     * All mount operations run on the client thread.
     */
    public static Mount mount(ClientLevel level, ModelInstance model, Vec3 position,
                              Function<Texture, Identifier> textures) {
        requireClientThread();
        if (level == null || level != Minecraft.getInstance().level) {
            throw new IllegalArgumentException("Model must be mounted in the current client level");
        }
        Objects.requireNonNull(model, "model").requireOpen();
        if (!model.getModel().isRenderable() || model.getMeshMode() == MeshMode.LINES) {
            throw new IllegalArgumentException("RenderPearl requires a model with polygon geometry");
        }
        Mount mount = new Mount(level, model, Objects.requireNonNull(position, "position"),
                Objects.requireNonNull(textures, "textures"));
        Map<ModelInstance, Mount> models = LEVELS.computeIfAbsent(level, key -> new LinkedHashMap<>());
        if (models.putIfAbsent(model, mount) != null) {
            throw new IllegalStateException("Model is already mounted in this level");
        }
        return mount;
    }

    @SubscribeEvent
    public static void extract(ExtractLevelRenderStateEvent event) {
        Map<ModelInstance, Mount> models = LEVELS.get(event.getLevel());
        if (models == null || models.isEmpty()) {
            event.getRenderState().setRenderData(DRAW_DATA, List.of());
            return;
        }
        float partialTick = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().position();
        List<Draw> draws = new ArrayList<>(models.size());
        for (Mount mount : List.copyOf(models.values())) {
            ModelInstance model = mount.model;
            if (mount.closed || model.isClosed()) continue;
            model.sample(partialTick, event);
            model.prepareRenderFrame(event);
            int light = LightCoordsUtil.getLightCoords(event.getLevel(), BlockPos.containing(mount.position));
            List<RenderPearlModelGeometry.Batch> batches = RenderPearlModelGeometry.extract(model, mount.textures, light);
            draws.add(new Draw(mount.position.subtract(camera), new Matrix4f(mount.transform), batches));
        }
        event.getRenderState().setRenderData(DRAW_DATA, List.copyOf(draws));
    }

    @SubscribeEvent
    public static void submit(SubmitCustomGeometryEvent event) {
        List<Draw> draws = event.getLevelRenderState().getRenderData(DRAW_DATA);
        if (draws == null || draws.isEmpty()) return;
        PoseStack poseStack = event.getPoseStack();
        for (Draw draw : draws) {
            poseStack.pushPose();
            try {
                poseStack.translate(draw.cameraRelativePosition());
                poseStack.mulPose(draw.transform());
                for (RenderPearlModelGeometry.Batch batch : draw.batches()) {
                    event.getSubmitNodeCollector().submitCustomGeometry(poseStack, batch.layer(), batch::submit);
                }
            } finally {
                poseStack.popPose();
            }
        }
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ClientLevel level)) return;
        Map<ModelInstance, Mount> removed = LEVELS.remove(level);
        if (removed == null) return;
        for (Mount mount : removed.values()) mount.closed = true;
    }

    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("World models are owned by the Minecraft client thread");
        }
    }

    public static final class Mount implements AutoCloseable {
        private final ClientLevel level;
        private final ModelInstance model;
        private final Function<Texture, Identifier> textures;
        private final Matrix4f transform = new Matrix4f();
        private Vec3 position;
        private boolean closed;

        private Mount(ClientLevel level, ModelInstance model, Vec3 position, Function<Texture, Identifier> textures) {
            this.level = level;
            this.model = model;
            this.position = position;
            this.textures = textures;
        }

        public void setPlacement(Vec3 position, Matrix4fc transform) {
            requireClientThread();
            if (closed) throw new IllegalStateException("World model mount is closed");
            Objects.requireNonNull(position, "position");
            this.transform.set(Objects.requireNonNull(transform, "transform"));
            this.position = position;
        }

        @Override
        public void close() {
            requireClientThread();
            if (closed) return;
            closed = true;
            Map<ModelInstance, Mount> models = LEVELS.get(level);
            if (models == null) return;
            models.remove(model, this);
            if (models.isEmpty()) LEVELS.remove(level);
        }
    }

    private record Draw(Vec3 cameraRelativePosition, Matrix4f transform,
                        List<RenderPearlModelGeometry.Batch> batches) {}
}
