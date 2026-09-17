package org.watermedia.binaries;

import org.watermedia.tools.IOTool;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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

    protected abstract Release latest() throws IOException;

    protected void extract(final Path archive, final Path executable) throws IOException {
        Files.move(archive, executable);
    }

    /** Resolves a verified executable, installing a release only after its checksum matches. */
    public final synchronized Path executable() throws IOException {
        final Path directory = WaterMediaBinaries.binaryDir(this.id);
        final String configured = System.getProperty(this.override);
        if (configured != null && !configured.isBlank()) {
            final Path file = Path.of(configured).toAbsolutePath().normalize();
            IOTool.makeExecutable(file);
            return file;
        }
        if (this.name == null) throw new IOException("No " + this.id + " executable for " + IOTool.platform());
        if (this.ready != null && directory.equals(this.readyRoot)) {
            IOTool.verifySha256(this.ready, this.readyHash);
            return this.ready;
        }
        // JAVA FILE LOCKS REJECT OVERLAPPING LOCKS IN ONE JVM; THE MONITOR COVERS SEPARATE RESOLVERS.
        synchronized (ExecutableBinary.class) {
            Files.createDirectories(directory);
            try (final var channel = FileChannel.open(directory.resolve(".install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 final var lock = channel.lock()) {
                final Path current = IOTool.currentGeneration(directory);
                Properties cached = null;
                if (current != null) {
                    try {
                        final Path record = current.resolve("installed.properties");
                        if (!Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing installation record");
                        cached = new Properties();
                        try (final var input = Files.newInputStream(record)) { cached.load(input); }
                        IOTool.verifySha256(current.resolve(this.name), cached.getProperty("executable.sha256"));
                        IOTool.sha256Digest(cached.getProperty("archive.sha256"));
                        if (!IOTool.platform().equals(cached.getProperty("platform"))) cached = null;
                    } catch (final IOException e) {
                        cached = null;
                    }
                }
                Release release = null;
                try {
                    release = this.latest();
                } catch (final IOException e) {
                    // A LOCAL TRUST-PIN ERROR MUST FAIL CLOSED; ONLY REMOTE UPDATE LOOKUPS MAY USE THE CACHE.
                    if (cached == null || this instanceof BotGuardBinary) throw e;
                    WaterMediaBinaries.LOGGER.warn("Could not check {} updates; retaining verified cached release {}", this.id, cached.getProperty("version"));
                }
                if (cached != null && (release == null || (release.sha256.equals(cached.getProperty("archive.sha256"))
                        && release.version.equals(cached.getProperty("version")) && release.url.toString().equals(cached.getProperty("origin"))))) {
                    final Path binary = current.resolve(this.name);
                    IOTool.makeExecutable(binary);
                    this.readyHash = cached.getProperty("executable.sha256");
                    this.readyRoot = directory;
                    return this.ready = binary;
                }
                final Path stage = Files.createTempDirectory(directory, "install-");
                boolean installed = false;
                try {
                    final Path archive = stage.resolve("download");
                    IOTool.downloadVerified(release.url, archive, release.sha256, 512L * 1024 * 1024);
                    final Path binary = stage.resolve(this.name);
                    this.extract(archive, binary);
                    Files.deleteIfExists(archive);
                    IOTool.makeExecutable(binary);
                    final var record = new Properties();
                    record.setProperty("version", release.version);
                    record.setProperty("origin", release.url.toString());
                    record.setProperty("platform", IOTool.platform());
                    record.setProperty("archive.sha256", release.sha256);
                    record.setProperty("executable.sha256", IOTool.sha256(binary));
                    try (final var output = Files.newOutputStream(stage.resolve("installed.properties"))) {
                        record.store(output, "VERIFIED NATIVE INSTALLATION");
                    }
                    IOTool.publishGeneration(directory, stage);
                    installed = true;
                    this.readyHash = record.getProperty("executable.sha256");
                    this.readyRoot = directory;
                    return this.ready = binary;
                } finally {
                    if (!installed) IOTool.deleteTree(stage);
                }
            }
        }
    }

    protected record Release(String version, URI url, String sha256) {
        protected Release {
            if (version == null || version.isBlank()) throw new IllegalArgumentException("Release version is empty");
            try {
                sha256 = IOTool.sha256Digest(sha256);
            } catch (final IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null) {
                throw new IllegalArgumentException("Release URL must use HTTPS without credentials");
            }
        }
    }
}
