package lib.kasuga.rendering.effect.builtin.light;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.post.WorldPostProcess;
import lib.kasuga.rendering.lighting.LightBuffer;
import lib.kasuga.rendering.lighting.LightFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

final class WorldLightRenderer implements AutoCloseable {
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "core/lighting/world_lights");
    private static final int VIEW_BYTES = 5 * 4 * Float.BYTES;
    private static final int MAX_LIGHT_DATA_BYTES = Integer.MAX_VALUE / LightBuffer.BYTES_PER_LIGHT * LightBuffer.BYTES_PER_LIGHT;
    static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(KasugaLib.MODID, "pipeline/lighting/world_lights"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("LightView", UniformType.UNIFORM_BUFFER)
                    .withUniform("SceneSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("LightSampler", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
                    .build())
            .withCull(false)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();

    private final WorldPostProcess effect = new WorldPostProcess("Kasuga world lighting");
    private final MappableRingBuffer view = new MappableRingBuffer(() -> "Kasuga light view",
            GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM, VIEW_BYTES);
    private MappableRingBuffer lightData;

    void draw(CameraRenderState camera, List<LightFrame> lights) {
        int bytes = Math.multiplyExact(lights.size(), LightBuffer.BYTES_PER_LIGHT);
        if (lightData == null || lightData.size() < bytes) {
            int previous = lightData == null ? 0 : lightData.size();
            int capacity = Math.toIntExact(Math.min(MAX_LIGHT_DATA_BYTES,
                    Math.max(bytes, Math.max(previous * 2L, 16 * LightBuffer.BYTES_PER_LIGHT))));
            MappableRingBuffer replacement = new MappableRingBuffer(() -> "Kasuga light data",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, capacity);
            if (lightData != null) lightData.close();
            lightData = replacement;
        }
        GpuBuffer dataBuffer = lightData.currentBuffer();
        GpuBuffer viewBuffer = view.currentBuffer();
        try (GpuBufferSlice.MappedView mapped = dataBuffer.slice(0, bytes).map(false, true)) {
            ByteBuffer target = mapped.data().order(ByteOrder.nativeOrder());
            Vec3 eye = camera.pos;
            for (LightFrame light : lights) LightBuffer.write(target, light, eye.x, eye.y, eye.z);
        }
        try (GpuBufferSlice.MappedView mapped = viewBuffer.map(false, true)) {
            boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
            Std140Builder.intoBuffer(mapped.data())
                    .putMat4f(camera.viewRotationMatrix)
                    .putInt(lights.size())
                    .putInt(zeroToOne ? 1 : 0);
        }
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try {
            effect.draw(Minecraft.getInstance().gameRenderer.mainRenderTarget(), PIPELINE, (pass, color, depth) -> {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("LightView", viewBuffer);
                pass.setUniform("SceneSampler", color, nearest);
                pass.setUniform("DepthSampler", depth, nearest);
                pass.setUniform("LightSampler", dataBuffer);
            });
        } finally {
            lightData.rotate();
            view.rotate();
        }
    }

    @Override public void close() {
        try {
            effect.close();
        } finally {
            try {
                if (lightData != null) lightData.close();
            } finally {
                view.close();
            }
        }
    }
}
