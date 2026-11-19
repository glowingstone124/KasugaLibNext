package lib.kasuga.registration.minecraft.common;

import com.mojang.datafixers.types.Type;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class RegistrationPlatform {
    private RegistrationPlatform() {}

    public static BlockBehaviour.Properties blockProperties(Identifier id, BlockBehaviour.Properties properties) {
        return properties.setId(ResourceKey.create(Registries.BLOCK, id));
    }

    public static Item.Properties itemProperties(Identifier id, Item.Properties properties) {
        return properties.setId(ResourceKey.create(Registries.ITEM, id));
    }

    public static <T extends Entity> EntityType<T> entityType(Identifier id, EntityType.Builder<T> builder) {
        return builder.build(ResourceKey.create(Registries.ENTITY_TYPE, id));
    }

    public static <T extends BlockEntity> BlockEntityType<T> blockEntityType(BlockEntityType.BlockEntitySupplier<T> supplier,
                                                                           Block[] blocks, Type<?> dataType) {
        return new BlockEntityType<>(supplier, blocks);
    }
}
