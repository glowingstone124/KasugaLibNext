package lib.kasuga.core.resource.pack;

import lib.kasuga.core.vfs.VfsInvalidPathException;
import lib.kasuga.core.vfs.VfsPath;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.fml.jarcontents.JarContents;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/** A borrowed view of one layer of a NeoForge mod resource pack. */
final class ScopedJarResourcePack implements HierarchicalScopedPackResources, FlattenScopedPackResources {
    private final PackResources pack;
    private final NavigableSet<String> paths = new TreeSet<>();

    ScopedJarResourcePack(PackResources pack, JarContents contents, String prefix) {
        this.pack = pack;
        String directory = prefix.isEmpty() ? "" : prefix + "/";
        contents.visitContent(directory, (path, resource) -> {
            if (!path.startsWith(directory)) return;
            String relative = path.substring(directory.length());
            try {
                VfsPath normalized = VfsPath.of(relative);
                if (!normalized.isRoot() && normalized.asString().equals(relative)) paths.add(relative);
            } catch (VfsInvalidPathException ignored) {
                // Entries outside the scoped path contract cannot be exposed.
            }
        });
    }

    @Override public InputStream open(String prefix, String path) throws IOException {
        VfsPath entry = ScopedPackResources.toLogicalPath(prefix, path);
        IoSupplier<InputStream> input = pack.getRootResource(entry.segments().toArray(String[]::new));
        if (input == null) throw new FileNotFoundException(entry.asString());
        return input.get();
    }

    @Override public boolean exists(String prefix, String path) {
        return isRegularFile(prefix, path) || isDirectory(prefix, path);
    }

    @Override public boolean isRegularFile(String prefix, String path) {
        return paths.contains(ScopedPackResources.toLogicalPath(prefix, path).asString());
    }

    @Override public boolean isDirectory(String prefix, String path) {
        String directory = start(ScopedPackResources.toLogicalPath(prefix, path));
        String child = paths.ceiling(directory);
        return child != null && child.startsWith(directory);
    }

    @Override public List<String> list(String prefix, String path) {
        String directory = start(ScopedPackResources.toLogicalPath(prefix, path));
        Set<String> children = new TreeSet<>();
        for (String child : paths.tailSet(directory)) {
            if (!child.startsWith(directory)) break;
            String relative = child.substring(directory.length());
            int slash = relative.indexOf('/');
            children.add(slash < 0 ? relative : relative.substring(0, slash));
        }
        return List.copyOf(children);
    }

    @Override public Stream<String> listEntries(String prefix) {
        String directory = start(ScopedPackResources.toLogicalPath(prefix, ""));
        return paths.tailSet(directory).stream().takeWhile(path -> path.startsWith(directory))
                .map(path -> path.substring(directory.length()));
    }

    @Override public String getName() { return pack.packId(); }

    private static String start(VfsPath path) { return path.isRoot() ? "" : path.asString() + "/"; }
}
