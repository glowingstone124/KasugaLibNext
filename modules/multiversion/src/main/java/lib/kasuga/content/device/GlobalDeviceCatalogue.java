package lib.kasuga.content.device;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

/** Keeps raw records intact when their device or attribute type is unavailable. */
final class GlobalDeviceCatalogue extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final SavedDataType<GlobalDeviceCatalogue> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(KasugaLib.MODID, "devices"),
            () -> new GlobalDeviceCatalogue(new CompoundTag()),
            CompoundTag.CODEC.xmap(GlobalDeviceCatalogue::new, catalogue -> catalogue.records.copy()));

    final CompoundTag records;

    private GlobalDeviceCatalogue(CompoundTag records) {
        this.records = records;
    }

    static GlobalDeviceCatalogue open(MinecraftServer server) {
        SavedDataStorage storage = server.overworld().getDataStorage();
        GlobalDeviceCatalogue existing = storage.get(TYPE);
        if (existing != null) return existing;
        Path world = server.getWorldPath(LevelResource.ROOT);
        Path dataDirectory = DimensionType.getStorageFolder(Level.OVERWORLD, world).resolve("data");
        Path current = TYPE.id().withSuffix(".dat").resolveAgainst(dataDirectory);
        if (Files.exists(current)) {
            throw new IllegalStateException("Could not read global device data at " + current);
        }

        GlobalDeviceCatalogue imported = TYPE.factory().create(server.overworld());
        Path previous = world.resolve("devices");
        if (!Files.isDirectory(previous)) return imported;
        try (Stream<Path> paths = Files.list(previous)) {
            for (Path file : paths.sorted().toList()) imported.readLegacy(file, storage);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not list legacy global device records in " + previous, failure);
        }
        if (!imported.records.isEmpty()) storage.set(TYPE, imported);
        return imported;
    }

    private void readLegacy(Path file, SavedDataStorage storage) {
        if (!Files.isRegularFile(file)) return;
        String name = file.getFileName().toString();
        if (!name.endsWith(".dat") && !name.endsWith(".nbt")) return;
        String id = name.substring(0, name.length() - 4);
        try {
            if (!UUID.fromString(id).toString().equals(id) || records.contains(id)) return;
        } catch (IllegalArgumentException invalidName) {
            return;
        }
        try {
            CompoundTag root = storage.readTagFromDisk(file, null, SharedConstants.getCurrentVersion().dataVersion().version());
            CompoundTag payload = root.get("data") instanceof CompoundTag wrapped ? wrapped : root;
            records.put(id, payload.copy());
        } catch (IOException failure) {
            LOGGER.warn("Could not import legacy global device file {}", file, failure);
        }
    }
}
