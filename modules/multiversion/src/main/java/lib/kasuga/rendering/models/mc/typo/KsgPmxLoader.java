package lib.kasuga.rendering.models.mc.typo;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.MmdTextureImages;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipHelper;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipMeta;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipResource;
import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlModelSource;
import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.MmdMeshLoader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterial;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterialData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.pmd.PmdToPmxConverter;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Locale;
import java.util.zip.ZipInputStream;

/** Opens PMX/PMD and legacy MMD archives from the client resource packs. */
public final class KsgPmxLoader {
    private KsgPmxLoader() {}

    public static RenderPearlModelSource open(Identifier identifier) throws IOException {
        if (identifier.getPath().endsWith(".zip")) return openArchive(identifier, null);
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        Model model;
        try (InputStream input = resources.open(identifier)) {
            model = load(identifier, ByteBuffer.wrap(input.readAllBytes()), new Vector3f(MmdMeshLoader.DEFAULT_MODEL_SCALE), path -> {
                Identifier texture = ModelResourcePaths.relativeTo(identifier, path);
                if (texture == null) throw new IOException("Invalid MMD texture path: " + path);
                try (InputStream pixels = resources.open(texture)) {
                    return MmdTextureImages.decode(path, pixels.readAllBytes());
                }
            });
        }
        return upload(model);
    }

    /** Opens the requested archive entry or the first model in normalized path order. */
    public static RenderPearlModelSource openArchive(Identifier identifier, String modelEntry) throws IOException {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        ZipMeta metadata = archiveMetadata(resources, identifier);
        Charset charset = metadata == null ? StandardCharsets.UTF_8 : metadata.getCharset();
        Vector3f scale = metadata == null ? new Vector3f(MmdMeshLoader.DEFAULT_MODEL_SCALE) : metadata.getModelScale();
        Model model;
        try (ZipInputStream input = new ZipInputStream(resources.open(identifier), charset);
             ZipHelper archive = new ZipHelper(identifier, input, scale)) {
            ZipResource entry = modelEntry == null ? archive.searchNameForResource(KsgPmxLoader::isModelEntry).stream()
                    .min(Comparator.comparing(ZipResource::name)).orElse(null) : archive.getResource(modelEntry);
            if (entry == null || !isModelEntry(entry.name())) {
                throw new IOException("MMD model entry " + (modelEntry == null ? "" : modelEntry + " ") + "not found in " + identifier);
            }
            String name = entry.name();
            String directory = name.substring(0, name.lastIndexOf('/') + 1);
            model = load(identifier, entry.buffer(), scale, path -> {
                ZipResource texture = archive.getResource(directory + path);
                if (texture == null) texture = archive.getResource(path);
                if (texture == null) throw new IOException("Archive texture not found: " + path);
                ByteBuffer buffer = texture.buffer().duplicate();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                return MmdTextureImages.decode(texture.name(), bytes);
            });
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Could not read MMD archive " + identifier, failure);
        }
        return upload(model);
    }

    private static ZipMeta archiveMetadata(ResourceManager resources, Identifier archive) throws IOException {
        String path = archive.getPath();
        if (!path.endsWith(".zip")) throw new IOException("Expected an MMD ZIP archive: " + archive);
        Identifier id = Identifier.fromNamespaceAndPath(archive.getNamespace(), path.substring(0, path.length() - 4) + ".json");
        Resource metadata = resources.getResource(id).orElse(null);
        if (metadata == null) return null;
        try (Reader reader = metadata.openAsReader()) {
            return new ZipMeta(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (RuntimeException failure) {
            throw new IOException("Could not read MMD archive metadata " + id, failure);
        }
    }

    private static boolean isModelEntry(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pmx") || lower.endsWith(".pmd");
    }

    private static Model load(Identifier identifier, ByteBuffer bytes, Vector3f scale, ImageSource images) throws IOException {
        try {
            return new ModelLoader(identifier, images, scale).load(identifier, bytes).get(identifier);
        } catch (RuntimeException failure) {
            throw new IOException("Could not load MMD model " + identifier, failure);
        }
    }

    private static RenderPearlModelSource upload(Model model) {
        return RenderPearlModelSource.open(model, texture -> null, texture -> ((Diffuse) texture.getData()).image());
    }

    @FunctionalInterface
    private interface ImageSource {
        BufferedImage read(String path) throws IOException;
    }

    private record Diffuse(BufferedImage image) implements TextureData {}

    private static final class Context implements ContextData<Context> {
        @Override public void build(SerialContext<Context> context) {}
    }

    private static final class ModelLoader extends MmdMeshLoader<ByteBuffer, Identifier, String, Context> {
        private static final String WHITE = "";
        private final ImageSource images;
        private final Identifier owner;
        private final Vector3f scale;

        private ModelLoader(Identifier owner, ImageSource images, Vector3f scale) {
            super(owner.toString());
            this.images = images;
            this.scale = scale;
            this.owner = owner;
        }

        @Override protected Vector3fc modelScale() { return scale; }
        @Override public boolean isValidInput(Object input) { return input instanceof ByteBuffer; }
        @Override public void beforeLoader(StreamLoader loader, ByteBuffer buffer, SerialContext<Context> context) {}

        @Override
        public ByteBuffer getAsByteBuffer(ByteBuffer bytes) {
            ByteBuffer buffer = bytes.slice().order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.remaining() >= 3 && buffer.get(0) == 'P' && buffer.get(1) == 'm' && buffer.get(2) == 'd') {
                return new PmdToPmxConverter().convert(buffer);
            }
            return buffer;
        }

        @Override
        public void beforeAllLoaders(ByteBuffer buffer, SerialContext<Context> context) {
            materialSetBuilder().registerTexture(WHITE, loadTexture(WHITE));
        }

        @Override public String getTextureIdentifier(String texturePath) { return texturePath.replace('\\', '/'); }

        @Override
        public Texture loadTexture(Object identifier) {
            String path = (String) identifier;
            Texture loaded = materialSetBuilder().getTexture(path);
            if (loaded != null) return loaded;
            if (path.equals(WHITE)) {
                BufferedImage white = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
                white.setRGB(0, 0, 0xffffffff);
                return new Texture("mmd:white", 1, 1, new Diffuse(white));
            }
            try {
                BufferedImage image = images.read(path);
                return new Texture(path, image.getWidth(), image.getHeight(), new Diffuse(image));
            } catch (IOException failure) {
                LogUtils.getLogger().warn("Could not load MMD texture '{}' from {}: {}", path, owner, failure.getMessage());
                return materialSetBuilder().getTexture(WHITE);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public void buildMaterial(MaterialSetBuilder builder, PmxMaterial material) {
            MaterialSetBuilder<String> materials = builder;
            int index = material.textureIndex.intValue();
            String path = index >= 0 && index < getTextures().size() ? getTextureIdentifier(getTextures().get(index)) : WHITE;
            Texture diffuse = materials.getTexture(path);
            materials.setMaterialData(PmxMaterialData.from(material, ((Diffuse) diffuse.getData()).image()));
            materials.useTexture(path);
            materials.addSpriteBuildingFunc((materialBuilder, sprites, result) -> sprites
                    .textureId(path)
                    .culled(!material.flags.noCull)
                    .shade(material.flags.drawShadow)
                    .color(new Vector4f(material.diffuseColor))
                    .endSprite());
            materials.endMaterial(material);
        }
    }
}
