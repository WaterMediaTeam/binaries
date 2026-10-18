package org.watermedia.binaries;

import org.apache.logging.log4j.Logger;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.bootstrap.WaterMediaModule;
import org.watermedia.tools.LogTool;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Provisions native files when WaterMedia loads and keeps their locations for the life of the process. */
public final class WaterMediaBinaries extends WaterMediaModule {
    public static final String ID = "watermedia_binaries";
    public static final String NAME = "WaterMedia: Binaries";
    public static final String FFMPEG_ID = "ffmpeg";
    public static final String YTDLP_ID = "yt-dlp";
    public static final String BOTGUARD_ID = "botguard";
    public static final Logger LOGGER = LogTool.logger(ID);
    private static volatile Map<String, Path> paths = Map.of();

    @Override
    protected void start() throws IOException {
        final Path root = WaterMedia.tmp();
        paths = Map.of(YTDLP_ID, root.resolve(YTDLP_ID), BOTGUARD_ID, root.resolve(BOTGUARD_ID));
        if (WaterMediaConfig.media.ffmpeg.disable) return;
        final Path installed = FFmpegBinaries.start(root.resolve(FFMPEG_ID));
        paths = Map.of(FFMPEG_ID, installed, YTDLP_ID, root.resolve(YTDLP_ID), BOTGUARD_ID, root.resolve(BOTGUARD_ID));
    }

    /** Returns a registered cache or provisioned native directory. */
    public static Path pathOf(final String id) {
        return paths.get(id);
    }

    static Path binaryDir(final String id) throws IOException {
        final Path result = paths.get(id);
        if (result == null) throw new IOException("WaterMedia Binaries is not initialized: " + id);
        return result;
    }
}
