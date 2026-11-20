package lib.kasuga.rendering.models.mc.scene;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/** NBT defaults shared by the blueprint decoders on both Minecraft targets. */
final class SchematicTags {
    private SchematicTags() {}

    static CompoundTag compound(CompoundTag tag, String key) {
        return tag.get(key) instanceof CompoundTag value ? value : new CompoundTag();
    }

    static CompoundTag requireCompound(Tag tag, String name) {
        if (tag instanceof CompoundTag value) return value;
        throw new IllegalArgumentException("Expected compound tag for " + name);
    }

    static ListTag list(CompoundTag tag, String key) {
        return tag.get(key) instanceof ListTag value ? value : new ListTag();
    }

    static ListTag requireList(Tag tag, String name) {
        if (tag instanceof ListTag value) return value;
        throw new IllegalArgumentException("Expected list tag for " + name);
    }

    static int integer(@Nullable Tag tag) {
        return tag instanceof NumericTag value ? SchematicPlatform.integer(value) : 0;
    }

    static String string(CompoundTag tag, String key) {
        return tag.get(key) instanceof StringTag value ? SchematicPlatform.string(value) : "";
    }

    static byte[] bytes(CompoundTag tag, String key) {
        return tag.get(key) instanceof ByteArrayTag value ? value.getAsByteArray() : new byte[0];
    }

    static int[] intArray(CompoundTag tag, String key) {
        return tag.get(key) instanceof IntArrayTag value ? value.getAsIntArray() : new int[0];
    }

    static long[] longs(CompoundTag tag, String key) {
        return tag.get(key) instanceof LongArrayTag value ? value.getAsLongArray() : new long[0];
    }
}
