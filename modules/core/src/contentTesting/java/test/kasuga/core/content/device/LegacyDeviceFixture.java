package test.kasuga.core.content.device;

import io.micronaut.context.annotation.Context;
import lib.kasuga.content.device.Device;
import lib.kasuga.content.device.DeviceType;
import lib.kasuga.registration.kasuga.device.global.GlobalDeviceReg;
import net.minecraft.nbt.CompoundTag;
import test.kasuga.core.CoreTestApplication;

@Context
public final class LegacyDeviceFixture {
    private static final GlobalDeviceReg<Counter> COUNTER = new GlobalDeviceReg<>("legacy_device_counter", Counter::new);

    public LegacyDeviceFixture() {
        CoreTestApplication.registry.addChild(COUNTER);
    }

    private static final class Counter extends Device {
        private int value;

        @Override public DeviceType<?> getType() { return COUNTER.getEntry(); }
        @Override public void load(CompoundTag tag) { value = tag.getInt("value"); }
        @Override public CompoundTag save(CompoundTag tag) {
            tag.putInt("value", value);
            return tag;
        }
    }
}
