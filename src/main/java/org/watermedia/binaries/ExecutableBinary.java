package org.watermedia.binaries;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

abstract sealed class ExecutableBinary permits YtDlpBinary, BotGuardBinary {
    final String id;
    final String name;
    private final String override;
    private Path ready;
    private Path readyRoot;
    private String readyHash;

    ExecutableBinary(final String id, final String name, final String override) {
        this.id = id;
        this.name = name;
        this.override = override;
    }

    abstract Release latest() throws IOException;
    abstract void extract(Path archive, Path executable) throws IOException;

    /** Resolves a verified executable, installing a release only after its checksum matches. */
    public final synchronized Path executable() throws IOException {
        final String configured = System.getProperty(this.override);
        if (configured != null && !configured.isBlank()) {
            final Path file = Path.of(configured).toAbsolutePath().normalize();
            NativeIO.executable(file);
            return file;
        }
        if (this.name == null) throw new IOException("No " + this.id + " executable for " + NativeIO.platform());
        final Path directory = WaterMediaBinaries.binaryDir(this.id);
        if (this.ready != null && directory.equals(this.readyRoot)) {
            NativeIO.verify(this.ready, this.readyHash);
            return this.ready;
        }
        // JAVA FILE LOCKS REJECT OVERLAPPING LOCKS IN ONE JVM; THE MONITOR COVERS SEPARATE RESOLVERS.
        synchronized (ExecutableBinary.class) {
            Files.createDirectories(directory);
            try (final var channel = FileChannel.open(directory.resolve(".install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 final var lock = channel.lock()) {
                final Path current = NativeIO.current(directory);
                Properties cached = null;
                if (current != null) {
                    try {
                        cached = NativeIO.properties(current.resolve("installed.properties"));
                        NativeIO.verify(current.resolve(this.name), cached.getProperty("executable.sha256"));
                        NativeIO.digest(cached.getProperty("archive.sha256"));
                        if (!NativeIO.platform().equals(cached.getProperty("platform"))) cached = null;
                    } catch (final IOException e) {
                        cached = null;
                    }
                }
                final Release release;
                try {
                    release = this.latest();
                } catch (final IOException e) {
                    // A LOCAL TRUST-PIN ERROR MUST FAIL CLOSED; ONLY REMOTE UPDATE LOOKUPS MAY USE THE CACHE.
                    if (cached == null || this instanceof BotGuardBinary) throw e;
                    WaterMediaBinaries.LOGGER.warn("Could not check {} updates; retaining verified cached release {}", this.id, cached.getProperty("version"));
                    this.readyHash = cached.getProperty("executable.sha256");
                    this.readyRoot = directory;
                    NativeIO.executable(current.resolve(this.name));
                    return this.ready = current.resolve(this.name);
                }
                if (cached != null && release.sha256.equals(cached.getProperty("archive.sha256"))
                        && release.version.equals(cached.getProperty("version")) && release.url.toString().equals(cached.getProperty("origin"))) {
                    this.readyHash = cached.getProperty("executable.sha256");
                    this.readyRoot = directory;
                    NativeIO.executable(current.resolve(this.name));
                    return this.ready = current.resolve(this.name);
                }
                final Path stage = Files.createTempDirectory(directory, "install-");
                boolean installed = false;
                try {
                    final Path archive = stage.resolve("download");
                    NativeIO.download(release.url, archive, release.sha256);
                    final Path binary = stage.resolve(this.name);
                    this.extract(archive, binary);
                    Files.deleteIfExists(archive);
                    NativeIO.executable(binary);
                    final var record = new Properties();
                    record.setProperty("version", release.version);
                    record.setProperty("origin", release.url.toString());
                    record.setProperty("platform", NativeIO.platform());
                    record.setProperty("archive.sha256", release.sha256);
                    record.setProperty("executable.sha256", NativeIO.hash(binary));
                    try (final var output = Files.newOutputStream(stage.resolve("installed.properties"))) {
                        record.store(output, "VERIFIED NATIVE INSTALLATION");
                    }
                    NativeIO.publish(directory, stage);
                    installed = true;
                    this.readyHash = record.getProperty("executable.sha256");
                    this.readyRoot = directory;
                    return this.ready = binary;
                } finally {
                    if (!installed) NativeIO.delete(stage);
                }
            }
        }
    }

    record Release(String version, URI url, String sha256) {
        Release {
            if (version == null || version.isBlank()) throw new IllegalArgumentException("Release version is empty");
            try {
                sha256 = NativeIO.digest(sha256);
            } catch (final IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null) {
                throw new IllegalArgumentException("Release URL must use HTTPS without credentials");
            }
        }
    }
}
