package lib.kasuga.host;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import lib.kasuga.KasugaLibApplication;
import lib.kasuga.content.document.DocumentComponentRegistries;
import lib.kasuga.content.document.DocumentComponentType;
import lib.kasuga.content.document.DocumentItem;
import lib.kasuga.registration.kasuga.document.DocumentComponentReg;
import lib.kasuga.registration.kasuga.document.DocumentComponentRendererReg;
import lib.kasuga.registration.kasuga.document.DocumentComponentTypes;
import lib.kasuga.registration.minecraft.item.ItemReg;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState.HandRenderSelection;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@Context
@Requires(property = "kasuga.graphicsSmoke")
public final class DocumentSmoke {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ItemReg<DocumentItem> DOCUMENT = ItemReg.of("smoke_document", DocumentItem::new);
    private static final DocumentComponentReg<DocumentComponentTypes.DocumentString> TEXT =
            new DocumentComponentReg<>("smoke_document_text", DocumentComponentTypes.DocumentString::new);
    private static final DocumentComponentReg<DocumentComponentTypes.DocumentInt> PAGE =
            new DocumentComponentReg<>("smoke_document_page", DocumentComponentTypes.DocumentInt::new);
    private static Phase phase;
    private static long equippedAt;
    private static String submitted;
    private static boolean capturing;
    private static RuntimeException failure;
    private static double twoHandX;

    public DocumentSmoke() {
        KasugaLibApplication.REGISTRY.addChild(DOCUMENT).addChild(TEXT).addChild(PAGE);
        KasugaLibApplication.REGISTRY.addChild(new DocumentComponentRendererReg<String>(() -> () -> DocumentSmoke::submitPage)
                .withComponent(TEXT));
    }

    public static void start(Minecraft client) {
        equip(client, Phase.TWO_HANDS);
    }

    private static void equip(Minecraft client, Phase next) {
        phase = next;
        equippedAt = -1;
        submitted = null;
        IntegratedServer server = client.getSingleplayerServer();
        UUID playerId = client.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            ItemStack stack = new ItemStack(DOCUMENT.getEntry());
            if (!DocumentItem.isEmptyLike(stack) || stack.getMaxStackSize() != 64) {
                throw new IllegalStateException("An empty registered document did not keep its stack properties");
            }
            Map<Holder<DocumentComponentType<?>>, Object> values = Map.of(
                    TEXT.getHolder(), next.text, PAGE.getHolder(), next.page);
            stack.set(DocumentComponentRegistries.DOCUMENT_COMPONENT.getEntry(), values);
            RegistryOps<Tag> ops = player.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            ItemStack restored = ItemStack.CODEC.parse(ops, ItemStack.CODEC.encodeStart(ops, stack).getOrThrow()).getOrThrow();
            if (!values.equals(restored.get(DocumentComponentRegistries.DOCUMENT_COMPONENT.getEntry()))
                    || DocumentItem.isEmptyLike(restored) || restored.getMaxStackSize() != 1) {
                throw new IllegalStateException("Filled document did not preserve its typed components through persistence");
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, next.offHand ? new ItemStack(Items.STONE) : restored);
            player.setItemInHand(InteractionHand.OFF_HAND, next.offHand ? restored : ItemStack.EMPTY);
        });
    }

    private static void submitPage(String value, ItemStack stack, SubmitNodeCollector nodes, PoseStack pose, int light) {
        submitted = value;
        nodes.submitCustomGeometry(pose, RenderTypes.debugQuads(), (geometry, vertices) -> {
            vertices.addVertex(geometry, 0, 0, 0).setColor(0, 234, 81, 255);
            vertices.addVertex(geometry, 128, 0, 0).setColor(0, 234, 81, 255);
            vertices.addVertex(geometry, 128, 128, 0).setColor(0, 234, 81, 255);
            vertices.addVertex(geometry, 0, 128, 0).setColor(0, 234, 81, 255);
        });
    }

    public static void afterFrame(Minecraft client) {
        if (failure != null) throw failure;
        if (capturing || client.level == null || client.player == null || client.gui.overlay() != null
                || client.gui.screen() != null || phase == null) return;
        InteractionHand hand = phase.offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack active = client.player.getItemInHand(hand);
        if (active.getItem() != DOCUMENT.getEntry()) return;
        Map<Holder<DocumentComponentType<?>>, Object> values = active.get(DocumentComponentRegistries.DOCUMENT_COMPONENT.getEntry());
        if (values == null || !phase.text.equals(values.get(TEXT.getHolder()))
                || !Integer.valueOf(phase.page).equals(values.get(PAGE.getHolder()))) {
            throw new IllegalStateException("Held document components did not synchronize over the inventory network");
        }
        if (equippedAt < 0) equippedAt = client.level.getGameTime();
        if (client.level.getGameTime() - equippedAt < 12) return;
        HandRenderSelection expected = phase.offHand ? HandRenderSelection.RENDER_OFF_HAND_ONLY
                : HandRenderSelection.RENDER_MAIN_HAND_ONLY;
        if (FirstPersonHandsAndItems.evaluateWhichHandsToRender(client.player) != expected) {
            throw new IllegalStateException("Document did not take the correct rendering priority");
        }
        if (!phase.text.equals(submitted)) throw new IllegalStateException("Registered document renderer was not called");

        Phase captured = phase;
        capturing = true;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                Path path = client.gameDirectory.toPath().resolve("kasuga-document-" + captured.text + ".png");
                image.writeToFile(path);
                PagePixels pixels = readPage(image);
                client.execute(() -> {
                    try {
                        checkCapture(client, captured, pixels, path);
                    } catch (RuntimeException error) {
                        failure = error;
                    }
                });
            } catch (IOException error) {
                throw new IllegalStateException("Could not write document frame capture", error);
            }
        });
    }

    private static void checkCapture(Minecraft client, Phase captured, PagePixels pixels, Path path) {
        LOGGER.info("KASUGA_DOCUMENT_CAPTURE phase={} pixels={} x={} file={}",
                captured, pixels.count(), pixels.xFraction(), path);
        if (pixels.count() < 500) throw new IllegalStateException("Document was missing from " + path);
        if (!captured.offHand) {
            twoHandX = pixels.xFraction();
            equip(client, Phase.OFF_HAND);
            capturing = false;
            return;
        }
        if (Math.abs(twoHandX - pixels.xFraction()) < .12) {
            throw new IllegalStateException("Two-handed and off-hand document poses did not move the page");
        }
        LOGGER.info("KASUGA_DOCUMENT_PASS registry,heterogeneous-codecs,inventory-sync,hand-priority,two-hands,off-hand");
        client.stop();
    }

    private static PagePixels readPage(NativeImage image) {
        int count = 0;
        long xTotal = 0;
        for (int y = image.getHeight() / 3; y < image.getHeight() * 9 / 10; y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getPixel(x, y);
                int red = pixel >>> 16 & 255;
                int green = pixel >>> 8 & 255;
                int blue = pixel & 255;
                if (green > 160 && red < 55 && blue < 110) {
                    count++;
                    xTotal += x;
                }
            }
        }
        return new PagePixels(count, count == 0 ? 0 : (double) xTotal / count / image.getWidth());
    }

    private enum Phase {
        TWO_HANDS("two-hands", 70, false), OFF_HAND("off-hand", 81, true);

        private final String text;
        private final int page;
        private final boolean offHand;

        Phase(String text, int page, boolean offHand) {
            this.text = text;
            this.page = page;
            this.offHand = offHand;
        }
    }

    private record PagePixels(int count, double xFraction) {}
}
