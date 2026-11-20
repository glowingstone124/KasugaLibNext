package lib.kasuga.rendering.effect.post;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import java.util.Objects;

/** Reuses a scene color copy for fullscreen passes that sample the destination's original color. */
public final class WorldPostProcess implements AutoCloseable {
    @FunctionalInterface
    public interface Inputs {
        void bind(RenderPass pass, GpuTextureView sceneColor, GpuTextureView sceneDepth);
    }

    private final String label;
    private TextureTarget snapshot;

    public WorldPostProcess(String label) {
        this.label = Objects.requireNonNull(label, "label");
    }

    /** Run outside an active render pass. The input depth remains available to subsequent world draws. */
    public void draw(RenderTarget destination, RenderPipeline pipeline, Inputs inputs) {
        RenderSystem.assertOnRenderThread();
        GpuTexture color = Objects.requireNonNull(destination.getColorTexture(), "World color texture");
        GpuTextureView depth = Objects.requireNonNull(destination.getDepthTextureView(), "World depth texture");
        GpuFormat format = color.getFormat();
        if (snapshot == null || snapshot.width != destination.width || snapshot.height != destination.height
                || snapshot.getColorTexture().getFormat() != format) {
            TextureTarget replacement = new TextureTarget(label + " scene", destination.width, destination.height, format, null);
            close();
            snapshot = replacement;
        }
        snapshot.copyColorFrom(destination);
        PostProcessContext.fullscreen(destination, pipeline, false,
                pass -> inputs.bind(pass, snapshot.getColorTextureView(), depth));
    }

    @Override public void close() {
        if (snapshot == null) return;
        snapshot.destroyBuffers();
        snapshot = null;
    }
}
