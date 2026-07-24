package org.watermedia.binaries;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.VersionTool;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.Objects;

import static org.watermedia.binaries.WaterMediaBinaries.LOGGER;

class FFmpegBinaries {
    private static final Marker IT = MarkerManager.getMarker(FFmpegBinaries.class.getSimpleName());
    private static final String RESOURCE_PATH = "libs/ffmpeg-%s.zip";

    static boolean start(final Path baseDir, final WaterMediaBinaries module) throws Exception {
        final String platform = IOTool.platformClassifier();
        final String resourcePath = String.format(RESOURCE_PATH, platform);
        final ClassLoader loader = FFmpegBinaries.class.getClassLoader();

        // CHECK VERSION
        final var zipVersion = new VersionTool(IOTool.jarReadZip(loader.getResourceAsStream(resourcePath), IOTool.VERSION_FILE));
        final var currentVersion = new VersionTool(IOTool.read(baseDir.resolve(IOTool.VERSION_FILE).toFile()));

        LOGGER.info(IT, "FFMPEG in JAR | Version: {} - Path: {}", zipVersion, resourcePath);
        LOGGER.info(IT, "FFMPEG extracted | Version: {} - Path: {}", currentVersion, baseDir);
        // RE-EXTRACT WHEN NOTHING IS ON DISK, THE EXTRACTED BUILD IS OLDER, OR IT SHARES THE SAME
        // NUMERIC VERSION BUT A DIFFERENT BUILD QUALIFIER (E.G. LGPL -> GPL). VersionTool.compareTo
        // ONLY WEIGHS major.minor.revision, SO THE EXTRA-FIELD CHECK IS NEEDED TO CATCH SAME-VERSION SWAPS.
        final boolean stale = currentVersion.isZero()
                || !currentVersion.atLeast(zipVersion)
                || (currentVersion.compareTo(zipVersion) == 0 && !Objects.equals(currentVersion.extra, zipVersion.extra));
        if (zipVersion.isZero()) {
            // NO BUNDLED ZIP FOR THIS PLATFORM (MISSING RESOURCE OR UNREADABLE): A VALID EXTRACTED
            // INSTALL STILL WORKS, BUT WITH NOTHING ON DISK EITHER THERE IS NOTHING TO LOAD
            if (currentVersion.isZero()) {
                LOGGER.error(IT, "FFmpeg not bundled at {} and no extracted copy at {}", resourcePath, baseDir);
                return false;
            }
            LOGGER.warn(IT, "FFmpeg not bundled at {}, keeping extracted {}", resourcePath, currentVersion);
        } else if (stale) {
            LOGGER.info(IT, "Starting FFmpeg {} extraction for platform {}", zipVersion, platform);

            // CLEANUP OLD VERSION
            if (!cleanup(baseDir)) {
                return  false;
            }

            // EXTRACT NEW VERSION — COUNT THE COMPRESSED BYTES READ FROM THE SHIPPED ZIP AGAINST ITS
            // RESOURCE SIZE SO THE BOOT UI CAN DRAW A REAL PROGRESS BAR (0 TOTAL = INDETERMINATE)
            final long totalBytes = resourceSize(loader.getResource(resourcePath));
            final String zipName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
            module.work(zipName, 0L, totalBytes);
            final InputStream counted = new FilterInputStream(loader.getResourceAsStream(resourcePath)) {
                private long count;

                @Override
                public int read() throws IOException {
                    final int b = super.read();
                    if (b >= 0) module.work(zipName, ++this.count, totalBytes);
                    return b;
                }

                @Override
                public int read(final byte[] buf, final int off, final int len) throws IOException {
                    final int n = super.read(buf, off, len);
                    if (n > 0) module.work(zipName, this.count += n, totalBytes);
                    return n;
                }
            };
            if (IOTool.jarExtractZip(counted, baseDir.toFile())) {
                // THE ZIP TAIL (CENTRAL DIRECTORY) MAY STAY UNREAD — SNAP THE BAR TO ITS END
                module.work(zipName, totalBytes, totalBytes);
                LOGGER.info(IT, "Successfully extracted FFmpeg {}", zipVersion);
            } else {
                LOGGER.error(IT, "Failed to extract FFmpeg {} for platform", zipVersion);
                return false;
            }
        } else {
            LOGGER.info(IT, "FFmpeg already up to date, skipping extraction");
        }

        // VERIFY EXTRACTION
        if (IOTool.count(baseDir.resolve(IOTool.VERSION_FILE).toFile()) != 1) {
            LOGGER.error(IT, "FFmpeg version file missing after extraction");
            return false;
        }

        return true;
    }

    static boolean cleanup(final Path baseDir) {
        final int count = IOTool.count(baseDir.toFile());
        if (count > 0) {
            LOGGER.info(IT, "Cleaning up {} of current FFmpeg files", count);
            final int deleted = IOTool.delete(baseDir.toFile());
            if (deleted == count) {
                LOGGER.info(IT, "Successfully deleted {} files", deleted);
            } else if (deleted > 0) {
                LOGGER.warn(IT, "Deleted {} out of {} files", deleted, count);
            } else {
                LOGGER.error(IT, "Failed to delete FFmpeg files");
                return false;
            }
        } else {
            LOGGER.info(IT, "No FFmpeg files to clean up");
        }
        return true;
    }

    // SIZE OF THE SHIPPED ZIP RESOURCE, OR 0 WHEN UNKNOWN (JAR AND FILE URLS BOTH REPORT IT)
    private static long resourceSize(final URL resource) {
        if (resource == null) return 0L;
        try {
            return Math.max(0L, resource.openConnection().getContentLengthLong());
        } catch (final IOException e) {
            return 0L;
        }
    }
}
