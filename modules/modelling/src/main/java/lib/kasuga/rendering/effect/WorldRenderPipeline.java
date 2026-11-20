package lib.kasuga.rendering.effect;

/**
 * A render-thread callback invoked at a registered world render stage.
 *
 * <p>Keep resources borrowed from the context within the callback. Finish any batch or pass
 * opened by the callback before returning.</p>
 */
@FunctionalInterface
public interface WorldRenderPipeline {

    void render(WorldRenderPipelineContext context);
}
