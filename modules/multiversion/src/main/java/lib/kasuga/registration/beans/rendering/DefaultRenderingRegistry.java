package lib.kasuga.registration.beans.rendering;

import jakarta.inject.Singleton;
import lib.kasuga.content.document.DocumentComponentType;
import lib.kasuga.registration.kasuga.document.DocumentComponentRendererSupplier;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityRendererBuilder;
import lib.kasuga.registration.minecraft.entity.EntityRendererBuilder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.function.Supplier;

/** Physical servers never resolve client renderer providers. */
@Singleton
public final class DefaultRenderingRegistry implements RenderingRegistry {
    @Override public <T extends BlockEntity> void registerBlockEntityRenderer(BlockEntityType<T> type,
                                                                             Supplier<BlockEntityRendererBuilder<T>> provider) {}

    @Override public <T extends Entity> void registerEntityRenderer(EntityType<T> type,
                                                                   Supplier<EntityRendererBuilder<T>> provider) {}

    @Override public <T> void registerDocumentComponentRenderer(DocumentComponentType<T> type,
                                                                 Supplier<DocumentComponentRendererSupplier<T>> provider) {}
}
