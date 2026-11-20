package lib.kasuga.rendering.models.mc.scene;

import com.mojang.logging.LogUtils;
import net.minecraft.client.ClientClockManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.crafting.RecipeAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import net.neoforged.neoforge.entity.PartEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Private block and block-entity view with no game ticks or world writes. */
public final class PreviewLevel extends Level {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Level host;
    private final Map<BlockPos, BlockState> blocks;
    private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
    private final Map<Long, LevelChunk> chunks = new HashMap<>();
    private final LevelEntityGetter<Entity> entities = new LevelEntityGetterAdapter<>(
            new EntityLookup<>(), new EntitySectionStorage<>(Entity.class, key -> Visibility.HIDDEN));
    private final TickRateManager ticks = new TickRateManager();
    private final ClockManager clocks = new ClientClockManager();
    private final Scoreboard scoreboard = new Scoreboard();
    private final WorldBorder border = new WorldBorder();
    private final EnvironmentAttributeSystem environment = EnvironmentAttributeSystem.builder().build();
    private final ChunkSource source;
    private final Holder<Biome> biome;
    private final int minimumY;
    private final int height;

    public PreviewLevel(Level host, Map<BlockPos, BlockState> states) {
        super(new ClientLevel.ClientLevelData(host.getDifficulty(), false, false), host.dimension(),
                host.registryAccess(), host.dimensionTypeRegistration(), true, false, 0, 0);
        if (states.size() > MinecraftModelSources.MAX_BLOCKS) throw new IllegalArgumentException("Too many preview blocks");
        this.host = host;
        this.blocks = new HashMap<>(states);
        int min = states.keySet().stream().mapToInt(BlockPos::getY).min().orElse(0);
        int max = states.keySet().stream().mapToInt(BlockPos::getY).max().orElse(0);
        if ((long) max - min >= MinecraftModelSources.MAX_SPAN) throw new IllegalArgumentException("Preview height exceeds limit");
        this.minimumY = Math.floorDiv(min, 16) * 16;
        this.height = (Math.floorDiv(max, 16) - Math.floorDiv(min, 16) + 1) * 16;
        this.biome = registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        this.source = new ChunkSource() {
            @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
                return PreviewLevel.this.getChunk(x, z);
            }
            @Override public void tick(BooleanSupplier haveTime, boolean tickChunks) {}
            @Override public String gatherStats() { return "preview"; }
            @Override public int getLoadedChunksCount() { return chunks.size(); }
            @Override public LevelLightEngine getLightEngine() { return LevelLightEngine.EMPTY; }
            @Override public PreviewLevel getLevel() { return PreviewLevel.this; }
        };
    }

    Map<BlockPos, BlockState> blockStates() { return Collections.unmodifiableMap(blocks); }

    void addPreviewBlockEntity(BlockEntity entity, @Nullable CompoundTag metadata) {
        BlockPos pos = entity.getBlockPos().immutable();
        entity.setLevel(this);
        blockEntities.put(pos, entity);
        if (metadata != null) {
            try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(entity.problemPath(), LOGGER)) {
                entity.loadWithComponents(TagValueInput.create(reporter, registryAccess(), metadata.copy()));
            }
        }
    }

    @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return blockEntities.get(pos); }
    @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) { return false; }
    @Override public void setBlockEntity(BlockEntity entity) {}
    @Override public void removeBlockEntity(BlockPos pos) {}
    @Override public boolean destroyBlock(BlockPos pos, boolean drops, Entity entity, int recursion) { return false; }
    @Override public boolean removeBlock(BlockPos pos, boolean moving) { return false; }
    @Override public void updateNeighborsAt(BlockPos pos, Block block) {}
    @Override public void blockEvent(BlockPos pos, Block block, int type, int data) {}
    @Override public int getBrightness(LightLayer layer, BlockPos pos) { return 15; }
    @Override public int getRawBrightness(BlockPos pos, int amount) { return 15; }
    @Override public int getMinY() { return minimumY; }
    @Override public int getHeight() { return height; }
    @Override public int getSeaLevel() { return host.getSeaLevel(); }
    @Override public Holder<Biome> getBiome(BlockPos pos) { return biome; }
    @Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) { return biome; }
    @Override public long getGameTime() { return host.getGameTime(); }
    @Override public ChunkSource getChunkSource() { return source; }

    @Override public LevelChunk getChunk(int x, int z) {
        long key = ChunkPos.pack(x, z);
        if (chunks.size() >= 256 && !chunks.containsKey(key)) {
            throw new IllegalStateException("Preview renderer requested too many chunks");
        }
        return chunks.computeIfAbsent(key, unused -> new EmptyLevelChunk(this, new ChunkPos(x, z), biome) {
            @Override public BlockState getBlockState(BlockPos pos) { return PreviewLevel.this.getBlockState(pos); }
            @Override public FluidState getFluidState(BlockPos pos) { return PreviewLevel.this.getFluidState(pos); }
            @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos, EntityCreationType creation) {
                return PreviewLevel.this.getBlockEntity(pos);
            }
        });
    }

    @Override protected LevelEntityGetter<Entity> getEntities() { return entities; }
    @Override public List<? extends Player> players() { return List.of(); }
    @Override public @Nullable Entity getEntity(int id) { return null; }
    @Override public Collection<? extends PartEntity<?>> dragonParts() { return List.of(); }
    @Override public LevelTickAccess<Block> getBlockTicks() { return BlackholeTickAccess.emptyLevelList(); }
    @Override public LevelTickAccess<Fluid> getFluidTicks() { return BlackholeTickAccess.emptyLevelList(); }
    @Override public TickRateManager tickRateManager() { return ticks; }
    @Override public ClockManager clockManager() { return clocks; }
    @Override public WorldBorder getWorldBorder() { return border; }
    @Override public EnvironmentAttributeSystem environmentAttributes() { return environment; }
    @Override public FeatureFlagSet enabledFeatures() { return host.enabledFeatures(); }
    @Override public Scoreboard getScoreboard() { return scoreboard; }
    @Override public RecipeAccess recipeAccess() { return host.recipeAccess(); }
    @Override public @Nullable MapItemSavedData getMapData(MapId id) { return null; }
    @Override public void setRespawnData(LevelData.RespawnData spawn) {}
    @Override public LevelData.RespawnData getRespawnData() { return LevelData.RespawnData.DEFAULT; }
    @Override public void sendBlockUpdated(BlockPos pos, BlockState previous, BlockState next, int flags) {}
    @Override public void updateNeighbourForOutputSignal(BlockPos pos, Block block) {}
    @Override public void levelEvent(Entity source, int type, BlockPos pos, int data) {}
    @Override public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {}
    @Override public void playSeededSound(Entity except, double x, double y, double z, Holder<SoundEvent> sound,
                                          SoundSource category, float volume, float pitch, long seed) {}
    @Override public void playSeededSound(Entity except, Entity source, Holder<SoundEvent> sound,
                                          SoundSource category, float volume, float pitch, long seed) {}
    @Override public void explode(Entity source, DamageSource damage, ExplosionDamageCalculator calculator,
                                  double x, double y, double z, float radius, boolean fire,
                                  ExplosionInteraction interaction, ParticleOptions small, ParticleOptions large,
                                  WeightedList<ExplosionParticleInfo> blocks, Holder<SoundEvent> sound) {}
    @Override public String gatherChunkSourceStats() { return "preview"; }
    @Override public void destroyBlockProgress(int id, BlockPos pos, int amount) {}
    @Override public boolean noSave() { return true; }

    @Override public void close() {
        for (BlockEntity entity : blockEntities.values()) entity.setLevel(null);
        blockEntities.clear();
        blocks.clear();
        chunks.clear();
    }
}
