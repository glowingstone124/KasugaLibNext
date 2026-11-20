package lib.kasuga.rendering.models.mc.scene;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

final class SchematicPlatform {
    private SchematicPlatform() {}

    static int dataVersion() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    static int integer(NumericTag value) { return value.getAsInt(); }
    static String string(StringTag value) { return value.getAsString(); }
    static Set<String> keys(CompoundTag value) { return value.getAllKeys(); }

    static BlockState readState(HolderGetter<Block> blocks, CompoundTag state) {
        String name = state.getString("Name");
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null || blocks.get(ResourceKey.create(Registries.BLOCK, id)).isEmpty()) {
            throw new IllegalArgumentException("Schematic requires an unavailable block: " + name);
        }
        return NbtUtils.readBlockState(blocks, state);
    }
}
