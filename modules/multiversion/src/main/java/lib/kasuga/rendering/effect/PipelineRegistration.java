package lib.kasuga.rendering.effect;

import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;

/** Registration of one scheduled world callback. */
public interface PipelineRegistration extends RenderRegistration {
    RenderPipelineDescriptor descriptor();
}
