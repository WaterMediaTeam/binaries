import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Repackages verified LGPL native distributions into flat resource archives. */
public final class RepackFFmpeg {
    public static void main(final String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: RepackFFmpeg <binaries-root> <verified-candidate-directory>");
        final Path root = Path.of(args[0]).toAbsolutePath().normalize();
        final Path rebuilt = Path.of(args[1]).toAbsolutePath().normalize();
        final Properties properties = new Properties();
        try (final var input = Files.newInputStream(root.resolve("gradle.properties"))) {
            properties.load(input);
        }
        final String version = properties.getProperty("ffmpeg_version");
        if (version == null || !version.matches("\\d+\\.\\d+\\.\\d+-\\d+\\.\\d+\\.\\d+")) {
            throw new IllegalArgumentException("ffmpeg_version must be an exact FFmpeg-JavaCPP coordinate");
        }
        final int level = Integer.parseInt(properties.getProperty("ffmpeg_compression"));
        if (level != 9) throw new IllegalArgumentException("ffmpeg_compression must be 9");
        final String variant = properties.getProperty("ffmpeg_variant");
        final String license = properties.getProperty("ffmpeg_license");
        if (!"lgpl".equals(variant) || !"LGPL-3.0-or-later".equals(license))
            throw new IllegalArgumentException("FFmpeg must use the suffix-free LGPLv3 variant");
        final Path tools = Files.createDirectories(root.resolve("tools"));
        final Path parentProperties = root.getParent().resolve("gradle.properties");
        if (Files.isRegularFile(parentProperties)) {
            final Properties parent = new Properties();
            try (final var input = Files.newInputStream(parentProperties)) {
                parent.load(input);
            }
            if (!version.equals(parent.getProperty("ffmpeg_version"))) {
                throw new IllegalArgumentException("WaterMedia and binaries must use the same ffmpeg_version");
            }
        }

        final Map<String, String> platforms = new LinkedHashMap<>();
        platforms.put("linux", "linux-x86_64");
        platforms.put("linux-arm64", "linux-arm64");
        platforms.put("macos", "macosx-x86_64");
        platforms.put("macos-arm64", "macosx-arm64");
        platforms.put("windows", "windows-x86_64");
        final Map<String, List<String>> support = Map.of(
                "linux", List.of("libva.so.2", "libva-drm.so.2", "libdrm.so.2"),
                "linux-arm64", List.of("libasound.so.2", "libbcm_host.so", "libvchiq_arm.so", "libvcos.so"),
                "macos", List.of("libatomic.1.dylib"),
                "macos-arm64", List.of("libatomic.1.dylib"),
                "windows", List.of("libwinpthread-1.dll"));
        final Path staging = Files.createDirectories(root.resolve("build/ffmpeg-packages"));
        final Path resources = Files.createDirectories(root.resolve("src/main/resources/libs"));
        final byte[] buffer = new byte[65536];
        final byte[] versionBytes = version.getBytes(StandardCharsets.UTF_8);
        final var timestamp = LocalDateTime.of(1980, 1, 2, 0, 0);
        final var manifest = new StringBuilder("version=" + version + "\nvariant=" + variant + "\nlicense=" + license + "\ncompression=DEFLATE\nlevel=" + level + "\n");
        manifest.append("source.kind=rebuilt\n")
                .append("source.ref=").append(properties.getProperty("ffmpeg_source_ref")).append('\n')
                .append("source.commit=").append(properties.getProperty("ffmpeg_source_commit")).append('\n')
                .append("libxml2.version=").append(properties.getProperty("libxml2_version")).append('\n')
                .append("libxml2.sha256=").append(properties.getProperty("libxml2_sha256")).append('\n')
                .append("openssl.version=").append(properties.getProperty("openssl_version")).append('\n')
                .append("openssl.sha256=").append(properties.getProperty("openssl_sha256")).append('\n')
                .append("tls.patch.sha256=").append(properties.getProperty("ffmpeg_tls_patch_sha256")).append('\n')
                .append("security.patch.sha256=").append(properties.getProperty("ffmpeg_security_patch_sha256")).append('\n')
                .append("headers.patch.sha256=").append(properties.getProperty("ffmpeg_headers_patch_sha256")).append('\n');

        for (final var platform: platforms.entrySet()) {
            final String name = "ffmpeg-" + version + "-" + platform.getValue() + ".jar";
            final URI uri;
            final Path source;
            final String expected;
            final Path candidate = rebuilt.resolve(platform.getValue());
            final var record = new Properties();
            try (final var input = Files.newInputStream(candidate.resolve("build.properties"))) {
                record.load(input);
            }
                if (!version.equals(record.getProperty("version")) || !platform.getValue().equals(record.getProperty("platform"))
                        || !name.equals(record.getProperty("archive"))
                        || !properties.getProperty("ffmpeg_source_ref").equals(record.getProperty("source.ref"))
                        || !properties.getProperty("ffmpeg_source_commit").equals(record.getProperty("source.commit"))
                        || !properties.getProperty("ffmpeg_source_sha256").equalsIgnoreCase(record.getProperty("source.sha256", ""))
                        || !properties.getProperty("libxml2_version").equals(record.getProperty("libxml2.version"))
                        || !properties.getProperty("libxml2_sha256").equalsIgnoreCase(record.getProperty("libxml2.sha256", ""))
                        || !properties.getProperty("openssl_version").equals(record.getProperty("openssl.version"))
                        || !properties.getProperty("openssl_sha256").equalsIgnoreCase(record.getProperty("openssl.sha256", ""))
                        || !variant.equals(record.getProperty("variant"))
                        || !license.equals(record.getProperty("license"))
                        || !properties.getProperty("ffmpeg_tls_patch_sha256", "").matches("[a-fA-F0-9]{64}")
                        || !properties.getProperty("ffmpeg_tls_patch_sha256").equalsIgnoreCase(record.getProperty("tls.patch.sha256", ""))
                        || !properties.getProperty("ffmpeg_security_patch_sha256", "").matches("[a-fA-F0-9]{64}")
                        || !properties.getProperty("ffmpeg_security_patch_sha256").equalsIgnoreCase(record.getProperty("security.patch.sha256", ""))
                        || !properties.getProperty("ffmpeg_headers_patch_sha256", "").matches("[a-fA-F0-9]{64}")
                        || !properties.getProperty("ffmpeg_headers_patch_sha256").equalsIgnoreCase(record.getProperty("headers.patch.sha256", ""))
                        || !"JNI-original-wrappers,DASH,recursive-entities,libxml2-version,openssl-version,imports-closure,clean-environment,TLS,HTTP-headers,LGPL,no-x264,no-x265".equals(record.getProperty("verification"))) {
                    throw new IOException("Candidate version or native verification record does not match: " + candidate);
                }
            source = candidate.resolve(name);
            final String recipeHash = record.getProperty("recipe.sha256");
            if (recipeHash == null || !recipeHash.matches("[a-fA-F0-9]{64}")) {
                throw new IOException("Candidate recipe SHA-256 is missing or invalid: " + candidate);
            }
            final String candidateHash = record.getProperty("archive.sha256");
            if (candidateHash == null || !candidateHash.matches("[a-fA-F0-9]{64}") || !hash(source, "SHA-256").equalsIgnoreCase(candidateHash)) {
                throw new IOException("Candidate archive SHA-256 mismatch: " + source);
            }
            expected = hash(source, "SHA-1");
            uri = URI.create("rebuilt:" + properties.getProperty("ffmpeg_source_ref") + "/" + name);

            final String prefix = "org/bytedeco/ffmpeg/" + platform.getValue() + "/";
            final Path output = staging.resolve("ffmpeg-" + platform.getKey() + ".zip");
            final Map<String, String> hashes = new TreeMap<>();
            try (final ZipFile jar = new ZipFile(source.toFile())) {
                final var libraries = new TreeMap<String, ZipEntry>();
                for (final var entry: jar.stream().toList()) {
                    if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;
                    final String file = entry.getName().substring(prefix.length());
                    if (!file.matches("(?s).*(?:\\.dll|\\.dylib|\\.so(?:\\.\\d+)*)")) continue;
                    if (!file.matches("[a-zA-Z0-9_+.-]+(?:\\.dll|\\.dylib|\\.so(?:\\.\\d+)*)")) {
                        throw new IOException("Native library name cannot be represented safely in the flat manifest: " + file);
                    }
                    if (libraries.put(file, entry) != null) throw new IOException("Duplicate native: " + file);
                }
                for (final String component: new String[]{"avcodec", "avdevice", "avfilter", "avformat", "avutil", "swresample", "swscale"}) {
                    final boolean nativePresent = libraries.keySet().stream().anyMatch(file -> file.matches("(?:lib)?" + component + "[.-].*"));
                    final boolean jniPresent = libraries.keySet().stream().anyMatch(file -> file.matches("(?:lib)?jni" + component + "\\..*"));
                    if (!nativePresent || !jniPresent) throw new IOException("Missing native or JNI component: " + component);
                }
                final List<String> required = support.get(platform.getKey());
                if (libraries.size() != 14 + required.size() || !libraries.keySet().containsAll(required)) {
                    throw new IOException("Candidate must preserve the complete native inventory for " + platform.getValue());
                }
                try (final var zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))) {
                    zip.setLevel(level);
                    // AVOID THE JDK'S 1980-01-01 SENTINEL, WHICH ADDS TIME ZONE DEPENDENT METADATA.
                    for (final var library: libraries.entrySet()) {
                        final var entry = new ZipEntry(library.getKey());
                        entry.setTimeLocal(timestamp);
                        zip.putNextEntry(entry);
                        final var crc = new CRC32();
                        final var digest = MessageDigest.getInstance("SHA-256");
                        long size = 0;
                        try (final InputStream input = jar.getInputStream(library.getValue())) {
                            int count;
                            while ((count = input.read(buffer)) != -1) {
                                zip.write(buffer, 0, count);
                                crc.update(buffer, 0, count);
                                digest.update(buffer, 0, count);
                                size += count;
                            }
                        }
                        if (size != library.getValue().getSize() || crc.getValue() != library.getValue().getCrc()) {
                            throw new IOException("Source native CRC or size mismatch: " + library.getKey());
                        }
                        hashes.put(library.getKey(), HexFormat.of().formatHex(digest.digest()));
                        zip.closeEntry();
                    }
                    // WRITE THE VERSION LAST SO AN INTERRUPTED EXTRACTION CANNOT LOOK COMPLETE.
                    final var marker = new ZipEntry("version.cfg");
                    marker.setTimeLocal(timestamp);
                    zip.putNextEntry(marker);
                    zip.write(versionBytes);
                    zip.closeEntry();
                }
            }

            // READ BACK EVERY BYTE AND COMPARE BOTH CRC AND SHA-256 AGAINST THE VERIFIED SOURCE.
            final var verified = new HashSet<String>();
            try (final ZipFile zip = new ZipFile(output.toFile())) {
                for (final var entry: zip.stream().toList()) {
                    if (entry.getMethod() != ZipEntry.DEFLATED || !verified.add(entry.getName())) {
                        throw new IOException("Invalid archive entry: " + entry.getName());
                    }
                    final var digest = MessageDigest.getInstance("SHA-256");
                    final var crc = new CRC32();
                    long size = 0;
                    try (final var input = zip.getInputStream(entry)) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            crc.update(buffer, 0, count);
                            digest.update(buffer, 0, count);
                            size += count;
                        }
                    }
                    final String wanted = entry.getName().equals("version.cfg")
                            ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(versionBytes)) : hashes.get(entry.getName());
                    if (size != entry.getSize() || crc.getValue() != entry.getCrc()
                            || !HexFormat.of().formatHex(digest.digest()).equals(wanted)) {
                        throw new IOException("Repacked native verification failed: " + entry.getName());
                    }
                }
                if (verified.size() != hashes.size() + 1) throw new IOException("Incomplete native archive: " + output);
            }
            final String key = platform.getKey();
            manifest.append('\n').append(key).append(".source=").append(uri).append('\n')
                    .append(key).append(".source.sha1=").append(expected).append('\n')
                    .append(key).append(".source.sha256=").append(hash(source, "SHA-256")).append('\n')
                    .append(key).append(".source.bytes=").append(Files.size(source)).append('\n')
                    .append(key).append(".archive=").append(output.getFileName()).append('\n')
                    .append(key).append(".archive.sha256=").append(hash(output, "SHA-256")).append('\n')
                    .append(key).append(".archive.bytes=").append(Files.size(output)).append('\n')
                    .append(key).append(".libraries=").append(hashes.size()).append('\n');
            manifest.append(key).append(".recipe.sha256=").append(recipeHash.toLowerCase(Locale.ROOT)).append('\n');
            for (final var library: hashes.entrySet()) {
                manifest.append(key).append(".native.").append(library.getKey()).append(".sha256=").append(library.getValue()).append('\n');
            }
            System.out.println(output.getFileName() + ": " + hashes.size() + " libraries, " + Files.size(output) + " bytes, SHA-256 " + hash(output, "SHA-256"));
        }

        // INSTALL ONLY AFTER ALL FIVE PACKAGES HAVE PASSED VERIFICATION.
        for (final String platform: platforms.keySet()) {
            final String file = "ffmpeg-" + platform + ".zip";
            final Path output = resources.resolve(file);
            if (Files.isRegularFile(output) && hash(output, "SHA-256").equals(hash(staging.resolve(file), "SHA-256"))) {
                Files.delete(staging.resolve(file));
            } else {
                Files.move(staging.resolve(file), output, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        final Path record = tools.resolve("ffmpeg-manifest.properties");
        if (!Files.isRegularFile(record) || !Files.readString(record).contentEquals(manifest)) {
            Files.writeString(record, manifest, StandardCharsets.UTF_8);
        }
    }

    private static String hash(final Path file, final String algorithm) throws Exception {
        final var digest = MessageDigest.getInstance(algorithm);
        try (final var input = Files.newInputStream(file)) {
            final byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
