package lib.kasuga.rendering.models.mc.typo.bbmodel;

import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlModelSource;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** Loads native Blockbench files and binds their textures to RenderPearl. */
public final class KsgBbModelLoader extends BbModelLoader<Identifier> {
    private final ResourceManager resources;

    public KsgBbModelLoader(String name, ResourceManager resources) {
        super(name);
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    @Override public Map<Identifier, Model> load(Identifier identifier, String input) {
        return loadModel(identifier, input);
    }

    /** Opens a scene node that owns the model instance and its embedded GPU textures. */
    public static RenderPearlModelSource open(Identifier identifier) throws IOException {
        RenderSystem.assertOnRenderThread();
        Minecraft client = Minecraft.getInstance();
        ResourceManager resources = client.getResourceManager();
        KsgBbModelLoader loader = new KsgBbModelLoader("bbmodel", resources);
        Model model;
        try (InputStream input = resources.open(identifier)) {
            model = loader.load(identifier, new String(input.readAllBytes(), StandardCharsets.UTF_8)).get(identifier);
        }
        return RenderPearlModelSource.open(model, texture -> ((BbTexture) texture.getData()).location(),
                texture -> ((BbTexture) texture.getData()).image());
    }

    @Override protected Texture createTexture(Identifier model, BbModelDefinition.Texture texture,
                                               BbModelDefinition definition, @Nullable BufferedImage image) {
        int[] canvas = uvCanvas(texture, definition);
        BbTexture data = texture.source().startsWith("data:image/")
                ? new BbTexture(null, Objects.requireNonNull(image))
                : new BbTexture(resolveTextureLocation(model, texture.source(), texture.name()), null);
        return new Texture(texture.id(), canvas[0], canvas[1], data);
    }

    @Override protected @Nullable BufferedImage textureImage(Identifier model, BbModelDefinition.Texture texture) {
        if (texture.source().startsWith("data:image/")) return decodeImage(texture.source(), model);
        Identifier location = resolveTextureLocation(model, texture.source(), texture.name());
        try (InputStream input = resources.open(location)) {
            return ImageIO.read(input);
        } catch (IOException unavailable) {
            return null;
        }
    }

    private static Identifier resolveTextureLocation(Identifier model, String source, String name) {
        Identifier location = source.contains(":") ? Identifier.parse(source)
                : Identifier.fromNamespaceAndPath(model.getNamespace(), source.isBlank() ? name : source);
        String path = location.getPath();
        if (!path.startsWith("textures/")) path = "textures/" + path;
        if (!path.endsWith(".png")) path += ".png";
        return Identifier.fromNamespaceAndPath(location.getNamespace(), path);
    }

    private record BbTexture(@Nullable Identifier location, @Nullable BufferedImage image) implements TextureData {}
}
