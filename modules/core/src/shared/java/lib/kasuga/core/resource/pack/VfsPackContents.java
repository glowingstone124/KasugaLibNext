package lib.kasuga.core.resource.pack;

import lib.kasuga.core.vfs.VfsInvalidPathException;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.VirtualFileSystem;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

final class VfsPackContents {
    private static final Logger LOGGER = LoggerFactory.getLogger(VfsPackContents.class);
    private final VirtualFileSystem vfs;
    private final boolean owned;
    private final VfsScopedPackResourcesAdapter scoped;

    VfsPackContents(VirtualFileSystem vfs, boolean owned) {
        this.vfs = Objects.requireNonNull(vfs, "vfs");
        this.owned = owned;
        this.scoped = new VfsScopedPackResourcesAdapter(vfs);
    }

    VirtualFileSystem vfs() { return vfs; }

    VfsScopedPackResourcesAdapter scoped() { return scoped; }

    @Nullable IoSupplier<InputStream> root(String... elements) {
        if (elements == null || elements.length == 0) return null;
        for (String element : elements) {
            if (element == null) return null;
        }
        try {
            return find(VfsPath.ofSegments(List.of(elements)));
        } catch (VfsInvalidPathException invalid) {
            return null;
        }
    }

    @Nullable IoSupplier<InputStream> find(String type, String namespace, String path) {
        try {
            return find(VfsPath.of(type).resolve(namespace).resolve(path));
        } catch (VfsInvalidPathException invalid) {
            return null;
        }
    }

    private @Nullable IoSupplier<InputStream> find(VfsPath path) {
        return vfs.isRegularFile(path) ? () -> vfs.openRead(path) : null;
    }

    void list(String type, String namespace, String prefix, BiConsumer<String, IoSupplier<InputStream>> output) {
        try {
            VfsPath root = VfsPath.of(type).resolve(namespace);
            VfsPath search = prefix.isEmpty() ? root : root.resolve(prefix);
            if (!vfs.isDirectory(search)) return;
            for (VfsPath file : vfs.walk(search)) {
                output.accept(root.relativize(file).asString(), () -> vfs.openRead(file));
            }
        } catch (VfsInvalidPathException | IOException failure) {
            LOGGER.warn("Failed to list VFS resources for {}:{}/{}", namespace, type, prefix, failure);
        }
    }

    Set<String> namespaces(String directory, Predicate<String> valid) {
        VfsPath root = VfsPath.of(directory);
        if (!vfs.isDirectory(root)) return Set.of();
        try {
            Set<String> namespaces = new TreeSet<>();
            for (String child : vfs.list(root)) {
                if (valid.test(child) && vfs.isDirectory(root.resolve(child))) namespaces.add(child);
            }
            return Set.copyOf(namespaces);
        } catch (IOException failure) {
            return Set.of();
        }
    }

    void close() {
        if (!owned) return;
        try {
            vfs.close();
        } catch (IOException failure) {
            LOGGER.warn("Error closing bridged VFS '{}'", vfs.sourceId(), failure);
        }
    }
}
