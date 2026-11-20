package lib.kasuga.content.device;

import io.micronaut.context.annotation.Context;
import lib.kasuga.KasugaLib;
import lib.kasuga.KasugaLibApplication;
import lib.kasuga.registration.minecraft.registry.RegistryReg;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.RegistryBuilder;

@Context()
public class DeviceRegistries {
    public static ResourceKey<Registry<DeviceAttributeType<?>>> DEVICE_ATTRIBUTE_KEY =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "device_attribute_type"));

    public static final Registry<DeviceAttributeType<?>> DEVICE_ATTRIBUTE = new RegistryBuilder<>(DEVICE_ATTRIBUTE_KEY)
            .sync(true)
            .create();

    public static final RegistryReg<DeviceAttributeType<?>> DEVICE_ATTRIBUTE_REG = new RegistryReg<>(DEVICE_ATTRIBUTE)
            .setParent(KasugaLibApplication.REGISTRY);

    public static ResourceKey<Registry<DeviceType<?>>> DEVICE_KEY =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "device_type"));

    public static final Registry<DeviceType<?>> DEVICE = new RegistryBuilder<>(DEVICE_KEY)
            .sync(true)
            .create();

    public static final RegistryReg<DeviceType<?>> DEVICE_REG = new RegistryReg<>(DEVICE)
            .setParent(KasugaLibApplication.REGISTRY);
}
