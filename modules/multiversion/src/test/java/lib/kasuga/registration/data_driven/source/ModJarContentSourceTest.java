package lib.kasuga.registration.data_driven.source;

import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.neoforgespi.locating.IModFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModJarContentSourceTest {
    @Test
    void readsTheLoaderViewAndKeepsItOpen(@TempDir Path root) throws Exception {
        Path first = Files.createDirectory(root.resolve("first"));
        Path second = Files.createDirectory(root.resolve("second"));
        String shared = "data/demo/shared.json";
        String secondary = "data/demo/secondary.json";
        Files.createDirectories(first.resolve("data/demo"));
        Files.createDirectories(second.resolve("data/demo"));
        Files.writeString(first.resolve(shared), "first");
        Files.writeString(second.resolve(shared), "second");
        Files.writeString(second.resolve(secondary), "secondary");
        try (JarContents contents = JarContents.ofPaths(List.of(first, second))) {
            IModFile file = modFile(contents);
            SourceDescriptor descriptor = SourceDescriptor.of("demo", "demo", List.of("demo"));
            try (ContentSource source = ModJarContentSource.open(file, descriptor)) {
                assertEquals(List.of(secondary, shared), source.files().stream().map(entry -> entry.path().value()).toList());
                try (java.io.InputStream input = source.open(LogicalPath.of(shared)).orElseThrow()) {
                    assertEquals(new String(contents.readFile(shared), StandardCharsets.UTF_8),
                            new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
                assertTrue(source.open(LogicalPath.of("data/demo/missing.json")).isEmpty());
            }
            assertTrue(contents.containsFile(secondary));
            SourceAccessException limit = assertThrows(SourceAccessException.class,
                    () -> ModJarContentSource.open(file, descriptor, new SourceLimits(64, 64, 64, 1)));
            assertEquals(SourceAccessException.Reason.LIMIT_EXCEEDED, limit.reason());
        }
    }

    private static IModFile modFile(JarContents contents) {
        return (IModFile) Proxy.newProxyInstance(IModFile.class.getClassLoader(), new Class<?>[]{IModFile.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getContents")) return contents;
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
