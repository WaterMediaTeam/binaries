package org.watermedia.binaries;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.binaries.manager.BinaryManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public class WaterMediaBinaries {
    public static final String ID = "waterbinaries";
    public static final String NAME = "WaterMedia Binaries";
    public static final String FFMPEG_ID = "ffmpeg";
    public static final String LIBVLC_ID = "libvlc";
    public static final Logger LOGGER = LogManager.getLogger(ID);
    private static final Marker IT = MarkerManager.getMarker(WaterMediaBinaries.class.getSimpleName());

    private static final ServiceLoader<BinaryManager> MANAGERS = ServiceLoader.load(BinaryManager.class);

    private static boolean initialized = false;

    public static synchronized void start(final String name, final Path tmp, final Path cwd, final boolean clientSide) {
        LOGGER.info(IT, "Starting WaterMedia Binaries initialization");
        if (initialized) {
            LOGGER.warn(IT, "WaterMedia Binaries already initialized");
            return;
        }

        int count = 0;
        for (final BinaryManager manager: MANAGERS) {
            LOGGER.debug(IT, "Registered binary manager: {}", manager.name());
            count++;
        }

        if (count == 0) {
            LOGGER.warn(IT, "No Binary Managers found!");
        } else {
            LOGGER.info(IT, "Total Binary Managers registered: {}", count);
        }

        final Path baseDir = tmp != null ? tmp : cwd;

        if (baseDir == null) {
            LOGGER.error(IT, "No valid base directory provided");
            return;
        }

        int successful = 0;
        int failed = 0;

        for (final BinaryManager manager: MANAGERS) {
            try {
                LOGGER.info(IT, "Extracting binaries for: {}", manager.name());

                if (manager.extract(baseDir)) {
                    successful++;
                    LOGGER.info(IT, "{} extraction completed successfully", manager.name());
                } else {
                    failed++;
                    LOGGER.warn(IT, "{} extraction failed or not available", manager.name());
                }

            } catch (final Exception e) {
                failed++;
                LOGGER.error(IT, "Failed to extract binaries for: {}", manager.name(), e);
            }
        }

        LOGGER.info(IT, "Binary extraction complete - Successful: {}, Failed: {}", successful, failed);

        initialized = true;
    }

    public static synchronized Path getBinaryPath(final String name) {
        if (!initialized) {
            LOGGER.warn(IT, "WaterMedia Binaries not initialized. Call start() first.");
            return null;
        }

        for (final BinaryManager manager: MANAGERS) {
            if (manager.name().equalsIgnoreCase(name)) {
                if (manager.ready()) {
                    LOGGER.debug(IT, "Binary path for {}: {}", name, manager.path().toAbsolutePath());
                    return manager.path().toAbsolutePath();
                } else {
                    LOGGER.warn(IT, "Binary manager {} is not ready", name);
                    return null;
                }
            }
        }

        LOGGER.warn(IT, "No binary manager found for name: {}", name);
        return null;
    }

    public static synchronized void cleanup(final boolean force) {
        LOGGER.info(IT, "Cleaning up all binaries (force={})", force);

        for (final BinaryManager manager: MANAGERS) {
            try {
                manager.cleanup(force);
                LOGGER.debug(IT, "Cleaned up {}", manager.name());
            } catch (final Exception e) {
                LOGGER.error(IT, "Failed to cleanup {}", manager.name(), e);
            }
        }

        if (force) {
            initialized = false;
        }
    }
}