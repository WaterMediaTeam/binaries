package org.watermedia.binaries;

import com.google.gson.JsonObject;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.JSONTool;
import java.io.IOException;
import java.net.URI;

/** Lazily installs the latest yt-dlp frozen executable using its publisher-provided SHA-256. */
public final class YtDlpBinary extends ExecutableBinary {
    private static final URI API = URI.create("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest");

    public YtDlpBinary() {
        super(WaterMediaBinaries.YTDLP_ID, switch (IOTool.platform()) {
            case "windows-x86_64" -> "yt-dlp.exe";
            case "windows-aarch64" -> "yt-dlp_arm64.exe";
            case "linux-x86_64" -> "yt-dlp_linux";
            case "linux-aarch64" -> "yt-dlp_linux_aarch64";
            case "macos-x86_64", "macos-aarch64" -> "yt-dlp_macos";
            default -> null;
        }, "watermedia.ytdlp.binary");
    }

    // BOUNDED HTTPS TEXT WITH FIXED LIMITS, INDEPENDENT OF THE USER'S NETWORK SETTINGS: EVERY HOP MUST BE HTTPS
    private static String fetch(final URI uri) throws IOException {
        try (final NetRequest request = new NetRequest(uri).httpsOnly().maxBytes(2L << 20).deadline(180_000L)
                .maxRedirects(8).connectTimeout(15_000).readTimeout(15_000).send()) {
            if (request.statusCode() != 200) throw new IOException("Download returned HTTP " + request.statusCode() + ": " + uri);
            return request.text();
        }
    }

    @Override
    protected Release latest() throws IOException {
        try {
            final JsonObject release = JSONTool.parse(fetch(API), JsonObject.class);
            final String version = release.get("tag_name").getAsString();
            JsonObject binary = null;
            URI checksums = null;
            for (final var value: release.getAsJsonArray("assets")) {
                final var asset = value.getAsJsonObject();
                final String name = asset.get("name").getAsString();
                if (this.name.equals(name)) binary = asset;
                else if ("SHA2-256SUMS".equals(name)) checksums = URI.create(asset.get("browser_download_url").getAsString());
            }
            if (binary == null || checksums == null) throw new IOException("yt-dlp release lacks its executable or mandatory SHA2-256SUMS");
            final String expected = checksum(fetch(checksums), this.name);
            if (binary.has("digest") && !binary.get("digest").isJsonNull()
                    && !binary.get("digest").getAsString().equalsIgnoreCase("sha256:" + expected)) {
                throw new IOException("yt-dlp asset digest disagrees with SHA2-256SUMS");
            }
            return new Release(version, URI.create(binary.get("browser_download_url").getAsString()), expected);
        } catch (final RuntimeException e) {
            throw new IOException("Invalid yt-dlp release metadata", e);
        }
    }

    static String checksum(final String sums, final String target) throws IOException {
        String result = null;
        for (final String line: sums.split("\\R")) {
            final String[] parts = line.strip().split("\\s+", 2);
            if (parts.length != 2) continue;
            final String name = parts[1].startsWith("*") ? parts[1].substring(1) : parts[1];
            if (!target.equals(name)) continue;
            if (result != null) throw new IOException("Duplicate yt-dlp checksum entry: " + target);
            result = IOTool.requireSha256(parts[0]);
        }
        if (result == null) throw new IOException("Missing yt-dlp SHA-256 entry: " + target);
        return result;
    }

}
