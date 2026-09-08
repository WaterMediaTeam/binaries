package org.watermedia.binaries;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class NativeProvisionTest {
    @TempDir Path directory;
    private static final WaterMediaBinaries.Progress PROGRESS = (name, done, total) -> {};

    @Test
    void checksumMustExistBeValidAndUnique() throws Exception {
        final String hash = "a".repeat(64);
        assertEquals(hash, YtDlpBinary.checksum(hash.toUpperCase() + " *yt-dlp.exe\n", "yt-dlp.exe"));
        assertThrows(IOException.class, () -> YtDlpBinary.checksum(hash + " other", "yt-dlp.exe"));
        assertThrows(IOException.class, () -> YtDlpBinary.checksum("garbage yt-dlp.exe", "yt-dlp.exe"));
        assertThrows(IOException.class, () -> YtDlpBinary.checksum(hash + " yt-dlp.exe\n" + hash + " yt-dlp.exe", "yt-dlp.exe"));
        assertThrows(IllegalArgumentException.class, () -> new ExecutableBinary.Release("v1", URI.create("https://example.com/a"), null));
        assertThrows(IllegalArgumentException.class, () -> new ExecutableBinary.Release("v1", URI.create("http://example.com/a"), hash));
    }

    @Test
    void trustPinsCoverOnlyReviewedRelease() throws Exception {
        final var pins = new Properties();
        try (final var input = getClass().getResourceAsStream("/META-INF/botguard-pins.properties")) {
            assertNotNull(input);
            pins.load(input);
        }
        assertEquals("v0.1.2", pins.getProperty("version"));
        for (final String platform: new String[]{"windows-x86_64", "linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64"}) {
            assertEquals(64, NativeIO.digest(pins.getProperty(platform + ".sha256")).length());
            assertTrue(pins.getProperty(platform + ".url").startsWith("https://codeberg.org/ThetaDev/rustypipe-botguard/releases/download/v0.1.2/"));
        }
    }

    @Test
    void corruptFileFailsVerification() throws Exception {
        final Path file = Files.writeString(this.directory.resolve("binary"), "valid");
        final String expected = NativeIO.hash(file);
        NativeIO.verify(file, expected);
        Files.writeString(file, "wrong");
        assertThrows(IOException.class, () -> NativeIO.verify(file, expected));
    }

    @Test
    void extractionRepairsMissingAndCorruptedLibrariesWithIntactMarker() throws Exception {
        final var fixture = fixture("8.1.2-1.5.14-gpl", Map.of("native.dll", "native"));
        final Path first = install(fixture);
        assertEquals(first, install(fixture));
        Files.writeString(first.resolve("native.dll"), "broken");
        final Path repaired = install(fixture);
        assertNotEquals(first, repaired);
        assertEquals("native", Files.readString(repaired.resolve("native.dll")));
        assertTrue(Files.exists(first));
        Files.delete(repaired.resolve("native.dll"));
        final Path replaced = install(fixture);
        assertNotEquals(repaired, replaced);
        assertEquals(replaced, NativeIO.current(this.directory));
    }

    @Test
    void differentBuildQualifierInstallsNewGeneration() throws Exception {
        final Path first = install(fixture("8.1.2-one", Map.of("native.dll", "native")));
        final Path second = install(fixture("8.1.2-two", Map.of("native.dll", "native")));
        assertNotEquals(first, second);
        assertEquals("8.1.2-one", Files.readString(first.resolve("version.cfg")));
        assertEquals("8.1.2-two", Files.readString(second.resolve("version.cfg")));
    }

    @Test
    void interruptedInstallationPreservesPreviousGeneration() throws Exception {
        final Path previous = install(fixture("old", Map.of("native.dll", "native")));
        final var next = fixture("new", Map.of("native.dll", "updated"));
        final InputStream failing = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("Interrupted"); }
        };
        assertThrows(IOException.class, () -> FFmpegBinaries.install(this.directory, next.version, next.hash, next.zip.length, next.libraries, failing, PROGRESS));
        assertEquals(previous, NativeIO.current(this.directory));
        try (final var files = Files.list(this.directory)) {
            assertEquals(1, files.filter(Files::isDirectory).count());
        }
        assertNotEquals(previous, install(next));
    }

    @Test
    void throwingProgressCannotPublishPartialInstallation() throws Exception {
        final var fixture = fixture("v1", Map.of("native.dll", "native"));
        assertThrows(IllegalStateException.class, () -> FFmpegBinaries.install(this.directory, fixture.version, fixture.hash, fixture.zip.length, fixture.libraries,
                new ByteArrayInputStream(fixture.zip), (name, done, total) -> { throw new IllegalStateException("cancel"); }));
        assertNull(NativeIO.current(this.directory));
        assertNotNull(install(fixture));
    }

    @Test
    void archiveDigestAndUnexpectedPathsFailClosed() throws Exception {
        final var good = fixture("v1", Map.of("native.dll", "native"));
        assertThrows(IOException.class, () -> FFmpegBinaries.install(this.directory, good.version, "0".repeat(64), good.zip.length, good.libraries,
                new ByteArrayInputStream(good.zip), PROGRESS));
        final var unsafe = fixture("v1", Map.of("../escape", "bad"));
        assertThrows(IOException.class, () -> FFmpegBinaries.install(this.directory, unsafe.version, unsafe.hash, unsafe.zip.length, good.libraries,
                new ByteArrayInputStream(unsafe.zip), PROGRESS));
        assertFalse(Files.exists(this.directory.getParent().resolve("escape")));
        assertNull(NativeIO.current(this.directory));
    }

    @Test
    void pointerCannotEscapeCacheDirectory() throws Exception {
        Files.writeString(this.directory.resolve("current"), "../other");
        assertNull(NativeIO.current(this.directory));
        Files.writeString(this.directory.resolve("current"), this.directory.toAbsolutePath().toString());
        assertNull(NativeIO.current(this.directory));
    }

    @Test
    void botguardZipMustContainExpectedExecutable() throws Exception {
        final var wrong = fixture("unused", Map.of("../rustypipe-botguard.exe", "bad"));
        final Path archive = Files.write(this.directory.resolve("archive.zip"), wrong.zip);
        assertThrows(IOException.class, () -> BotGuardBinary.extract(archive, this.directory.resolve("binary"), "rustypipe-botguard.exe"));
        assertFalse(Files.exists(this.directory.resolve("binary")));
    }

    @Test
    void botguardTarChecksHeaderBeforeWritingExecutable() throws Exception {
        final Path archive = Files.write(this.directory.resolve("botguard.tar.xz"), tar(false));
        final Path binary = this.directory.resolve("binary");
        BotGuardBinary.extract(archive, binary, "rustypipe-botguard");
        assertEquals("verified", Files.readString(binary));
        Files.write(archive, tar(true));
        assertThrows(IOException.class, () -> BotGuardBinary.extract(archive, this.directory.resolve("bad"), "rustypipe-botguard"));
        assertFalse(Files.exists(this.directory.resolve("bad")));
    }

    @Test
    void botguardTarDrainsAndValidatesXzFooter() throws Exception {
        final byte[] bytes = tar(false);
        final Path archive = Files.write(this.directory.resolve("truncated.tar.xz"), java.util.Arrays.copyOf(bytes, bytes.length - 8));
        assertThrows(IOException.class, () -> BotGuardBinary.extract(archive, this.directory.resolve("partial"), "rustypipe-botguard"));
    }

    private static byte[] tar(final boolean corrupt) throws Exception {
        final byte[] header = new byte[512];
        final byte[] name = "rustypipe-botguard".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, header, 0, name.length);
        final byte[] size = "00000000010".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, header, 124, size.length);
        java.util.Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = '0';
        int checksum = 0;
        for (final byte value: header) checksum += value & 255;
        final byte[] field = String.format(java.util.Locale.ROOT, "%06o\0 ", checksum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(field, 0, header, 148, field.length);
        if (corrupt) header[0] ^= 1;
        final var bytes = new ByteArrayOutputStream();
        try (final var xz = new org.tukaani.xz.XZOutputStream(bytes, new org.tukaani.xz.LZMA2Options(1))) {
            xz.write(header);
            xz.write("verified".getBytes(StandardCharsets.UTF_8));
            xz.write(new byte[504 + 1024]);
        }
        return bytes.toByteArray();
    }

    private Path install(final Fixture fixture) throws IOException {
        return FFmpegBinaries.install(this.directory, fixture.version, fixture.hash, fixture.zip.length, fixture.libraries, new ByteArrayInputStream(fixture.zip), PROGRESS);
    }

    private static Fixture fixture(final String version, final Map<String, String> files) throws Exception {
        final var output = new ByteArrayOutputStream();
        final var hashes = new LinkedHashMap<String, String>();
        try (final var zip = new ZipOutputStream(output)) {
            for (final var file: files.entrySet()) {
                final byte[] data = file.getValue().getBytes(StandardCharsets.UTF_8);
                hashes.put(file.getKey(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(data);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("version.cfg"));
            zip.write(version.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        final byte[] bytes = output.toByteArray();
        return new Fixture(version, bytes, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), hashes);
    }

    private record Fixture(String version, byte[] zip, String hash, Map<String, String> libraries) {}
}
