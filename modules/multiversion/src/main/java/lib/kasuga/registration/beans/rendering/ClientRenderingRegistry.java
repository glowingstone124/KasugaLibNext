package lib.kasuga.registration.beans.rendering;

import io.micronaut.context.annotation.Replaces;
import jakarta.inject.Singleton;
import lib.kasuga.content.document.DocumentComponentType;
import lib.kasuga.content.document.DocumentItemRenderer;
import lib.kasuga.inject.auto_configure.Configurable;
import lib.kasuga.inject.class_loader.BeanOnlyIn;
import lib.kasuga.registration.kasuga.document.DocumentComponentRendererSupplier;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityRendererBuilder;
import lib.kasuga.registration.minecraft.entity.EntityRendererBuilder;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.function.Supplier;

@Singleton
@Replaces(DefaultRenderingRegistry.class)
@BeanOnlyIn.Client
public final class ClientRenderingRegistry implements RenderingRegistry, Configurable {
    private ModConfigSpec.BooleanValue ignoreBlockEntityRenderer;

    @Override public void configureClient(ModConfigSpec.Builder builder) {
        ignoreBlockEntityRenderer = builder.comment("Disable the registration of block entity renderers.")
                .define("ignore_block_entity_renderer", false);
    }

    @Override public <T extends BlockEntity> void registerBlockEntityRenderer(BlockEntityType<T> type,
                                                                             Supplier<BlockEntityRendererBuilder<T>> provider) {
        if (ignoreBlockEntityRenderer != null && ignoreBlockEntityRenderer.getAsBoolean()) return;
        BlockEntityRendererBuilder<T> builder = provider.get();
        BlockEntityRenderers.register(type, context -> renderStateRenderer(builder.build(context)));
    }

    @SuppressWarnings("unchecked")
    private static <T extends BlockEntity, S extends BlockEntityRenderState> BlockEntityRenderer<T, S>
            renderStateRenderer(BlockEntityRenderer<T, ?> renderer) {
        // Each provider produces one renderer that both extracts and consumes its own render state.
        return (BlockEntityRenderer<T, S>) renderer;
    }

    @Override public <T extends Entity> void registerEntityRenderer(EntityType<T> type,
                                                                   Supplier<EntityRendererBuilder<T>> provider) {
        EntityRenderers.register(type, provider.get()::build);
    }

    @Override public <T> void registerDocumentComponentRenderer(DocumentComponentType<T> type,
                                                                 Supplier<DocumentComponentRendererSupplier<T>> provider) {
        DocumentItemRenderer.registerComponentRenderer(type, provider.get().get());
    }
}
