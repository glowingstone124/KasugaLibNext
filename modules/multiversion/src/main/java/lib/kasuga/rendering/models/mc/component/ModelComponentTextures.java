package lib.kasuga.rendering.models.mc.component;

import net.minecraft.resources.Identifier;

final class ModelComponentTextures {
    private ModelComponentTextures() {}

    static boolean isValid(String texture) {
        return Identifier.tryParse(texture) != null;
    }
}
