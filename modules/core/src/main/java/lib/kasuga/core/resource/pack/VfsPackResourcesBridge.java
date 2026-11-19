package lib.kasuga.core.resource.pack;

import lib.kasuga.core.vfs.VirtualFileSystem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/**
 * Bridges any {@link VirtualFileSystem} into Minecraft's {@link PackResources} pipeline
 * without referencing client-only classes (safe on Dedicated Server and Client).
 */
public final class VfsPackResourcesBridge implements VfsBackedPackResources {
    private static final Logger LOGGER = LoggerFactory.getLogger(VfsPackResourcesBridge.class);

    private final VfsPackContents contents;
    private final PackLocationInfo locationInfo;

    public VfsPackResourcesBridge(VirtualFileSystem vfs, PackLocationInfo locationInfo) {
        this(vfs, locationInfo, false);
    }

    public VfsPackResourcesBridge(VirtualFileSystem vfs, PackLocationInfo locationInfo, boolean closeVfsOnClose) {
        this.contents = new VfsPackContents(vfs, closeVfsOnClose);
        this.locationInfo = Objects.requireNonNull(locationInfo, "locationInfo");
    }

    @Override
    public VirtualFileSystem getVfs() { return contents.vfs(); }

    @Override
    public VfsScopedPackResourcesAdapter asScopedPackResources() { return contents.scoped(); }

    @Override
    public @Nullable IoSupplier<InputStream> getRootResource(String... elements) {
        return contents.root(elements);
    }

    @Override
    public @Nullable IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (type == null || location == null) return null;
        return contents.find(type.getDirectory(), location.getNamespace(), location.getPath());
    }

    @Override
    public void listResources(PackType type, String namespace, String prefix, ResourceOutput output) {
        if (type == null || namespace == null || prefix == null || output == null
                || !ResourceLocation.isValidNamespace(namespace)) return;
        contents.list(type.getDirectory(), namespace, prefix, (path, input) -> {
            ResourceLocation resource = ResourceLocation.tryBuild(namespace, path);
            if (resource != null) output.accept(resource, input);
        });
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == null ? Set.of() : contents.namespaces(type.getDirectory(), ResourceLocation::isValidNamespace);
    }

    @Override
    public <T> @Nullable T getMetadataSection(MetadataSectionSerializer<T> deserializer) throws IOException {
        IoSupplier<InputStream> mcmeta = getRootResource(PACK_META);
        if (mcmeta == null) {
            return null;
        }
        try (InputStream stream = mcmeta.get();
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var json = GsonHelper.parse(reader);
            String sectionName = deserializer.getMetadataSectionName();
            if (!json.has(sectionName)) {
                return null;
            }
            return deserializer.fromJson(GsonHelper.getAsJsonObject(json, sectionName));
        } catch (RuntimeException invalidMetadata) {
            LOGGER.error("Couldn't load {} metadata from VFS '{}'", deserializer.getMetadataSectionName(), contents.vfs().sourceId(), invalidMetadata);
            return null;
        }
    }

    @Override
    public PackLocationInfo location() {
        return locationInfo;
    }

    @Override
    public void close() {
        contents.close();
    }
}
