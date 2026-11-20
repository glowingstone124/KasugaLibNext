package lib.kasuga.rendering.models.mc.scene;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** A frozen block snapshot rendered with Minecraft's baked block and fluid geometry. */
public class BlockModelSource extends LayeredModelSource {
    private final Map<BlockPos, BlockState> states = new HashMap<>();
    private final Map<BlockPos, CompoundTag> blockEntityData = new HashMap<>();
    private final BlockPos origin;
    private final Optional<BlockDimensions> dimensions;
    private final Biome biome;
    private final View fullView = new View(null);
    private final Slice assembled;
    private final Map<Slice, BlockPreviewGeometry> cachedGeometry = new IdentityHashMap<>();
    private final Map<LayerLayout.Axis, Map<Integer, Map<Identifier, Integer>>> statistics =
            new EnumMap<>(LayerLayout.Axis.class);
    private @Nullable LayerLayout.Axis cachedAxis;
    private long bakedGeneration = -1;
    private long geometryBuilds;

    public BlockModelSource(Level host, List<StructureBlockInfo> blocks) {
        this(host, blocks, BlockPos.ZERO);
    }

    public BlockModelSource(Level host, List<StructureBlockInfo> blocks, BlockPos origin) {
        this.origin = origin.immutable();
        if (blocks.size() > MinecraftModelSources.MAX_BLOCKS) throw new IllegalArgumentException("Too many structure blocks");
        for (StructureBlockInfo info : blocks) {
            if (info.state().isAir()) continue;
            BlockPos position = info.pos().immutable();
            if (states.put(position, info.state()) != null) throw new IllegalArgumentException("Duplicate structure position " + position);
            if (info.nbt() != null) blockEntityData.put(position, info.nbt().copy());
        }
        dimensions = BlockDimensions.fromBlocks(states.keySet());
        if (dimensions.isPresent() && dimensions.get().longestAxis() > MinecraftModelSources.MAX_SPAN) {
            throw new IllegalArgumentException("Structure span exceeds limit");
        }
        List<Element> elements = new ArrayList<>(states.size());
        for (BlockPos position : states.keySet()) {
            int x = Math.subtractExact(position.getX(), origin.getX());
            int y = Math.subtractExact(position.getY(), origin.getY());
            int z = Math.subtractExact(position.getZ(), origin.getZ());
            elements.add(new Element(x, y, z, new ModelBounds(x, y, z, x + 1.0, y + 1.0, z + 1.0)));
        }
        assembled = new Slice(LayerLayout.Axis.Y, 0, elements, false);
        biome = host.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value();
    }

    public Optional<BlockDimensions> dimensions() { return dimensions; }
    public long geometryBuilds() { return geometryBuilds; }

    /** A copy of the imported metadata at the original block position. */
    public @Nullable CompoundTag blockEntityData(BlockPos position) {
        CompoundTag data = blockEntityData.get(position);
        return data == null ? null : data.copy();
    }

    @Override protected List<Element> elements() { return assembled.elements(); }
    @Override protected Object geometryKey() { return assembled; }

    @Override public Map<Integer, Map<Identifier, Integer>> blockStatistics(LayerLayout.Axis axis) {
        if (closed) return Map.of();
        return statistics.computeIfAbsent(axis, this::countBlocks);
    }

    private Map<Integer, Map<Identifier, Integer>> countBlocks(LayerLayout.Axis axis) {
        Map<Integer, Map<Identifier, Integer>> counts = new TreeMap<>();
        for (Element element : elements()) {
            int layer = axis.coordinate(element.x(), element.y(), element.z());
            Identifier id = BuiltInRegistries.BLOCK.getKey(states.get(position(element)).getBlock());
            counts.computeIfAbsent(layer, key -> new TreeMap<>()).merge(id, 1, Integer::sum);
        }
        counts.replaceAll((layer, blocks) -> Collections.unmodifiableMap(blocks));
        return Collections.unmodifiableMap(counts);
    }

    @Override protected void renderAssembled(Context context) { renderSlice(assembled, context); }

    @Override protected void renderSlice(Slice slice, Context context) {
        if (closed) return;
        long generation = ModelPreviewResources.generation();
        if (bakedGeneration != generation || slice.isolated() && cachedAxis != null && cachedAxis != slice.axis()) {
            cachedGeometry.clear();
            cachedAxis = null;
            bakedGeneration = generation;
        }
        if (slice.isolated()) cachedAxis = slice.axis();
        BlockPreviewGeometry geometry = cachedGeometry.get(slice);
        if (geometry == null) {
            View view = slice.isolated() ? new View(slice) : fullView;
            geometry = BlockPreviewGeometry.build(Minecraft.getInstance(), view, fullView, origin, slice.elements());
            cachedGeometry.put(slice, geometry);
            geometryBuilds++;
        }
        geometry.submit(context);
    }

    @Override protected Optional<BlockHit> pickSlice(Slice slice, Vec3 from, Vec3 to) {
        Vec3 base = Vec3.atLowerCornerOf(origin);
        Vec3 start = from.add(base);
        Vec3 end = to.add(base);
        BlockHit nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Element element : slice.elements()) {
            BlockPos pos = position(element);
            BlockState state = states.get(pos);
            VoxelShape shape = state.getShape(fullView, pos);
            if (shape.isEmpty()) shape = Shapes.block();
            BlockHitResult hit = shape.clip(start, end, pos);
            if (hit == null) continue;
            double next = hit.getLocation().distanceToSqr(start);
            if (next < distance) {
                distance = next;
                nearest = new BlockHit(pos, state, hit.getLocation().subtract(base), hit.getDirection());
            }
        }
        return Optional.ofNullable(nearest);
    }

    private BlockPos position(Element element) {
        return origin.offset(element.x(), element.y(), element.z());
    }

    @Override public void close() {
        super.close();
        cachedGeometry.clear();
        blockEntityData.clear();
        states.clear();
        statistics.clear();
    }

    private final class View implements BlockAndTintGetter {
        private final @Nullable Slice slice;

        private View(@Nullable Slice slice) { this.slice = slice; }

        @Override public BlockState getBlockState(BlockPos pos) {
            if (slice != null && slice.axis().coordinate(pos.getX() - origin.getX(), pos.getY() - origin.getY(),
                    pos.getZ() - origin.getZ()) != slice.coordinate()) return Blocks.AIR.defaultBlockState();
            return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getHeight() { return MinecraftModelSources.MAX_SPAN; }
        @Override public int getMinY() { return origin.getY(); }
        @Override public LevelLightEngine getLightEngine() { return LevelLightEngine.EMPTY; }
        @Override public int getBrightness(LightLayer layer, BlockPos pos) { return 0; }
        @Override public int getRawBrightness(BlockPos pos, int darkening) { return 0; }
        @Override public CardinalLighting cardinalLighting() { return CardinalLighting.DEFAULT; }
        @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return resolver.getColor(biome, pos.getX(), pos.getZ());
        }
    }
}
