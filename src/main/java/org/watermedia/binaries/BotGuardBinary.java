package org.watermedia.binaries;

import org.tukaani.xz.XZInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.zip.ZipFile;

/** Installs the reviewed BotGuard release pinned by WaterMedia's native trust manifest. */
public final class BotGuardBinary extends ExecutableBinary {
    public BotGuardBinary() {
        super(WaterMediaBinaries.BOTGUARD_ID, switch (NativeIO.platform()) {
            case "windows-x86_64" -> "rustypipe-botguard.exe";
            case "linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64" -> "rustypipe-botguard";
            default -> null;
        }, "watermedia.botguard.binary");
    }

    /** Returns the snapshot cache belonging to the verified executable installation. */
    public Path snapshot() throws IOException {
        final String hash = NativeIO.hash(this.executable());
        final Path snapshots = Files.createDirectories(WaterMediaBinaries.binaryDir(WaterMediaBinaries.BOTGUARD_ID).resolve("snapshots"));
        return snapshots.resolve(hash + ".bin");
    }

    @Override
    Release latest() throws IOException {
        final var pins = new Properties();
        try (final var input = BotGuardBinary.class.getResourceAsStream("/META-INF/botguard-pins.properties")) {
            if (input == null) throw new IOException("BotGuard trust manifest is missing");
            pins.load(input);
        }
        try {
            final String key = NativeIO.platform();
            return new Release(pins.getProperty("version"), java.net.URI.create(pins.getProperty(key + ".url")), pins.getProperty(key + ".sha256"));
        } catch (final RuntimeException e) {
            throw new IOException("Invalid BotGuard trust manifest for " + NativeIO.platform(), e);
        }
    }

    @Override
    void extract(final Path archive, final Path executable) throws IOException {
        extract(archive, executable, this.name);
    }

    static void extract(final Path archive, final Path executable, final String name) throws IOException {
        final long limit = 256L * 1024 * 1024;
        if (name.endsWith(".exe")) {
            try (final var zip = new ZipFile(archive.toFile())) {
                final var entries = zip.stream().filter(entry -> entry.getName().equals(name)).toList();
                if (entries.size() != 1 || entries.get(0).isDirectory() || entries.get(0).getSize() <= 0 || entries.get(0).getSize() > limit) {
                    throw new IOException("BotGuard archive lacks one valid " + name);
                }
                final var entry = entries.get(0);
                final var crc = new java.util.zip.CRC32();
                long total = 0;
                try (final var input = zip.getInputStream(entry); final var output = Files.newOutputStream(executable)) {
                    final byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > limit) throw new IOException("BotGuard executable exceeds its size limit");
                        output.write(buffer, 0, count);
                        crc.update(buffer, 0, count);
                    }
                }
                if (total != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("BotGuard ZIP entry failed CRC or size validation");
            }
            return;
        }
        try (final InputStream input = new XZInputStream(new BufferedInputStream(Files.newInputStream(archive)), 65536)) {
            final byte[] header = new byte[512];
            final byte[] buffer = new byte[65536];
            boolean found = false;
            long total = 0;
            while (true) {
                if (input.readNBytes(header, 0, header.length) != header.length) throw new IOException("Truncated BotGuard TAR header");
                boolean zero = true;
                for (final byte value: header) zero &= value == 0;
                if (zero) {
                    // DRAIN TO VALIDATE THE XZ CHECKSUM; TAR PADDING AFTER ITS END MUST BE ZERO.
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > limit * 2) throw new IOException("BotGuard archive exceeds its size limit");
                        for (int i = 0; i < count; i++) if (buffer[i] != 0) throw new IOException("Unexpected data after BotGuard TAR end");
                    }
                    break;
                }
                long checksum = 0;
                for (int i = 0; i < header.length; i++) checksum += i >= 148 && i < 156 ? 32 : header[i] & 255;
                if (checksum != octal(header, 148, 8)) throw new IOException("Invalid BotGuard TAR header checksum");
                final long size = octal(header, 124, 12);
                total += 512 + size;
                if (size > limit || total > limit * 2) throw new IOException("BotGuard archive exceeds its size limit");
                int end = 0;
                while (end < 100 && header[end] != 0) end++;
                final String file = new String(header, 0, end, StandardCharsets.UTF_8);
                final boolean wanted = name.equals(file) || ("./" + name).equals(file);
                if (wanted && (found || size == 0 || header[345] != 0 || (header[156] != 0 && header[156] != '0'))) {
                    throw new IOException("Invalid or duplicate BotGuard TAR executable");
                }
                try (final var output = wanted ? Files.newOutputStream(executable) : java.io.OutputStream.nullOutputStream()) {
                    long remaining = size;
                    while (remaining > 0) {
                        final int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (count < 0) throw new IOException("Truncated BotGuard TAR entry");
                        output.write(buffer, 0, count);
                        remaining -= count;
                    }
                }
                input.skipNBytes((512 - size % 512) % 512);
                found |= wanted;
            }
            if (!found) throw new IOException("BotGuard TAR does not contain " + name);
        }
    }

    private static long octal(final byte[] data, final int offset, final int length) throws IOException {
        long value = 0;
        boolean ended = false;
        for (int i = offset; i < offset + length; i++) {
            final int c = data[i] & 255;
            if (c == 0 || c == ' ') {
                if (value != 0) ended = true;
                continue;
            }
            if (ended || c < '0' || c > '7') throw new IOException("Invalid BotGuard TAR numeric field");
            value = Math.addExact(Math.multiplyExact(value, 8), c - '0');
        }
        return value;
    }
}
