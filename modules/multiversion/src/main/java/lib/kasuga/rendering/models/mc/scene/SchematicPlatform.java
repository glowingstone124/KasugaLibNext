package lib.kasuga.rendering.models.mc.scene;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

final class SchematicPlatform {
    private SchematicPlatform() {}

    static int dataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    static int integer(NumericTag value) { return value.intValue(); }
    static String string(StringTag value) { return value.value(); }
    static Set<String> keys(CompoundTag value) { return value.keySet(); }

    static BlockState readState(HolderGetter<Block> blocks, CompoundTag state) {
        // External palette formats may keep the legacy tag names even with a current DataVersion.
        if (!state.contains("id") && state.contains("Name")) {
            CompoundTag modern = new CompoundTag();
            modern.putString("id", state.getStringOr("Name", ""));
            state.getCompound("Properties").ifPresent(properties -> modern.put("properties", properties));
            state = modern;
        }
        String name = state.getStringOr("id", "");
        Identifier id = Identifier.tryParse(name);
        if (id == null || blocks.get(ResourceKey.create(Registries.BLOCK, id)).isEmpty()) {
            throw new IllegalArgumentException("Schematic requires an unavailable block: " + name);
        }
        return NbtUtils.readBlockState(blocks, state);
    }
}
