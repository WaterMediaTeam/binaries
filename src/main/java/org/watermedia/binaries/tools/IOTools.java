package org.watermedia.binaries.tools;

import com.sun.jna.Platform;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.watermedia.binaries.WaterMediaBinaries.LOGGER;

public class IOTools {
    private static final Marker IT = MarkerManager.getMarker(IOTools.class.getSimpleName());
    private static final String VERSION_FILE = "version.cfg";
    
    public static String getPlatformId() {
        final String os = Platform.isWindows()
                ? "win" : Platform.isMac()
                ? "mac" : Platform.isLinux()
                ? "linux" : null;

        final String arch = !Platform.is64Bit()
                ? null : Platform.isARM()
                ? "arm64" : Platform.isIntel()
                ? "x86_64" : null;

        if (os == null || arch == null)
            throw new UnsupportedOperationException("Unsupported system: " + System.getProperty("os.name") + "-" + Platform.ARCH);
        
        return os + "-" + arch;
    }
    
    public static boolean extract(final InputStream in, final Path target, final Marker marker) {
        try {
            LOGGER.info(marker, "Extracting to: {}", target);
            Files.createDirectories(target);

            try (final ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry entry = zip.getNextEntry();
                final byte[] buffer = new byte[131072]; // 128 KB buffer


                while (entry != null) {
                    final File file = target.resolve(entry.getName()).toFile();
                    
                    if ((entry.isDirectory() && !file.mkdirs()) || !file.getParentFile().mkdirs()) {
                        LOGGER.warn(IT, "Could not create directories for: {}", file.getAbsolutePath());
                    } else {
                        try (final FileOutputStream out = new FileOutputStream(file)) {
                            int len;
                            while ((len = zip.read(buffer)) > 0) {
                                out.write(buffer, 0, len);
                            }
                        }

                        // Set executable permission for binaries
                        if (!entry.getName().endsWith(".cfg")) {
                            setExecutable(file);
                        }
                    }
                    zip.closeEntry();
                    entry = zip.getNextEntry();
                }
            }
            
            LOGGER.info(marker, "Extraction complete");
            return true;
            
        } catch (final Exception e) {
            LOGGER.error(marker, "Extraction failed", e);
            return false;
        }
    }
    
    public static String readVersion(final Path dir) {
        final Path versionFile = dir.resolve(VERSION_FILE);
        if (!Files.exists(versionFile)) {
            return null;
        }
        
        try {
            return Files.readString(versionFile).trim();
        } catch (final IOException e) {
            return null;
        }
    }
    
    public static boolean shouldExtract(final Path dir, final String zipResource, final Marker marker) {
        if (!Files.exists(dir)) {
            LOGGER.debug(marker, "Directory does not exist, extraction needed");
            return true;
        }
        
        final String currentVersion = readVersion(dir);
        if (currentVersion == null) {
            LOGGER.debug(marker, "No version file found, extraction needed");
            return true;
        }
        
        final String zipVersion = readZipVersion(zipResource);
        if (zipVersion == null) {
            LOGGER.warn(marker, "Could not read version from zip");
            return true;
        }
        
        final int comparison = compareVersions(currentVersion, zipVersion);
        if (comparison < 0) {
            LOGGER.info(marker, "Current version {} is older than zip version {}, extraction needed", 
                       currentVersion, zipVersion);
            return true;
        }
        
        LOGGER.debug(marker, "Current version {} is up to date", currentVersion);
        return false;
    }
    
    public static String readZipVersion(final String resource) {
        try (final InputStream is = IOTools.class.getResourceAsStream(resource)) {
            if (is == null) return null;
            
            try (final ZipInputStream zis = new ZipInputStream(is)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (VERSION_FILE.equals(entry.getName())) {
                        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        final byte[] buffer = new byte[1024];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            baos.write(buffer, 0, len);
                        }
                        return baos.toString().trim();
                    }
                }
            }
        } catch (final IOException e) {
            return null;
        }
        return null;
    }
    
    public static void deleteDir(final Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    
    public static void cleanup(final Path dir, final boolean force, final Marker marker) {
        if (!Files.exists(dir)) return;
        
        try {
            if (force) {
                LOGGER.info(marker, "Force cleanup: {}", dir);
                deleteDir(dir);
            } else {
                LOGGER.debug(marker, "Soft cleanup skipped (force=false)");
            }
        } catch (final IOException e) {
            LOGGER.error(marker, "Cleanup failed", e);
        }
    }
    
    private static void setExecutable(final File file) {
        try {
            if (file.setExecutable(true, false)) {
                return;
            }
        } catch (final SecurityException ignored) {}

        LOGGER.warn(IT, "Could not set executable permission for: {}", file.getAbsolutePath());
    }
    
    private static int compareVersions(final String v1, final String v2) {
        final String[] parts1 = v1.split("\\.");
        final String[] parts2 = v2.split("\\.");
        
        final int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            final int num1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            final int num2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            
            if (num1 != num2) {
                return Integer.compare(num1, num2);
            }
        }
        
        return 0;
    }
    
    private static int parseVersionPart(final String part) {
        try {
            return Integer.parseInt(part);
        } catch (final NumberFormatException e) {
            return 0;
        }
    }
}