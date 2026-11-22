package lib.kasuga.rendering.models.mc.api.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import lib.kasuga.KasugaLib;
import lib.kasuga.core.resource.ClientResourceReloadFinishEvent;
import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlModelSource;
import lib.kasuga.rendering.models.mc.scene.ModelBounds;
import lib.kasuga.rendering.models.mc.typo.KsgPmxLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.NoDataSpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Submits authored models through the native item pipeline in every display context. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ItemModelRenderer implements NoDataSpecialModelRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Unbaked, Optional<RenderPearlModelSource>> SOURCES = new HashMap<>();
    private final Unbaked definition;

    private ItemModelRenderer(Unbaked definition) {
        this.definition = definition;
    }

    @SubscribeEvent
    public static void register(RegisterSpecialModelRendererEvent event) {
        event.register(Identifier.fromNamespaceAndPath(KasugaLib.MODID, "model"), Unbaked.CODEC);
    }

    @SubscribeEvent
    public static void reload(ClientResourceReloadFinishEvent event) { releaseSources(); }

    @SubscribeEvent
    public static void shutdown(GameShuttingDownEvent event) { releaseSources(); }

    private static void releaseSources() {
        for (Optional<RenderPearlModelSource> source : SOURCES.values()) {
            source.ifPresent(RenderPearlModelSource::close);
        }
        SOURCES.clear();
    }

    @Override
    public void submit(PoseStack pose, SubmitNodeCollector collector, int light, int overlay,
                       boolean foil, int outline) {
        RenderPearlModelSource source = source();
        if (source == null) return;
        float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        source.captureGeometry(partialTick, new Object(), light).submit(pose, collector);
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        RenderPearlModelSource source = source();
        if (source == null) return;
        ModelBounds bounds = source.bounds();
        for (int corner = 0; corner < 8; corner++) {
            output.accept(new Vector3f((float) ((corner & 1) == 0 ? bounds.minX() : bounds.maxX()),
                    (float) ((corner & 2) == 0 ? bounds.minY() : bounds.maxY()),
                    (float) ((corner & 4) == 0 ? bounds.minZ() : bounds.maxZ())));
        }
    }

    private RenderPearlModelSource source() {
        return SOURCES.computeIfAbsent(definition, Unbaked::open).orElse(null);
    }

    public record Unbaked(Identifier model, Optional<String> modelName) implements NoDataSpecialModelRenderer.Unbaked {
        public static final MapCodec<Unbaked> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Identifier.CODEC.fieldOf("model").forGetter(Unbaked::model),
                Codec.STRING.optionalFieldOf("model_name").forGetter(Unbaked::modelName)
        ).apply(instance, Unbaked::new));

        @Override
        public ItemModelRenderer bake(SpecialModelRenderer.BakingContext context) {
            return new ItemModelRenderer(this);
        }

        @Override
        public MapCodec<Unbaked> type() { return CODEC; }

        private Optional<RenderPearlModelSource> open() {
            try {
                if (modelName.isPresent()) return Optional.of(KsgPmxLoader.openArchive(model, modelName.get()));
                return Optional.of(RenderPearlModelSource.open(model));
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("Could not open item model {} {}", model, modelName.orElse(""), failure);
                return Optional.empty();
            }
        }
    }
}
