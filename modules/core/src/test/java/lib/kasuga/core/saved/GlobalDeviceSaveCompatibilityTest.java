package lib.kasuga.core.saved;

import lib.kasuga.content.device.Device;
import lib.kasuga.content.device.DeviceRegistries;
import lib.kasuga.content.device.DeviceType;
import lib.kasuga.content.device.GlobalDeviceSavedData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalDeviceSaveCompatibilityTest {
    private static final ResourceLocation TYPE_ID = ResourceLocation.fromNamespaceAndPath("kasuga_lib", "legacy_device_counter");

    @Test
    void legacyWriterSavesTypeAndDiscoversItsOwnDatFiles(@TempDir Path world) throws IOException {
        HolderLookup.Provider registries = HolderLookup.Provider.create(Stream.empty());
        DeviceType<?> type = DeviceRegistries.DEVICE.get(TYPE_ID);
        assertNotNull(type);
        Device device = type.create();
        CompoundTag value = new CompoundTag();
        value.putInt("value", 31);
        device.load(value);
        ScopedNbtSavedData<GlobalDeviceSavedData> written = storage(world, registries);
        GlobalDeviceSavedData data = written.computeIfAbsent("counter");
        data.load(device);
        data.setDirty();
        written.save(world, registries);
        IOUtilities.waitUntilIOWorkerComplete();
        Path file = world.resolve("devices/counter.dat");
        CompoundTag payload = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data");
        assertEquals(TYPE_ID.toString(), payload.getString("type"));

        ScopedNbtSavedData<GlobalDeviceSavedData> reopened = storage(world, registries);
        assertEquals(List.of("counter"), reopened.getAllSavedNames());
        GlobalDeviceSavedData decoded = reopened.get("counter");
        assertNotNull(decoded);
        Device restored = decoded.create();
        assertNotNull(restored);
        assertEquals(type, restored.getType());
        assertEquals(31, restored.save(new CompoundTag()).getInt("value"));
        reopened.remove("counter");
        reopened.save(world, registries);
        assertFalse(Files.exists(file));
    }

    private static ScopedNbtSavedData<GlobalDeviceSavedData> storage(Path world, HolderLookup.Provider registries) {
        return new ScopedNbtSavedData<>("devices", GlobalDeviceSavedData.FACTORY, world, registries, DataFixers.getDataFixer());
    }

}
