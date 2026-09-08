package org.watermedia.binaries;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Provides native files and verified executable downloads independently of the host lifecycle. */
public final class WaterMediaBinaries {
    public static final String ID = "watermedia_binaries";
    public static final String NAME = "WaterMedia: Binaries";
    public static final String FFMPEG_ID = "ffmpeg";
    public static final String YTDLP_ID = "yt-dlp";
    public static final String BOTGUARD_ID = "botguard";
    public static final Logger LOGGER = LogManager.getLogger(ID);
    private static volatile Map<String, Path> paths = Map.of();
    private static Path root;

    private WaterMediaBinaries() {}

    /** Receives provisioning progress on the calling thread. */
    @FunctionalInterface
    public interface Progress {
        void update(String name, long done, long total);
    }

    /** Sets the cache root before native provisioning or executable resolution. */
    public static synchronized void resolve(final Path base) {
        root = Objects.requireNonNull(base).toAbsolutePath().normalize();
        paths = Map.of(FFMPEG_ID, root.resolve(FFMPEG_ID), YTDLP_ID, root.resolve(YTDLP_ID), BOTGUARD_ID, root.resolve(BOTGUARD_ID));
    }

    /** Publishes the FFmpeg directory only after a complete, verified installation. */
    public static synchronized void provision(final Progress progress) throws IOException {
        if (root == null) throw new IOException("WaterMedia Binaries is not initialized");
        final Path installed = FFmpegBinaries.start(root.resolve(FFMPEG_ID), progress == null ? (name, done, total) -> {} : progress);
        paths = Map.of(FFMPEG_ID, installed, YTDLP_ID, binaryDir(YTDLP_ID), BOTGUARD_ID, binaryDir(BOTGUARD_ID));
    }

    /** Returns a registered cache or provisioned native directory. */
    public static Path pathOf(final String id) {
        return paths.get(id);
    }

    /** Releases host bindings after consumers stop; installed files remain available for reuse. */
    public static synchronized void release() {
        paths = Map.of();
        root = null;
    }

    static Path binaryDir(final String id) throws IOException {
        final Path result = paths.get(id);
        if (result == null) throw new IOException("WaterMedia Binaries is not initialized: " + id);
        return result;
    }
}
