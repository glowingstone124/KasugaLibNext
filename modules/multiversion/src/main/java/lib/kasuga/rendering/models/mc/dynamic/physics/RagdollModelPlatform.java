package lib.kasuga.rendering.models.mc.dynamic.physics;

import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlModelSource;
import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlWorldModels;
import lib.kasuga.rendering.models.mc.typo.KsgPmxLoader;
import lib.kasuga.rendering.models.mc.typo.gltf.KsgGltfLoader;
import lib.kasuga.rendering.models.mc.typo.gltf_entry.GltfModelManifest;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.io.IOException;
import java.util.function.Supplier;

final class RagdollModelPlatform {
    private RagdollModelPlatform() {}

    static @Nullable MinecraftRagdollDeployments.ModelLease open(MinecraftRagdollDeployments.Request request,
            ResourceManager resources, Supplier<? extends Level> levelSupplier) throws IOException {
        if (!(levelSupplier.get() instanceof ClientLevel level)) return null;
        Identifier model = request.modelResource();
        String path = model.getPath();
        boolean gltf = path.endsWith(".gltf") || path.endsWith(".glb");
        if (!gltf && !path.endsWith(".pmx") && !path.endsWith(".pmd") && !path.endsWith(".zip")) return null;
        if (resources.getResource(model).isEmpty()) return null;
        Identifier config = request.configResource();
        if (config == null && gltf) config = GltfModelManifest.load(resources, model).ragdollConfig();
        if (config == null) throw new IOException("Model manifest does not declare a ragdoll config: " + model);

        String entry = request.modelName().isEmpty() ? null : request.modelName();
        RenderPearlModelSource source = gltf ? KsgGltfLoader.open(resources, model)
                : KsgPmxLoader.open(resources, model, entry);
        RenderedRagdoll rendered = new RenderedRagdoll(source, level, request.worldOrigin());
        return new MinecraftRagdollDeployments.ModelLease(config, source.instance(),
                rendered::mount, rendered::live, rendered::close);
    }

    private static final class RenderedRagdoll {
        private final RenderPearlModelSource source;
        private final ClientLevel level;
        private final Vector3d origin;
        private RenderPearlWorldModels.Mount mount;

        private RenderedRagdoll(RenderPearlModelSource source, ClientLevel level, Vector3d origin) {
            this.source = source;
            this.level = level;
            this.origin = origin;
        }

        private void mount() {
            mount = source.mount(level, origin.x, origin.y, origin.z);
        }

        private boolean live() {
            return mount != null && !source.instance().isClosed();
        }

        private void close() {
            try {
                if (mount != null) mount.close();
            } finally {
                source.close();
            }
        }
    }
}
