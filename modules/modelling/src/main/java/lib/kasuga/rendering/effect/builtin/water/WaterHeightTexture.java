package lib.kasuga.rendering.effect.builtin.water;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.rendering.water.ShallowWaterGrid;
import net.minecraft.client.renderer.texture.DynamicTexture;

final class WaterHeightTexture implements AutoCloseable {
    private DynamicTexture texture;
    private long uploaded = -1;

    void update(ShallowWaterGrid grid, long revision) {
        if (uploaded == revision) return;
        if (texture == null) {
            texture = new DynamicTexture(new NativeImage(grid.size(), grid.size(), false));
            texture.setFilter(true, false);
        }
        NativeImage pixels = texture.getPixels();
        for (int z = 0; z < grid.size(); z++) {
            for (int x = 0; x < grid.size(); x++) {
                int height = Math.round(Math.clamp((grid.cellDepth(x, z) - grid.restDepth() + .25f) * 2, 0, 1) * 65535);
                pixels.setPixelRGBA(x, z, 0xff000000 | (height & 255) << 8 | height >>> 8);
            }
        }
        texture.upload();
        uploaded = revision;
    }

    int id() { return texture.getId(); }

    @Override public void close() {
        DynamicTexture retired = texture;
        texture = null;
        if (retired == null) return;
        if (RenderSystem.isOnRenderThread()) retired.close();
        else RenderSystem.recordRenderCall(retired::close);
    }
}
