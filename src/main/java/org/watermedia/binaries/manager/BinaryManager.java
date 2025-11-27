package org.watermedia.binaries.manager;

import java.nio.file.Path;

public interface BinaryManager {

    String name();

    boolean extract(Path baseDir) throws Exception;

    Path path();

    boolean ready();

    void cleanup(boolean force);
}
