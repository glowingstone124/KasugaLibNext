package lib.kasuga.rendering.models.mc.java_and_bedrock.loader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Layer;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

public final class TextureLayer extends Layer<JsonObject> {
    private final NativeModelTextures textures;
    private final Identifier textureOverride;

    public TextureLayer() {
        this(null, null);
    }

    public TextureLayer(@Nullable NativeModelTextures textures, @Nullable Identifier textureOverride) {
        this.textures = textures;
        this.textureOverride = textureOverride;
    }

    @Override public void process(@Nullable JsonObject input, Context context) {
        NativeModelTextures bindings = textures != null ? textures
                : new NativeModelTextures(Minecraft.getInstance().getResourceManager());
        Identifier image = textureOverride;
        if (image == null) {
            JsonElement root = input == null ? null : input.get("root");
            if (root != null && root.isJsonPrimitive() && root.getAsJsonPrimitive().isString()) {
                image = NativeModelTextures.resourcePath(root.getAsString());
            } else {
                Identifier model = (Identifier) context.getData("identifier");
                String path = model.getPath();
                if (path.startsWith("models/")) path = path.substring("models/".length());
                if (path.endsWith(".geo.json")) path = path.substring(0, path.length() - ".geo.json".length());
                image = model.withPath("textures/" + path + ".png");
            }
        }
        context.setData("bedrock_texture", new RootTexture(bindings, image));
    }

    public static Material bindRoot(Context context, float width, float height) {
        RootTexture root = (RootTexture) context.getData("bedrock_texture");
        return root.bindings().bind(context, "root", root.image(), width, height);
    }

    private record RootTexture(NativeModelTextures bindings, @Nullable Identifier image) {}
}
