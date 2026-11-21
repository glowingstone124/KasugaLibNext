package lib.kasuga.rendering.models.mc.dynamic;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;

/** RenderPearl model effects are owned and collected by the model's scene attachment. */
final class ModelRuntimeEffects {
    private ModelRuntimeEffects() {}

    static void register(ModelInstance instance) {}
    static void unregister(ModelInstance instance) {}
    static void clearWorldState() {}
}
