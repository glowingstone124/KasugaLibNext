package lib.kasuga.rendering.models.mc.typo.gltf;

import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlModelSource;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfLoader;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfModelConverter;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfModelData;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/** Opens a resource-pack glTF or GLB with its textures and animation clips. */
public final class KsgGltfLoader {
    private KsgGltfLoader() {}

    public static RenderPearlModelSource open(Identifier identifier) throws IOException {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        Model model;
        try (InputStream input = resources.open(identifier)) {
            model = GltfModelConverter.convert(GltfLoader.loadAllAnimations(input,
                    reference -> readReference(resources, identifier, reference)));
        }
        return RenderPearlModelSource.open(model, texture -> null,
                texture -> ((GltfModelData.GltfTextureData) texture.getData()).image());
    }

    private static byte[] readReference(ResourceManager resources, Identifier model, String reference) throws IOException {
        Identifier location;
        try {
            URI uri = URI.create(reference);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Expected a resource-pack path");
            }
            String path = URI.create("/" + model.getPath()).resolve(uri).normalize().getPath();
            location = Identifier.tryBuild(model.getNamespace(), path.substring(1));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid glTF resource reference in " + model + ": " + reference, invalid);
        }
        if (location == null) throw new IOException("Invalid glTF resource path in " + model + ": " + reference);
        try (InputStream input = resources.open(location)) {
            return input.readAllBytes();
        }
    }
}
