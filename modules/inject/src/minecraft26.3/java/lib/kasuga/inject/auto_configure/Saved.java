package lib.kasuga.inject.auto_configure;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public interface Saved<T extends SavedData> {
    T get();

    T get(ResourceKey<Level> dimension);

    void onLoad(MinecraftServer server);

    class Impl<T extends SavedData> implements Saved<T> {
        private final SavedDataType<T> type;
        private final String legacyName;
        private MinecraftServer server;

        public Impl(SavedDataType<T> type) {
            this(type, null);
        }

        /** Imports the old unnamespaced data file when the new type has no stored value. */
        public Impl(SavedDataType<T> type, String legacyName) {
            this.type = Objects.requireNonNull(type, "type");
            if (legacyName != null && !legacyName.matches("[a-z0-9_.-]+")) {
                throw new IllegalArgumentException("Invalid legacy saved data name: " + legacyName);
            }
            this.legacyName = legacyName;
        }

        @Override
        public T get() {
            return get(Level.OVERWORLD);
        }

        @Override
        public T get(ResourceKey<Level> dimension) {
            if (server == null) throw new IllegalStateException("The level is not loaded yet.");
            ServerLevel level = server.getLevel(dimension);
            if (level == null) throw new IllegalArgumentException("The dimension " + dimension + " is not loaded.");
            SavedDataStorage storage = level.getDataStorage();
            T loaded = storage.get(type);
            if (loaded != null) return loaded;
            T imported = legacyName == null ? null : importLegacy(level, storage);
            return imported == null ? storage.computeIfAbsent(type) : imported;
        }

        private T importLegacy(ServerLevel level, SavedDataStorage storage) {
            Path world = server.getWorldPath(LevelResource.ROOT);
            ResourceKey<Level> dimension = level.dimension();
            Path currentData = DimensionType.getStorageFolder(dimension, world).resolve("data");
            Path currentFile = type.id().withSuffix(".dat").resolveAgainst(currentData);
            if (Files.exists(currentFile)) return null;

            String fileName = legacyName + ".dat";
            Path legacyFile = currentData.resolve(fileName);
            if (!Files.isRegularFile(legacyFile)) {
                Path oldDimension = legacyDimensionPath(world, dimension);
                if (oldDimension == null) return null;
                legacyFile = oldDimension.resolve("data").resolve(fileName);
                if (!Files.isRegularFile(legacyFile)) return null;
            }

            try {
                CompoundTag tag = storage.readTagFromDisk(legacyFile, type.dataFixType(),
                        SharedConstants.getCurrentVersion().dataVersion().version());
                if (!(tag.get("data") instanceof CompoundTag payload)) {
                    throw new IllegalStateException("Missing saved data payload in " + legacyFile);
                }
                T value = type.codecFactory().create(level).parse(
                        level.registryAccess().createSerializationContext(NbtOps.INSTANCE),
                        payload).getOrThrow();
                storage.set(type, value);
                return value;
            } catch (IOException exception) {
                throw new UncheckedIOException("Could not import saved data from " + legacyFile, exception);
            }
        }

        private static Path legacyDimensionPath(Path world, ResourceKey<Level> dimension) {
            if (dimension == Level.OVERWORLD) return world;
            if (dimension == Level.NETHER) return world.resolve("DIM-1");
            if (dimension == Level.END) return world.resolve("DIM1");
            return null;
        }

        @Override
        public void onLoad(MinecraftServer server) {
            this.server = Objects.requireNonNull(server, "server");
        }

        public void onUnload() {
            server = null;
        }

        public void onServerStart(ServerStartedEvent event) {
            onLoad(event.getServer());
        }

        public void onServerStop(ServerStoppingEvent event) {
            if (server == event.getServer()) onUnload();
        }
    }
}
