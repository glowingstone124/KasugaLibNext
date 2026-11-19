package lib.kasuga.core.resource.pack;

import lib.kasuga.core.resource.identity.ResourcePaths;
import lib.kasuga.core.resource.lifecycle.*;
import lib.kasuga.core.resource.routing.ResourceRouting;
import lib.kasuga.core.resource.source.*;
import lib.kasuga.core.resource.view.ResourceCatalog;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Register at host bootstrap; the installed finder participates in initial and later repository discovery. */
public final class ApprovedResourcePacks implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApprovedResourcePacks.class);

    private record Registration(String id, List<ResourceSource> sources, ResourceRouting plan) {}
    private final Map<String, Registration> registrations = new LinkedHashMap<>();
    private final List<ResourceSnapshot> clientSnapshots = new ArrayList<>(), serverSnapshots = new ArrayList<>();
    private final AtomicLong generation = new AtomicLong();
    private boolean closed;
    public synchronized AutoCloseable register(String id, List<ResourceSource> sources, ResourceRouting plan) {
        ResourcePaths.id(id);
        Registration registration = new Registration(id, List.copyOf(sources), Objects.requireNonNull(plan));
        if (closed || registrations.putIfAbsent(id, registration) != null) throw new IllegalStateException("Closed/duplicate resource pack registration");
        return () -> { synchronized (this) { registrations.remove(id, registration); } };
    }
    public void onAddPackFinders(AddPackFindersEvent event) { event.addRepositorySource(repositorySource(event.getPackType())); }
    public RepositorySource repositorySource(PackType type) { return output -> discover(type, output); }
    private synchronized void discover(PackType type, Consumer<Pack> output) {
        if (closed) throw new IllegalStateException("Resource pack registry closed");
        record OrderedPack(Pack pack, int rank) {}
        List<ResourceSnapshot> pending = new ArrayList<>(); List<OrderedPack> discovered = new ArrayList<>();
        Map<String, Map<Integer, String>> targets = new HashMap<>();
        try {
            for (Registration registration : registrations.values()) {
                List<ResourceSnapshot> captured = new ArrayList<>();
                for (ResourceSource source : registration.sources()) { ResourceSnapshot snapshot = source.capture(); captured.add(snapshot); pending.add(snapshot); }
                ResourceCatalog catalog = new ResourceCatalog("mc/" + type.name() + "/" + registration.id(), generation.incrementAndGet(), captured);
                var side = type == PackType.CLIENT_RESOURCES ? LogicalSide.CLIENT : LogicalSide.SERVER;
                List<ResourceRouting.Route> routes = registration.plan().route(catalog, ResourceEvent.RESOURCE_RELOAD, side).stream()
                        .filter(r -> r.output().entryPointId().equals(ResourceRouting.MINECRAFT_ENTRY_POINT)).toList();
                Map<String, List<ResourceRouting.Route>> layers = new LinkedHashMap<>();
                routes.stream().sorted(Comparator.comparing(ResourceRouting.Route::file, ResourceCatalog.ORDER)).forEach(route -> {
                    var d = route.file().descriptor();
                    String previous = targets.computeIfAbsent(route.targetPath(), ignored -> new HashMap<>()).putIfAbsent(d.source().rank(), d.uri().toString());
                    if (previous != null) throw new IllegalArgumentException("ROUTING_CONFLICT: equal rank/duplicate projected providers");
                    layers.computeIfAbsent(d.uri().sourceId() + "/" + d.uri().rootId(), ignored -> new ArrayList<>()).add(route);
                });
                for (var layer : layers.entrySet()) {
                    ResourceSourceDescriptor source = layer.getValue().getFirst().file().descriptor().source();
                    List<ResourceRouting.Route> inputs = List.copyOf(layer.getValue());
                    String id = "kasuga/" + registration.id() + "/" + source.sourceId() + "/" + source.rootId();
                    Pack pack = PlatformPacks.create(id, inputs, type);
                    if (pack == null) throw new IOException("Invalid projected pack metadata: " + id);
                    discovered.add(new OrderedPack(pack, source.rank()));
                }
            }
            discovered.sort(Comparator.comparingInt(OrderedPack::rank).thenComparing(p -> p.pack().getId()));
            List<Pack> ordered = new ArrayList<>(discovered.size());
            for (OrderedPack entry : discovered) ordered.add(entry.pack());
            PlatformPacks.publish(type, ordered, output);
            List<ResourceSnapshot> active = type == PackType.CLIENT_RESOURCES ? clientSnapshots : serverSnapshots;
            List<ResourceSnapshot> retired = new ArrayList<>(active);
            active.clear();
            active.addAll(pending);
            pending.clear();
            try {
                closeSnapshots(retired);
            } catch (UncheckedIOException e) {
                LOGGER.warn("Could not clean up retired resource pack snapshots", e);
            }
        } catch (IOException e) { throw new UncheckedIOException("Resource projection failed", e); }
        finally { closeSnapshots(pending); }
    }
    private static void closeSnapshots(List<ResourceSnapshot> snapshots) {
        UncheckedIOException failure = null;
        for (ResourceSnapshot snapshot : snapshots) try { snapshot.close(); }
        catch (IOException e) { if (failure == null) failure = new UncheckedIOException(e); else failure.addSuppressed(e); }
        snapshots.clear(); if (failure != null) throw failure;
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true; registrations.clear();
        try { closeSnapshots(clientSnapshots); } finally { closeSnapshots(serverSnapshots); }
    }
}
