package lib.kasuga.core.resource;

import lib.kasuga.core.resource.pack.PackScopes;
import lib.kasuga.core.resource.pack.ScopedPackResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ScopedResourceManager implements ResourceManagerReloadListener {
    private record State(ResourceManager manager, List<ScopedPackResources> packs) {}
    private volatile State state;
    private final List<ScopedResourcePackListener> listeners = new CopyOnWriteArrayList<>();

    public ScopedResourceManager(ResourceManager resourceManager) {
        reload(resourceManager);
    }

    public void reload(ResourceManager rm) {
        this.state = new State(rm, PackScopes.adapt(rm));
        for (ScopedResourcePackListener listener : listeners) {
            listener.onReloaded(this);
        }
    }

    /** The vanilla resource manager this scoped manager wraps. */
    public ResourceManager getResourceManager() {
        return state.manager();
    }

    public lib.kasuga.core.resource.pack.McManagedResourceView managedView(net.minecraft.server.packs.PackType type) {
        return new lib.kasuga.core.resource.pack.McManagedResourceView(this::getResourceManager, type);
    }

    @Override
    public void onResourceManagerReload(@NotNull ResourceManager rm) {
        this.reload(rm);
    }

    public List<ScopedPackResources> getPackResources() {
        return state.packs();
    }

    public void addListener(ScopedResourcePackListener listener) {
        listeners.add(listener);
        listener.onReloaded(this);
    }
}
