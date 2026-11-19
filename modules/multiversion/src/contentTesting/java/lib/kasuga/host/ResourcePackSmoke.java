package lib.kasuga.host;

import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import lib.kasuga.KasugaLib;
import lib.kasuga.core.resource.ClientResourceReloadFinishEvent;
import lib.kasuga.core.resource.ResourceSystem;
import lib.kasuga.core.resource.ScopedResourceManager;
import lib.kasuga.core.resource.ServerResourceReloadFinishEvent;
import lib.kasuga.core.resource.pack.ScopedPackResources;
import lib.kasuga.core.resource.routing.ResourceRouting;
import lib.kasuga.core.resource.source.InputPolicy;
import lib.kasuga.core.resource.source.ResourceSource;
import lib.kasuga.core.resource.source.ResourceSourceDescriptor;
import lib.kasuga.core.resource.source.ResourceSources;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.backend.InMemoryVfs;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.OverlayedPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.neoforge.resource.JarContentsPackResources;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Context
@Requires(property = "kasuga.graphicsSmoke")
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ResourcePackSmoke {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier FILE = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "pack_smoke/check.txt");
    private static final VfsPath ASSET = VfsPath.of("assets/" + FILE.getNamespace() + "/" + FILE.getPath());
    private static final VfsPath DATA = VfsPath.of("data/" + FILE.getNamespace() + "/" + FILE.getPath());
    private final ResourceSystem resources;
    private final InMemoryVfs override = new InMemoryVfs("smoke_override");
    private int clientPublications;
    private int serverPublications;
    private boolean clientReloadRequested;

    public ResourcePackSmoke(ResourceSystem resources) throws IOException {
        this.resources = resources;
        InMemoryVfs base = new InMemoryVfs("smoke_base");
        base.writeString(ASSET, "base");
        base.writeString(DATA, "base");
        override.writeString(ASSET, "initial");
        override.writeString(DATA, "initial");
        ResourceSource low = source("base", 1, base);
        ResourceSource high = source("override", 2, override);
        ResourceRouting plan = ResourceRouting.decode("""
                {"format_version":1,"bindings":[{"id":"root","source_id":"pack_smoke","schema_id":"minecraft_resource_schema"}]}
                """, ResourceRouting.builtinTemplates(), Set.of("pack_smoke"));
        resources.registerManagedCloseable(low);
        resources.registerManagedCloseable(high);
        resources.getApprovedPacks().register("pack_smoke", List.of(low, high), plan);
    }

    private static ResourceSource source(String root, int rank, InMemoryVfs vfs) {
        return ResourceSources.vfs(new ResourceSourceDescriptor("smoke", "pack_smoke", root,
                rank, ResourceSourceDescriptor.Origin.EXTERNAL), vfs, true, "", "", InputPolicy.DEFAULT);
    }

    public static boolean clientReady() throws IOException {
        ResourcePackSmoke smoke = KasugaLib.getBean(ResourcePackSmoke.class);
        if (smoke.clientPublications >= 2) return true;
        if (smoke.clientPublications == 1 && !smoke.clientReloadRequested) {
            smoke.clientReloadRequested = true;
            smoke.override.writeString(ASSET, "reloaded");
            Minecraft.getInstance().getResourcePackRepository().reload();
            Minecraft.getInstance().reloadResourcePacks();
        }
        return false;
    }

    @SubscribeEvent
    public static void clientPublished(ClientResourceReloadFinishEvent event) throws IOException {
        if (!enabled()) return;
        ResourcePackSmoke smoke = KasugaLib.getBean(ResourcePackSmoke.class);
        if (smoke.clientPublications == 0) checkArchiveLayers();
        String expected = smoke.clientPublications == 0 ? "initial" : "reloaded";
        check(smoke.resources.getServerManager(null), PackType.CLIENT_RESOURCES, expected);
        smoke.clientPublications++;
        LOGGER.info("KASUGA_CLIENT_PACK_PASS pass={} value={}", smoke.clientPublications, expected);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void serverStarted(ServerStartedEvent event) throws IOException {
        if (!enabled()) return;
        ResourcePackSmoke smoke = KasugaLib.getBean(ResourcePackSmoke.class);
        MinecraftServer server = event.getServer();
        ScopedResourceManager scope = smoke.resources.getServerManager(server);
        ResourceManager first = scope.getResourceManager();
        check(scope, PackType.SERVER_DATA, "initial");
        smoke.override.writeString(DATA, "reloaded");
        server.getPackRepository().reload();
        server.reloadResources(server.getPackRepository().getSelectedIds()).join();
        if (smoke.serverPublications != 1 || scope != smoke.resources.getServerManager(server)
                || first == scope.getResourceManager() || scope.getResourceManager() != server.getResourceManager()) {
            throw new IllegalStateException("Server resource reload did not publish the replacement manager");
        }
        check(scope, PackType.SERVER_DATA, "reloaded");
        LOGGER.info("KASUGA_SERVER_PACK_PASS initial and reloaded");
    }

    @SubscribeEvent
    public static void serverPublished(ServerResourceReloadFinishEvent event) {
        if (enabled()) KasugaLib.getBean(ResourcePackSmoke.class).serverPublications++;
    }

    private static void check(ScopedResourceManager manager, PackType type, String expected) throws IOException {
        if (manager == null) throw new IllegalStateException("Missing scoped manager for " + type);
        List<String> stack = new ArrayList<>();
        for (Resource resource : manager.managedView(type).stack(FILE)) {
            try (InputStream input = resource.open()) { stack.add(read(input)); }
        }
        if (!stack.equals(List.of("base", expected))) {
            throw new IllegalStateException("Unexpected " + type + " pack priority: " + stack);
        }
        String path = type.getDirectory() + "/" + FILE.getNamespace() + "/" + FILE.getPath();
        for (ScopedPackResources pack : manager.getPackResources()) {
            if (!pack.getName().endsWith("/override") || !pack.isRegularFile("", path)) continue;
            try (InputStream input = pack.open("", path)) {
                if (expected.equals(read(input))) return;
            }
        }
        throw new IllegalStateException("Missing scoped " + type + " resource " + path);
    }

    private static void checkArchiveLayers() throws IOException {
        Path archive = Files.createTempFile("kasuga-pack-smoke-", ".zip");
        try {
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
                writeEntry(output, ASSET.asString(), "base-zip");
                writeEntry(output, "overlay/" + ASSET.asString(), "overlay-zip");
            }
            PackLocationInfo location = new PackLocationInfo("kasuga/smoke_zip", Component.literal("Smoke archive"),
                    PackSource.BUILT_IN, Optional.empty());
            FilePackResources.SharedZipFileAccess zip = new FilePackResources.SharedZipFileAccess(archive.toFile());
            OverlayedPackResources overlay = new OverlayedPackResources(
                    new FilePackResources(location, zip, ""),
                    List.of(new FilePackResources(location, zip, "overlay")));
            try (MultiPackResourceManager manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(overlay))) {
                List<ScopedPackResources> layers = new ScopedResourceManager(manager).getPackResources();
                if (layers.size() != 2) throw new IllegalStateException("Incorrect archive layer count");
                checkLayer(layers.getFirst(), "overlay-zip");
                checkLayer(layers.getLast(), "base-zip");
            }
            try (JarContents jar = JarContents.ofPath(archive);
                 MultiPackResourceManager manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES,
                         List.of(new JarContentsPackResources(location, jar, "overlay")))) {
                checkLayer(new ScopedResourceManager(manager).getPackResources().getFirst(), "overlay-zip");
            }
            LOGGER.info("KASUGA_ARCHIVE_PACK_PASS zip,overlay,mod-jar");
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    private static void checkLayer(ScopedPackResources layer, String expected) throws IOException {
        if (!layer.isRegularFile("", ASSET.asString())) {
            throw new IllegalStateException("Missing scoped archive entry " + ASSET);
        }
        try (InputStream input = layer.open("", ASSET.asString())) {
            if (!expected.equals(read(input))) throw new IllegalStateException("Incorrect archive layer bytes");
        }
    }

    private static void writeEntry(ZipOutputStream output, String path, String value) throws IOException {
        output.putNextEntry(new ZipEntry(path));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static String read(InputStream input) throws IOException {
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static boolean enabled() { return !System.getProperty("kasuga.graphicsSmoke", "").isEmpty(); }
}
