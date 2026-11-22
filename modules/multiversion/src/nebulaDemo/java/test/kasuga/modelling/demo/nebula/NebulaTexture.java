package test.kasuga.modelling.demo.nebula;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

record NebulaTexture(GpuTexture texture, GpuTextureView view) implements AutoCloseable {
    static NebulaTexture rgba8(String label, int width, int height, byte[] source) {
        ByteBuffer pixels = MemoryUtil.memAlloc(source.length);
        try {
            pixels.put(source).flip();
            return upload(label, GpuFormat.RGBA8_UNORM, width, height, pixels);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    static NebulaTexture read(ResourceManager resources, Identifier id, boolean srgb) throws IOException {
        try (InputStream input = resources.open(id); NativeImage image = NativeImage.read(input)) {
            GpuFormat format = srgb ? GpuFormat.RGBA16_FLOAT : GpuFormat.RGBA8_UNORM;
            int width = image.getWidth();
            int height = image.getHeight();
            ByteBuffer pixels = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(width, height), format.blockSize()));
            try {
                for (int y = height - 1; y >= 0; y--) {
                    for (int x = 0; x < width; x++) {
                        int color = image.getPixel(x, y);
                        if (srgb) {
                            pixels.putShort(Float.floatToFloat16(linear(color >>> 16 & 255)));
                            pixels.putShort(Float.floatToFloat16(linear(color >>> 8 & 255)));
                            pixels.putShort(Float.floatToFloat16(linear(color & 255)));
                            pixels.putShort(Float.floatToFloat16((color >>> 24) / 255f));
                        } else {
                            pixels.put((byte) (color >>> 16)).put((byte) (color >>> 8))
                                    .put((byte) color).put((byte) (color >>> 24));
                        }
                    }
                }
                pixels.flip();
                return upload(id.toString(), format, width, height, pixels);
            } finally {
                MemoryUtil.memFree(pixels);
            }
        }
    }

    private static float linear(int channel) {
        float value = channel / 255f;
        return value <= .04045f ? value / 12.92f : (float) Math.pow((value + .055f) / 1.055f, 2.4f);
    }

    private static NebulaTexture upload(String label, GpuFormat format, int width, int height, ByteBuffer data) {
        GpuDevice device = RenderSystem.getDevice();
        GpuTexture texture = device.createTexture(label, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                format, width, height, 1, 1);
        try {
            device.createCommandEncoder().writeToTexture(texture, data, 0, 0, 0, 0, width, height);
            return new NebulaTexture(texture, device.createTextureView(texture));
        } catch (RuntimeException | Error failure) {
            texture.close();
            throw failure;
        }
    }

    @Override public void close() {
        view.close();
        texture.close();
    }
}
