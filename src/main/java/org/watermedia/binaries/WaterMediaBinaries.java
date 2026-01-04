package org.watermedia.binaries;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

public class WaterMediaBinaries {
    private static final Marker IT = MarkerManager.getMarker(WaterMediaBinaries.class.getSimpleName());
    public static final String ID = "wm_binaries";
    public static final String NAME = "WaterMedia: Binaries";
    public static final String FFMPEG_ID = "ffmpeg";
    public static final Logger LOGGER = LogManager.getLogger(ID);
    private static final Map<String, Path> BINARY_PATHS = new HashMap<>();

    public static synchronized void start(final String name, final Path tmp, final Path cwd, final boolean clientSide) {
        final var baseDir = tmp != null ? tmp : cwd;
        if (baseDir == null) {
            LOGGER.error(IT, "Failed to start WaterMedia Binaries, no valid base directory (tmp or cwd)");
            return;
        }

        // RESOLVING PATHS
        LOGGER.info(IT, "Resolving binaries paths");
        BINARY_PATHS.put(FFMPEG_ID, baseDir.resolve(FFMPEG_ID));

        // STARTING BINARIES
        LOGGER.info(IT, "Starting binaries extraction in path: {}", baseDir.toAbsolutePath());
        if (clientSide) execute(FFMPEG_ID, () -> FFmpegBinaries.start(baseDir.resolve(FFMPEG_ID)));
    }

    public static synchronized void cleanup() {
        if (BINARY_PATHS.isEmpty()) {
            LOGGER.warn(IT, "Binaries paths not initialized, skipping cleanup");
            return;
        }

        LOGGER.info(IT, "Starting binaries cleanup");
        execute(FFMPEG_ID, () -> FFmpegBinaries.cleanup(BINARY_PATHS.get(FFMPEG_ID)));
    }

    public static Path pathOf(String id) {
        return BINARY_PATHS.get(id);
    }

    private static void execute(String id, Callable<Boolean> task) {
        try {
            task.call();
        } catch (final Exception e) {
            LOGGER.error(IT, "Exception occurred for {}", id, e);
        }
    }
}