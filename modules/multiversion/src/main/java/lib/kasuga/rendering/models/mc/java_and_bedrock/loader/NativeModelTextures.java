package lib.kasuga.rendering.models.mc.java_and_bedrock.loader;

import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialAlphaMode;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Supplies RenderPearl texture bindings to the native JSON model processors. */
public final class NativeModelTextures {
    private final ResourceManager resources;
    private final Map<Identifier, MaterialAlphaMode> alpha = new HashMap<>();

    public NativeModelTextures(ResourceManager resources) {
        this.resources = resources;
    }

    public static @Nullable Identifier resourcePath(String value) {
        Identifier id = Identifier.tryParse(value);
        if (id == null) return null;
        String path = id.getPath();
        if (!path.startsWith("textures/")) path = "textures/" + path;
        if (!path.endsWith(".png")) path += ".png";
        return id.withPath(path);
    }

    public static Identifier location(Texture texture) {
        return ((TextureLocation) texture.getData()).id();
    }

    @SuppressWarnings("unchecked")
    public Material bind(Context context, @Nullable String name, @Nullable Identifier resource,
                         float width, float height) {
        Identifier id = resource;
        if (resource == null || resources.getResource(resource).isEmpty()) {
            id = MissingTextureAtlasSprite.getLocation();
        }
        String textureKey = name == null ? id.toString() : name;
        MaterialSetBuilder<String> builder = context.getLoader().getMaterialSetBuilder();
        Material material = builder.getNamedMaterial(textureKey);
        if (material != null) return material;
        builder.registerTexture(textureKey, new Texture(textureKey, width, height, new TextureLocation(id)));
        builder.beginMaterial().useTexture(textureKey)
                .setMaterialData(new TextureMaterial(alpha.computeIfAbsent(id, this::readAlpha)))
                .addSpriteBuildingFunc((materials, sprite, ignored) -> sprite.textureId(textureKey).endSprite())
                .endMaterial(textureKey);
        return builder.getNamedMaterial(textureKey);
    }

    private MaterialAlphaMode readAlpha(Identifier id) {
        if (id.equals(MissingTextureAtlasSprite.getLocation())) return MaterialAlphaMode.OPAQUE;
        try (InputStream input = resources.open(id)) {
            return MaterialAlphaMode.fromImage(ImageIO.read(input));
        } catch (IOException ignored) {
            return MaterialAlphaMode.OPAQUE;
        }
    }

    private record TextureLocation(Identifier id) implements TextureData {}
    private record TextureMaterial(MaterialAlphaMode alphaMode) implements MaterialData {}
}
