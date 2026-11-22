package lib.kasuga.rendering.models.mc.component;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.core.resource.ClientResourceReloadFinishEvent;
import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlModelSource;
import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlWorldModels;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.io.IOException;

/** Owns an optional world preview that can exchange its resource-backed model after a reload. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class LiveModelPreview {
    private static Preview current;

    private LiveModelPreview() {}

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kasuga_models").then(Commands.literal("live")
                .then(Commands.literal("preview")
                        .then(Commands.literal("close").executes(context -> {
                            close();
                            return 1;
                        }))
                        .then(Commands.argument("model", IdentifierArgument.id()).executes(context -> {
                            Minecraft client = Minecraft.getInstance();
                            Identifier id = context.getArgument("model", Identifier.class);
                            if (client.level == null) return 0;
                            Camera camera = client.gameRenderer.mainCamera();
                            Vec3 position = camera.position().add(new Vec3(camera.forwardVector()).scale(3))
                                    .subtract(0, .55, 0);
                            try {
                                show(id, client.level, position, new Matrix4f().rotate(camera.rotation()));
                                return 1;
                            } catch (IOException | RuntimeException failure) {
                                context.getSource().sendFailure(Component.literal("无法打开预览 " + id + " " + failure.getMessage()));
                                return 0;
                            }
                        })))));
    }

    static void show(Identifier id, ClientLevel level, Vec3 position, Matrix4fc transform) throws IOException {
        RenderPearlModelSource model = RenderPearlModelSource.open(id);
        RenderPearlWorldModels.Mount mount = null;
        try {
            mount = model.mount(level, position.x, position.y, position.z);
            mount.setPlacement(position, transform);
        } catch (RuntimeException | Error failure) {
            try {
                if (mount != null) mount.close();
            } finally {
                model.close();
            }
            throw failure;
        }
        Preview previous = current;
        current = new Preview(id, level, position, new Matrix4f(transform), model, mount);
        if (previous != null) previous.close();
    }

    static RenderPearlModelSource currentModel() { return current == null ? null : current.model(); }

    @SubscribeEvent
    public static void reload(ClientResourceReloadFinishEvent event) {
        Preview previous = current;
        if (previous == null || Minecraft.getInstance().level != previous.level()) return;
        try {
            show(previous.id(), previous.level(), previous.position(), previous.transform());
        } catch (IOException | RuntimeException failure) {
            LogUtils.getLogger().warn("Could not reload the Blockbench preview of {}", previous.id(), failure);
        }
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (current != null && event.getLevel() == current.level()) close();
    }

    @SubscribeEvent
    public static void shutdown(GameShuttingDownEvent event) { close(); }

    static void close() {
        Preview preview = current;
        current = null;
        if (preview != null) preview.close();
    }

    private record Preview(Identifier id, ClientLevel level, Vec3 position, Matrix4f transform,
                           RenderPearlModelSource model, RenderPearlWorldModels.Mount mount) implements AutoCloseable {
        @Override public void close() {
            try { mount.close(); }
            finally { model.close(); }
        }
    }
}
