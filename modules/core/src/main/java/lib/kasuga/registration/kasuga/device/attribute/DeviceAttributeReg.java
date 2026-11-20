package lib.kasuga.registration.kasuga.device.attribute;

import lib.kasuga.content.device.DeviceAttribute;
import lib.kasuga.content.device.DeviceRegistries;
import lib.kasuga.content.device.DeviceAttributeType;
import lib.kasuga.registration.minecraft.common.MinecraftDeferRegistryReg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.function.BiFunction;

public class DeviceAttributeReg<T extends DeviceAttribute, U extends DeviceAttributeType<T>> extends MinecraftDeferRegistryReg<DeviceAttributeReg<T, U>, DeviceAttributeType<?>, U> implements DeviceAttributeConfigurations<DeviceAttributeReg<T, U>> {
    private BiFunction<CompoundTag, HolderLookup.Provider, T> factory;

    protected DeviceAttributeReg(String name, BiFunction<CompoundTag, HolderLookup.Provider, T> deviceTypeFactory) {
        super(name, DeviceRegistries.DEVICE_ATTRIBUTE_KEY);
        this.factory = deviceTypeFactory;
    }

    public static <T extends DeviceAttribute> DeviceAttributeReg<T, DeviceAttributeType<T>> of(
            String name, BiFunction<CompoundTag, HolderLookup.Provider, T> factory) {
        return new DeviceAttributeReg<>(name, factory);
    }

    @Override
    protected U createObject(ResourceLocation id) {
        DeviceAttributeType.Builder<T> type = new DeviceAttributeType.Builder<>(this.factory);
        //noinspection unchecked
        return (U) transform(DeviceAttributeModifiers.DEVICE_ATTRIBUTE, type).build();
    }
}
