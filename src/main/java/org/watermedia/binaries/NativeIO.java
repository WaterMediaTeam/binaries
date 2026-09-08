package org.watermedia.binaries;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;

final class NativeIO {
    private NativeIO() {}

    static String platform() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        final String system = os.contains("mac") || os.contains("darwin") ? "macos" : os.contains("win") ? "windows" : os.contains("linux") ? "linux" : null;
        final String cpu = switch (arch) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> null;
        };
        return system == null || cpu == null ? "unsupported" : system + "-" + cpu;
    }

    static JsonObject json(final URI uri) throws IOException {
        try {
            return JsonParser.parseString(text(uri)).getAsJsonObject();
        } catch (final RuntimeException e) {
            throw new IOException("Invalid release metadata: " + uri, e);
        }
    }

    static String text(final URI uri) throws IOException {
        final var output = new ByteArrayOutputStream();
        transfer(uri, output, 2L * 1024 * 1024);
        return output.toString(StandardCharsets.UTF_8);
    }

    static void download(final URI uri, final Path destination, final String expected) throws IOException {
        digest(expected);
        try (final var output = Files.newOutputStream(destination)) {
            transfer(uri, output, 512L * 1024 * 1024);
        }
        verify(destination, expected);
    }

    static void transfer(final URI origin, final OutputStream output, final long limit) throws IOException {
        URI uri = origin;
        final long deadline = System.nanoTime() + 180_000_000_000L;
        for (int redirects = 0; redirects <= 8; redirects++) {
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IOException("Native downloads require HTTPS without credentials: " + uri);
            }
            final HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "WaterMedia-Binaries");
            try {
                final int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    final String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect without a location: " + uri);
                    uri = uri.resolve(location);
                    continue;
                }
                if (status != 200) throw new IOException("Download returned HTTP " + status + ": " + uri);
                final long expected = connection.getContentLengthLong();
                if (expected > limit) throw new IOException("Native download exceeds its size limit: " + uri);
                long total = 0;
                try (final InputStream input = connection.getInputStream()) {
                    final byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > limit || System.nanoTime() > deadline) throw new IOException("Native transfer budget exceeded: " + uri);
                        output.write(buffer, 0, count);
                    }
                }
                if (expected >= 0 && total != expected) throw new IOException("Truncated native download: " + uri);
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many native download redirects: " + origin);
    }

    static String digest(final String value) throws IOException {
        if (value == null || !value.matches("[a-fA-F0-9]{64}")) throw new IOException("Missing or invalid SHA-256 digest");
        return value.toLowerCase(Locale.ROOT);
    }

    static String hash(final Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (final var input = Files.newInputStream(file)) {
            final byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void verify(final Path file, final String expected) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !hash(file).equals(digest(expected))) {
            throw new IOException("Native file SHA-256 mismatch: " + file.getFileName());
        }
    }

    static Properties properties(final Path file) throws IOException {
        final var result = new Properties();
        try (final var input = Files.newInputStream(file)) {
            result.load(input);
        }
        return result;
    }

    static void executable(final Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Executable is missing or is a symbolic link: " + file);
        if (!NativeIO.platform().startsWith("windows-") && !file.toFile().setExecutable(true, true)) {
            throw new IOException("Cannot grant executable permission: " + file);
        }
    }

    static void publish(final Path directory, final Path installation) throws IOException {
        final Path pointer = Files.createTempFile(directory, ".current-", ".tmp");
        try {
            Files.writeString(pointer, installation.getFileName().toString(), StandardCharsets.UTF_8);
            Files.move(pointer, directory.resolve("current"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pointer);
        }
    }

    static Path current(final Path directory) throws IOException {
        final Path pointer = directory.resolve("current");
        if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)) return null;
        final String name = Files.readString(pointer, StandardCharsets.UTF_8);
        if (!name.matches("install-[a-zA-Z0-9-]+")) return null;
        final Path result = directory.resolve(name);
        return Files.isDirectory(result, LinkOption.NOFOLLOW_LINKS) ? result : null;
    }

    static void delete(final Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (final var paths = Files.walk(directory)) {
            for (final Path path: paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
