package lib.kasuga.rendering.models.mc.dynamic.physics;

import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.mc.typo.KsgGltfLoader;
import lib.kasuga.rendering.models.mc.typo.KsgPmxLoader;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.function.Supplier;

final class RagdollModelPlatform {
    private RagdollModelPlatform() {}

    static @Nullable MinecraftRagdollDeployments.ModelLease open(MinecraftRagdollDeployments.Request request,
            ResourceManager resources, Supplier<? extends Level> level) throws IOException {
        ModelPipeLine<ResourceLocation, ResourceLocation> pipeline = PipelineRegistry.resolve(request.modelResource());
        if (pipeline == null) return null;
        ResourceLocation model = resolveModel(request, pipeline);
        if (model == null || !pipeline.hasModel(model)) return null;

        ResourceLocation config = request.configResource();
        if (config == null && pipeline == PipelineRegistry.gltf()) {
            KsgGltfLoader loader = PipelineRegistry.gltfLoader();
            if (loader != null) config = loader.manifest(model).map(manifest -> manifest.ragdollConfig()).orElse(null);
        }
        if (config == null) throw new IOException("Model manifest does not declare a ragdoll config: " + model);

        ResourceLocation instanceId = request.instanceId();
        if (pipeline.hasInstance(model, instanceId)) {
            throw new IllegalStateException("pipeline instance id is already in use: " + instanceId);
        }
        ModelInstance instance = pipeline.createInstance(model, instanceId, null, null, null);
        if (instance == null) return null;
        return new MinecraftRagdollDeployments.ModelLease(config, instance,
                () -> pipeline.addToRenderer(model, instanceId, "mc_bridge", "mc_backend"),
                () -> pipeline.getInstance(model, instanceId) == instance,
                () -> {
                    if (pipeline.getInstance(model, instanceId) != instance || !pipeline.removeInstance(model, instanceId)) {
                        instance.close();
                    }
                });
    }

    private static @Nullable ResourceLocation resolveModel(MinecraftRagdollDeployments.Request request,
                                                            ModelPipeLine<ResourceLocation, ResourceLocation> pipeline) {
        if (pipeline != PipelineRegistry.pmx()) return request.modelResource();
        KsgPmxLoader loader = PipelineRegistry.pmxLoader();
        return loader == null ? null : loader.getLocByFileAndName(request.modelResource(), request.modelName());
    }
}
