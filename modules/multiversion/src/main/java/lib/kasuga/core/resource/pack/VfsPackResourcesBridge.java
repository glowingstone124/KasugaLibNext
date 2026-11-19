package lib.kasuga.core.resource.pack;

import lib.kasuga.core.vfs.VirtualFileSystem;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.AbstractPackMetadataResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.util.Set;

/**
 * Bridges any {@link VirtualFileSystem} into Minecraft's {@link PackResources} pipeline
 * without referencing client-only classes (safe on Dedicated Server and Client).
 */
public final class VfsPackResourcesBridge extends AbstractPackMetadataResources implements VfsBackedPackResources {
    private final VfsPackContents contents;

    public VfsPackResourcesBridge(VirtualFileSystem vfs, PackLocationInfo locationInfo) {
        this(vfs, locationInfo, false);
    }

    public VfsPackResourcesBridge(VirtualFileSystem vfs, PackLocationInfo locationInfo, boolean closeVfsOnClose) {
        super(locationInfo);
        this.contents = new VfsPackContents(vfs, closeVfsOnClose);
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
    public @Nullable IoSupplier<InputStream> getResource(PackType type, Identifier location) {
        if (type == null || location == null) return null;
        return contents.find(type.getDirectory(), location.getNamespace(), location.getPath());
    }

    @Override
    public void listResources(PackType type, String namespace, String prefix, ResourceOutput output) {
        if (type == null || namespace == null || prefix == null || output == null
                || !Identifier.isValidNamespace(namespace)) return;
        contents.list(type.getDirectory(), namespace, prefix, (path, input) -> {
            Identifier resource = Identifier.tryBuild(namespace, path);
            if (resource != null) output.accept(resource, input);
        });
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == null ? Set.of() : contents.namespaces(type.getDirectory(), Identifier::isValidNamespace);
    }

    @Override
    public void close() {
        contents.close();
    }
}
