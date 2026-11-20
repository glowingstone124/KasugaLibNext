package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.builtin.light.WorldLights;
import lib.kasuga.rendering.lighting.LightFrame;
import lib.kasuga.rendering.models.mc.component.ModelComponents;
import lib.kasuga.rendering.models.mc.component.ModelComponentsHolder;
import lib.kasuga.rendering.models.mc.component.ModelLightPlacement;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Renders portable Kasuga models through the active Minecraft graphics backend. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class RenderPearlWorldModels {
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
        if (models == null) return;
        Vec3 camera = event.getCamera().position();
        for (Mount mount : List.copyOf(models.values())) {
            ModelInstance model = mount.model;
            if (mount.closed || model.isClosed()) continue;
            Vec3 offset = mount.position.subtract(camera);
            Matrix4f cameraRelative = new Matrix4f().translation((float) offset.x, (float) offset.y, (float) offset.z)
                    .mul(mount.transform);
            Vec3 position = mount.position;
            Matrix4d world = new Matrix4d().translation(position.x, position.y, position.z)
                    .mul(new Matrix4d(mount.transform));
            int light = LightCoordsUtil.getLightCoords(event.getLevel(), BlockPos.containing(position));
            addToFrame(event, model, mount.textures, cameraRelative, world, light);
        }
    }

    /** Captures model geometry and its authored bone lights from the same evaluated pose. */
    public static void addToFrame(ExtractLevelRenderStateEvent event, ModelInstance model,
                                  Function<Texture, Identifier> textures, Matrix4fc cameraRelative,
                                  Matrix4dc worldPlacement, int packedLight) {
        if (model.isClosed()) return;
        float partialTick = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        List<RenderPearlModelGeometry.Batch> batches =
                RenderPearlModelGeometry.capture(model, textures, packedLight, partialTick, event);
        if (!batches.isEmpty()) {
            PoseStack pose = new PoseStack();
            pose.mulPose(cameraRelative);
            MinecraftModelSource.Context context = new MinecraftModelSource.Context(pose, event, partialTick, packedLight);
            for (RenderPearlModelGeometry.Batch batch : batches) context.submit(batch.layer(), batch::submit);
        }
        if (!(model.getModel().getModelData() instanceof ModelComponentsHolder holder)) return;
        ModelComponents components = holder.components();
        if (components == null) return;
        for (ModelComponents.LightDefinition light : components.lights()) {
            if (!light.enabled()) continue;
            LightFrame frame = ModelLightPlacement.sample(model.getSkeletonInstance(), light, worldPlacement);
            if (frame != null) WorldLights.addToFrame(event, frame);
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

}
