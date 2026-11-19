package lib.kasuga.core.resource;

import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;

public interface ScopedResourceManagerConsumer {
    public void onResourceManagerAdded(@Nullable MinecraftServer server, ScopedResourceManager resourceManager);
    public void onResourceManagerRemoved(@Nullable MinecraftServer server, ScopedResourceManager resourceManager);
}
