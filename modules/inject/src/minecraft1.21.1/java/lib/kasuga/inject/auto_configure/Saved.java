package lib.kasuga.inject.auto_configure;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.HashMap;
import java.util.Map;

public interface Saved<T extends SavedData> {
    public class Impl<T extends SavedData> implements Saved<T> {
        private final SavedData.Factory<T> factory;
        private MinecraftServer server;
        private final String name;

        private Map<ResourceKey<Level>, T> dataMap = new HashMap<>();

        public Impl(String name, SavedData.Factory<T> factory) {
            this.name = name;
            this.factory = factory;
        }

        public T get(ResourceKey<Level> dimension) {
            if(server == null) {
                throw new IllegalStateException("The level is not loaded yet.");
            }
            return dataMap.computeIfAbsent(dimension, (dim)->{
                ServerLevel level = server.getLevel(dim);
                if(level == null) {
                    throw new IllegalArgumentException("The dimension " + dim + " is not loaded.");
                }
                return level.getDataStorage().computeIfAbsent(factory, name);
            });
        }


        public void onLoad(MinecraftServer server) {
            onUnload();
            this.server = server;
        }

        public void onUnload() {
            this.server = null;
            this.dataMap.clear();
        }

        public void onServerStart(ServerStartedEvent startedEvent) {
            this.onLoad(startedEvent.getServer());
        }

        public void onServerStop(ServerStoppingEvent event) {
            this.onUnload();
        }

        @Override
        public T get() {
            return get(Level.OVERWORLD);
        }
    }

    T get();

    T get(ResourceKey<Level> levelResourceKey);

    public void onLoad(MinecraftServer server);
}
