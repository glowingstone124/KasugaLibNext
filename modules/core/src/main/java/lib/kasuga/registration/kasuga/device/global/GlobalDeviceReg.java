package lib.kasuga.registration.kasuga.device.global;

import lib.kasuga.content.device.Device;
import lib.kasuga.content.device.DeviceRegistries;
import lib.kasuga.content.device.DeviceType;
import lib.kasuga.registration.minecraft.common.MinecraftDeferRegistryReg;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.function.Supplier;

public final class GlobalDeviceReg<T extends Device>
        extends MinecraftDeferRegistryReg<GlobalDeviceReg<T>, DeviceType<?>, DeviceType<T>> {
    private final Supplier<T> factory;

    public GlobalDeviceReg(String name, Supplier<T> factory) {
        super(name, DeviceRegistries.DEVICE_KEY);
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    protected DeviceType<T> createObject(ResourceLocation id) {
        return new DeviceType<>(factory);
    }
}
