package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.mc.backend.ui.WorldUiSmoke;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.backend.ui.ModelPreviewWidget;
import lib.kasuga.rendering.models.mc.scene.BlockDimensions;
import lib.kasuga.rendering.models.mc.scene.BlockModelSource;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource.BlockHit;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSources;
import lib.kasuga.scene.SceneModels;
import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

public final class StructurePreviewSmoke {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DATA_VERSION = SharedConstants.getCurrentVersion().dataVersion().version();
    private static final BlockPos RED = new BlockPos(1, 0, 0);
    private static final BlockPos LIME = new BlockPos(1, 1, 0);
    private static final BlockPos CHEST = new BlockPos(0, 0, 1);
    private static final BlockPos SIGN = new BlockPos(0, 1, 1);
    private static final String SIGN_MESSAGE = "KASUGA";
    private static Phase phase = Phase.WORLD;
    private static SceneModels scene;
    private static BlockModelSource source;
    private static PreviewScreen screen;
    private static int frames;
    private static boolean capturing;
    private static boolean reloadFinished;
    private static boolean finished;
    private static long builds;
    private static RuntimeException failure;

    private StructurePreviewSmoke() {}

    public static void start(Minecraft client) {
        try {
            validateImporters(client);
            source = readFixture(client, structure());
            CompoundTag metadata = source.blockEntityData(CHEST);
            if (metadata == null || !metadata.getStringOr("id", "").equals("minecraft:chest")) {
                throw new IllegalStateException("Structure import lost block entity metadata");
            }
            checkBlockEntities(client, source);
        } catch (IOException error) {
            throw new IllegalStateException("Could not read structure fixtures", error);
        }
        Camera camera = client.gameRenderer.mainCamera();
        Vector3fc forward = camera.forwardVector();
        Vec3 position = camera.position().add(forward.x() * 4 - 1.5, forward.y() * 4 - 1, forward.z() * 4);
        scene = SceneModels.open(client.level);
        scene.add(source, position);
        BlockHit hit = scene.pick(position.add(1.5, 1.5, -1), position.add(1.5, 1.5, 2), 0).orElseThrow();
        if (!hit.position().equals(LIME)) throw new IllegalStateException("Could not pick the mounted structure");
    }

    public static boolean started() { return source != null; }

    public static void afterFrame(Minecraft client) {
        if (failure != null) throw failure;
        if (finished || capturing || client.gui.overlay() != null) return;
        if (phase != Phase.WORLD && client.gui.screen() != screen || phase == Phase.RELOADED && !reloadFinished) return;
        if (++frames < 12) return;
        frames = 0;
        capturing = true;
        Phase sampled = phase;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                Path file = client.gameDirectory.toPath().resolve(
                        "kasuga-structure-preview-" + sampled.name().toLowerCase(Locale.ROOT) + ".png");
                image.writeToFile(file);
                Pixels pixels = readPixels(image);
                client.execute(() -> {
                    try {
                        evaluate(client, sampled, pixels);
                    } catch (RuntimeException error) {
                        failure = error;
                    }
                });
            } catch (IOException error) {
                throw new IllegalStateException("Could not write structure preview capture", error);
            }
        });
    }

    private static void evaluate(Minecraft client, Phase sampled, Pixels pixels) {
        LOGGER.info("KASUGA_STRUCTURE_CAPTURE phase={} red={} lime={} water={} chest={} sign={}",
                sampled, pixels.red(), pixels.lime(), pixels.water(), pixels.chest(), pixels.sign());
        boolean sliced = sampled == Phase.SLICED || sampled == Phase.SPACED || sampled == Phase.RELOADED;
        if (pixels.red() < 400 || pixels.chest() < 300 || sampled != Phase.WORLD && pixels.water() < 200
                || (sliced ? pixels.sign() > 20 : pixels.sign() < 30)
                || (sliced ? pixels.lime() > 20 : pixels.lime() < 400)) {
            throw new IllegalStateException("Structure geometry missing or incorrect at " + sampled + ": " + pixels);
        }
        switch (sampled) {
            case WORLD -> {
                BlockEntity sign = source.blockEntity(SIGN);
                scene.close();
                if (sign == null || sign.hasLevel()) throw new IllegalStateException("World scene retained a preview block entity");
                try {
                    source = readFixture(client, structure());
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
                checkBlockEntities(client, source);
                screen = new PreviewScreen(source);
                client.gui.setScreen(screen);
                phase = Phase.ASSEMBLED;
            }
            case ASSEMBLED -> {
                assertPick(.35, LIME);
                screen.widget.preview().setLayers(LayerLayout.ASSEMBLED.range(0, 0).separation(.8));
                phase = Phase.SLICED;
            }
            case SLICED -> {
                assertPick(.60, RED);
                if (source.geometryBuilds() != 2 || screen.widget.preview().pick(.5, .35).isPresent()) {
                    throw new IllegalStateException("Hidden layers remained pickable or static layers rebuilt per frame");
                }
                builds = source.geometryBuilds();
                screen.widget.preview().setLayers(LayerLayout.ASSEMBLED.range(0, 0).separation(1.05));
                phase = Phase.SPACED;
            }
            case SPACED -> {
                if (source.geometryBuilds() != builds) {
                    throw new IllegalStateException("Layer spacing rebuilt static geometry");
                }
                phase = Phase.RELOADED;
                client.reloadResourcePacks().whenComplete((ignored, error) -> client.execute(() -> {
                    if (error == null) reloadFinished = true;
                    else failure = new IllegalStateException("Could not reload structure preview atlas", error);
                }));
            }
            case RELOADED -> {
                if (source.geometryBuilds() != builds + 1) {
                    throw new IllegalStateException("Resource reload failed to invalidate cached structure geometry");
                }
                BlockEntity sign = source.blockEntity(SIGN);
                client.gui.setScreen(null);
                if (source.snapshot(LayerLayout.ASSEMBLED, 0) != null || !screen.widget.preview().isClosed()
                        || sign == null || sign.hasLevel() || source.blockEntity(SIGN) != null) {
                    throw new IllegalStateException("Removing the structure preview did not close its source");
                }
                finished = true;
                LOGGER.info("KASUGA_STRUCTURE_PREVIEW_PASS world,gui,vanilla-nbt,litematic,sponge,fluid,layers,cache,reload,pick,closed");
                LOGGER.info("KASUGA_STRUCTURE_BLOCK_ENTITIES_PASS chest,sign-text,private-level,layer-visibility,detached");
                WorldUiSmoke.start(client);
            }
        }
        capturing = false;
    }

    private static void assertPick(double y, BlockPos expected) {
        BlockHit hit = screen.widget.preview().pick(.5, y).orElseThrow();
        if (!hit.position().equals(expected)) throw new IllegalStateException("Unexpected structure block at " + hit);
    }

    private static void checkBlockEntities(Minecraft client, BlockModelSource source) {
        if (!(source.blockEntity(CHEST) instanceof ChestBlockEntity chest)
                || !(source.blockEntity(SIGN) instanceof SignBlockEntity sign)
                || sign.getLevel() != source.previewLevel() || sign.getLevel() == client.level
                || chest.getLevel() != sign.getLevel()
                || source.previewLevel().setBlock(SIGN, Blocks.AIR.defaultBlockState(), 3)
                || !sign.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString().equals(SIGN_MESSAGE)) {
            throw new IllegalStateException("Structure block entities were not loaded in a private read-only level");
        }
    }

    private static Pixels readPixels(NativeImage image) {
        int red = 0;
        int lime = 0;
        int water = 0;
        int chest = 0;
        int sign = 0;
        for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
            for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
                int pixel = image.getPixel(x, y);
                int r = pixel >>> 16 & 255;
                int g = pixel >>> 8 & 255;
                int b = pixel & 255;
                if (r > 65 && r > g * 2 && r > b * 2) red++;
                if (g > 75 && g > r * 1.35 && g > b * 2) lime++;
                if (b > 78 && b > r * 1.45 && b > g * 1.15 && b > r + 45) water++;
                if (r > 60 && g > 36 && r > g * 1.25 && g > b * 1.45) chest++;
                if (r > 110 && b > 115 && r > g * 1.55 && b > g * 1.55) sign++;
            }
        }
        return new Pixels(red, lime, water, chest, sign);
    }

    private static BlockModelSource readFixture(Minecraft client, CompoundTag fixture) throws IOException {
        Path file = Files.createTempFile(client.gameDirectory.toPath(), "structure-fixture-", ".nbt");
        try {
            NbtIo.writeCompressed(fixture, file);
            return MinecraftModelSources.blueprint(client.level, file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void validateImporters(Minecraft client) throws IOException {
        try (BlockModelSource builtIn = MinecraftModelSources.structure(client.level, Identifier.parse("minecraft:end_city/base_floor"));
             BlockModelSource litematic = readFixture(client, litematic());
             BlockModelSource sponge = readFixture(client, sponge())) {
            Identifier red = Identifier.parse("minecraft:red_concrete");
            Identifier lime = Identifier.parse("minecraft:lime_concrete");
            if (builtIn.dimensions().isEmpty()
                    || !litematic.dimensions().orElseThrow().equals(new BlockDimensions(1, 1, 2))
                    || !litematic.blockStatistics(LayerLayout.Axis.Y).equals(Map.of(-1, Map.of(red, 1), 0, Map.of(lime, 1)))
                    || !sponge.blockStatistics(LayerLayout.Axis.Y).equals(Map.of(2, Map.of(red, 1), 3, Map.of(lime, 1)))) {
                throw new IllegalStateException("Imported structure dimensions or palette contents differ");
            }
        }
    }

    private static CompoundTag structure() {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", DATA_VERSION);
        root.put("size", ints(3, 2, 2));
        ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.WATER.defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.CHEST.defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.OAK_SIGN.defaultBlockState()));
        root.put("palette", palette);
        ListTag blocks = new ListTag();
        blocks.add(block(RED, 0));
        blocks.add(block(LIME, 1));
        blocks.add(block(new BlockPos(2, 0, 0), 2));
        CompoundTag chest = block(CHEST, 3);
        CompoundTag metadata = new CompoundTag();
        metadata.putString("id", "minecraft:chest");
        chest.put("nbt", metadata);
        blocks.add(chest);
        CompoundTag sign = block(SIGN, 4);
        CompoundTag signMetadata = new CompoundTag();
        signMetadata.putString("id", "minecraft:sign");
        SignText text = SignText.EMPTY.asMutable().setLine(0, Component.literal(SIGN_MESSAGE))
                .setColor(DyeColor.MAGENTA).setTextGlowing(true).asImmutable();
        signMetadata.store("front_text", SignText.CODEC, text);
        signMetadata.store("back_text", SignText.CODEC, text);
        sign.put("nbt", signMetadata);
        blocks.add(sign);
        root.put("blocks", blocks);
        return root;
    }

    private static CompoundTag block(BlockPos pos, int state) {
        CompoundTag block = new CompoundTag();
        block.put("pos", ints(pos.getX(), pos.getY(), pos.getZ()));
        block.putInt("state", state);
        return block;
    }

    private static ListTag ints(int x, int y, int z) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(x));
        list.add(IntTag.valueOf(y));
        list.add(IntTag.valueOf(z));
        return list;
    }

    private static CompoundTag vector(int x, int y, int z) {
        CompoundTag value = new CompoundTag();
        value.putInt("x", x);
        value.putInt("y", y);
        value.putInt("z", z);
        return value;
    }

    private static CompoundTag litematic() {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 7);
        root.putInt("MinecraftDataVersion", DATA_VERSION);
        CompoundTag regions = new CompoundTag();
        CompoundTag region = new CompoundTag();
        region.put("Position", vector(0, 0, 0));
        region.put("Size", vector(1, -2, 1));
        ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState()));
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", new long[]{4});
        regions.put("small", region);
        root.put("Regions", regions);
        return root;
    }

    private static CompoundTag sponge() {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 3);
        root.putInt("DataVersion", DATA_VERSION);
        root.putShort("Width", (short) 1);
        root.putShort("Height", (short) 2);
        root.putShort("Length", (short) 1);
        root.putIntArray("Offset", new int[]{0, 2, 0});
        CompoundTag blocks = new CompoundTag();
        CompoundTag palette = new CompoundTag();
        palette.putInt("minecraft:red_concrete", 0);
        palette.putInt("minecraft:lime_concrete", 1);
        blocks.put("Palette", palette);
        blocks.putByteArray("Data", new byte[]{0, 1});
        root.put("Blocks", blocks);
        return root;
    }

    private enum Phase { WORLD, ASSEMBLED, SLICED, SPACED, RELOADED }
    private record Pixels(int red, int lime, int water, int chest, int sign) {}

    private static final class PreviewScreen extends Screen {
        private final ModelPreviewWidget widget;

        private PreviewScreen(BlockModelSource source) {
            super(Component.literal("Structure preview smoke"));
            widget = new ModelPreviewWidget(0, 0, 1, 1, new GuiModelPreview(source));
        }

        @Override protected void init() {
            int size = height * 4 / 5;
            widget.setRectangle(size, size, (width - size) / 2, (height - size) / 2);
            addRenderableWidget(widget);
        }

        @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float frameDelta) {
            graphics.fill(0, 0, width, height, 0xff161923);
        }

        @Override public void removed() {
            widget.close();
            super.removed();
        }
    }
}
