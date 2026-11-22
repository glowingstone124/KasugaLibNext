package test.kasuga.modelling.demo.cobalt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.DuplicatePolicy;
import lib.kasuga.rendering.effect.PipelineRegistration;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.WorldRenderPipelineRegistry;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjFlatMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Draws the authored coast into the same native depth attachment sampled by the water effect. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
final class CobaltBeach implements AutoCloseable {
    static final Identifier MODEL = id("models/cobalt/beach.obj");
    private static final Identifier MATERIAL = id("models/cobalt/beach.mtl");
    private static final Identifier ALBEDO = id("textures/cobalt/coast.png");
    private static final Identifier COAST_TEXTURE = id("cobalt_coast_runtime");
    private static final Identifier ID = id("cobalt_beach");
    private static final Identifier SHADER = id("core/cobalt_beach");
    private static final String FRAME = "CoastFrame";
    private static final String MAP = "CoastMap";
    private static final VertexFormat FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("Normal", GpuFormat.RGB32_FLOAT)
            .addAttribute("UV", GpuFormat.RG32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA32_FLOAT)
            .addAttribute("Material", GpuFormat.RGBA32_FLOAT).build();
    private static final RenderPipeline PIPELINE = RenderPipeline.builder().withLocation(id("pipeline/cobalt_beach"))
            .withVertexShader(SHADER).withFragmentShader(SHADER).withVertexBinding(0, FORMAT)
            .withBindGroupLayout(BindGroupLayout.builder().withUniform(FRAME, UniformType.UNIFORM_BUFFER)
                    .withUniform(MAP, UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    private static CobaltBeach active;

    private final ClientLevel level;
    private final TextureManager textures;
    private final SimpleTexture albedo;
    private final GpuBuffer vertices;
    private final int vertexCount;
    private final PipelineRegistration registration;
    private final Matrix4f viewProjection = new Matrix4f();
    private boolean closed;
    private boolean drawn;

    @SubscribeEvent
    public static void registerPipelines(RegisterRenderPipelinesEvent event) { event.registerPipeline(PIPELINE); }

    static boolean ready() {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        return resources.getResource(MODEL).isPresent() && resources.getResource(MATERIAL).isPresent()
                && resources.getResource(ALBEDO).isPresent();
    }

    static CobaltBeach mount() {
        RenderSystem.assertOnRenderThread();
        if (active != null) throw new IllegalStateException("Cobalt beach is already mounted");
        if (!ready()) throw new IllegalStateException("Cobalt beach resources are not loaded");
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) throw new IllegalStateException("Cobalt beach requires a client level");
        active = new CobaltBeach(client);
        return active;
    }

    static int activeInstances() { return active == null ? 0 : 1; }

    boolean hasDrawn() { return drawn; }

    private CobaltBeach(Minecraft client) {
        level = client.level;
        textures = client.getTextureManager();
        albedo = new SimpleTexture(ALBEDO);
        ResourceManager resources = client.getResourceManager();
        ObjFlatMesh mesh;
        try {
            mesh = ObjFlatMesh.load(read(resources, MODEL), read(resources, MATERIAL), material -> 0f);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not load the Cobalt coast", failure);
        }
        float[] data = mesh.vertices();
        ByteBuffer bytes = MemoryUtil.memAlloc(Math.multiplyExact(data.length, Float.BYTES));
        try {
            bytes.asFloatBuffer().put(data);
            vertices = RenderSystem.getDevice().createBuffer(() -> "Cobalt beach", GpuBuffer.USAGE_VERTEX, bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
        vertexCount = mesh.vertexCount();
        try {
            textures.registerAndLoad(COAST_TEXTURE, albedo);
            registration = WorldRenderPipelineRegistry.register(ID,
                    RenderPipelineDescriptor.builder(ID, RenderPhase.AFTER_OPAQUE_FEATURES).priority(90).build(),
                    DuplicatePolicy.FAIL, this::render);
        } catch (RuntimeException | Error failure) {
            textures.release(COAST_TEXTURE);
            albedo.close();
            vertices.close();
            throw failure;
        }
        LogUtils.getLogger().info("COBALT_BEACH_MOUNT triangles={} vertices={} texture={}",
                mesh.triangleCount(), vertexCount, ALBEDO);
    }

    private void render(WorldRenderPipelineContext context) {
        Minecraft client = Minecraft.getInstance();
        RenderPass pass = context.renderPass();
        if (closed || client.level != level || pass == null) return;
        CobaltBlueDemo.Session scene = CobaltBlueDemo.session();
        if (scene == null || scene.sequence.playbackTime() < CobaltRoom.FLOOR_OPEN_SECONDS) return;
        Vec3 camera = context.camera().pos;
        viewProjection.set(context.projectionMatrix()).mul(context.modelViewMatrix())
                .translate((float) -camera.x, (float) -camera.y, (float) -camera.z);
        GpuDevice device = RenderSystem.getDevice();
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice frame;
        try (GpuBufferSlice.MappedView mapped = device.createCommandEncoder().transientMemory()
                .allocateGpuMapped(64, alignment, GpuBuffer.USAGE_UNIFORM)) {
            viewProjection.get(0, mapped.data());
            frame = mapped.slice().slice(0, 64);
        }
        pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
        pass.setUniform(FRAME, frame);
        pass.setUniform(MAP, albedo.getTextureView(),
                RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
        pass.setVertexBuffer(0, vertices.slice());
        pass.draw(vertexCount, 1, 0, 0);
        drawn = true;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        active = null;
        try {
            registration.close();
        } finally {
            textures.release(COAST_TEXTURE);
            vertices.close();
        }
    }

    private static String read(ResourceManager resources, Identifier id) throws IOException {
        try (InputStream stream = resources.open(id)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("kasuga_demo", path); }
}
