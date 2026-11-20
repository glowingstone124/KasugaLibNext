package lib.kasuga.content.device;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Serialized state for one global device. */
public class GlobalDeviceSavedData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    protected @Nullable Identifier type;
    protected CompoundTag data = new CompoundTag();
    protected List<DeviceAttribute> attributes = new ArrayList<>();
    private final ListTag unresolvedAttributes = new ListTag();

    public GlobalDeviceSavedData() {}

    public GlobalDeviceSavedData(CompoundTag tag, HolderLookup.Provider registries) {
        type = tag.getString("type").map(Identifier::tryParse).orElse(null);
        data = tag.getCompoundOrEmpty("deviceData").copy();
        Tag stored = tag.get("attributes");
        if (stored instanceof ListTag list) {
            for (Tag entry : list) {
                if (entry instanceof CompoundTag attribute) readAttribute(attribute, registries);
                else unresolvedAttributes.add(entry.copy());
            }
        } else if (stored instanceof CompoundTag legacy) {
            for (String id : legacy.keySet()) {
                CompoundTag entry = legacy.getCompoundOrEmpty(id).copy();
                entry.putString("type", id);
                readAttribute(entry, registries);
            }
        }
    }

    private void readAttribute(CompoundTag entry, HolderLookup.Provider registries) {
        Identifier id = entry.getString("type").map(Identifier::tryParse).orElse(null);
        DeviceAttributeType<?> attributeType = id == null ? null : DeviceRegistries.DEVICE_ATTRIBUTE.getValue(id);
        if (attributeType != null) {
            try {
                attributes.add(Objects.requireNonNull(attributeType.factory().apply(
                        entry.getCompoundOrEmpty("data").copy(), registries)));
                return;
            } catch (RuntimeException failure) {
                LOGGER.warn("Could not load global device attribute {}", id, failure);
            }
        }
        unresolvedAttributes.add(entry.copy());
    }

    public void load(Device device) {
        Identifier id = Objects.requireNonNull(DeviceRegistries.DEVICE.getKey(device.getType()), "Unregistered device type");
        CompoundTag payload = Objects.requireNonNull(device.save(new CompoundTag()), "Missing device data");
        type = id;
        data = payload.copy();
    }

    public void save(Device device) {
        if (type != null && type.equals(DeviceRegistries.DEVICE.getKey(device.getType()))) device.load(data.copy());
    }

    public @Nullable Device create() {
        if (type == null) return null;
        DeviceType<?> deviceType = DeviceRegistries.DEVICE.getValue(type);
        if (deviceType == null) return null;
        Device device = deviceType.create();
        device.load(data.copy());
        return device;
    }

    public CompoundTag save(CompoundTag output, HolderLookup.Provider registries) {
        if (type != null) output.putString("type", type.toString());
        output.put("deviceData", data.copy());
        ListTag entries = unresolvedAttributes.copy();
        for (DeviceAttribute attribute : attributes) {
            DeviceAttributeType<?> attributeType = attribute.getType();
            if (!attributeType.persistent()) continue;
            Identifier id = Objects.requireNonNull(DeviceRegistries.DEVICE_ATTRIBUTE.getKey(attributeType),
                    "Unregistered device attribute type");
            CompoundTag tag = new CompoundTag();
            tag.putString("type", id.toString());
            tag.put("data", attribute.save(new CompoundTag(), registries));
            entries.add(tag);
        }
        output.put("attributes", entries);
        return output;
    }
}
