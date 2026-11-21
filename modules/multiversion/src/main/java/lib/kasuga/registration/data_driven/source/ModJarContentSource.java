package lib.kasuga.registration.data_driven.source;

import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.fml.jarcontents.JarResource;
import net.neoforged.neoforgespi.locating.IModFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** A bounded view of the logical mod contents owned by the NeoForge loader. */
public final class ModJarContentSource implements ContentSource {
    private final JarContents contents;
    private final SourceDescriptor descriptor;
    private final SourceLimits limits;
    private final NavigableMap<LogicalPath, LocatedFile> files = new TreeMap<>();
    private long totalBytes;
    private boolean closed;

    private ModJarContentSource(JarContents contents, SourceDescriptor descriptor, SourceLimits limits) {
        this.contents = contents;
        this.descriptor = descriptor;
        this.limits = limits;
    }

    public static ModJarContentSource open(IModFile modFile, SourceDescriptor descriptor) throws IOException {
        return open(modFile, descriptor, SourceLimits.DEFAULT);
    }

    public static ModJarContentSource open(IModFile modFile, SourceDescriptor descriptor, SourceLimits limits)
            throws IOException {
        Objects.requireNonNull(modFile, "modFile");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(limits, "limits");
        try {
            JarContents contents = modFile.getContents();
            ModJarContentSource source = new ModJarContentSource(contents, descriptor, limits);
            contents.visitContent((name, resource) -> {
                try {
                    source.index(name, resource);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
            return source;
        } catch (UncheckedIOException failure) {
            if (failure.getCause() instanceof SourceAccessException accessFailure) throw accessFailure;
            throw new SourceAccessException(SourceAccessException.Reason.IO_ERROR, descriptor, null,
                    "cannot enumerate mod contents", failure.getCause());
        } catch (RuntimeException failure) {
            throw new SourceAccessException(SourceAccessException.Reason.IO_ERROR, descriptor, null,
                    "cannot enumerate mod contents", failure);
        }
    }

    private void index(String name, JarResource resource) throws IOException {
        LogicalPath path;
        try {
            path = LogicalPath.of(name);
        } catch (IllegalArgumentException failure) {
            throw new SourceAccessException(SourceAccessException.Reason.INVALID_ENTRY, descriptor, null,
                    "invalid mod entry: " + name, failure);
        }
        verifyPath(path);
        if (files.containsKey(path)) {
            throw failure(SourceAccessException.Reason.DUPLICATE_ENTRY, path, "duplicate mod entry");
        }
        long size = resource.attributes().size();
        if (size < 0) throw failure(SourceAccessException.Reason.INVALID_ENTRY, path, "invalid mod entry size");
        if (files.size() >= limits.maxFiles() || size > limits.limitFor(path)
                || size > limits.maxTotalBytes() - totalBytes) {
            throw failure(SourceAccessException.Reason.LIMIT_EXCEEDED, path, "mod contents exceed source limits");
        }
        files.put(path, new LocatedFile(descriptor, path, size));
        totalBytes += size;
    }

    private void verifyPath(LogicalPath path) throws SourceAccessException {
        Optional<URI> location = contents.findFile(path.value());
        if (location.isEmpty() || !"file".equals(location.get().getScheme())) return;
        Path cursor = Path.of(location.get());
        for (String segment : path.segments()) {
            if (Files.isSymbolicLink(cursor)) {
                throw failure(SourceAccessException.Reason.SYMLINK_REJECTED, path, "mod entry traverses a symbolic link");
            }
            cursor = cursor.getParent();
            if (cursor == null) break;
        }
    }

    @Override
    public SourceDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Optional<InputStream> open(LogicalPath path) throws IOException {
        if (closed) throw failure(SourceAccessException.Reason.SOURCE_CLOSED, path, "content source is closed");
        if (!files.containsKey(path)) return Optional.empty();
        verifyPath(path);
        JarResource resource = contents.get(path.value());
        if (resource == null) return Optional.empty();
        try {
            return Optional.of(new LimitedInputStream(resource.open(), descriptor, path, limits.limitFor(path)));
        } catch (IOException failure) {
            throw new SourceAccessException(SourceAccessException.Reason.IO_ERROR, descriptor, path,
                    "cannot read mod entry: " + path, failure);
        }
    }

    @Override
    public boolean exists(LogicalPath path) {
        ensureOpen();
        return files.containsKey(path);
    }

    @Override
    public List<LocatedFile> files() {
        ensureOpen();
        return List.copyOf(files.values());
    }

    @Override
    public void close() {
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("content source is closed: " + descriptor.diagnosticLabel());
    }

    private SourceAccessException failure(SourceAccessException.Reason reason, LogicalPath path, String detail) {
        return new SourceAccessException(reason, descriptor, path, detail + ": " + path);
    }
}
