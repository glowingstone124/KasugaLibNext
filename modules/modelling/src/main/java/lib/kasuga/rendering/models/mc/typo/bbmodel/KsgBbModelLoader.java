package lib.kasuga.rendering.models.mc.typo.bbmodel;

import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.java_and_bedrock.IdentifierHelper;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTexture;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.source.texture.KasugaTextureManager;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.structure.Pair;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Binds BBModel textures to the 1.21.1 Kasuga atlas. */
public final class KsgBbModelLoader extends BbModelLoader<ResourceLocation> {
    public KsgBbModelLoader(String name) {
        super(name);
    }

    @Override public Map<ResourceLocation, Model> load(ResourceLocation identifier, String input) {
        return loadModel(identifier, input);
    }

    @Override protected MCTexture createTexture(ResourceLocation modelIdentifier, BbModelDefinition.Texture texture,
                                    BbModelDefinition definition, @Nullable BufferedImage image) {
        KasugaTextureManager textureManager = Constants.TEXTURE_BASIC;
        Object sourceIdentifier;
        ResourceLocation textureIdentifier;
        int[] canvas = uvCanvas(texture, definition);
        if (texture.source().startsWith("data:image/")) {
            textureIdentifier = embeddedTextureLocation(modelIdentifier, texture.index());
            sourceIdentifier = Pair.of(textureIdentifier, image);
        } else {
            textureIdentifier = resolveTextureLocation(modelIdentifier, texture.source(), texture.name());
            sourceIdentifier = textureIdentifier;
        }
        textureManager.load(sourceIdentifier);
        net.minecraft.client.resources.model.Material material =
                new net.minecraft.client.resources.model.Material(RenderState.KSG_LAYER_0, textureIdentifier);
        // The MCTexture size is the divisor normalizePixelUv() uses, so it must be the UV canvas — never the
        // decoded image size, which only supplies the sprite content.
        return new MCTexture(texture.id(), () -> material, canvas[0], canvas[1],
                new MCTextureData(sourceIdentifier, textureManager, true));
    }

    @Override protected @Nullable BufferedImage textureImage(
            ResourceLocation modelIdentifier,
            BbModelDefinition.Texture texture
    ) {
        if (texture.source().startsWith("data:image/")) {
            return decodeImage(texture.source(), modelIdentifier);
        }
        ResourceLocation sprite = resolveTextureLocation(modelIdentifier, texture.source(), texture.name());
        ResourceLocation imageLocation = ResourceLocation.fromNamespaceAndPath(
                sprite.getNamespace(), "textures/" + sprite.getPath() + ".png");
        var stream = IdentifierHelper.getInputStream(imageLocation);
        if (stream.isEmpty()) {
            return null;
        }
        try (InputStream input = stream.get()) {
            return ImageIO.read(input);
        } catch (IOException exception) {
            return null;
        }
    }

    static ResourceLocation resolveTextureLocation(ResourceLocation model, String source, String name) {
        ResourceLocation sourceLocation = source.contains(":")
                ? ResourceLocation.parse(source)
                : ResourceLocation.fromNamespaceAndPath(model.getNamespace(), source.isBlank() ? name : source);
        String path = sourceLocation.getPath();
        if (path.startsWith("textures/")) path = path.substring("textures/".length());
        if (path.endsWith(".png")) path = path.substring(0, path.length() - ".png".length());
        return ResourceLocation.fromNamespaceAndPath(sourceLocation.getNamespace(), path);
    }

    private static ResourceLocation embeddedTextureLocation(ResourceLocation model, int index) {
        String path = model.getPath().replaceAll("[^a-z0-9_./-]", "_").replace(".bbmodel", "");
        // Atlas sprite identifiers omit the conventional textures/ directory and .png suffix.
        // Keeping those filesystem markers here makes the stitched sprite lookup miss.
        return ResourceLocation.fromNamespaceAndPath(model.getNamespace(), "bbmodel/" + path + "_" + index);
    }
}
