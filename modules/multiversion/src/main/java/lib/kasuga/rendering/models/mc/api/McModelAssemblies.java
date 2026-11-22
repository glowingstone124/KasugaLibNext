package lib.kasuga.rendering.models.mc.api;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.core.resource.ClientResourceReloadFinishEvent;
import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlModelSource;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.models.mc.scene.display.WorldDisplayTarget;
import lib.kasuga.rendering.models.mc.source.model.assembly.AssemblyResourceDefinitions;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPoseBinding;
import lib.kasuga.rendering.models.uml.dynamic.RebindablePoseDriver;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.loaders.assembly.ModelAssembly;
import lib.kasuga.rendering.models.uml.loaders.assembly.ModelAssemblyCache;
import lib.kasuga.rendering.models.uml.loaders.assembly.ModelAssemblyDefinition;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/** Loads resource-pack outfits as movable, reloadable RenderPearl scene nodes. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class McModelAssemblies {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<Outfit> OPEN = new LinkedHashSet<>();
    private static Generation current = new Generation(Map.of());
    private static Map<Identifier, ModelAssemblyDefinition<Reference>> prepared = Map.of();

    private McModelAssemblies() {}

    public record Reference(Identifier model, @Nullable String modelName) {
        public Reference { Objects.requireNonNull(model, "model"); }
        public Reference(Identifier model) { this(model, null); }
    }

    public static Map<Identifier, ModelAssemblyDefinition<Reference>> definitions() { return current.definitions; }

    /** Opens an outfit with an independent pose. A scene can take ownership of the returned node. */
    public static Outfit open(Identifier id) throws IOException {
        RenderSystem.assertOnRenderThread();
        Outfit outfit = new Outfit(id, current, current.open(id));
        OPEN.add(outfit);
        return outfit;
    }

    @SubscribeEvent
    public static void registerReloadListener(AddClientReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath(KasugaLib.MODID, "model_assemblies"),
                new SimplePreparableReloadListener<Map<Identifier, ModelAssemblyDefinition<Reference>>>() {
                    @Override
                    protected Map<Identifier, ModelAssemblyDefinition<Reference>> prepare(ResourceManager manager,
                                                                                            ProfilerFiller profiler) {
                        return AssemblyResourceDefinitions.load(manager);
                    }

                    @Override
                    protected void apply(Map<Identifier, ModelAssemblyDefinition<Reference>> definitions,
                                         ResourceManager manager, ProfilerFiller profiler) {
                        prepared = definitions;
                    }
                });
    }

    @SubscribeEvent
    public static void reload(ClientResourceReloadFinishEvent event) {
        Generation previous = current;
        current = new Generation(prepared);
        for (Outfit outfit : List.copyOf(OPEN)) {
            try {
                outfit.switchAssembly(outfit.id());
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("Could not refresh model assembly {}", outfit.id(), failure);
            }
        }
        previous.closeIfUnused();
    }

    @SubscribeEvent
    public static void shutdown(GameShuttingDownEvent event) {
        for (Outfit outfit : List.copyOf(OPEN)) outfit.close();
        Generation previous = current;
        current = new Generation(Map.of());
        previous.closeIfUnused();
        prepared = Map.of();
    }

    private static final class Generation {
        private final Map<Identifier, ModelAssemblyDefinition<Reference>> definitions;
        private final Map<Reference, RenderPearlModelSource> parts = new HashMap<>();
        private final ModelAssemblyCache cache = new ModelAssemblyCache();
        private int outfits;

        private Generation(Map<Identifier, ModelAssemblyDefinition<Reference>> definitions) {
            this.definitions = definitions;
        }

        private RenderPearlModelSource open(Identifier id) throws IOException {
            ModelAssemblyDefinition<Reference> definition = definitions.get(id);
            if (definition == null) throw new FileNotFoundException("Unknown model assembly " + id);
            Map<Texture, Identifier> textures = new IdentityHashMap<>();
            ResourceManager resources = Minecraft.getInstance().getResourceManager();
            for (ModelAssemblyDefinition.Part<Reference> part : definition.parts()) {
                Reference reference = part.source();
                RenderPearlModelSource source = parts.get(reference);
                if (source == null) {
                    if (resources.getResource(reference.model()).isEmpty()) {
                        throw new FileNotFoundException("Missing assembly part " + reference.model());
                    }
                    source = RenderPearlModelSource.open(reference.model(), reference.modelName());
                    parts.put(reference, source);
                }
                for (Texture texture : source.instance().getModel().getMaterialSet().getTextures()) {
                    textures.put(texture, source.textureId(texture));
                }
            }
            ModelAssembly.Request request = definition.resolve(reference ->
                    new ModelAssembly.Source(parts.get(reference).instance().getModel(), 0));
            Model model = cache.getOrAssemble(request).model();
            ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
            try {
                RenderPearlModelSource result = RenderPearlModelSource.own(instance, textures::get, this::release);
                outfits++;
                return result;
            } catch (RuntimeException | Error failure) {
                try { instance.close(); }
                catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        private void release() {
            outfits--;
            closeIfUnused();
        }

        private void closeIfUnused() {
            if (current == this || outfits != 0) return;
            cache.clear();
            for (RenderPearlModelSource source : parts.values()) source.close();
            parts.clear();
        }
    }

    public static final class Outfit extends WorldDisplayTarget {
        private Identifier id;
        private Generation generation;
        private RenderPearlModelSource source;
        private @Nullable BiConsumer<ModelInstance, ModelInstance> onRebind;

        private Outfit(Identifier id, Generation generation, RenderPearlModelSource source) {
            this.id = id;
            this.generation = generation;
            this.source = source;
            source.claim(this);
        }

        public Identifier id() { return id; }

        public ModelInstance instance() { requireOpen(); return source.instance(); }

        public Outfit onInstanceChanged(@Nullable BiConsumer<ModelInstance, ModelInstance> callback) {
            requireOpen();
            onRebind = callback;
            return this;
        }

        /** Keeps the current outfit when the replacement definition or one of its parts is unavailable. */
        public boolean switchAssembly(Identifier nextId) throws IOException {
            RenderSystem.assertOnRenderThread();
            requireOpen();
            if (generation == current && id.equals(nextId)) return true;
            Generation next = current;
            RenderPearlModelSource replacement;
            try {
                replacement = next.open(nextId);
            } catch (FileNotFoundException missing) {
                return false;
            }
            try {
                replacement.claim(this);
                restore(source.instance(), replacement.instance());
            } catch (RuntimeException | Error failure) {
                try { replacement.close(); }
                catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            RenderPearlModelSource previous = source;
            source = replacement;
            generation = next;
            id = nextId;
            previous.close();
            return true;
        }

        private void restore(ModelInstance previous, ModelInstance fresh) {
            SkeletonInstance oldSkeleton = previous.getSkeletonInstance();
            SkeletonInstance newSkeleton = fresh.getSkeletonInstance();
            if (oldSkeleton.isFloatingOriginEnabled()) {
                newSkeleton.enableFloatingOrigin(oldSkeleton.getWorldOrigin());
                newSkeleton.transformRoot(oldSkeleton.getTransform().copy());
            } else {
                newSkeleton.transformRootWorld(oldSkeleton.getTransform().copy());
            }
            oldSkeleton.copyPoseInputsTo(newSkeleton);
            previous.getMorph().copyInputsTo(fresh.getMorph());
            fresh.setAmbientLightEnhancement(previous.getAmbientLightEnhancement());
            fresh.setDepthBias(previous.getDepthBias());
            if (onRebind != null) onRebind.accept(previous, fresh);
            if (fresh.getPoseDriver() == null && previous.getPoseDriver() instanceof ModelPoseBinding binding
                    && binding.source() instanceof RebindablePoseDriver) {
                binding.rebind(fresh);
            }
        }

        @Override protected Snapshot contentSnapshot(LayerLayout layout, float partialTick) {
            return source.snapshot(layout, partialTick);
        }

        @Override protected void tickChildren(long tick, double deltaSeconds) {
            source.tick(tick, deltaSeconds);
        }

        @Override protected void closeContent() {
            OPEN.remove(this);
            onRebind = null;
            source.close();
        }
    }
}
