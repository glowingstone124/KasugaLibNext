package lib.kasuga.rendering.models.mc.dynamic;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;

final class ModelRuntimeEffects {
    private ModelRuntimeEffects() {}

    static void register(ModelInstance instance) {
        ModelParticleRuntime.register(instance);
        ModelLightRuntime.register(instance);
    }

    static void unregister(ModelInstance instance) {
        ModelParticleRuntime.unregister(instance);
        ModelLightRuntime.unregister(instance);
    }

    static void clearWorldState() {
        ModelParticleRuntime.clearWorldState();
        ModelLightRuntime.clearWorldState();
    }
}
