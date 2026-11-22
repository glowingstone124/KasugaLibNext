package test.kasuga.modelling.demo.nebula;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphContext;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjFlatMesh;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjTextureData;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

final class NebulaStarshipRenderer implements AutoCloseable {
    private static final String MODELS = "models/";
    private static final String PARAMETERS = "StarshipFrame";
    private static final String[] SAMPLERS = {"AlbedoMap", "NormalMap", "MetallicRoughnessMap", "EmissiveMap"};
    private static final VertexFormat FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("Normal", GpuFormat.RGB32_FLOAT)
            .addAttribute("UV", GpuFormat.RG32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA32_FLOAT)
            .addAttribute("Material", GpuFormat.RGBA32_FLOAT).build();
    private static final ColorTargetState HDR = new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT,
            ColorTargetState.WRITE_ALL);
    static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(NebulaRenderer.id("pipeline/starship"))
            .withVertexShader(NebulaRenderer.id("core/nebula_starship"))
            .withFragmentShader(NebulaRenderer.id("core/nebula_starship"))
            .withVertexBinding(0, FORMAT)
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform(PARAMETERS, UniformType.UNIFORM_BUFFER)
                    .withUniform(SAMPLERS[0], UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform(SAMPLERS[1], UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform(SAMPLERS[2], UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform(SAMPLERS[3], UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withColorTargetState(0, HDR).withColorTargetState(1, HDR).withColorTargetState(2, HDR)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();

    private final NebulaTexture[] maps = new NebulaTexture[4];
    private GpuBuffer vertices;
    private int vertexCount;
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();

    void draw(PostProcessGraphContext context, Matrix4f projection, Matrix4f inverseView, float time,
              NebulaFlight.Pose craft, NebulaFlight.WarpState warp, boolean visible, float hullTextureStrength) {
        if (!visible) {
            context.render(NebulaPipeline.GBUFFER, PIPELINE, true, true, pass -> {});
            return;
        }
        prepare();
        inverseView.invert(view);
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        viewProjection.scaling(1f, 1f, zeroToOne ? -.5f : -1f);
        if (zeroToOne) viewProjection.m32(.5f);
        viewProjection.mul(projection).mul(view);
        GpuBufferSlice uniform = NebulaRenderer.uniform(64 + 5 * 16, data -> {
            NebulaRenderer.matrix(data, viewProjection);
            NebulaRenderer.vector(data, craft.position(), NebulaFlight.SHIP_SCALE);
            NebulaRenderer.vector(data, craft.right(), warp.stretch());
            NebulaRenderer.vector(data, craft.up(), time);
            NebulaRenderer.vector(data, craft.forward(), hullTextureStrength);
            data.putFloat(warp.charge()).putFloat(warp.warpActive()).putFloat(0f).putFloat(0f);
        });
        GpuSampler sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
        context.render(NebulaPipeline.GBUFFER, PIPELINE, true, true, pass -> {
            pass.setUniform(PARAMETERS, uniform);
            for (int i = 0; i < maps.length; i++) pass.setUniform(SAMPLERS[i], maps[i].view(), sampler);
            pass.setVertexBuffer(0, vertices.slice());
            pass.draw(vertexCount, 1, 0, 0);
        });
    }

    int triangles() { return vertexCount / 3; }

    private void prepare() {
        if (vertices != null) return;
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        try {
            ObjFlatMesh mesh = ObjFlatMesh.load(read(resources, "constitution_starship.obj"),
                    read(resources, "constitution_starship.mtl"), NebulaTextureData::materialKind);
            ObjTextureData hull = mesh.materials().get("hull_mid");
            if (hull == null) throw new IllegalStateException("Missing Nebula hull material");
            maps[0] = NebulaTexture.read(resources, modelTexture(hull.getMap_Kd()), true);
            maps[1] = NebulaTexture.read(resources, modelTexture(hull.getMap_Bump()), false);
            maps[2] = NebulaTexture.read(resources, modelTexture(hull.getMap_Pr()), false);
            maps[3] = NebulaTexture.read(resources, modelTexture(hull.getMap_Ke()), false);
            ByteBuffer bytes = MemoryUtil.memAlloc(Math.multiplyExact(mesh.vertices().length, Float.BYTES));
            try {
                bytes.asFloatBuffer().put(mesh.vertices());
                vertices = RenderSystem.getDevice().createBuffer(() -> "Nebula starship", GpuBuffer.USAGE_VERTEX, bytes);
            } finally {
                MemoryUtil.memFree(bytes);
            }
            vertexCount = mesh.vertexCount();
        } catch (IOException failure) {
            close();
            throw new IllegalStateException("Could not load the Nebula starship", failure);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private static Identifier modelTexture(String path) {
        if (path == null) throw new IllegalStateException("A Nebula hull texture slot is missing");
        return NebulaRenderer.id(MODELS + path);
    }

    private static String read(ResourceManager resources, String path) throws IOException {
        try (InputStream input = resources.open(NebulaRenderer.id(MODELS + path))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override public void close() {
        if (vertices != null) vertices.close();
        vertices = null;
        vertexCount = 0;
        for (int i = 0; i < maps.length; i++) {
            if (maps[i] != null) maps[i].close();
            maps[i] = null;
        }
    }
}
