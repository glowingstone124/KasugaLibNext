package lib.kasuga.rendering.effect.builtin.water;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.rendering.water.ShallowWaterGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;

final class WaterHeightTexture implements AutoCloseable {
    private DynamicTexture texture;
    private long uploaded = -1;

    void update(ShallowWaterGrid grid, long revision) {
        if (uploaded == revision) return;
        if (texture == null) texture = new DynamicTexture("Kasuga water height", grid.size(), grid.size(), false);
        NativeImage pixels = texture.getPixels();
        for (int z = 0; z < grid.size(); z++) {
            for (int x = 0; x < grid.size(); x++) {
                int height = Math.round(Math.clamp((grid.cellDepth(x, z) - grid.restDepth() + .25f) * 2, 0, 1) * 65535);
                pixels.setPixel(x, z, 0xff000000 | (height >>> 8) << 16 | (height & 255) << 8);
            }
        }
        texture.upload();
        uploaded = revision;
    }

    GpuTextureView view() { return texture.getTextureView(); }

    @Override public void close() {
        DynamicTexture retired = texture;
        texture = null;
        if (retired != null) Minecraft.getInstance().execute(retired::close);
    }
}
