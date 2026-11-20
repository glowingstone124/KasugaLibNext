package lib.kasuga.rendering.effect.builtin.cloud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.rendering.cloud.CloudNoise;
import lib.kasuga.rendering.cloud.CloudShapes;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/** Packs the legacy noise and shapes into portable 2D textures with isolated filtering gutters. */
final class CloudVolumeAtlas implements AutoCloseable {
    private static final int SIZE = 64;
    private static final int TILES = 8;
    private static final int STRIDE = SIZE + 2;
    private static final int SIDE = TILES * STRIDE;
    private static final CompletableFuture<byte[]> NOISE = CompletableFuture.supplyAsync(() -> CloudNoise.generate(SIZE).pixels());
    private static final CompletableFuture<float[]> SHAPES = CompletableFuture.supplyAsync(() -> CloudShapes.bake(SIZE));
    private GpuTexture noise;
    private GpuTexture shapes;
    private GpuTextureView noiseView;
    private GpuTextureView shapesView;

    static boolean ready() { return NOISE.isDone() && SHAPES.isDone(); }

    void prepare() {
        if (noiseView != null) return;
        byte[] noiseData = NOISE.join();
        float[] shapeData = SHAPES.join();
        try {
            noise = upload("Kasuga cloud noise", GpuFormat.RGBA8_UNORM, (pixels, x, y, z) ->
                    pixels.put(noiseData, index(Math.floorMod(x, SIZE), Math.floorMod(y, SIZE), z), 4));
            shapes = upload("Kasuga cloud shape", GpuFormat.RGBA16_FLOAT, (pixels, x, y, z) -> {
                int index = index(Math.clamp(x, 0, SIZE - 1), Math.clamp(y, 0, SIZE - 1), z);
                for (int channel = 0; channel < 4; channel++) pixels.putShort(Float.floatToFloat16(shapeData[index + channel]));
            });
            noiseView = RenderSystem.getDevice().createTextureView(noise);
            shapesView = RenderSystem.getDevice().createTextureView(shapes);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private static int index(int x, int y, int z) { return ((z * SIZE + y) * SIZE + x) * 4; }

    private static GpuTexture upload(String name, GpuFormat format, VoxelWriter writer) {
        ByteBuffer pixels = MemoryUtil.memAlloc(SIDE * SIDE * format.blockSize());
        try {
            for (int y = 0; y < SIDE; y++) {
                for (int x = 0; x < SIDE; x++) {
                    int slice = (y / STRIDE) * TILES + x / STRIDE;
                    writer.write(pixels, x % STRIDE - 1, y % STRIDE - 1, slice);
                }
            }
            pixels.flip();
            GpuTexture texture = RenderSystem.getDevice().createTexture(name,
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, format, SIDE, SIDE, 1, 1);
            try {
                RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, pixels, 0, 0, 0, 0, SIDE, SIDE);
                return texture;
            } catch (RuntimeException | Error failure) {
                texture.close();
                throw failure;
            }
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    GpuTextureView noise() { return noiseView; }
    GpuTextureView shapes() { return shapesView; }

    @Override public void close() {
        if (noiseView != null) noiseView.close();
        if (shapesView != null) shapesView.close();
        if (noise != null) noise.close();
        if (shapes != null) shapes.close();
        noise = null;
        shapes = null;
        noiseView = null;
        shapesView = null;
    }

    @FunctionalInterface
    private interface VoxelWriter {
        void write(ByteBuffer destination, int x, int y, int z);
    }
}
