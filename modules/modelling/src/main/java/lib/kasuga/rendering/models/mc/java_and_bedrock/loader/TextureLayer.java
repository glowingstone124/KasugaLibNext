package lib.kasuga.rendering.models.mc.java_and_bedrock.loader;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import lib.kasuga.utils.json.GsonBindings;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.java_and_bedrock.IdentifierHelper;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTexture;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.source.texture.KasugaTextureManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Layer;
import lib.kasuga.rendering.models.uml.loaders.structural.Loader;
import lib.kasuga.structure.Pair;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector2f;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Objects;

public class TextureLayer extends Layer<JsonObject> {

    @Override
    public void process(JsonObject input, Context context) {
        Loader loader = context.getLoader();
        HashMap<String, Pair<Material, MCTextureData>> textures = new HashMap<>();
        java.util.Map<String, String> references = GsonBindings.GSON.fromJson(input,
                new TypeToken<java.util.Map<String, String>>() {}.getType());
        for (var entry : references.entrySet()) {
            String key = entry.getKey(), val = entry.getValue();
            Object identifier;
            ResourceLocation rl;
            Pair<ResourceLocation, Path> resolved = IdentifierHelper.getRLAndPath(val);
            Objects.requireNonNull(resolved);
            rl = resolved.getFirst();
            identifier = resolved.getSecond() == null ? rl : resolved.getSecond();
            Material material = new Material(RenderState.KSG_LAYER_0, rl);
            loader.loadType("texture", "mc_layer_0", identifier);
            KasugaTextureManager textureManager = (KasugaTextureManager)
                    ((HashMap<String, SourceManager>) loader.getSidedSources().get(Constants.TEXTURE_TYPE))
                    .get("mc_layer_0");
            textureManager.load(identifier);
            MCTextureData mcTextureData = new MCTextureData(
                    identifier,
                    textureManager
            );
            textures.put(key, Pair.of(material, mcTextureData));
        }
        context.setData("textures", textures);
    }

    @SuppressWarnings("unchecked")
    public static lib.kasuga.rendering.models.uml.structure.material.Material bindRoot(
            Context context, float width, float height) {
        HashMap<String, Pair<Material, MCTextureData>> textures =
                (HashMap<String, Pair<Material, MCTextureData>>) context.getData("textures");
        Pair<Material, MCTextureData> texture = textures.get("root");
        MaterialSetBuilder<String> builder = context.getLoader().getMaterialSetBuilder();
        builder.registerTexture("root", new MCTexture("root", texture::getFirst, width, height, texture.getSecond()));
        builder.beginMaterial().useTexture("root")
                .addSpriteBuildingFunc((materials, sprite, material) -> {
                    sprite.textureId("root").rectangularUVs(new Vector2f(), new Vector2f(1f)).endSprite();
                }).endMaterial();
        return builder.getMaterials().getFirst();
    }
}
