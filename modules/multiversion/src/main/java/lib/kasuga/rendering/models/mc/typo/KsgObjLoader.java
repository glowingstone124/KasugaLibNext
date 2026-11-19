package lib.kasuga.rendering.models.mc.typo;

import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlModelSource;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.rendering.models.uml.structure.basic.data.mesh.MeshData;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.VertexData;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.SkeletonData;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjContextData;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjModelLoader;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjTextureData;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Loads OBJ geometry and MTL surface materials from Minecraft resource packs. */
public final class KsgObjLoader extends ObjModelLoader<String, Identifier, String> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WHITE = "kasuga:untextured";
    private final ResourceManager resources;
    private final Identifier modelId;
    private final Map<Identifier, ObjImage> images = new HashMap<>();
    private Identifier materialLibrary;

    private KsgObjLoader(ResourceManager resources, Identifier modelId) {
        super("resource_obj", true, true, true);
        this.resources = resources;
        this.modelId = modelId;
    }

    public static RenderPearlModelSource open(Identifier identifier) throws IOException {
        ResourceManager resourceManager = Minecraft.getInstance().getResourceManager();
        KsgObjLoader loader = new KsgObjLoader(resourceManager, identifier);
        Model model;
        try (InputStream input = resourceManager.open(identifier)) {
            model = loader.load(identifier, new String(input.readAllBytes(), StandardCharsets.UTF_8)).get(identifier);
        }
        return RenderPearlModelSource.open(model, texture -> null, texture -> ((ObjImage) texture.getData()).pixels());
    }

    @Override public String getTextureContent(String reference) {
        materialLibrary = reference.contains(":") ? resourceAlias(reference, "models/", ".mtl")
                : ModelResourcePaths.relativeTo(modelId, reference);
        if (materialLibrary == null || resources.getResource(materialLibrary).isEmpty()) return "";
        try (InputStream input = resources.open(materialLibrary)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            LOGGER.warn("Could not read OBJ material library {}", materialLibrary, error);
            return "";
        }
    }

    @Override public void consumeTexture(ObjTextureData definition) {
        String diffuse = definition.getMap_Kd();
        Identifier imageId = diffuse == null ? null : diffuse.contains(":")
                ? resourceAlias(diffuse, "textures/", ".png")
                : ModelResourcePaths.relativeTo(materialLibrary, diffuse);
        ObjImage image = imageId == null ? null : images.computeIfAbsent(imageId, this::readImage);
        registerMaterial(definition.getName(), image == null ? WHITE : imageId.toString(), image,
                new Vector4f(definition.getKd(), Math.clamp(definition.getD(), 0f, 1f)));
    }

    private static @Nullable Identifier resourceAlias(String reference, String prefix, String suffix) {
        Identifier location = Identifier.tryParse(reference);
        if (location == null) return null;
        String path = location.getPath();
        if (!path.startsWith(prefix)) path = prefix + path;
        if (!path.endsWith(suffix)) path += suffix;
        return Identifier.tryBuild(location.getNamespace(), path);
    }

    private @Nullable ObjImage readImage(Identifier id) {
        try (InputStream input = resources.open(id)) {
            BufferedImage pixels = ImageIO.read(input);
            if (pixels != null) return ObjImage.describe(pixels);
        } catch (IOException error) {
            LOGGER.warn("Could not read OBJ diffuse texture {}", id, error);
        }
        return null;
    }

    private Material registerMaterial(String name, String textureId, @Nullable ObjImage image, Vector4f color) {
        if (image == null) image = ObjImage.WHITE;
        if (materialSetBuilder().getTexture(textureId) == null) {
            materialSetBuilder().registerTexture(textureId,
                    new Texture(textureId, image.pixels().getWidth(), image.pixels().getHeight(), image));
        }
        ObjMaterialData alpha = ObjMaterialData.from(color.w, image.transparent(), image.partial());
        materialSetBuilder().useTexture(textureId).setMaterialData(alpha)
                .addSpriteBuildingFunc((builder, sprite, material) ->
                        sprite.textureId(textureId).flipV(true).color(color).endSprite())
                .endMaterial(name);
        return materialSetBuilder().getNamedMaterial(name);
    }

    @Override public Material getMaterial(ObjContextData data, ObjModelLoader loader, String name) {
        String key = name != null ? name : "__default__";
        Material existing = materialSetBuilder().getNamedMaterial(key);
        return existing != null ? existing : registerMaterial(key, WHITE, ObjImage.WHITE, new Vector4f(1f));
    }

    @Override public Texture getTexture(ObjContextData data, ObjModelLoader loader, String name) {
        return getMaterial(data, loader, name).getTextures()[0];
    }

    @Override public @Nullable Texture loadTexture(Object identifier) {
        return identifier instanceof String name ? materialSetBuilder().getTexture(name) : null;
    }

    @Override public Identifier getIdentifier(ObjModelLoader loader, String input) { return modelId; }
    @Override public String getAsString(String input) { return input; }
    @Override public boolean isValidInput(Object input) { return input instanceof String; }
    @Override public @Nullable VertexData getVertexData(ObjContextData data, ObjModelLoader loader, String bone) { return null; }
    @Override public @Nullable MeshData getMeshData(ObjContextData data, ObjModelLoader loader) { return null; }
    @Override public @Nullable BoneData getBoneData(ObjModelLoader loader, String bone) { return null; }
    @Override public @Nullable BoneBindingData getBoneBindingData(ObjModelLoader loader, Bone bone, Vertex vertex) { return null; }
    @Override public @Nullable SkeletonData getSkeletonData(ObjModelLoader loader, Bone[] bones) { return null; }
    @Override public @Nullable ModelData getModelData(ObjModelLoader loader) { return null; }
    @Override public Anchor[] getAnchors(ObjModelLoader loader, Bone[] bones, Bone root) { return new Anchor[0]; }

    private record ObjImage(BufferedImage pixels, boolean transparent, boolean partial) implements TextureData {
        private static final ObjImage WHITE = new ObjImage(whitePixel(), false, false);

        private static BufferedImage whitePixel() {
            BufferedImage pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            pixel.setRGB(0, 0, 0xffffffff);
            return pixel;
        }

        private static ObjImage describe(BufferedImage pixels) {
            boolean transparent = false;
            for (int y = 0; y < pixels.getHeight(); y++) {
                for (int x = 0; x < pixels.getWidth(); x++) {
                    int alpha = pixels.getRGB(x, y) >>> 24;
                    if (alpha == 255) continue;
                    if (alpha != 0) return new ObjImage(pixels, true, true);
                    transparent = true;
                }
            }
            return new ObjImage(pixels, transparent, false);
        }
    }
}
