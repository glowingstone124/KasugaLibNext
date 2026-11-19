package lib.kasuga.core.resource.pack;

import java.util.stream.Stream;

public interface FlattenScopedPackResources extends ScopedPackResources {

    public Stream<String> listEntries(String prefix);
}
