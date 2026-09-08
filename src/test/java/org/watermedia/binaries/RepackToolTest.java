package org.watermedia.binaries;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RepackToolTest {
    private static final String[] ARCHIVES = {"linux", "linux-arm64", "macos", "macos-arm64", "windows"};
    @TempDir Path directory;

    @Test
    void officialDownloadCannotReplaceAnInstalledSecurityRebuild() throws Exception {
        Files.writeString(this.directory.resolve("gradle.properties"), "ffmpeg_version=8.1.2-1.5.14\nffmpeg_compression=9\n");
        Files.createDirectories(this.directory.resolve("tools"));
        Files.writeString(this.directory.resolve("tools/ffmpeg-manifest.properties"), "source.kind=rebuilt\n");
        final Path output = this.directory.resolve("process.log");
        final var process = new ProcessBuilder(java(), Path.of("tools/RepackFFmpeg.java").toAbsolutePath().toString(), this.directory.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertNotEquals(0, process.exitValue());
            assertTrue(Files.readString(output).contains("preserve the installed native security rebuild"));
            assertFalse(Files.exists(this.directory.resolve("src/main/resources/libs")));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void invalidCandidateRecordPreservesInstalledArchives() throws Exception {
        Files.copy(Path.of("gradle.properties"), this.directory.resolve("gradle.properties"));
        final Path library = Files.createDirectories(this.directory.resolve("src/main/resources/libs")).resolve("ffmpeg-linux.zip");
        Files.writeString(library, "existing archive");
        final Path candidates = Files.createDirectories(this.directory.resolve("candidates/linux-x86_64"));
        Files.writeString(candidates.resolve("build.properties"), "version=8.1.2-1.5.14\nplatform=linux-x86_64\narchive=../other.jar\n");
        final Path output = this.directory.resolve("process.log");
        final var process = new ProcessBuilder(java(), Path.of("tools/RepackFFmpeg.java").toAbsolutePath().toString(), this.directory.toString(), candidates.getParent().toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertNotEquals(0, process.exitValue());
            assertTrue(Files.readString(output).contains("Candidate version or native verification record does not match"));
            assertEquals("existing archive", Files.readString(library));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void invalidFinalCandidatePreservesEveryInstalledArchive() throws Exception {
        final Path candidates = this.candidates(true, false);
        final Path resources = this.directory.resolve("src/main/resources/libs");
        final Path output = this.directory.resolve("process.log");
        final var process = new ProcessBuilder(java(), Path.of("tools/RepackFFmpeg.java").toAbsolutePath().toString(), this.directory.toString(), candidates.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertNotEquals(0, process.exitValue());
            final String log = Files.readString(output);
            assertTrue(log.contains("ffmpeg-macos-arm64.zip:"), log);
            assertTrue(log.contains("Candidate version or native verification record does not match"), log);
            for (final String archive: ARCHIVES) assertEquals("existing " + archive, Files.readString(resources.resolve("ffmpeg-" + archive + ".zip")));
            assertFalse(Files.exists(this.directory.resolve("tools/ffmpeg-manifest.properties")));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void missingPackagedDependenciesPreserveArchivesAndManifest() throws Exception {
        final Path candidates = this.candidates(false, true);
        final Path resources = this.directory.resolve("src/main/resources/libs");
        final Path manifest = Files.createDirectories(this.directory.resolve("tools")).resolve("ffmpeg-manifest.properties");
        Files.writeString(manifest, "existing manifest");
        final Path output = this.directory.resolve("process.log");
        final var process = new ProcessBuilder(java(), Path.of("tools/RepackFFmpeg.java").toAbsolutePath().toString(), this.directory.toString(), candidates.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertNotEquals(0, process.exitValue());
            final String log = Files.readString(output);
            assertTrue(log.contains("complete native inventory for linux-x86_64"), log);
            for (final String archive: ARCHIVES) assertEquals("existing " + archive, Files.readString(resources.resolve("ffmpeg-" + archive + ".zip")));
            assertEquals("existing manifest", Files.readString(manifest));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void priorSecurityCandidateCannotOmitHeadersProtection() throws Exception {
        final Path candidates = this.candidates(false, false);
        final Path recordPath = candidates.resolve("linux-x86_64/build.properties");
        final Properties record = new Properties();
        try (final var input = Files.newInputStream(recordPath)) { record.load(input); }
        record.remove("headers.patch.sha256");
        try (final var output = Files.newOutputStream(recordPath)) { record.store(output, null); }
        final Path manifest = Files.createDirectories(this.directory.resolve("tools")).resolve("ffmpeg-manifest.properties");
        Files.writeString(manifest, "existing manifest");
        final Path output = this.directory.resolve("process.log");
        final var process = new ProcessBuilder(java(), Path.of("tools/RepackFFmpeg.java").toAbsolutePath().toString(), this.directory.toString(), candidates.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertNotEquals(0, process.exitValue());
            final String log = Files.readString(output);
            assertTrue(log.contains("Candidate version or native verification record does not match"), log);
            for (final String archive: ARCHIVES) {
                assertEquals("existing " + archive, Files.readString(this.directory.resolve("src/main/resources/libs/ffmpeg-" + archive + ".zip")));
            }
            assertEquals("existing manifest", Files.readString(manifest));
        } finally {
            process.destroyForcibly();
        }
    }

    private Path candidates(final boolean invalidFinal, final boolean missingLinuxDependencies) throws Exception {
        Files.copy(Path.of("gradle.properties"), this.directory.resolve("gradle.properties"));
        final Properties properties = new Properties();
        try (final var input = Files.newInputStream(this.directory.resolve("gradle.properties"))) { properties.load(input); }
        properties.putIfAbsent("ffmpeg_security_patch_sha256", "1".repeat(64));
        properties.putIfAbsent("ffmpeg_headers_patch_sha256", "2".repeat(64));
        try (final var output = Files.newOutputStream(this.directory.resolve("gradle.properties"))) { properties.store(output, null); }
        final Path resources = Files.createDirectories(this.directory.resolve("src/main/resources/libs"));
        final Path candidates = Files.createDirectory(this.directory.resolve("candidates"));
        final String[] platforms = {"linux-x86_64", "linux-arm64", "macosx-x86_64", "macosx-arm64", "windows-x86_64"};
        final String[][] support = {{"libva.so.2", "libva-drm.so.2", "libdrm.so.2"},
                {"libasound.so.2", "libbcm_host.so", "libvchiq_arm.so", "libvcos.so"},
                {"libatomic.1.dylib"}, {"libatomic.1.dylib"}, {"libwinpthread-1.dll"}};
        for (int i = 0; i < platforms.length; i++) {
            Files.writeString(resources.resolve("ffmpeg-" + ARCHIVES[i] + ".zip"), "existing " + ARCHIVES[i]);
            final String platform = platforms[i];
            final Path candidate = Files.createDirectory(candidates.resolve(platform));
            final String name = "ffmpeg-" + properties.getProperty("ffmpeg_version") + "-" + platform + "-gpl.jar";
            final Path jar = candidate.resolve(name);
            final String extension = platform.startsWith("windows-") ? ".dll" : platform.startsWith("macosx-") ? ".dylib" : ".so";
            final List<String> libraries = new ArrayList<>();
            for (final String component: new String[]{"avcodec", "avdevice", "avfilter", "avformat", "avutil", "swresample", "swscale"}) {
                libraries.add("lib" + component + extension);
                libraries.add("libjni" + component + extension);
            }
            if (i != 0 || !missingLinuxDependencies) libraries.addAll(List.of(support[i]));
            try (final var output = new ZipOutputStream(Files.newOutputStream(jar))) {
                for (final String library: libraries) {
                    output.putNextEntry(new ZipEntry("org/bytedeco/ffmpeg/" + platform + "-gpl/" + library));
                    output.write(library.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    output.closeEntry();
                }
            }
            final Properties record = new Properties();
            record.setProperty("version", properties.getProperty("ffmpeg_version"));
            record.setProperty("platform", platform);
            record.setProperty("archive", name);
            record.setProperty("archive.sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
            record.setProperty("source.ref", properties.getProperty("ffmpeg_source_ref"));
            record.setProperty("source.sha256", properties.getProperty("ffmpeg_source_sha256"));
            record.setProperty("libxml2.version", properties.getProperty("libxml2_version"));
            record.setProperty("libxml2.sha256", properties.getProperty("libxml2_sha256"));
            record.setProperty("openssl.version", properties.getProperty("openssl_version"));
            record.setProperty("openssl.sha256", properties.getProperty("openssl_sha256"));
            record.setProperty("tls.patch.sha256", properties.getProperty("ffmpeg_tls_patch_sha256"));
            record.setProperty("security.patch.sha256", properties.getProperty("ffmpeg_security_patch_sha256"));
            record.setProperty("headers.patch.sha256", properties.getProperty("ffmpeg_headers_patch_sha256"));
            record.setProperty("recipe.sha256", "0".repeat(64));
            record.setProperty("verification", invalidFinal && i == platforms.length - 1 ? "missing-TLS"
                    : "JNI-original-wrappers,DASH,recursive-entities,libxml2-version,openssl-version,imports-closure,clean-environment,TLS,HTTP-headers");
            try (final var output = Files.newOutputStream(candidate.resolve("build.properties"))) { record.store(output, null); }
        }
        return candidates;
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", NativeIO.platform().startsWith("windows-") ? "java.exe" : "java").toString();
    }
}
