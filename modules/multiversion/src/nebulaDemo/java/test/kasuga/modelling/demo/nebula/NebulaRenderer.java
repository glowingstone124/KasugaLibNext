package test.kasuga.modelling.demo.nebula;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.DuplicatePolicy;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphContext;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphRegistration;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphRegistry;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.mc.CameraRenderScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

final class NebulaRenderer implements AutoCloseable {
    private static final Identifier FRAME_STATE = id("frame_state");
    private static final String PARAMETERS = "NebulaFrame";
    private static final RenderPipeline VOLUME = RenderPipeline.builder()
            .withLocation(id("pipeline/volume"))
            .withVertexShader(Identifier.fromNamespaceAndPath(KasugaLib.MODID, "core/post/fullscreen"))
            .withFragmentShader(id("core/nebula_volume"))
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform(PARAMETERS, UniformType.UNIFORM_BUFFER)
                    .withUniform("NoiseSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("CellSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("DataSampler", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
                    .withUniform("CraftSurfaceSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("CraftNormalSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("CraftPbrSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("CraftDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    private final NebulaSimulation simulation;
    private final NebulaTextures textures = new NebulaTextures();
    private final NebulaStarshipRenderer starship = new NebulaStarshipRenderer();
    private final PostProcessGraphRegistration graph;
    private final float[] bolts = new float[512];
    private final float[] lights = new float[8];
    private final float[] wake = new float[NebulaFlight.WAKE_POINTS * 4];
    private final float[] bounds = new float[24];
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f inverseProjection = new Matrix4f();
    private final Matrix4f inverseView = new Matrix4f();
    private final Vector3f eye = new Vector3f();
    private final Vector3f forward = new Vector3f();
    private final Vector3f velocity = new Vector3f();
    private float azimuth;
    private float elevation;
    private int drawCount;
    private int probe;
    private boolean capturing;
    private float cameraDistance;
    private NebulaGpuTiming timing;

    NebulaRenderer(ClientLevel level, NebulaSimulation simulation) {
        this.simulation = simulation;
        graph = PostProcessGraphRegistry.register(id("nebula_demo"), 2000, DuplicatePolicy.FAIL,
                NebulaPipeline.create((context, frame) -> Minecraft.getInstance().level == level
                                && CameraRenderScope.current() == null, this::drawStarship, this::drawVolume));
    }

    static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(NebulaStarshipRenderer.PIPELINE);
        event.registerPipeline(VOLUME);
    }

    void orbit(double dx, double dy) {
        azimuth += (float) dx * .225f;
        elevation = Math.clamp(elevation + (float) dy * .225f, -27, 48);
    }

    int drawCount() { return drawCount; }
    int triangles() { return starship.triangles(); }
    void probe(int mode) { probe = mode; }
    void capturing(boolean value) { capturing = value; }
    float cameraDistance() { return cameraDistance; }

    NebulaGpuTiming measureGpuTime() {
        if (timing == null) timing = new NebulaGpuTiming();
        return timing;
    }

    void stopGpuTiming() {
        if (timing == null) return;
        timing.close();
        timing = null;
    }

    private void drawStarship(PostProcessGraphContext context) {
        float partial = capturing ? 1f : context.raw().partialTick();
        float time = simulation.time(partial);
        NebulaFlight.WarpState warp = NebulaFlight.sampleWarp(simulation.warpTime(partial));
        NebulaFlight.Pose craft = NebulaFlight.sample(time);
        NebulaLightningData.Counts counts = NebulaLightningData.pack(simulation.bolts(), time, lights, bolts);
        NebulaFlight.fillWakeAndBounds(time, wake, bounds);
        WorldCameraView camera = simulation.camera(craft, warp, context.raw().mainTarget().width,
                context.raw().mainTarget().height, azimuth, elevation, partial);
        eye.set((float) camera.x(), (float) camera.y(), (float) camera.z());
        cameraDistance = eye.distance(craft.position());
        NebulaFlight.cameraForward(camera, forward);
        textures.uploadFrameData(time, eye, forward, wake, lights, counts.lights(), bolts, counts.segments(), craft, warp);
        camera.projection(.01f, 100, projection);
        inverseProjection.set(projection).invert();
        camera.inverseView(inverseView);
        velocity.set(NebulaFlight.sample(time + .01f).position()).sub(craft.position())
                .mul(100f * (1f + warp.warpSpeed() * .45f));
        context.frame().put(FRAME_STATE, new View(time, craft, warp, counts));
        starship.draw(context, projection, inverseView, time, craft, warp, true, 1f);
    }

    private void drawVolume(PostProcessGraphContext context) {
        View view = context.frame().require(FRAME_STATE, View.class);
        GpuBufferSlice parameters = uniform(512, data -> writeVolumeUniforms(view, data));
        if (timing != null) timing.begin();
        try {
            context.fullscreen(NebulaPipeline.VOLUME, VOLUME, false, pass -> {
                pass.setUniform(PARAMETERS, parameters);
                textures.bind(pass);
                context.bindColor(pass, "CraftSurfaceSampler", NebulaPipeline.GBUFFER, 0);
                context.bindColor(pass, "CraftNormalSampler", NebulaPipeline.GBUFFER, 1);
                context.bindColor(pass, "CraftPbrSampler", NebulaPipeline.GBUFFER, 2);
                context.bindDepth(pass, "CraftDepthSampler", NebulaPipeline.GBUFFER);
            });
        } finally {
            if (timing != null) timing.end();
        }
        drawCount++;
    }

    private void writeVolumeUniforms(View view, ByteBuffer data) {
        NebulaFlight.Pose craft = view.craft();
        NebulaFlight.WarpState warp = view.warp();
        matrix(data, inverseProjection);
        matrix(data, inverseView);
        matrix(data, craft.toLocal());
        vector(data, eye, view.time());
        data.putInt(view.counts().segments()).putInt(view.counts().lights())
                .putInt(textures.visibleStarCount()).putInt(textures.visibleDustCount());
        vector(data, craft.position(), 1f);
        vector(data, velocity, NebulaFlight.SHIP_SCALE);
        vector(data, craft.forward(), 1f);
        vector(data, craft.right(), 1f);
        vector(data, craft.up(), 1f);
        for (int i = 0; i < bounds.length; i += 3) {
            data.putFloat(bounds[i]).putFloat(bounds[i + 1]).putFloat(bounds[i + 2]).putFloat(0f);
        }
        data.putFloat(warp.charge()).putFloat(warp.warpActive()).putFloat(warp.stretch()).putFloat(warp.flash());
        data.putFloat((float) (warp.warpDistance() % 6400)).putFloat((float) (warp.warpDistance() % 628.31853))
                .putFloat(warp.shockProgress()).putFloat(warp.shockIntensity());
        textures.nacelleParticleSphere().get(data.position(), data);
        data.position(data.position() + 16);
        data.putInt(probe).putInt(textures.visibleNacelleParticleCount()).putInt(0).putInt(0);
        data.putFloat(1f).putFloat(1f).putFloat(0f).putFloat(0f);
    }

    static GpuBufferSlice uniform(int size, Consumer<ByteBuffer> writer) {
        GpuDevice device = RenderSystem.getDevice();
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        try (GpuBufferSlice.MappedView mapped = device.createCommandEncoder().transientMemory()
                .allocateGpuMapped(size, alignment, GpuBuffer.USAGE_UNIFORM)) {
            writer.accept(mapped.data());
            return mapped.slice().slice(0, size);
        }
    }

    static void matrix(ByteBuffer data, Matrix4fc matrix) {
        int offset = data.position();
        matrix.get(offset, data);
        data.position(offset + 64);
    }

    static void vector(ByteBuffer data, Vector3fc vector, float w) {
        data.putFloat(vector.x()).putFloat(vector.y()).putFloat(vector.z()).putFloat(w);
    }

    static Identifier id(String path) { return Identifier.fromNamespaceAndPath("kasuga_demo", path); }

    @Override public void close() {
        graph.close();
        stopGpuTiming();
        reload();
    }

    void reload() {
        starship.close();
        textures.close();
    }

    private record View(float time, NebulaFlight.Pose craft, NebulaFlight.WarpState warp, NebulaLightningData.Counts counts) {}
}
