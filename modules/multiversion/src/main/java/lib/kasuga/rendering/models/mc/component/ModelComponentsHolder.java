package lib.kasuga.rendering.models.mc.component;

import lib.kasuga.rendering.models.mc.dynamic.ModelParticleRuntime;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;

/** Model data with the Kasuga extension read from the authored asset. */
public interface ModelComponentsHolder extends ModelData {
    ModelComponents components();

    @Override
    default void configureInstance(ModelInstance instance) {
        ModelComponents definition = components();
        if (definition != null) ModelParticleRuntime.installWithWorldPlacement(instance, definition);
    }
}
