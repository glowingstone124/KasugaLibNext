package lib.kasuga.content.device;

import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import jakarta.inject.Named;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Manages a world's global devices and their attribute index on the server thread. */
@Context
public class GlobalDeviceManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final HashMap<UUID, Device> devices = new HashMap<>();
    private final HashMap<UUID, GlobalDeviceSavedData> records = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private final AttributeIndex index = new AttributeIndex();
    private MinecraftServer server;
    private GlobalDeviceCatalogue catalogue;
    private boolean removedRecords;

    public GlobalDeviceManager(@Named("forgeEventBus") IEventBus eventBus) {
        eventBus.addListener(this::onServerStarted);
        eventBus.addListener(this::onLevelSaving);
        eventBus.addListener(this::onServerStopped);
    }

    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        catalogue = GlobalDeviceCatalogue.open(server);
        HashMap<UUID, GlobalDeviceSavedData> active = new HashMap<>();
        for (String key : catalogue.records.keySet()) {
            if (!(catalogue.records.get(key) instanceof CompoundTag payload)) continue;
            try {
                UUID id = UUID.fromString(key);
                GlobalDeviceSavedData record = new GlobalDeviceSavedData(payload, server.registryAccess());
                records.put(id, record);
                Device device = record.create();
                if (device != null) {
                    devices.put(id, device);
                    active.put(id, record);
                } else {
                    LOGGER.warn("Preserving global device {} with unavailable type {}", id, record.type);
                }
            } catch (RuntimeException failure) {
                LOGGER.warn("Could not load global device {}", key, failure);
            }
        }
        index.reindex(active);
    }

    private void onLevelSaving(LevelEvent.Save event) {
        if (server != null && event.getLevel() == server.overworld() && flushChanges()) {
            server.overworld().getDataStorage().scheduleSave();
        }
    }

    public void onServerStopped(ServerStoppingEvent event) {
        if (server != event.getServer()) return;
        flushChanges();
        devices.clear();
        records.clear();
        dirty.clear();
        index.primaryIndex.clear();
        index.indexable.clear();
        removedRecords = false;
        catalogue = null;
        server = null;
    }

    public @Nullable Device get(UUID id) {
        return devices.get(id);
    }

    /** Adds or replaces the device at this UUID while retaining its attributes. */
    public @Nullable Device put(UUID id, Device device) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(device, "device");
        if (catalogue == null) throw new IllegalStateException("Global devices require a running server");
        if (DeviceRegistries.DEVICE.getKey(device.getType()) == null) {
            throw new IllegalArgumentException("Unregistered device type");
        }
        GlobalDeviceSavedData record = records.computeIfAbsent(id, ignored -> new GlobalDeviceSavedData());
        Device previous = devices.put(id, device);
        if (previous == null) {
            for (DeviceAttribute attribute : record.attributes) index.notifyAttributeAdded(id, attribute);
        }
        dirty.add(id);
        return previous;
    }

    public void saveToData() {
        flushChanges();
    }

    private boolean flushChanges() {
        if (catalogue == null) return false;
        boolean changed = removedRecords;
        for (Map.Entry<UUID, Device> entry : devices.entrySet()) {
            UUID id = entry.getKey();
            Device device = entry.getValue();
            if (!dirty.contains(id) && !device.isDirty()) continue;
            GlobalDeviceSavedData record = records.get(id);
            try {
                record.load(device);
                CompoundTag result = record.save(new CompoundTag(), server.registryAccess());
                catalogue.records.put(id.toString(), result);
                dirty.remove(id);
                device.cleanDirty();
                changed = true;
            } catch (RuntimeException failure) {
                LOGGER.warn("Could not save global device {}", id, failure);
            }
        }
        if (changed) server.overworld().getDataStorage().set(GlobalDeviceCatalogue.TYPE, catalogue);
        removedRecords = false;
        return changed;
    }

    public void addAttribute(UUID deviceId, DeviceAttribute attribute) {
        if (!devices.containsKey(deviceId)) return;
        if (DeviceRegistries.DEVICE_ATTRIBUTE.getKey(attribute.getType()) == null) {
            throw new IllegalArgumentException("Unregistered device attribute type");
        }
        List<DeviceAttribute> attributes = records.get(deviceId).attributes;
        if (attributes.contains(attribute)) return;
        attributes.add(attribute);
        index.notifyAttributeAdded(deviceId, attribute);
        dirty.add(deviceId);
    }

    public void removeAttribute(UUID deviceId, DeviceAttribute attribute) {
        if (!devices.containsKey(deviceId) || !records.get(deviceId).attributes.remove(attribute)) return;
        index.notifyAttributeRemoved(deviceId, attribute);
        dirty.add(deviceId);
    }

    public List<DeviceAttribute> getAttributes(UUID deviceId) {
        return devices.containsKey(deviceId) ? List.copyOf(records.get(deviceId).attributes) : List.of();
    }

    public List<UUID> queryAttribute(DeviceAttribute attribute) {
        return index.queryAttribute(attribute);
    }

    public void remove(UUID deviceId) {
        if (devices.remove(deviceId) == null) return;
        GlobalDeviceSavedData record = records.remove(deviceId);
        for (DeviceAttribute attribute : record.attributes) index.notifyAttributeRemoved(deviceId, attribute);
        dirty.remove(deviceId);
        catalogue.records.remove(deviceId.toString());
        removedRecords = true;
    }

    public void merge(UUID target, UUID from) {
        if (target.equals(from)) return;
        Device targetDevice = devices.get(target);
        Device fromDevice = devices.get(from);
        if (targetDevice == null || fromDevice == null) return;
        targetDevice.acceptMerge(fromDevice);
        dirty.add(target);
        remove(from);
    }
}
