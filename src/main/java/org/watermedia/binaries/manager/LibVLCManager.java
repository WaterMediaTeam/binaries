package org.watermedia.binaries.manager;

import com.sun.jna.Platform;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.binaries.tools.IOTools;

import static org.watermedia.binaries.WaterMediaBinaries.LOGGER;

public class LibVLCManager implements BinaryManager {
    private static final Marker IT = MarkerManager.getMarker(LibVLCManager.class.getSimpleName());
    private static final String RESOURCE_PATH = "/libs/libvlc-%s.zip";

    private Path extractPath;
    private boolean isReady = false;

    @Override
    public String name() {
        return WaterMediaBinaries.LIBVLC_ID;
    }

    @Override
    public boolean extract(final Path baseDir) throws Exception {
        LOGGER.info(IT, "Starting LibVLC extraction process");

        // LibVLC only available for Windows x64 currently
        if (!Platform.isWindows() || !Platform.is64Bit()) {
            LOGGER.warn(IT, "LibVLC not available for current platform: OS={}, 64bit={}", Platform.getOSType(), Platform.is64Bit());
            return false;
        }

        final String resource = String.format(RESOURCE_PATH, "win-x86_64");
        this.extractPath = baseDir.resolve("watermedia").resolve(WaterMediaBinaries.LIBVLC_ID);

        // Check if extraction is needed
        if (IOTools.shouldExtract(this.extractPath, resource, IT)) {
            LOGGER.info(IT, "Extraction required for Windows x64");

            // Clean up old version if exists
            if (Files.exists(this.extractPath)) {
                LOGGER.info(IT, "Removing old version");
                IOTools.cleanup(this.extractPath, true, IT);
            }

            // Extract new version
            try (final InputStream is = this.getClass().getResourceAsStream(resource)) {
                if (is == null) {
                    LOGGER.error(IT, "Resource not found: {}", resource);
                    return false;
                }

                if (!IOTools.extract(is, this.extractPath, IT)) {
                    LOGGER.error(IT, "Extraction failed");
                    return false;
                }
            }

            LOGGER.info(IT, "LibVLC extracted successfully");
        } else {
            LOGGER.info(IT, "LibVLC already up to date, skipping extraction");
        }

        this.isReady = Files.exists(this.extractPath);

        if (this.isReady) {
            final String version = IOTools.readVersion(this.extractPath);
            LOGGER.info(IT, "LibVLC ready - Version: {}", version != null ? version : "unknown");
        }

        return this.isReady;
    }

    @Override
    public Path path() {
        return this.extractPath;
    }

    @Override
    public boolean ready() {
        return this.isReady;
    }

    @Override
    public void cleanup(final boolean force) {
        LOGGER.debug(IT, "Cleanup requested (force={})", force);

        if (this.extractPath != null) {
            IOTools.cleanup(this.extractPath, force, IT);
            if (force) {
                this.isReady = false;
                this.extractPath = null;
            }
        }
    }
}