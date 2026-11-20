package lib.kasuga.content.device;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AttributeIndex {
    public HashMap<DeviceAttribute, UUID> primaryIndex = new HashMap<>();
    public HashMap<DeviceAttribute, Set<UUID>> indexable = new HashMap<>();

    public void reindex(HashMap<UUID, GlobalDeviceSavedData> allData) {
        primaryIndex.clear();
        indexable.clear();
        for (Map.Entry<UUID, GlobalDeviceSavedData> entry : allData.entrySet()) {
            UUID deviceId = entry.getKey();
            GlobalDeviceSavedData data = entry.getValue();
            for (DeviceAttribute attribute : data.attributes) {
                notifyAttributeAdded(deviceId, attribute);
            }
        }
    }

    public void notifyAttributeAdded(UUID deviceId, DeviceAttribute attribute) {
        DeviceAttributeType<?> type = attribute.getType();
        if(type.isUnique()) {
            primaryIndex.put(attribute, deviceId);
        }
        if(type.canIndex()) {
            indexable.computeIfAbsent(attribute, k -> new java.util.HashSet<>()).add(deviceId);
        }
    }

    public void notifyAttributeRemoved(UUID deviceId, DeviceAttribute attribute) {
        DeviceAttributeType<?> type = attribute.getType();
        if(type.isUnique()) {
            primaryIndex.remove(attribute, deviceId);
        }
        if(type.canIndex()) {
            Set<UUID> ids = indexable.get(attribute);
            if(ids != null) {
                ids.remove(deviceId);
                if(ids.isEmpty()) {
                    indexable.remove(attribute);
                }
            }
        }
    }

    public List<UUID> queryAttribute(DeviceAttribute attribute) {
        DeviceAttributeType<?> type = attribute.getType();
        if(type.isUnique()) {
            UUID id = primaryIndex.get(attribute);
            if(id != null) {
                return List.of(id);
            } else {
                return List.of();
            }
        }
        if(type.canIndex()) {
            Set<UUID> ids = indexable.get(attribute);
            if(ids != null) {
                return List.copyOf(ids);
            } else {
                return List.of();
            }
        }
        return List.of();
    }
}
