package lib.kasuga.registration.beans.rendering;

import lib.kasuga.content.document.DocumentComponentType;
import lib.kasuga.registration.kasuga.document.DocumentComponentRendererSupplier;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityRendererBuilder;
import lib.kasuga.registration.minecraft.entity.EntityRendererBuilder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.function.Supplier;

public interface RenderingRegistry {
    <T extends BlockEntity> void registerBlockEntityRenderer(BlockEntityType<T> type,
                                                            Supplier<BlockEntityRendererBuilder<T>> provider);

    <T extends Entity> void registerEntityRenderer(EntityType<T> type, Supplier<EntityRendererBuilder<T>> provider);

    <T> void registerDocumentComponentRenderer(DocumentComponentType<T> type,
                                               Supplier<DocumentComponentRendererSupplier<T>> provider);
}
