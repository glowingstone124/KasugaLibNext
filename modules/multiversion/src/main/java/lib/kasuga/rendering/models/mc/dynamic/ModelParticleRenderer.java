package lib.kasuga.rendering.models.mc.dynamic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lib.kasuga.KasugaLib;
import lib.kasuga.core.resource.ClientResourceReloadFinishEvent;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.output.mc.CameraRenderScope;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Extracts textured billboards for the native translucent feature renderer. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ModelParticleRenderer {
    private static final ContextKey<EmitterFrame> FRAME = new ContextKey<>(
            Identifier.fromNamespaceAndPath(KasugaLib.MODID, "model_emitters"));
    private static final Map<Identifier, Identifier> TEXTURES = new HashMap<>();
    private static int lastVisibleCount;

    private ModelParticleRenderer() {}

    @SubscribeEvent
    public static void onSubmit(SubmitCustomGeometryEvent event) {
        if (CameraRenderScope.current() != null) return;
        EmitterFrame frame = event.getLevelRenderState().getRenderData(FRAME);
        lastVisibleCount = frame == null ? 0 : frame.visible;
    }

    @SubscribeEvent
    public static void onReload(ClientResourceReloadFinishEvent event) { invalidateTextures(); }

    static void invalidateTextures() { TEXTURES.clear(); }
    static int lastVisibleCount() { return lastVisibleCount; }

    public static void extract(ExtractLevelRenderStateEvent event, ModelInstance model, Matrix4dc placement, float partialTick) {
        ModelParticleRuntime.EmitterModule emitter = ModelParticleRuntime.module(model);
        if (emitter == null) return;
        LevelRenderState state = event.getRenderState();
        EmitterFrame frame = state.getRenderData(FRAME);
        if (frame == null) {
            frame = new EmitterFrame();
            state.setRenderData(FRAME, frame);
        }
        if (!frame.seen.add(emitter)) return;
        if (CameraRenderScope.current() == null) {
            float delta = Minecraft.getInstance().isPaused() ? 0 : event.getDeltaTracker().getRealtimeDeltaTicks() / 20f;
            emitter.advanceInWorld(model.getSkeletonInstance(), placement, delta);
        }
        if (!emitter.enabled()) return;

        Camera camera = event.getCamera();
        Vec3 eye = camera.position();
        Frustum frustum = state.cameraRenderState.cullFrustum;
        Map<Identifier, List<Billboard>> draws = new LinkedHashMap<>();
        for (ModelParticleRuntime.ParticleView particle : emitter.particles()) {
            Vector3d point = particle.position();
            float size = particle.size();
            if (frustum != null && !frustum.isVisible(new AABB(point.x - size, point.y - size, point.z - size,
                    point.x + size, point.y + size, point.z + size))) continue;
            Identifier texture = TEXTURES.computeIfAbsent(particle.texture(), ModelParticleRenderer::resolveTexture);
            Vector3f center = new Vector3f((float) (point.x - eye.x), (float) (point.y - eye.y), (float) (point.z - eye.z));
            draws.computeIfAbsent(texture, key -> new ArrayList<>()).add(new Billboard(center, size * .5f, particle.color()));
            frame.visible++;
        }
        if (draws.isEmpty()) return;

        Vector3f right = new Vector3f(camera.leftVector()).negate();
        Vector3f up = new Vector3f(camera.upVector());
        Vector3f normal = new Vector3f(right).cross(up);
        MinecraftModelSource.Context context = new MinecraftModelSource.Context(
                new PoseStack(), event, partialTick, LightCoordsUtil.FULL_BRIGHT);
        for (Map.Entry<Identifier, List<Billboard>> draw : draws.entrySet()) {
            context.submit(RenderTypes.entityTranslucentEmissive(draw.getKey()), (pose, vertices) -> {
                for (Billboard billboard : draw.getValue()) {
                    float half = billboard.halfSize();
                    vertex(pose, vertices, billboard, right, up, normal, -half, -half, 0, 1);
                    vertex(pose, vertices, billboard, right, up, normal, half, -half, 1, 1);
                    vertex(pose, vertices, billboard, right, up, normal, half, half, 1, 0);
                    vertex(pose, vertices, billboard, right, up, normal, -half, half, 0, 0);
                }
            });
        }
    }

    private static Identifier resolveTexture(Identifier texture) {
        return Minecraft.getInstance().getResourceManager().getResource(texture).isPresent()
                ? texture : ModelParticleRuntime.DEFAULT_TEXTURE;
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vertices, Billboard billboard,
                               Vector3f right, Vector3f up, Vector3f normal, float x, float y, float u, float v) {
        Vector3f center = billboard.center();
        Vector4f color = billboard.color();
        vertices.addVertex(pose, center.x + x * right.x + y * up.x, center.y + x * right.y + y * up.y,
                        center.z + x * right.z + y * up.z)
                .setColor(color.x, color.y, color.z, color.w).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, normal);
    }

    private record Billboard(Vector3f center, float halfSize, Vector4f color) {}

    private static final class EmitterFrame {
        private final Set<ModelParticleRuntime.EmitterModule> seen = new HashSet<>();
        private int visible;
    }
}
