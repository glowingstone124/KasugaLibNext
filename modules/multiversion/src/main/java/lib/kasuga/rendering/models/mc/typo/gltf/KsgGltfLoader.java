package lib.kasuga.rendering.models.mc.typo.gltf;

import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlModelSource;
import lib.kasuga.rendering.models.mc.typo.ModelResourcePaths;
import lib.kasuga.rendering.models.mc.typo.gltf_entry.GltfModelManifest;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfLoader;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfModelConverter;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfModelData;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;

/** Opens a resource-pack glTF or GLB with its textures and animation clips. */
public final class KsgGltfLoader {
    private KsgGltfLoader() {}

    public static RenderPearlModelSource open(Identifier identifier) throws IOException {
        return open(Minecraft.getInstance().getResourceManager(), identifier);
    }

    public static RenderPearlModelSource open(ResourceManager resources, Identifier identifier) throws IOException {
        Model model;
        GltfModelManifest manifest = GltfModelManifest.load(resources, identifier);
        try (InputStream input = resources.open(identifier)) {
            model = GltfModelConverter.convertWithScale(GltfLoader.loadAllAnimations(input,
                    reference -> readReference(resources, identifier, reference)), manifest.modelScale());
        }
        return RenderPearlModelSource.open(model, texture -> null,
                texture -> ((GltfModelData.GltfTextureData) texture.getData()).image());
    }

    private static byte[] readReference(ResourceManager resources, Identifier model, String reference) throws IOException {
        Identifier location = ModelResourcePaths.relativeTo(model, reference);
        if (location == null) throw new IOException("Invalid glTF resource path in " + model + ": " + reference);
        try (InputStream input = resources.open(location)) {
            return input.readAllBytes();
        }
    }
}
