package lib.kasuga.rendering.models.mc.scene;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Construction entry points for read-only Minecraft structure and model displays. */
public final class MinecraftModelSources {
    public static final int MAX_BLOCKS = 131072;
    public static final int MAX_LAYERS = 4096;
    public static final int MAX_SPAN = 4096;

    private MinecraftModelSources() {}

    public static BlockModelSource blocks(Level host, List<StructureBlockInfo> blocks) {
        return new BlockModelSource(host, blocks);
    }

    public static BlockModelSource blueprint(Level host, Path file) throws IOException {
        return BlueprintImporters.load(host, file);
    }

    public static BlockModelSource structure(Level host, StructureTemplate template) {
        return blocks(host, SchematicModelData.structure(template, host.holderLookup(Registries.BLOCK)));
    }

    public static BlockModelSource structure(Level host, Path file) throws IOException {
        return blocks(host, SchematicModelData.readStructure(file, host.holderLookup(Registries.BLOCK)));
    }

    /** Reads singleplayer server data packs and bundled vanilla structures. */
    public static BlockModelSource structure(Level host, Identifier structure) throws IOException {
        Identifier location = Identifier.fromNamespaceAndPath(structure.getNamespace(), "structure/" + structure.getPath() + ".nbt");
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            Optional<Resource> resource = server.getResourceManager().getResource(location);
            if (resource.isPresent()) {
                try (InputStream stream = resource.get().open()) {
                    return readStructure(host, stream);
                }
            }
        }
        Resource resource = ServerPacksSource.createVanillaPackSource().asResourceManager().getResource(location)
                .orElseThrow(() -> new IOException("Structure resource does not exist: " + structure));
        try (InputStream stream = resource.open()) {
            return readStructure(host, stream);
        }
    }

    private static BlockModelSource readStructure(Level host, InputStream stream) throws IOException {
        return blocks(host, SchematicModelData.structure(NbtIo.readCompressed(stream,
                NbtAccounter.create(SchematicModelData.MAX_NBT_BYTES)), host.holderLookup(Registries.BLOCK), 0));
    }

    public static BlockModelSource litematic(Level host, Path file) throws IOException {
        return blocks(host, SchematicModelData.readLitematic(file, host.holderLookup(Registries.BLOCK)));
    }

    /** Captures loaded blocks without creating chunks or modifying the host level. */
    public static BlockModelSource capture(Level host, BlockPos min, BlockPos max) {
        long dx = (long) max.getX() - min.getX() + 1;
        long dy = (long) max.getY() - min.getY() + 1;
        long dz = (long) max.getZ() - min.getZ() + 1;
        if (dx <= 0 || dy <= 0 || dz <= 0 || dx > MAX_SPAN || dy > MAX_SPAN || dz > MAX_SPAN
                || dx * dy * dz > MAX_BLOCKS) {
            throw new IllegalArgumentException("Invalid capture area");
        }
        List<StructureBlockInfo> result = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!host.hasChunkAt(pos)) throw new IllegalArgumentException("Capture includes an unloaded chunk");
            BlockEntity entity = host.getBlockEntity(pos);
            result.add(new StructureBlockInfo(pos.immutable(), host.getBlockState(pos),
                    entity == null ? null : entity.saveWithFullMetadata(host.registryAccess())));
        }
        return new BlockModelSource(host, result, min);
    }

    public record Placement(MinecraftModelSource source, Matrix4f transform) {
        public Placement {
            Objects.requireNonNull(source, "source");
            transform = new Matrix4f(transform);
            if (!transform.isFinite() || !transform.isAffine() || transform.determinant() == 0) {
                throw new IllegalArgumentException("Model placement must be finite, affine and invertible");
            }
        }

        public Placement(MinecraftModelSource source, Matrix4fc transform) {
            this(source, new Matrix4f(transform));
        }

        @Override public Matrix4f transform() { return new Matrix4f(transform); }
    }

    /** Combines sources into one depth-tested display and owns their lifecycles. */
    public static MinecraftModelSource compose(Placement... placements) {
        List<Placement> children = List.of(placements);
        IdentityHashMap<MinecraftModelSource, Boolean> seen = new IdentityHashMap<>();
        for (Placement child : children) {
            if (seen.put(child.source(), true) != null) {
                throw new IllegalArgumentException("A composed source cannot own the same child twice");
            }
        }
        return new MinecraftModelSource() {
            private boolean closed;

            @Override public Snapshot snapshot(LayerLayout layout, float partialTick) {
                if (closed) return null;
                List<Part> parts = new ArrayList<>();
                ModelBounds bounds = null;
                for (int i = 0; i < children.size(); i++) {
                    Placement child = children.get(i);
                    Snapshot frame = child.source().snapshot(layout, partialTick);
                    if (frame == null) continue;
                    ModelBounds childBounds = frame.bounds().transform(child.transform);
                    bounds = bounds == null ? childBounds : bounds.union(childBounds);
                    for (Part part : frame.parts()) {
                        parts.add(new Part(i + "/" + part.id(), part.layer(), part.bounds().transform(child.transform), context -> {
                            context.pose().pushPose();
                            try {
                                context.pose().mulPose(child.transform);
                                part.renderer().render(context);
                            } finally {
                                context.pose().popPose();
                            }
                        }, MinecraftModelSource.placedPicker(child.transform, part.picker())));
                    }
                }
                return bounds == null ? null : new Snapshot(bounds, parts);
            }

            @Override public void close() {
                if (closed) return;
                closed = true;
                RuntimeException failure = null;
                for (Placement child : children) {
                    try {
                        child.source().close();
                    } catch (RuntimeException error) {
                        if (failure == null) failure = error;
                        else failure.addSuppressed(error);
                    }
                }
                if (failure != null) throw failure;
            }
        };
    }
}
