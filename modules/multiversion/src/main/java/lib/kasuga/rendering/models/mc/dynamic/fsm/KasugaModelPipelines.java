package lib.kasuga.rendering.models.mc.dynamic.fsm;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.core.resource.ClientResourceReloadFinishEvent;
import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlModelSource;
import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlWorldModels;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Binds the FSM host's model to the active client level's RenderPearl renderer. */
public final class KasugaModelPipelines {
    private KasugaModelPipelines() {}

    public static @Nullable ModelInstance createAndBind(Identifier modelLoc, Identifier instanceLoc,
                                                       @Nullable String modelName, Transform rootTransform) {
        return Client.bind(modelLoc, instanceLoc, rootTransform);
    }

    public static boolean isRendering(Identifier modelLoc, @Nullable String modelName, Identifier instanceLoc) {
        return Client.isRendering(modelLoc, instanceLoc);
    }

    public static void remove(Identifier modelLoc, @Nullable String modelName, Identifier instanceLoc) {
        if (Client.isRendering(modelLoc, instanceLoc)) Client.remove(instanceLoc);
    }

    @EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
    public static final class Client {
        private static final Logger LOGGER = LogUtils.getLogger();
        private static final Map<Identifier, Binding> MODELS = new HashMap<>();
        private static final Set<Identifier> FAILED_MODELS = new HashSet<>();

        private Client() {}

        private static @Nullable ModelInstance bind(Identifier model, Identifier instance, Transform placement) {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null || FAILED_MODELS.contains(model)) return null;
            if (isRendering(model, instance)) return MODELS.get(instance).source().instance();
            remove(instance);
            RenderPearlModelSource source = null;
            try {
                source = RenderPearlModelSource.open(model);
                RenderPearlWorldModels.Mount mount = source.mount(level, placement.transform());
                MODELS.put(instance, new Binding(model, level, source, mount));
                return source.instance();
            } catch (IOException | RuntimeException failure) {
                if (source != null) source.close();
                FAILED_MODELS.add(model);
                LOGGER.warn("Could not open animated model {}", model, failure);
                return null;
            }
        }

        private static boolean isRendering(Identifier model, Identifier instance) {
            Binding binding = MODELS.get(instance);
            return binding != null && binding.model().equals(model)
                    && binding.level() == Minecraft.getInstance().level && !binding.source().isClosed();
        }

        private static void remove(Identifier instance) {
            Binding binding = MODELS.remove(instance);
            if (binding != null) binding.close();
        }

        @SubscribeEvent
        public static void onReload(ClientResourceReloadFinishEvent event) {
            for (Binding binding : MODELS.values()) binding.close();
            MODELS.clear();
            FAILED_MODELS.clear();
        }

        @SubscribeEvent
        public static void onUnload(LevelEvent.Unload event) {
            MODELS.values().removeIf(binding -> {
                if (binding.level() != event.getLevel()) return false;
                binding.close();
                return true;
            });
        }

        private record Binding(Identifier model, ClientLevel level, RenderPearlModelSource source,
                               RenderPearlWorldModels.Mount mount) {
            private void close() {
                try {
                    mount.close();
                } finally {
                    source.close();
                }
            }
        }
    }
}
