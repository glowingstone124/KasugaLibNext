package lib.kasuga.rendering.models.mc.typo.bbmodel;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlModelSource;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Loads native Blockbench files and binds their textures to RenderPearl. */
public final class KsgBbModelLoader extends BbModelLoader<Identifier> {
    private static final AtomicLong TEXTURE_IDS = new AtomicLong();
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
        TextureManager manager = client.getTextureManager();
        List<Identifier> ownedTextures = new ArrayList<>();
        Map<Texture, Identifier> bindings = new IdentityHashMap<>();
        ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
        try {
            for (Texture texture : model.getMaterialSet().getTextures()) {
                BbTexture data = (BbTexture) texture.getData();
                Identifier location = data.location();
                if (data.image() != null) {
                    location = registerEmbedded(manager, data.image());
                    ownedTextures.add(location);
                }
                bindings.put(texture, location);
            }
            return RenderPearlModelSource.own(instance, bindings::get, () -> release(manager, ownedTextures));
        } catch (RuntimeException | Error failure) {
            try {
                instance.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            } finally {
                release(manager, ownedTextures);
            }
            throw failure;
        }
    }

    private static Identifier registerEmbedded(TextureManager textures, BufferedImage image) {
        Identifier id = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "bbmodel/embedded/" + TEXTURE_IDS.incrementAndGet());
        DynamicTexture texture = new DynamicTexture(id::toString, image.getWidth(), image.getHeight(), false);
        try {
            NativeImage pixels = texture.getPixels();
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) pixels.setPixel(x, y, image.getRGB(x, y));
            }
            texture.upload();
            textures.register(id, texture);
            return id;
        } catch (RuntimeException | Error failure) {
            texture.close();
            throw failure;
        }
    }

    private static void release(TextureManager manager, List<Identifier> textures) {
        for (Identifier texture : textures) manager.release(texture);
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
