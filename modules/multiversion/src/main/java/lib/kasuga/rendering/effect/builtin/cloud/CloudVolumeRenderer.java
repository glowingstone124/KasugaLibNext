package lib.kasuga.rendering.effect.builtin.cloud;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.cloud.CloudPose;
import lib.kasuga.rendering.cloud.CloudSettings;
import lib.kasuga.rendering.cloud.CumulonimbusVolume;
import lib.kasuga.rendering.effect.EffectRenderer;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.pipeline.WorldProjection;
import lib.kasuga.rendering.effect.post.PostProcessContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.LinkedHashMap;
import java.util.Optional;

final class CloudVolumeRenderer implements EffectRenderer<CumulonimbusCloud>, AutoCloseable {
    private static final String PARAMETERS = "CloudParameters";
    private static final int UNIFORM_BYTES = 2 * 64 + 11 * 16;
    private static final RenderPipeline LIGHTING = pipeline("lighting", GpuFormat.R16_FLOAT, false, "NoiseVolume", "ShapeVolume");
    private static final RenderPipeline MARCH = pipeline("volume", GpuFormat.RGBA16_FLOAT, false,
            "NoiseVolume", "ShapeVolume", "SceneDepth", "LightVolume");
    private static final RenderPipeline DIRECT = pipeline("volume", GpuFormat.RGBA8_UNORM, true,
            "NoiseVolume", "ShapeVolume", "SceneDepth", "LightVolume");
    private static final RenderPipeline UPSAMPLE = pipeline("upsample", GpuFormat.RGBA8_UNORM, true, "CloudColor", "SceneDepth");
    private static final int MAX_LIGHT_CACHE = 8;
    private final CloudVolumeAtlas atlas = new CloudVolumeAtlas();
    private final LinkedHashMap<LightKey, LightTarget> lights = new LinkedHashMap<>(8, .75f, true);
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f inverseViewProjection = new Matrix4f();
    private final Matrix4f worldToLocal = new Matrix4f();
    private final Vector3f sun = new Vector3f();
    private final Vector3f sunColor = new Vector3f();
    private final Vector3f ambient = new Vector3f();
    private final Vector3f localSun = new Vector3f();
    private final Vector3f tangent = new Vector3f();
    private final Vector3f bitangent = new Vector3f();
    private final Vector3f up = new Vector3f();
    private TextureTarget reduced;
    private RenderTarget main;

    static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(LIGHTING);
        event.registerPipeline(MARCH);
        event.registerPipeline(DIRECT);
        event.registerPipeline(UPSAMPLE);
    }

    @Override public void begin(WorldRenderPipelineContext context) {
        if (!CloudEffects.isEnabled() || !CloudVolumeAtlas.ready()) return;
        atlas.prepare();
        LevelRenderState state = context.event().getLevelRenderState();
        float angle = state.skyRenderState.sunAngle;
        sun.set(-(float) Math.sin(angle), (float) Math.cos(angle), 0);
        float twilight = Math.clamp((sun.y + .06f) / .18f, 0, 1);
        float sunlight = twilight * twilight * (3 - 2 * twilight) * state.skyRenderState.rainBrightness;
        float warm = Math.clamp(sun.y / .28f, 0, 1);
        sunColor.set(.98f - .08f * warm, .42f + .42f * warm, .19f + .55f * warm).mul(sunlight);
        int color = state.cloudColor;
        ambient.set(ARGB.red(color), ARGB.green(color), ARGB.blue(color)).mul(.32f / 255);
        viewProjection.set(WorldProjection.current()).mul(context.camera().viewRotationMatrix);
        inverseViewProjection.set(viewProjection).invert();
        main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
    }

    @Override public void render(CumulonimbusCloud cloud, WorldRenderPipelineContext context) {
        if (main == null || main.getDepthTextureView() == null) return;
        CumulonimbusVolume.Frame frame = cloud.volume().sample(context.partialTick());
        CloudSettings settings = frame.settings();
        if (settings.density() == 0 || settings.coverage() == 0) return;
        Vec3 eye = context.camera().pos;
        CloudPose pose = frame.pose();
        worldToLocal.set(pose.worldToLocal(eye.x, eye.y, eye.z));
        if (!worldToLocal.isFinite()) return;
        Bounds bounds = projectBounds();
        if (bounds == null) return;
        LightTarget light = lightTarget(frame);
        int divisor = settings.quality().resolutionDivisor;
        RenderTarget drawTarget = divisor == 1 ? main : reduced(main.width, main.height, divisor);
        GpuBufferSlice parameters = upload(frame, frame.timeSeconds(), settings.quality().viewSteps, light.grid,
                drawTarget.width, drawTarget.height, divisor != 1, false);
        draw(drawTarget, divisor == 1 ? DIRECT : MARCH, divisor != 1, bounds, pass -> {
            pass.setUniform(PARAMETERS, parameters);
            bindAtlas(pass);
            pass.setUniform("SceneDepth", main.getDepthTextureView(), sampler(FilterMode.NEAREST));
            pass.setUniform("LightVolume", light.target.getColorTextureView(), sampler(FilterMode.LINEAR));
        });
        if (divisor != 1) {
            draw(main, UPSAMPLE, false, bounds, pass -> {
                pass.setUniform(PARAMETERS, parameters);
                pass.setUniform("SceneDepth", main.getDepthTextureView(), sampler(FilterMode.NEAREST));
                pass.setUniform("CloudColor", drawTarget.getColorTextureView(), sampler(FilterMode.NEAREST));
            });
        }
    }

    @Override public void end(WorldRenderPipelineContext context) { main = null; }

    private LightTarget lightTarget(CumulonimbusVolume.Frame frame) {
        CloudPose pose = frame.pose();
        CloudSettings settings = frame.settings();
        LightKey key = new LightKey(settings, pose.width(), pose.height(), pose.depth(), pose.yaw());
        LightTarget light = lights.get(key);
        if (light == null) {
            light = new LightTarget(settings.quality().lightGridSize);
            lights.put(key, light);
            if (lights.size() > MAX_LIGHT_CACHE) lights.pollFirstEntry().getValue().target.destroyBuffers();
        }
        long bucket = (long) Math.floor(frame.timeSeconds() * 4);
        if (light.bucket == bucket && light.sun.distanceSquared(sun) < .0001f) return light;
        worldToLocal.transformDirection(sun, localSun);
        up.set(Math.abs(sun.y) > .95f ? 1 : 0, Math.abs(sun.y) > .95f ? 0 : 1, 0);
        sun.cross(up, tangent).normalize();
        sun.cross(tangent, bitangent).normalize();
        worldToLocal.transformDirection(tangent);
        worldToLocal.transformDirection(bitangent);
        GpuBufferSlice parameters = upload(frame, bucket / 4.0, settings.quality().lightSteps, light.grid,
                light.target.width, light.target.height, false, true);
        PostProcessContext.fullscreen(light.target, LIGHTING, false, pass -> {
            bindAtlas(pass);
            pass.setUniform(PARAMETERS, parameters);
        });
        light.bucket = bucket;
        light.sun.set(sun);
        return light;
    }

    private GpuBufferSlice upload(CumulonimbusVolume.Frame frame, double time, int steps, int grid,
                                  int width, int height, boolean downsampled, boolean lighting) {
        GpuDevice device = RenderSystem.getDevice();
        boolean zeroToOne = device.getDeviceInfo().isZZeroToOne();
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        CloudPose pose = frame.pose();
        CloudSettings settings = frame.settings();
        int seed = settings.seed() * 0x45d9f3b;
        seed ^= seed >>> 16;
        float noise = Math.max(pose.width(), Math.max(pose.height(), pose.depth())) * .5f;
        float warp = Math.min(pose.width(), Math.min(pose.height(), pose.depth())) * .12f;
        Vector3f direction = lighting ? localSun : sun;
        try (GpuBufferSlice.MappedView mapped = device.createCommandEncoder().transientMemory()
                .allocateGpuMapped(UNIFORM_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            Std140Builder.intoBuffer(mapped.data()).putMat4f(inverseViewProjection).putMat4f(worldToLocal)
                    .putVec4(settings.density(), settings.extinction(), settings.erosion(), settings.anvil())
                    .putVec4(phase((seed & 255) / 256.0 + time * .006), phase((seed >>> 8 & 255) / 256.0 - time * .0021),
                            phase((seed >>> 16 & 255) / 256.0), settings.coverage())
                    .putVec4(pose.width() * .5f / noise, pose.height() / noise, pose.depth() * .5f / noise, 0)
                    .putVec4(warp / (pose.width() * .5f), warp / pose.height(), warp / (pose.depth() * .5f), 0)
                    .putVec4(direction.x, direction.y, direction.z, steps)
                    .putVec4(sunColor.x, sunColor.y, sunColor.z, grid)
                    .putVec4(ambient.x, ambient.y, ambient.z, 0)
                    .putVec4(width, height, downsampled ? .49f / width : 0, downsampled ? .49f / height : 0)
                    .putVec4(zeroToOne ? 1 : 2, zeroToOne ? 0 : -1, 0, 0)
                    .putVec4(tangent.x, tangent.y, tangent.z, 0)
                    .putVec4(bitangent.x, bitangent.y, bitangent.z, 0);
            return mapped.slice().slice(0, UNIFORM_BYTES);
        }
    }

    private Bounds projectBounds() {
        Matrix4f localToClip = new Matrix4f(viewProjection).mul(new Matrix4f(worldToLocal).invert());
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        int behind = 0;
        Vector4f projected = new Vector4f();
        for (int x = -1; x <= 1; x += 2) {
            for (int y = 0; y <= 1; y++) {
                for (int z = -1; z <= 1; z += 2) {
                    localToClip.transform(projected.set(x, y, z, 1));
                    if (projected.w <= .00001f) { behind++; continue; }
                    float px = projected.x / projected.w * .5f + .5f;
                    float py = projected.y / projected.w * .5f + .5f;
                    minX = Math.min(minX, px);
                    minY = Math.min(minY, py);
                    maxX = Math.max(maxX, px);
                    maxY = Math.max(maxY, py);
                }
            }
        }
        if (behind == 8) return null;
        if (behind != 0) return new Bounds(0, 0, 1, 1);
        if (maxX <= 0 || minX >= 1 || maxY <= 0 || minY >= 1) return null;
        return new Bounds(Math.max(0, minX), Math.max(0, minY), Math.min(1, maxX), Math.min(1, maxY));
    }

    private TextureTarget reduced(int width, int height, int divisor) {
        int w = Math.ceilDiv(width, divisor);
        int h = Math.ceilDiv(height, divisor);
        if (reduced == null || reduced.width != w || reduced.height != h) {
            TextureTarget replacement = new TextureTarget("Kasuga cloud ray march", w, h, GpuFormat.RGBA16_FLOAT, null);
            if (reduced != null) reduced.destroyBuffers();
            reduced = replacement;
        }
        return reduced;
    }

    private static void draw(RenderTarget target, RenderPipeline pipeline, boolean clear, Bounds bounds,
                             java.util.function.Consumer<RenderPass> bind) {
        RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> pipeline.getLocation().toString())
                .withColorAttachment(target.getColorTextureView(), clear ? Optional.of(new Vector4f()) : Optional.empty()).build();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            int left = Math.max(0, (int) Math.floor(bounds.left * target.width) - 2);
            int bottom = Math.max(0, (int) Math.floor(bounds.bottom * target.height) - 2);
            int right = Math.min(target.width, (int) Math.ceil(bounds.right * target.width) + 2);
            int top = Math.min(target.height, (int) Math.ceil(bounds.top * target.height) + 2);
            pass.enableScissor(left, bottom, right - left, top - bottom);
            bind.accept(pass);
            pass.draw(3, 1, 0, 0);
        }
    }

    private void bindAtlas(RenderPass pass) {
        GpuSampler sampler = sampler(FilterMode.LINEAR);
        pass.setUniform("NoiseVolume", atlas.noise(), sampler);
        pass.setUniform("ShapeVolume", atlas.shapes(), sampler);
    }

    private static GpuSampler sampler(FilterMode mode) { return RenderSystem.getSamplerCache().getClampToEdge(mode); }

    private static RenderPipeline pipeline(String shader, GpuFormat format, boolean blend, String... samplers) {
        String variant = blend ? shader + "_blend" : shader;
        BindGroupLayout.Builder layout = BindGroupLayout.builder().withUniform(PARAMETERS, UniformType.UNIFORM_BUFFER);
        for (String sampler : samplers) layout.withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER);
        return RenderPipeline.builder().withLocation(id("pipeline/cloud/" + variant))
                .withVertexShader(id("core/post/fullscreen")).withFragmentShader(id("core/cloud/" + shader))
                .withBindGroupLayout(layout.build())
                .withColorTargetState(new ColorTargetState(blend ? Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)
                        : Optional.empty(), format, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath(KasugaLib.MODID, path); }
    private static float phase(double value) { return (float) (value - Math.floor(value)); }

    @Override public void close() {
        if (reduced != null) reduced.destroyBuffers();
        reduced = null;
        for (LightTarget light : lights.values()) light.target.destroyBuffers();
        lights.clear();
        atlas.close();
        main = null;
    }

    private record Bounds(float left, float bottom, float right, float top) {}
    private record LightKey(CloudSettings settings, float width, float height, float depth, float yaw) {}

    private static final class LightTarget {
        final TextureTarget target;
        final int grid;
        final Vector3f sun = new Vector3f();
        long bucket = Long.MIN_VALUE;

        LightTarget(int grid) {
            this.grid = grid;
            target = new TextureTarget("Kasuga cloud light volume", grid * 8, grid * Math.ceilDiv(grid, 8), GpuFormat.R16_FLOAT, null);
        }
    }
}
