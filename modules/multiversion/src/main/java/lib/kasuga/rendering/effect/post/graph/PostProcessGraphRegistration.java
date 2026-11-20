package lib.kasuga.rendering.effect.post.graph;

import lib.kasuga.rendering.effect.RenderRegistration;

/** Ownership and scheduling information for a RenderPearl post-process graph. */
public interface PostProcessGraphRegistration extends RenderRegistration {
    PostProcessGraph graph();
    int priority();
}
