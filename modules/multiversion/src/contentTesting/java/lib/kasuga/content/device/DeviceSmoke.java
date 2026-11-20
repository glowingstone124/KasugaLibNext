package lib.kasuga.content.device;

import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import lib.kasuga.KasugaLib;
import lib.kasuga.KasugaLibApplication;
import lib.kasuga.registration.kasuga.device.attribute.DeviceAttributeReg;
import lib.kasuga.registration.kasuga.device.global.GlobalDeviceReg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Context
@Requires(property = "kasuga.graphicsSmoke")
@EventBusSubscriber(modid = KasugaLib.MODID)
public final class DeviceSmoke {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final UUID LEGACY = UUID.fromString("f59e2731-da85-4a63-9a4c-53a16082bbd9");
    private static final UUID MISSING_TYPE = UUID.fromString("21b96aec-cac1-4691-b6ba-a66b7abd0d72");
    private static final UUID REMOVED = UUID.fromString("3d7c8639-85ec-42b5-b56c-dc971a644b44");
    private static final UUID ADDED = UUID.fromString("d74d62f3-c718-43d6-9e9a-8666105573c1");
    private static final String UNKNOWN_ATTRIBUTE = "kasuga_lib:unavailable_device_attribute";

    private static final GlobalDeviceReg<Counter> COUNTER = new GlobalDeviceReg<>("smoke_device_counter", Counter::new);
    private static final DeviceAttributeReg<Label, DeviceAttributeType<Label>> LABEL =
            DeviceAttributeReg.of("smoke_device_label", Label::persistent).indexable();
    private static final DeviceAttributeReg<Label, DeviceAttributeType<Label>> TRANSIENT =
            DeviceAttributeReg.of("smoke_device_transient", Label::temporary).notPersistent();

    public DeviceSmoke() {
        KasugaLibApplication.REGISTRY.addChild(COUNTER).addChild(LABEL).addChild(TRANSIENT);
    }

    @SubscribeEvent
    public static void seedLegacy(ServerAboutToStartEvent event) throws IOException {
        if (!enabled()) return;
        Path previous = event.getServer().getWorldPath(LevelResource.ROOT).resolve("devices");
        Files.createDirectories(previous);
        CompoundTag typed = new CompoundTag();
        typed.putString("type", COUNTER.getResourceLocation().toString());
        CompoundTag device = new CompoundTag();
        device.putInt("value", 21);
        typed.put("deviceData", device);
        CompoundTag known = new CompoundTag();
        CompoundTag knownData = new CompoundTag();
        knownData.putString("value", "legacy");
        known.put("data", knownData);
        CompoundTag oldAttributes = new CompoundTag();
        oldAttributes.put(LABEL.getResourceLocation().toString(), known);
        oldAttributes.put(UNKNOWN_ATTRIBUTE, known.copy());
        typed.put("attributes", oldAttributes);
        writeLegacy(previous.resolve(LEGACY + ".dat"), typed);
        CompoundTag removed = typed.copy();
        removed.getCompoundOrEmpty("deviceData").putInt("value", 16);
        writeLegacy(previous.resolve(REMOVED + ".dat"), removed);

        CompoundTag missing = new CompoundTag();
        missing.put("deviceData", device.copy());
        writeLegacy(previous.resolve(MISSING_TYPE + ".nbt"), missing);
    }

    private static void writeLegacy(Path path, CompoundTag data) throws IOException {
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        NbtIo.writeCompressed(root, path);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void check(ServerStartedEvent event) {
        if (!enabled()) return;
        MinecraftServer server = event.getServer();
        GlobalDeviceManager manager = KasugaLib.getBean(GlobalDeviceManager.class);
        if (!(manager.get(LEGACY) instanceof Counter counter) || counter.value != 21 || manager.get(MISSING_TYPE) != null) {
            throw new IllegalStateException("Legacy global device import did not use the registered type");
        }
        List<DeviceAttribute> attributes = manager.getAttributes(LEGACY);
        if (attributes.size() != 1 || !manager.queryAttribute(attributes.getFirst()).equals(List.of(LEGACY))) {
            throw new IllegalStateException("Imported device attribute was not indexed");
        }

        Label second = new Label("current", false);
        Label temporary = new Label("session", true);
        manager.addAttribute(LEGACY, second);
        manager.addAttribute(LEGACY, temporary);
        manager.put(ADDED, new Counter(9));
        manager.merge(LEGACY, REMOVED);
        if (counter.value != 37 || manager.get(REMOVED) != null || second.getType() != LABEL.getEntry()) {
            throw new IllegalStateException("Global device mutation or registration failed");
        }
        server.saveEverything(false, true, true);
        ServerLevel level = server.overworld();
        level.getDataStorage().saveAndJoin();

        Path world = server.getWorldPath(LevelResource.ROOT);
        Path dataDirectory = DimensionType.getStorageFolder(Level.OVERWORLD, world).resolve("data");
        try (SavedDataStorage reopened = new SavedDataStorage(level, dataDirectory, DataFixers.getDataFixer(), level.registryAccess())) {
            GlobalDeviceCatalogue saved = reopened.get(GlobalDeviceCatalogue.TYPE);
            if (saved == null || saved.records.contains(REMOVED.toString())) {
                throw new IllegalStateException("Device removal was not persisted");
            }
            CompoundTag unavailable = saved.records.getCompoundOrEmpty(MISSING_TYPE.toString());
            if (unavailable.contains("type") || unavailable.getCompoundOrEmpty("deviceData").getIntOr("value", 0) != 21) {
                throw new IllegalStateException("Legacy payload with an unknown type was lost");
            }
            CompoundTag stored = saved.records.getCompoundOrEmpty(LEGACY.toString());
            GlobalDeviceSavedData data = new GlobalDeviceSavedData(stored, level.registryAccess());
            if (!(data.create() instanceof Counter restored) || restored.value != 37 || data.attributes.size() != 2
                    || !(data.attributes.getLast() instanceof Label label) || !label.value.equals("current")
                    || !hasUnknownAttribute(stored)) {
                throw new IllegalStateException("Global device or its attributes did not round-trip");
            }
            GlobalDeviceSavedData added = new GlobalDeviceSavedData(
                    saved.records.getCompoundOrEmpty(ADDED.toString()), level.registryAccess());
            if (!(added.create() instanceof Counter inserted) || inserted.value != 9) {
                throw new IllegalStateException("New device was not persisted");
            }
        }
        if (!Files.isRegularFile(world.resolve("devices").resolve(LEGACY + ".dat"))) {
            throw new IllegalStateException("Legacy global device file was removed");
        }
        LOGGER.info("KASUGA_DEVICE_PASS registration,index,legacy-import,unknown-preserved,save,reopen,merge");
    }

    private static boolean hasUnknownAttribute(CompoundTag record) {
        for (Tag entry : record.getListOrEmpty("attributes")) {
            if (entry instanceof CompoundTag attribute && UNKNOWN_ATTRIBUTE.equals(attribute.getStringOr("type", ""))) {
                return true;
            }
        }
        return false;
    }

    private static boolean enabled() {
        return !System.getProperty("kasuga.graphicsSmoke", "").isEmpty();
    }

    private static final class Counter extends Device {
        private int value;
        private Counter() {}
        private Counter(int value) { this.value = value; }
        @Override public DeviceType<?> getType() { return COUNTER.getEntry(); }
        @Override public void load(CompoundTag data) { value = data.getIntOr("value", 0); }
        @Override public CompoundTag save(CompoundTag tag) {
            tag.putInt("value", value);
            return tag;
        }

        @Override public void acceptMerge(Device other) { value += ((Counter) other).value; }
    }

    private static final class Label extends DeviceAttribute {
        private final String value;
        private final boolean temporary;

        private Label(String value, boolean temporary) {
            this.value = value;
            this.temporary = temporary;
        }

        private static Label persistent(CompoundTag tag, HolderLookup.Provider registries) {
            return new Label(tag.getStringOr("value", ""), false);
        }

        private static Label temporary(CompoundTag tag, HolderLookup.Provider registries) {
            return new Label(tag.getStringOr("value", ""), true);
        }

        @Override public DeviceAttributeType<?> getType() { return temporary ? TRANSIENT.getEntry() : LABEL.getEntry(); }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putString("value", value);
            return tag;
        }
    }
}
