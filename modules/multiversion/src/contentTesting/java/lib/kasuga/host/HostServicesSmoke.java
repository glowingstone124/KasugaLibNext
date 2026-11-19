package lib.kasuga.host;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lib.kasuga.KasugaLib;
import lib.kasuga.inject.auto_configure.Configurable;
import lib.kasuga.inject.auto_configure.Saved;
import lib.kasuga.inject.class_loader.ForgePathFinder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Singleton
@Requires(property = "kasuga.graphicsSmoke")
@EventBusSubscriber(modid = KasugaLib.MODID)
public final class HostServicesSmoke implements Configurable {
    private static final Logger LOGGER = LogUtils.getLogger();
    @Inject Saved<Counter> saved;
    @Inject @Named("modContainer") ModContainer container;
    private ModConfigSpec.IntValue local;
    private ModConfigSpec.IntValue synced;

    @Override
    public void configureCommon(ModConfigSpec.Builder builder) {
        local = builder.defineInRange("hostSmokeLocal", 11, 0, 100);
    }

    @Override
    public void configureServer(ModConfigSpec.Builder builder) {
        synced = builder.defineInRange("hostSmokeSynced", 23, 0, 100);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void check(ServerStartedEvent event) throws IOException {
        if (System.getProperty("kasuga.graphicsSmoke", "").isEmpty()) return;
        HostServicesSmoke smoke = KasugaLib.getBean(HostServicesSmoke.class);
        if (smoke.local.get() != 11 || smoke.synced.get() != 23) {
            throw new IllegalStateException("Host configuration was not loaded");
        }
        try (ForgePathFinder finder = new ForgePathFinder(smoke.container)) {
            Path metadata = finder.find("META-INF/neoforge.mods.toml");
            if (metadata == null || !Files.readString(metadata).contains("kasuga_lib")) {
                throw new IllegalStateException("Mod metadata could not be read through the path finder");
            }
        }

        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        Path legacyDirectory = server.getWorldPath(LevelResource.ROOT).resolve("data");
        Files.createDirectories(legacyDirectory);
        Path legacyFile = legacyDirectory.resolve("counter.dat");
        CompoundTag legacyValue = new CompoundTag();
        legacyValue.putInt("value", 21);
        CompoundTag legacySave = new CompoundTag();
        legacySave.put("data", legacyValue);
        NbtIo.writeCompressed(legacySave, legacyFile);

        Counter data = smoke.saved.get();
        if (data.value != 21 || data != smoke.saved.get(Level.OVERWORLD)) {
            throw new IllegalStateException("Saved data instance was not scoped to the level");
        }
        data.value = 37;
        data.setDirty();
        level.getDataStorage().saveAndJoin();
        Path directory = DimensionType.getStorageFolder(Level.OVERWORLD, server.getWorldPath(LevelResource.ROOT));
        try (SavedDataStorage reopened = new SavedDataStorage(level, directory.resolve("data"),
                DataFixers.getDataFixer(), level.registryAccess())) {
            Counter restored = reopened.get(Counter.TYPE);
            if (restored == null || restored.value != 37 || data == restored) {
                throw new IllegalStateException("Saved data did not survive a disk round trip");
            }
        }
        if (!Files.isRegularFile(legacyFile)) throw new IllegalStateException("Legacy saved data file was removed");
        LOGGER.info("KASUGA_HOST_SERVICES_PASS config=local,synced importedData=21 savedData={}", data.value);
    }

    public static final class Counter extends SavedData {
        public static final SavedDataType<Counter> TYPE = new SavedDataType<>(
                Identifier.fromNamespaceAndPath(KasugaLib.MODID, "host_smoke"),
                Counter::new, Codec.INT.fieldOf("value").codec().xmap(Counter::new, data -> data.value));
        private int value;

        private Counter() {}

        private Counter(int value) {
            this.value = value;
        }
    }
}
