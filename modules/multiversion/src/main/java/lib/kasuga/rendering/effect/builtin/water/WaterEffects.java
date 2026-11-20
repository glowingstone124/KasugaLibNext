package lib.kasuga.rendering.effect.builtin.water;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.DuplicatePolicy;
import lib.kasuga.rendering.effect.PipelineRegistration;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.WorldRenderPipelineRegistry;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.effect.pipeline.WorldProjection;
import lib.kasuga.rendering.effect.post.WorldPostProcess;
import lib.kasuga.rendering.water.WaterOptics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.joml.Matrix4f;

/** Built-in clear water surface driven by the shared shallow-water simulation. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class WaterEffects {
    private static final Identifier ID = id("water/clear_surface");
    private static final String UNIFORMS = "WaterParameters";
    private static final int UNIFORM_BYTES = 2 * 64 + 6 * 16;
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/water/clear_surface"))
            .withVertexShader(id("core/post/fullscreen"))
            .withFragmentShader(id("core/water/clear_surface"))
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform(UNIFORMS, UniformType.UNIFORM_BUFFER)
                    .withUniform("SceneSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("HeightSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();
    private static final WorldPostProcess POST = new WorldPostProcess("Kasuga clear water");
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static final Matrix4f INVERSE = new Matrix4f();
    private static WaterSurface active;
    private static PipelineRegistration registration;

    private WaterEffects() {}

    @SubscribeEvent
    public static void registerPipeline(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
    }

    public static boolean ready() {
        return RenderSystem.isOnRenderThread() && RenderSystem.getCompiledPipelineNullable(PIPELINE) != null;
    }

    /** Opens an unbounded optical water plane with a bounded simulation window. */
    public static WaterSurface open(ClientLevel level, double centerX, float bedY, double centerZ,
                                    int resolution, float cellSize, float depth) {
        if (level == null || level != Minecraft.getInstance().level) throw new IllegalArgumentException("Water requires the active client level");
        if (!ready()) throw new IllegalStateException("Built-in water shaders are not ready");
        if (active != null) throw new IllegalStateException("Level already owns a water surface");
        WaterSurface surface = new WaterSurface(level, centerX, bedY, centerZ, resolution, cellSize, depth);
        registration = WorldRenderPipelineRegistry.register(ID,
                RenderPipelineDescriptor.builder(ID, RenderPhase.POST_PROCESS).priority(800).build(),
                DuplicatePolicy.FAIL, WaterEffects::draw);
        active = surface;
        return surface;
    }

    public static int activeCount() { return active == null ? 0 : 1; }

    static void remove(WaterSurface surface) {
        Minecraft.getInstance().execute(() -> {
            if (active != surface) return;
            active = null;
            if (registration != null) registration.close();
            registration = null;
            POST.close();
        });
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        WaterSurface water = active;
        if (water == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != water.level) water.close();
        else if (!minecraft.isPaused()) water.tick();
    }

    @SubscribeEvent
    public static void shutdown(GameShuttingDownEvent event) {
        if (active != null) active.close();
    }

    private static void draw(WorldRenderPipelineContext context) {
        WaterSurface water = active;
        if (water == null || water.closed() || !water.renderEnabled() || water.level != Minecraft.getInstance().level) return;
        GpuTextureView height = water.texture().view();
        GpuBufferSlice uniforms = upload(water, context);
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        POST.draw(Minecraft.getInstance().gameRenderer.mainRenderTarget(), PIPELINE, (pass, scene, depth) -> {
            pass.setUniform(UNIFORMS, uniforms);
            pass.setUniform("SceneSampler", scene, linear);
            pass.setUniform("DepthSampler", depth, nearest);
            pass.setUniform("HeightSampler", height, linear);
        });
    }

    private static GpuBufferSlice upload(WaterSurface water, WorldRenderPipelineContext context) {
        GpuDevice device = RenderSystem.getDevice();
        boolean zeroToOne = device.getDeviceInfo().isZZeroToOne();
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        VIEW_PROJECTION.set(WorldProjection.current()).mul(context.camera().viewRotationMatrix);
        INVERSE.set(VIEW_PROJECTION).invert();
        Vec3 eye = context.camera().pos;
        WaterOptics optics = water.optics();
        try (GpuBufferSlice.MappedView mapped = device.createCommandEncoder().transientMemory()
                .allocateGpuMapped(UNIFORM_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            Std140Builder.intoBuffer(mapped.data()).putMat4f(INVERSE).putMat4f(VIEW_PROJECTION)
                    .putVec4((float) eye.x, (float) eye.y, (float) eye.z, 0)
                    .putVec4((float) water.centerX, (float) water.centerZ, water.grid.extent(), water.surfaceY())
                    .putVec4(optics.absorptionRed(), optics.absorptionGreen(), optics.absorptionBlue(), 0)
                    .putVec4(optics.scatterRed(), optics.scatterGreen(), optics.scatterBlue(), 0)
                    .putVec4(water.seconds, water.grid.cellSize(), optics.shorelineFoam(), 0)
                    .putVec4(zeroToOne ? 1 : 2, zeroToOne ? 0 : -1, 0, 0);
            return mapped.slice().slice(0, UNIFORM_BYTES);
        }
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath(KasugaLib.MODID, path); }
}
