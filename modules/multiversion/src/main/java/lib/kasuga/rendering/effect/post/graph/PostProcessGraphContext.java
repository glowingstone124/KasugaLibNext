package lib.kasuga.rendering.effect.post.graph;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import lib.kasuga.rendering.effect.post.PostProcessContext;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Gives a graph pass access to its declared targets. */
public final class PostProcessGraphContext {
    private final PostProcessContext post;
    private final PostProcessGraphPassDescriptor pass;
    private final Map<PostProcessGraphTarget, RenderTarget> targets;
    private final PostProcessGraphFrame frame;

    PostProcessGraphContext(PostProcessContext post, PostProcessGraphPassDescriptor pass,
                            Map<PostProcessGraphTarget, RenderTarget> targets, PostProcessGraphFrame frame) {
        this.post = post;
        this.pass = pass;
        this.targets = targets;
        this.frame = frame;
    }

    public PostProcessGraphPassDescriptor pass() { return pass; }
    public PostProcessGraphFrame frame() { return frame; }
    public PostProcessContext raw() { return post; }

    public RenderTarget read(PostProcessGraphTarget target) {
        if (!pass.reads().contains(target)) throw new IllegalStateException("Pass " + pass.id() + " did not declare read access to " + target);
        return Objects.requireNonNull(targets.get(target));
    }

    public RenderTarget write(PostProcessGraphTarget target) {
        if (!pass.writes().contains(target)) throw new IllegalStateException("Pass " + pass.id() + " did not declare write access to " + target);
        return Objects.requireNonNull(targets.get(target));
    }

    public void copyColor(PostProcessGraphTarget source, PostProcessGraphTarget destination, boolean linearFilter) {
        copyColor(source, 0, destination, 0, linearFilter);
    }

    public void copyColor(PostProcessGraphTarget source, int sourceIndex, PostProcessGraphTarget destination,
                          int destinationIndex, boolean linearFilter) {
        post.copyColor(read(source), sourceIndex, write(destination), destinationIndex, linearFilter);
    }

    public void copyDepth(PostProcessGraphTarget source, PostProcessGraphTarget destination) {
        post.copyDepth(read(source), write(destination));
    }

    public void fullscreen(PostProcessGraphTarget output, RenderPipeline pipeline, boolean clear, Consumer<RenderPass> inputs) {
        PostProcessContext.fullscreen(write(output), pipeline, clear, inputs);
    }

    /** Bind a declared color input using the target's sampling policy. */
    public void bindColor(RenderPass pass, String sampler, PostProcessGraphTarget source, int index) {
        RenderTarget target = read(source);
        pass.setUniform(sampler, PostProcessContext.color(target, index),
                RenderSystem.getSamplerCache().getClampToEdge(PostProcessContext.filter(target)));
    }

    /** Bind a declared depth input with nearest filtering. */
    public void bindDepth(RenderPass pass, String sampler, PostProcessGraphTarget source) {
        pass.setUniform(sampler, PostProcessContext.depth(read(source)),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
    }
}
