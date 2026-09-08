package org.watermedia.binaries;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.ZipFile;

final class FFmpegBinaries {
    private FFmpegBinaries() {}

    static Path start(final Path directory, final WaterMediaBinaries.Progress progress) throws IOException {
        final String platform = switch (NativeIO.platform()) {
            case "windows-x86_64" -> "windows";
            case "linux-x86_64" -> "linux";
            case "linux-aarch64" -> "linux-arm64";
            case "macos-x86_64" -> "macos";
            case "macos-aarch64" -> "macos-arm64";
            default -> throw new IOException("FFmpeg is not bundled for " + NativeIO.platform());
        };
        final var manifest = new Properties();
        try (final var input = FFmpegBinaries.class.getResourceAsStream("/META-INF/ffmpeg-manifest.properties")) {
            if (input == null) throw new IOException("FFmpeg integrity manifest is missing");
            manifest.load(input);
        }
        final var hashes = new TreeMap<String, String>();
        final String prefix = platform + ".native.";
        for (final String key: manifest.stringPropertyNames()) {
            if (key.startsWith(prefix) && key.endsWith(".sha256")) {
                final String file = key.substring(prefix.length(), key.length() - ".sha256".length());
                if (file.contains("/") || file.contains("\\") || file.equals(".") || file.equals("..")) throw new IOException("Invalid native manifest filename");
                hashes.put(file, NativeIO.digest(manifest.getProperty(key)));
            }
        }
        if (hashes.isEmpty()) throw new IOException("FFmpeg manifest contains no libraries for " + platform);
        final String version = manifest.getProperty("version") + "-gpl";
        final String hash = NativeIO.digest(manifest.getProperty(platform + ".archive.sha256"));
        final long size;
        try {
            size = Long.parseLong(manifest.getProperty(platform + ".archive.bytes"));
        } catch (final RuntimeException e) {
            throw new IOException("Invalid FFmpeg archive size", e);
        }
        final String resource = "/libs/ffmpeg-" + platform + ".zip";
        try (final var input = FFmpegBinaries.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("FFmpeg archive is missing: " + resource);
            return install(directory, version, hash, size, hashes, input, progress);
        }
    }

    static synchronized Path install(final Path directory, final String version, final String archiveHash, final long archiveSize,
                                     final Map<String, String> hashes, final InputStream source, final WaterMediaBinaries.Progress progress) throws IOException {
        Files.createDirectories(directory);
        try (final var channel = FileChannel.open(directory.resolve(".install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             final var lock = channel.lock()) {
            final Path current = NativeIO.current(directory);
            if (current != null && valid(current, version, hashes)) return current;
            final Path stage = Files.createTempDirectory(directory, "install-");
            boolean installed = false;
            try {
                final Path archive = stage.resolve("archive.zip");
                final byte[] buffer = new byte[65536];
                long copied = 0;
                try (final var output = Files.newOutputStream(archive)) {
                    int count;
                    while ((count = source.read(buffer)) != -1) {
                        copied += count;
                        if (copied > archiveSize) throw new IOException("FFmpeg archive exceeds its recorded size");
                        output.write(buffer, 0, count);
                        progress.update("FFmpeg archive", copied, archiveSize);
                    }
                }
                if (copied != archiveSize) throw new IOException("Truncated FFmpeg archive");
                NativeIO.verify(archive, archiveHash);
                final var found = new HashSet<String>();
                try (final var zip = new ZipFile(archive.toFile())) {
                    for (final var entry: zip.stream().toList()) {
                        final String name = entry.getName();
                        if ((!hashes.containsKey(name) && !name.equals("version.cfg")) || !found.add(name) || entry.isDirectory()) {
                            throw new IOException("Unexpected FFmpeg archive entry: " + name);
                        }
                        if (entry.getSize() < 0 || entry.getSize() > 256L * 1024 * 1024) throw new IOException("Invalid FFmpeg native size");
                        final Path destination = stage.resolve(name);
                        long size = 0;
                        final var crc = new java.util.zip.CRC32();
                        try (final var input = zip.getInputStream(entry); final var output = Files.newOutputStream(destination)) {
                            int count;
                            while ((count = input.read(buffer)) != -1) {
                                size += count;
                                if (size > entry.getSize()) throw new IOException("FFmpeg native exceeds its recorded size");
                                crc.update(buffer, 0, count);
                                output.write(buffer, 0, count);
                                progress.update(name, size, entry.getSize());
                            }
                        }
                        if (size != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("FFmpeg native failed CRC or size validation: " + name);
                        if (hashes.containsKey(name)) NativeIO.verify(destination, hashes.get(name));
                    }
                }
                Files.delete(archive);
                if (found.size() != hashes.size() + 1 || !valid(stage, version, hashes)) throw new IOException("Incomplete FFmpeg installation");
                // PUBLISH AN IMMUTABLE GENERATION; LOADED DLLS IN THE PREVIOUS GENERATION STAY UNTOUCHED.
                NativeIO.publish(directory, stage);
                installed = true;
                return stage;
            } finally {
                if (!installed) NativeIO.delete(stage);
            }
        }
    }

    static boolean valid(final Path directory, final String version, final Map<String, String> hashes) throws IOException {
        try {
            if (!version.equals(Files.readString(directory.resolve("version.cfg")))) return false;
            try (final var files = Files.list(directory)) {
                if (files.count() != hashes.size() + 1L) return false;
            }
            for (final var file: hashes.entrySet()) NativeIO.verify(directory.resolve(file.getKey()), file.getValue());
            return true;
        } catch (final IOException e) {
            return false;
        }
    }
}
