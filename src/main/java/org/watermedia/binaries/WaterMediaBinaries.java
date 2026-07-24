package org.watermedia.binaries;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaModule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binaries module: resolves the per-binary cache directories and extracts the
 * shipped FFmpeg natives. Booted by {@link WaterMedia} as the first module so
 * the media engine finds its files on disk.
 */
public class WaterMediaBinaries extends WaterMediaModule {
    private static final Marker IT = MarkerManager.getMarker(WaterMediaBinaries.class.getSimpleName());
    public static final String ID = "watermedia_binaries";
    public static final String NAME = "WaterMedia: Binaries";
    public static final String FFMPEG_ID = "ffmpeg";
    // yt-dlp AND rustypipe-botguard ARE DOWNLOADED LAZILY ON FIRST USE (NOT EXTRACTED AT STARTUP LIKE
    // FFMPEG); ONLY THEIR CACHE DIRECTORIES ARE REGISTERED HERE FOR YtDlpBinary/BotGuardBinary TO RESOLVE.
    public static final String YTDLP_ID = "yt-dlp";
    public static final String BOTGUARD_ID = "botguard";
    public static final Logger LOGGER = LogManager.getLogger(ID);
    private static final String STEP_FFMPEG = "FFMPEG";
    // CONCURRENT: WRITTEN ONCE AT BOOT BY resolve(), READ LATER FROM MEDIA-RESOLUTION THREADS VIA
    // pathOf()/binaryDir() (THE LAZILY-DOWNLOADED yt-dlp/botguard BINARIES RESOLVE OFF-THREAD)
    private static final Map<String, Path> BINARY_PATHS = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return WaterMediaBinaries.class.getSimpleName();
    }

    @Override
    protected void load(final WaterMedia instance) {
        super.load(instance);
        this.steps = instance.clientSide ? 1 : 0; // FFMPEG EXTRACTION
    }

    @Override
    protected boolean start(final WaterMedia instance) {
        resolve(instance.tmp);
        if (!instance.clientSide) {
            LOGGER.info(IT, "Skipping binaries extraction on server-side environment");
            return true;
        }

        this.step++;
        this.stepName = STEP_FFMPEG;
        LOGGER.info(IT, "Starting binaries extraction in path: {}", instance.tmp.toAbsolutePath());
        try {
            if (!FFmpegBinaries.start(BINARY_PATHS.get(FFMPEG_ID), this)) this.failures.add(STEP_FFMPEG);
        } catch (final Exception e) {
            LOGGER.error(IT, "Exception occurred for {}", FFMPEG_ID, e);
            this.failures.add(STEP_FFMPEG);
        } finally {
            // EXTRACTION IS OVER EITHER WAY — DEACTIVATE THE DIMENSION PROGRESS BAR
            this.work = 0L;
            this.workTotal = 0L;
            this.workName = "";
        }
        return true;
    }

    // PACKAGE HOOK FOR FFmpegBinaries TO PUBLISH EXTRACTION PROGRESS (BYTES OF THE SHIPPED ZIP)
    void work(final String name, final long done, final long total) {
        this.workName = name;
        this.work = done;
        this.workTotal = total;
    }

    /**
     * Resolves and publishes the per-binary cache directories under {@code baseDir}. Runs as part
     * of the module boot; also callable standalone (e.g. probes) to enable the lazily-downloaded
     * binaries without booting the full stack.
     */
    public static void resolve(final Path baseDir) {
        LOGGER.info(IT, "Resolving binaries paths");
        BINARY_PATHS.put(FFMPEG_ID, baseDir.resolve(FFMPEG_ID));
        BINARY_PATHS.put(YTDLP_ID, baseDir.resolve(YTDLP_ID));
        BINARY_PATHS.put(BOTGUARD_ID, baseDir.resolve(BOTGUARD_ID));
    }

    public static synchronized void cleanup() {
        if (BINARY_PATHS.isEmpty()) {
            LOGGER.warn(IT, "Binaries paths not initialized, skipping cleanup");
            return;
        }

        LOGGER.info(IT, "Starting binaries cleanup");
        try {
            FFmpegBinaries.cleanup(BINARY_PATHS.get(FFMPEG_ID));
        } catch (final Exception e) {
            LOGGER.error(IT, "Exception occurred for {}", FFMPEG_ID, e);
        }
    }

    public static Path pathOf(String id) {
        return BINARY_PATHS.get(id);
    }

    // RESOLVES A REGISTERED BINARY CACHE DIRECTORY, FAILING CLEANLY IF resolve() HAS NOT RUN YET (THE
    // LAZILY-DOWNLOADED BINARIES NEED THE BASE DIR IT PUBLISHES). USED BY YtDlpBinary/BotGuardBinary.
    static Path binaryDir(final String id) throws IOException {
        final Path dir = BINARY_PATHS.get(id);
        if (dir == null) {
            throw new IOException("WaterMedia Binaries is not initialized; cannot resolve '" + id + "'");
        }
        return dir;
    }
}
