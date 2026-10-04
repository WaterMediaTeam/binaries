package org.watermedia.binaries;

import org.apache.logging.log4j.Logger;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.WaterMediaModule;
import org.watermedia.tools.LogTool;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Provisions native files through WaterMedia's module lifecycle. */
public final class WaterMediaBinaries extends WaterMediaModule {
    public static final String ID = "watermedia_binaries";
    public static final String NAME = "WaterMedia: Binaries";
    public static final String FFMPEG_ID = "ffmpeg";
    public static final String YTDLP_ID = "yt-dlp";
    public static final String BOTGUARD_ID = "botguard";
    public static final Logger LOGGER = LogTool.logger(ID);
    private static volatile Map<String, Path> paths = Map.of();

    @Override
    protected void start(final WaterMedia context) throws IOException {
        final Path root = context.tmp;
        paths = Map.of(YTDLP_ID, root.resolve(YTDLP_ID), BOTGUARD_ID, root.resolve(BOTGUARD_ID));
        if (WaterMediaConfig.media.ffmpeg.disable) return;
        this.task(1, 1, "FFmpeg");
        try {
            final Path installed = FFmpegBinaries.start(root.resolve(FFMPEG_ID), this);
            paths = Map.of(FFMPEG_ID, installed, YTDLP_ID, root.resolve(YTDLP_ID), BOTGUARD_ID, root.resolve(BOTGUARD_ID));
        } finally {
            this.work("", 0, 0, false);
        }
    }

    // EXTRACTION REPORTS INTO THE SAME MODULE SNAPSHOT AS THE REST OF WATERMEDIA.
    void progress(final String name, final long done, final long total) {
        this.work(name, done, total, false);
    }

    /** Returns a registered cache or provisioned native directory. */
    public static Path pathOf(final String id) {
        return paths.get(id);
    }

    @Override
    protected void release(final WaterMedia context) {
        paths = Map.of();
    }

    static Path binaryDir(final String id) throws IOException {
        final Path result = paths.get(id);
        if (result == null) throw new IOException("WaterMedia Binaries is not initialized: " + id);
        return result;
    }
}
