package lib.kasuga.rendering.models.mc.scene;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

import static lib.kasuga.rendering.models.mc.scene.SchematicTags.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, read-only import of vanilla structure NBT and Litematica/Forgematica .litematic v5-v7. */
public final class SchematicModelData {
    public static final long MAX_NBT_BYTES = 64L * 1024 * 1024;
    private SchematicModelData() {}

    /** File type is explicit: never guess that a foreign NBT format is a vanilla structure. */
    public static List<StructureBlockInfo> readStructure(Path file, HolderGetter<Block> blocks) throws IOException {
        return structure(NbtIo.readCompressed(file, NbtAccounter.create(MAX_NBT_BYTES)), blocks, 0);
    }

    public static List<StructureBlockInfo> readLitematic(Path file, HolderGetter<Block> blocks) throws IOException {
        return litematic(NbtIo.readCompressed(file, NbtAccounter.create(MAX_NBT_BYTES)), blocks);
    }

    public static List<StructureBlockInfo> structure(CompoundTag input, HolderGetter<Block> blocks, int paletteIndex) {
        if (!(input.get("blocks") instanceof ListTag) || !(input.get("size") instanceof ListTag))
            throw new IllegalArgumentException("Not a vanilla structure: missing blocks/size");
        CompoundTag data = fix(input);
        ListTag palette;
        if (data.get("palettes") instanceof ListTag) {
            ListTag palettes = list(data, "palettes");
            if (paletteIndex < 0 || paletteIndex >= palettes.size()) throw new IllegalArgumentException("Invalid structure palette");
            palette = requireList(palettes.get(paletteIndex), "structure palette");
        } else {
            if (paletteIndex != 0 || !(data.get("palette") instanceof ListTag)) throw new IllegalArgumentException("Missing structure palette");
            palette = list(data, "palette");
        }
        List<BlockState> states = new ArrayList<>(palette.size());
        for (Tag tag : palette) {
            CompoundTag state = requireCompound(tag, "palette state");
            states.add(SchematicPlatform.readState(blocks, state));
        }
        ListTag entries = list(data, "blocks");
        if (entries.size() > MinecraftModelSources.MAX_BLOCKS) throw new IllegalArgumentException("Structure exceeds block limit");
        List<StructureBlockInfo> result = new ArrayList<>(entries.size());
        for (Tag entry : entries) {
            CompoundTag tag = requireCompound(entry, "structure block");
            ListTag pos = list(tag, "pos");
            if (pos.size() != 3 || !(tag.get("state") instanceof NumericTag)) throw new IllegalArgumentException("Invalid structure block");
            int state = integer(tag.get("state"));
            if (state < 0 || state >= states.size()) throw new IllegalArgumentException("Invalid block palette index " + state);
            result.add(new StructureBlockInfo(new BlockPos(integer(pos.get(0)), integer(pos.get(1)), integer(pos.get(2))), states.get(state),
                    tag.get("nbt") instanceof CompoundTag nbt ? nbt.copy() : null));
        }
        return List.copyOf(result);
    }

    public static List<StructureBlockInfo> structure(StructureTemplate template, HolderGetter<Block> blocks) {
        return structure(template.save(new CompoundTag()), blocks, 0);
    }

    public static List<StructureBlockInfo> litematic(CompoundTag root, HolderGetter<Block> blocks) {
        int version = integer(root.get("Version"));
        if (version < 5 || version > 7 || !(root.get("Regions") instanceof CompoundTag))
            throw new IllegalArgumentException("Expected Litematica/Forgematica v5-v7 with Regions");
        int dataVersion = integer(root.get("MinecraftDataVersion"));
        if (dataVersion <= 0) throw new IllegalArgumentException("Litematic has no MinecraftDataVersion");
        Map<BlockPos, StructureBlockInfo> result = new LinkedHashMap<>();
        CompoundTag regions = compound(root, "Regions");
        if (SchematicPlatform.keys(regions).size() > 256) throw new IllegalArgumentException("Too many schematic regions");
        long totalVolume = 0;
        // Stable order makes overlapping subregion precedence repeatable.
        for (String name : SchematicPlatform.keys(regions).stream().sorted().toList()) {
            CompoundTag region = compound(regions, name);
            BlockPos size = vector(compound(region, "Size"));
            int sx = dimension(size.getX()), sy = dimension(size.getY()), sz = dimension(size.getZ());
            totalVolume += (long) sx * sy * sz;
            if (totalVolume > MinecraftModelSources.MAX_BLOCKS) throw new IllegalArgumentException("Litematic volume exceeds block limit");
            BlockPos start = vector(compound(region, "Position"));
            BlockPos minimum = start.offset(negativeOffset(size.getX()), negativeOffset(size.getY()), negativeOffset(size.getZ()));
            ListTag palette = list(region, "BlockStatePalette");
            if (palette.isEmpty() || palette.size() > MinecraftModelSources.MAX_BLOCKS) throw new IllegalArgumentException("Invalid litematic palette");
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            long[] packed = longs(region, "BlockStates");
            if (packed.length != ((long) sx * sy * sz * bits + 63) / 64) throw new IllegalArgumentException("Invalid litematic BlockStates length");
            Map<BlockPos, CompoundTag> entities = new LinkedHashMap<>();
            for (Tag entry : list(region, "TileEntities")) {
                CompoundTag entity = requireCompound(entry, "block entity");
                entities.put(vector(entity), entity);
            }
            CompoundTag vanilla = new CompoundTag();
            vanilla.putInt("DataVersion", dataVersion);
            vanilla.put("palette", palette.copy());
            vanilla.put("size", integers(sx, sy, sz));
            ListTag entries = new ListTag();
            for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
                int state = unpack(packed, ((long) y * sz + z) * sx + x, bits);
                if (state >= palette.size()) throw new IllegalArgumentException("Litematic references missing palette state");
                CompoundTag entry = new CompoundTag();
                entry.put("pos", integers(x, y, z));
                entry.putInt("state", state);
                CompoundTag entity = entities.get(new BlockPos(x, y, z));
                if (entity != null) entry.put("nbt", entity.copy());
                entries.add(entry);
            }
            vanilla.put("blocks", entries);
            for (StructureBlockInfo info : structure(vanilla, blocks, 0)) {
                BlockPos position = info.pos().offset(minimum);
                result.put(position, new StructureBlockInfo(position, info.state(), info.nbt()));
            }
        }
        return List.copyOf(result.values());
    }

    /** Litematica packs continuously; entries can straddle two longs (unlike modern chunk storage). */
    static int unpack(long[] values, long index, int bits) {
        if (index < 0 || bits < 2 || bits > 31) throw new IllegalArgumentException("Invalid packed block index/bits");
        long bit = Math.multiplyExact(index, bits);
        int word = Math.toIntExact(bit >>> 6);
        int shift = (int) (bit & 63);
        if (word >= values.length || shift + bits > 64 && word + 1 >= values.length)
            throw new IllegalArgumentException("Truncated packed block states");
        long value = values[word] >>> shift;
        if (shift + bits > 64) value |= values[word + 1] << (64 - shift);
        return (int) (value & ((1L << bits) - 1));
    }

    static int negativeOffset(int signedSize) { return signedSize < 0 ? signedSize + 1 : 0; }
    private static int dimension(int value) {
        if (value == 0 || value < -MinecraftModelSources.MAX_SPAN || value > MinecraftModelSources.MAX_SPAN)
            throw new IllegalArgumentException("Invalid schematic dimension");
        return Math.abs(value);
    }
    private static BlockPos vector(CompoundTag tag) {
        if (!(tag.get("x") instanceof NumericTag) || !(tag.get("y") instanceof NumericTag) || !(tag.get("z") instanceof NumericTag))
            throw new IllegalArgumentException("Incomplete schematic position/size");
        return new BlockPos(integer(tag.get("x")), integer(tag.get("y")), integer(tag.get("z")));
    }
    private static ListTag integers(int x, int y, int z) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(x)); list.add(IntTag.valueOf(y)); list.add(IntTag.valueOf(z));
        return list;
    }
    private static CompoundTag fix(CompoundTag input) {
        int version = NbtUtils.getDataVersion(input, 500);
        if (version > SchematicPlatform.dataVersion())
            throw new IllegalArgumentException("Schematic comes from a newer Minecraft version");
        return DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(), input.copy(), version);
    }
}
