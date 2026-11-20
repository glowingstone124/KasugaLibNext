package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.je;

import lib.kasuga.rendering.models.mc.java_and_bedrock.data.je.JEModelData;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.NativeModelTextures;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Layer;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class JETextureLayer extends Layer<JEModelData> {
    private final NativeModelTextures textures;

    public JETextureLayer() {
        this(null);
    }

    public JETextureLayer(@Nullable NativeModelTextures textures) {
        this.textures = textures;
    }

    @Override public void process(JEModelData data, Context context) {
        NativeModelTextures bindings = textures != null ? textures
                : new NativeModelTextures(Minecraft.getInstance().getResourceManager());
        context.setData("java_textures", new FaceTextures(bindings, data.getTextures()));
    }

    static Material materialFor(String reference, Context context) {
        FaceTextures textures = (FaceTextures) context.getData("java_textures");
        return textures.bindings().bind(context, null, resolve(reference, textures.aliases()), 16f, 16f);
    }

    private static @Nullable Identifier resolve(String reference, Map<String, String> aliases) {
        Set<String> seen = new HashSet<>();
        String value = reference;
        while (value != null && value.startsWith("#")) {
            if (!seen.add(value)) return null;
            value = aliases.get(value.substring(1));
        }
        return value == null ? null : NativeModelTextures.resourcePath(value);
    }

    private record FaceTextures(NativeModelTextures bindings, Map<String, String> aliases) {}
}
