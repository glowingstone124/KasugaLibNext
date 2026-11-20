package test.kasuga.modelling.demo.blackhole;

import lib.kasuga.KasugaLib;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

import java.util.function.Consumer;

/** Development-client black hole with a lensed, orbiting accretion disk. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class BlackHoleDemo {
    private static ClientLevel level;
    private static LensRegistration renderer;

    private BlackHoleDemo() {}

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kasuga_black_hole")
                .then(Commands.literal("demo").executes(context -> place(Mode.SINGLE, context.getSource()::sendSystemMessage)))
                .then(Commands.literal("orbit").executes(context -> place(Mode.ORBIT, context.getSource()::sendSystemMessage)))
                .then(Commands.literal("massive").executes(context -> place(Mode.MASSIVE, context.getSource()::sendSystemMessage)))
                .then(Commands.literal("clear").executes(context -> {
                    clear();
                    return 1;
                }))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(renderer != null
                            ? "Black-hole demo active" : "Black-hole demo inactive"), false);
                    return 1;
                })));
    }

    private static int place(Mode mode, Consumer<Component> feedback) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (minecraft.level == null || player == null) return 0;
        Vec3 forward = player.getLookAngle().normalize();
        double distance = mode == Mode.MASSIVE ? 42.0 : mode == Mode.ORBIT ? 19.0 : 14.0;
        Vec3 center = player.getEyePosition().add(forward.scale(distance));
        Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalize();
        if (right.lengthSqr() < 1.0e-6) right = new Vec3(1, 0, 0);
        Vec3 up = right.cross(forward).normalize();
        show(minecraft.level, new Scene(mode, center, right, up, forward));
        feedback.accept(Component.literal("Black-hole demo placed ahead"));
        return 1;
    }

    public static void show(ClientLevel activeLevel, Scene scene) {
        clear();
        renderer = new LensRegistration(scene);
        level = activeLevel;
    }

    public static void clear() {
        if (renderer != null) renderer.close();
        renderer = null;
        level = null;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (level != null && level != Minecraft.getInstance().level) clear();
    }

    @SubscribeEvent
    public static void shutdown(GameShuttingDownEvent event) {
        clear();
    }

    public enum Mode { SINGLE, ORBIT, MASSIVE }

    public record Scene(Mode mode, Vec3 center, Vec3 right, Vec3 up, Vec3 forward) {
        int count() { return mode == Mode.ORBIT ? 3 : 1; }

        float radius() {
            return switch (mode) {
                case SINGLE -> .75f;
                case ORBIT -> .36f;
                case MASSIVE -> 3.1f;
            };
        }

        Vec3 position(int index, float time) {
            if (mode != Mode.ORBIT) return center;
            double angle = time * .46 + Math.PI * 2.0 * index / count();
            return center.add(right.scale(Math.cos(angle) * 5.4))
                    .add(up.scale(Math.sin(angle) * 3.0));
        }

        Vec3 normal() { return up.scale(.96).add(forward.scale(-.28)).normalize(); }
    }
}
