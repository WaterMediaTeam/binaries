package org.watermedia.binaries;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.binaries.manager.BinaryManager;
import org.watermedia.binaries.manager.FFmpegManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class WaterMediaBinaries {
    public static final String ID = "wm_binaries";
    public static final String NAME = "WaterMedia Binaries";
    public static final String
            FFMPEG_ID = "ffmpeg";

    public static final Logger LOGGER = LogManager.getLogger(ID);
    private static final List<BinaryManager> MANAGERS = new ArrayList<>();
    private static final Marker IT = MarkerManager.getMarker(WaterMediaBinaries.class.getSimpleName());

    static {
        LOGGER.info(IT, "Loading Binary Managers");
        MANAGERS.add(new FFmpegManager());
    }

    public static synchronized void start(final String name, final Path tmp, final Path cwd, final boolean clientSide) {
        final Path dir = tmp != null ? tmp : cwd;
        if (dir == null || clientSide) {
            LOGGER.error(IT, "Failed to run Binaries extraction");
            return;
        }

        LOGGER.info(IT, "Starting Binaries extraction on {} in path: {}", name, dir.toAbsolutePath());
        for (final BinaryManager manager: MANAGERS) {
            try {
                LOGGER.info(IT, "Extracting binaries for: {}", manager.name());
                LOGGER.info(IT, "{} extraction {}", manager.name(), manager.extract(dir) ? "succeeded" : "failed");
            } catch (final Exception e) {
                LOGGER.error(IT, "Failed to extract binaries for: {}", manager.name(), e);
            }
        }
    }

    public static synchronized Path pathOf(final String name) {
        for (final BinaryManager manager: MANAGERS) {
            if (manager.name().equalsIgnoreCase(name)) {
                if (manager.ready()) {
                    LOGGER.debug(IT, "Binary path for {}: {}", name, manager.path().toAbsolutePath());
                    return manager.path().toAbsolutePath();
                } else {
                    LOGGER.error(IT, "Binary manager {} is not ready", name);
                    return null;
                }
            }
        }

        LOGGER.error(IT, "Failed to get binary manager for name: {}", name);
        return null;
    }

    public static synchronized void cleanup(final boolean force) {
        LOGGER.info(IT, "Starting cleanup for all binaries (force={})", force);

        for (final BinaryManager manager: MANAGERS) {
            try {
                manager.cleanup(force);
                LOGGER.info(IT, "Cleaned up {}", manager.name());
            } catch (final Exception e) {
                LOGGER.error(IT, "Failed to cleanup {}", manager.name(), e);
            }
        }
    }
}