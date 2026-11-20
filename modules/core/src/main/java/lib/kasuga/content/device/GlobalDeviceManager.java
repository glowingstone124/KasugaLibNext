package lib.kasuga.content.device;

import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lib.kasuga.core.saved.CustomSavedDataManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Context()
public class GlobalDeviceManager {
    private Logger LOGGER = LogUtils.getLogger();
    HashMap<UUID, Device> devices = new HashMap<>();
    GlobalDeviceSavedData.Customized manager;

    @Inject() CustomSavedDataManager dataManager;

    AttributeIndex index = new AttributeIndex();

    public GlobalDeviceManager(@Named("forgeEventBus")IEventBus eventBus) {
        eventBus.addListener(this::onServerStarted);
        eventBus.addListener(this::onServerStopped);
    }
    public void onServerStarted(ServerStartedEvent event) {
        manager = dataManager.get(
                GlobalDeviceSavedData.Customized.class,
                (p,r,f)->new GlobalDeviceSavedData.Customized(this, p, r, f)
        );
        HashMap<UUID, GlobalDeviceSavedData> allSavedData = new HashMap<>();
        for (String savedName : manager.getAllSavedNames()) {
            try{
                UUID id = UUID.fromString(savedName);
                GlobalDeviceSavedData data = manager.get(savedName);
                Device device = data == null ? null : data.create();
                if (device == null) continue;
                devices.put(id, device);
                allSavedData.put(id, data);
            }catch (RuntimeException e) {
                LOGGER.error(e.getLocalizedMessage());
            }
        }
        index.reindex(allSavedData);
    }

    public void onServerStopped(ServerStoppingEvent event) {
        if (manager == null) return;
        try {
            manager.save(dataManager.getLevelPath(event.getServer()), event.getServer().registryAccess());
        } finally {
            manager = null;
            devices.clear();
            index.primaryIndex.clear();
            index.indexable.clear();
        }
    }

    public @Nullable Device get(UUID id) {
        return devices.get(id);
    }

    public @Nullable Device put(UUID id, Device device) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(device, "device");
        if (manager == null) throw new IllegalStateException("Global devices require a running server");
        if (DeviceRegistries.DEVICE.getKey(device.getType()) == null) {
            throw new IllegalArgumentException("Unregistered device type");
        }
        GlobalDeviceSavedData data = manager.computeIfAbsent(id.toString());
        Device previous = devices.put(id, device);
        if (previous == null) {
            for (DeviceAttribute attribute : data.attributes) index.notifyAttributeAdded(id, attribute);
        }
        device.setDirty();
        return previous;
    }

    public List<UUID> queryAttribute(DeviceAttribute attribute) {
        return index.queryAttribute(attribute);
    }

    public void saveToData() {
        if (manager == null) return;
        for (Map.Entry<UUID, Device> idDeviceEntry : this.devices.entrySet()) {
            if(!idDeviceEntry.getValue().isDirty())
                continue;
            GlobalDeviceSavedData savedData = manager.computeIfAbsent(idDeviceEntry.getKey().toString());
            savedData.load(idDeviceEntry.getValue());
            savedData.setDirty();
            idDeviceEntry.getValue().cleanDirty();
        }
    }

    public void addAttribute(UUID deviceId, DeviceAttribute attribute) {
        if(!this.devices.containsKey(deviceId))
            return;
        GlobalDeviceSavedData data = manager.computeIfAbsent(deviceId.toString());
        if (data.attributes.contains(attribute)) return;
        data.attributes.add(attribute);
        index.notifyAttributeAdded(deviceId, attribute);
        data.setDirty();
    }

    public void removeAttribute(UUID deviceId, DeviceAttribute attribute) {
        if(!this.devices.containsKey(deviceId))
            return;
        GlobalDeviceSavedData data = manager.computeIfAbsent(deviceId.toString());
        if (!data.attributes.remove(attribute)) return;
        index.notifyAttributeRemoved(deviceId, attribute);
        data.setDirty();
    }

    public List<DeviceAttribute> getAttributes(UUID deviceId) {
        if(!this.devices.containsKey(deviceId))
            return List.of();
        return List.copyOf(manager.computeIfAbsent(deviceId.toString()).attributes);
    }

    public void remove(UUID deviceId) {
        if(!this.devices.containsKey(deviceId))
            return;
        for (DeviceAttribute attribute : getAttributes(deviceId)) {
            index.notifyAttributeRemoved(deviceId, attribute);
        }
        this.devices.remove(deviceId);
        manager.remove(deviceId.toString());
    }

    public void merge(UUID target, UUID from) {
        if(target.equals(from) || !this.devices.containsKey(target) || !this.devices.containsKey(from))
            return;
        Device targetDevice = this.devices.get(target);
        Device fromDevice = this.devices.get(from);
        targetDevice.acceptMerge(fromDevice);
        targetDevice.setDirty();
        remove(from);
    }
}
