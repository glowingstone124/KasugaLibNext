package lib.kasuga.registration.minecraft.common;

import com.mojang.datafixers.types.Type;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class RegistrationPlatform {
    private RegistrationPlatform() {}

    public static BlockBehaviour.Properties blockProperties(ResourceLocation id, BlockBehaviour.Properties properties) {
        return properties;
    }

    public static Item.Properties itemProperties(ResourceLocation id, Item.Properties properties) {
        return properties;
    }

    public static <T extends Entity> EntityType<T> entityType(ResourceLocation id, EntityType.Builder<T> builder) {
        return builder.build(id.toString());
    }

    public static <T extends BlockEntity> BlockEntityType<T> blockEntityType(BlockEntityType.BlockEntitySupplier<T> supplier,
                                                                           Block[] blocks, Type<?> dataType) {
        return BlockEntityType.Builder.of(supplier, blocks).build(dataType);
    }
}
