package lib.kasuga.rendering.models.mc.component;

import net.minecraft.resources.ResourceLocation;

final class ModelComponentTextures {
    private ModelComponentTextures() {}

    static boolean isValid(String texture) {
        return ResourceLocation.tryParse(texture) != null;
    }
}
