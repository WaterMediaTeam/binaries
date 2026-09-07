import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB.Callback_Pointer;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Checks certificate trust and endpoint identity through real native HTTPS, HLS and DASH reads. */
public final class VerifyTLS {
    private static final char[] PASSWORD = "watermedia-test-only".toCharArray();

    public static void main(final String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected the generated single-stream DASH manifest");
        final Path manifest = Path.of(args[0]).toAbsolutePath();
        final Path media = manifest.getParent();
        final Path work = Files.createDirectory(media.resolve("tls-" + UUID.randomUUID()));
        final Map<String, byte[]> files = new HashMap<>();
        final List<String> initializations = new ArrayList<>(), segments = new ArrayList<>();
        try (final var entries = Files.list(media)) {
            for (final Path file: entries.filter(Files::isRegularFile).toList()) {
                final String name = file.getFileName().toString();
                if (name.startsWith("init-") && name.endsWith(".m4s")) initializations.add(name);
                if (name.startsWith("chunk-") && name.endsWith(".m4s")) segments.add(name);
                if (name.endsWith(".m4s")) files.put("/" + name, Files.readAllBytes(file));
            }
        }
        if (initializations.size() != 1 || segments.size() != 1)
            throw new IllegalStateException("TLS verification requires the generated one-stream, one-segment DASH fixture");
        files.put("/sample.wav", wave());
        final KeyStore goodKeys = keys(work, "good", "localhost", "dns:localhost,ip:127.0.0.1");
        final KeyStore wrongKeys = keys(work, "wrong", "wrong.invalid", "dns:wrong.invalid");
        final KeyStore unknownKeys = keys(work, "unknown", "localhost", "dns:localhost,ip:127.0.0.1");
        final KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, PASSWORD);
        trust.setCertificateEntry("good", goodKeys.getCertificate("server"));
        trust.setCertificateEntry("wrong", wrongKeys.getCertificate("server"));
        try (final var output = Files.newOutputStream(work.resolve("trust.p12"))) { trust.store(output, PASSWORD); }
        final var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        final StringBuilder pem = new StringBuilder();
        for (final var manager: managers.getTrustManagers()) {
            if (manager instanceof final X509TrustManager x509) {
                for (final var certificate: x509.getAcceptedIssuers()) {
                    pem.append("-----BEGIN CERTIFICATE-----\n")
                            .append(Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(certificate.getEncoded()))
                            .append("\n-----END CERTIFICATE-----\n");
                }
            }
        }
        if (pem.length() == 0) throw new IllegalStateException("Fixture trust manager contains no trust anchors");
        final Path roots = Files.writeString(work.resolve("roots.pem"), pem, StandardCharsets.US_ASCII);
        final Path unicodeRoots = Files.writeString(work.resolve("roots-\u00e9.pem"), pem, StandardCharsets.US_ASCII);
        final String mpd = Files.readString(manifest);
        final int period = mpd.indexOf("<Period");
        if (period < 0) throw new IllegalStateException("Generated DASH fixture lacks a Period");
        try (final Endpoint good = new Endpoint(goodKeys, files);
             final Endpoint wrong = new Endpoint(wrongKeys, files);
             final Endpoint unknown = new Endpoint(unknownKeys, files)) {
            final String init = initializations.get(0), segment = segments.get(0);
            good.files.put("/good.m3u8", playlist(good.ip(), good.ip(), init, segment));
            good.files.put("/unknown.m3u8", playlist(unknown.dns(), unknown.dns(), init, segment));
            good.files.put("/wrong.m3u8", playlist(wrong.ip(), wrong.ip(), init, segment));
            good.files.put("/unknown-segment.m3u8", playlist(good.ip(), unknown.dns(), init, segment));
            unknown.files.put("/good.m3u8", playlist(unknown.dns(), unknown.dns(), init, segment));
            good.files.put("/master-good.m3u8", master(good.ip() + "/good.m3u8"));
            good.files.put("/master-unknown.m3u8", master(unknown.dns() + "/good.m3u8"));
            for (final Endpoint target: List.of(good, wrong, unknown)) {
                final String name = target == good ? "good" : target == wrong ? "wrong" : "unknown";
                final String base = target == good || target == wrong ? target.ip() : target.dns();
                final String modified = mpd.substring(0, period) + "<BaseURL>" + base + "/</BaseURL>\n" + mpd.substring(period);
                good.files.put("/" + name + ".mpd", modified.getBytes(StandardCharsets.UTF_8));
            }
            good.files.put("/unknown-segment.mpd", new String(good.files.get("/good.mpd"), StandardCharsets.UTF_8)
                    .replace("media=\"", "media=\"" + unknown.dns() + "/").getBytes(StandardCharsets.UTF_8));
            good.redirects.put("/redirect-good", good.ip() + "/sample.wav");
            good.redirects.put("/redirect-unknown", unknown.dns() + "/sample.wav");
            expect("HTTPS private CA and DNS", good.dns() + "/sample.wav", null, roots, true, null);
            expect("HTTPS private CA and IP", good.ip() + "/sample.wav", null, roots, true, null);
            expect("HTTPS Unicode CA path", good.dns() + "/sample.wav", null, unicodeRoots, true, null);
            expect("HTTPS untrusted chain", unknown.dns() + "/sample.wav", null, roots, false, unknown);
            expect("HTTPS wrong DNS", wrong.dns() + "/sample.wav", null, roots, false, wrong);
            expect("HTTPS wrong IP", wrong.ip() + "/sample.wav", null, roots, false, wrong);
            expect("HTTPS verifies by default", unknown.dns() + "/sample.wav", null, null, false, unknown);
            expect("HTTPS redirect to valid IP", good.dns() + "/redirect-good", null, roots, true, null);
            expect("HTTPS redirect to untrusted peer", good.dns() + "/redirect-unknown", null, roots, false, unknown);
            expect("HLS private CA across DNS and IP", good.dns() + "/good.m3u8", "hls", roots, true, null);
            expect("HLS rejects untrusted initialization", good.dns() + "/unknown.m3u8", "hls", roots, false, unknown);
            expect("HLS rejects wrong-IP initialization", good.dns() + "/wrong.m3u8", "hls", roots, false, wrong);
            expect("HLS rejects untrusted segment after valid initialization", good.dns() + "/unknown-segment.m3u8", "hls", roots, false, unknown);
            expect("HLS master retains private CA", good.dns() + "/master-good.m3u8", "hls", roots, true, null);
            expect("HLS master rejects untrusted playlist", good.dns() + "/master-unknown.m3u8", "hls", roots, false, unknown);
            expect("DASH private CA across DNS and IP", good.dns() + "/good.mpd", "dash", roots, true, null);
            expect("DASH rejects untrusted initialization", good.dns() + "/unknown.mpd", "dash", roots, false, unknown);
            expect("DASH rejects wrong-IP initialization", good.dns() + "/wrong.mpd", "dash", roots, false, wrong);
            expect("DASH rejects untrusted segment after valid initialization", good.dns() + "/unknown-segment.mpd", "dash", roots, false, unknown);
            for (final String format: List.of("m3u8", "mpd")) {
                final String demuxer = format.equals("m3u8") ? "hls" : "dash";
                final Path localGood = Files.write(work.resolve("local-good." + format), good.files.get("/good." + format));
                final Path localBad = Files.write(work.resolve("local-unknown." + format), good.files.get("/unknown." + format));
                expect("Local " + demuxer + " retains private CA", localGood.toString().replace('\\', '/'), demuxer, roots, true, null);
                expect("Local " + demuxer + " retains Unicode CA path", localGood.toString().replace('\\', '/'), demuxer, unicodeRoots, true, null);
                expect("Local " + demuxer + " rejects untrusted HTTPS media", localBad.toString().replace('\\', '/'), demuxer, roots, false, unknown);
            }
            final HttpServer plain = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            plain.createContext("/", exchange -> good.handle(exchange));
            plain.start();
            try {
                final String base = "http://127.0.0.1:" + plain.getAddress().getPort();
                expect("HTTP playlist retains HTTPS trust", base + "/good.m3u8", "hls", roots, true, null);
                expect("HTTP playlist rejects untrusted HTTPS media", base + "/unknown.m3u8", "hls", roots, false, unknown);
                expect("HTTP DASH retains HTTPS trust", base + "/good.mpd", "dash", roots, true, null);
                expect("HTTP DASH rejects untrusted HTTPS media", base + "/unknown.mpd", "dash", roots, false, unknown);
            } finally { plain.stop(0); }
        }
        System.out.println("TLS_VERIFIED HTTPS,HLS,DASH,private-CA,DNS,IP,redirects,Unicode-CA,default-verify,local-manifests");
    }

    private static byte[] wave() {
        final ByteBuffer data = ByteBuffer.allocate(16044).order(ByteOrder.LITTLE_ENDIAN);
        data.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(16036)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(16000);
        return data.array();
    }

    private static KeyStore keys(final Path directory, final String name, final String host, final String san) throws Exception {
        final String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        final Path store = directory.resolve(name + ".p12"), log = directory.resolve(name + "-keytool.log");
        final Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool" + suffix).toString(),
                "-J-Dfile.encoding=UTF-8", "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=" + host, "-ext", "SAN=" + san, "-validity", "2", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD), "-noprompt")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!keytool.waitFor(30, TimeUnit.SECONDS)) throw new IOException("Fixture key generation timed out");
            if (keytool.exitValue() != 0) throw new IOException(Files.readString(log));
        } finally {
            if (keytool.isAlive()) keytool.destroyForcibly().waitFor();
        }
        final KeyStore keys = KeyStore.getInstance("PKCS12");
        try (final var input = Files.newInputStream(store)) { keys.load(input, PASSWORD); }
        return keys;
    }

    private static byte[] playlist(final String initBase, final String segmentBase, final String init, final String segment) {
        return ("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n"
                + "#EXT-X-MAP:URI=\"" + initBase + "/" + init + "\"\n#EXTINF:1,\n" + segmentBase + "/" + segment
                + "\n#EXT-X-ENDLIST\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] master(final String target) {
        return ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000\n" + target + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void expect(final String name, final String url, final String demuxer, final Path roots,
                               final boolean success, final Endpoint rejected) {
        final int previous = rejected == null ? 0 : rejected.requests.get();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        final Callback_Pointer interrupt = new Callback_Pointer() {
            @Override public int call(final Pointer ignored) { return System.nanoTime() >= deadline ? 1 : 0; }
        };
        AVFormatContext context = avformat.avformat_alloc_context();
        final AVDictionary options = new AVDictionary((Pointer) null);
        final boolean local = !url.startsWith("https:") && !url.startsWith("http:");
        AVPacket packet = null;
        boolean read = false;
        try {
            context.interrupt_callback().callback(interrupt);
            if (roots != null) {
                final var nested = new AVDictionary((Pointer) null);
                final var serialized = new BytePointer((Pointer) null);
                try {
                    if (avutil.av_dict_set(nested, "tls_verify", "1", 0) < 0
                            || avutil.av_dict_set(nested, "ca_file", roots.toString(), 0) < 0
                            || avutil.av_dict_copy(options, nested, 0) < 0)
                        throw new IllegalStateException("Could not configure TLS fixture options");
                    // REMOTE MANIFESTS MUST INHERIT HTTP OPTIONS; ONLY LOCAL INPUTS SEED THE DEMUXER DICTIONARY.
                    if (local && (avutil.av_dict_get_string(nested, serialized, (byte) '=', (byte) ':') < 0
                            || avutil.av_dict_set(options, "protocol_opts", serialized.getString(StandardCharsets.UTF_8), 0) < 0))
                        throw new IllegalStateException("Could not configure nested TLS fixture options");
                } finally {
                    avutil.av_free(serialized);
                    avutil.av_dict_free(nested);
                }
            }
            // ONLY LOCAL FIXTURES AUTHORIZE NETWORK PROTOCOLS TO EXERCISE NESTED TLS OPTIONS.
            if (local)
                avutil.av_dict_set(options, "protocol_whitelist", "file,http,https,tcp,tls,crypto,data", 0);
            avutil.av_dict_set(options, "rw_timeout", "3000000", 0);
            final int result = avformat.avformat_open_input(context, url,
                    demuxer == null ? null : avformat.av_find_input_format(demuxer), options);
            if (result < 0) context = null;
            else if (avformat.avformat_find_stream_info(context, (PointerPointer<?>) null) >= 0) {
                packet = avcodec.av_packet_alloc();
                read = avformat.av_read_frame(context, packet) >= 0;
            }
        } finally {
            if (packet != null) avcodec.av_packet_free(packet);
            if (context != null) avformat.avformat_close_input(context);
            avutil.av_dict_free(options);
            interrupt.close();
        }
        if (read != success || (rejected != null && rejected.requests.get() != previous))
            throw new IllegalStateException(name + ": read=" + read + ", expected=" + success + ", rejected peer HTTP requests="
                    + (rejected == null ? 0 : rejected.requests.get() - previous));
        System.out.println("PASS " + name);
    }

    private static final class Endpoint implements AutoCloseable {
        final HttpsServer server;
        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final Map<String, String> redirects = new ConcurrentHashMap<>();
        final AtomicInteger requests = new AtomicInteger();

        Endpoint(final KeyStore keys, final Map<String, byte[]> media) throws Exception {
            this.files.putAll(media);
            final var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keys, PASSWORD);
            final var ssl = SSLContext.getInstance("TLS");
            ssl.init(managers.getKeyManagers(), null, null);
            this.server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.setHttpsConfigurator(new HttpsConfigurator(ssl));
            this.server.createContext("/", this::handle);
            this.server.start();
        }

        String dns() { return "https://localhost:" + this.server.getAddress().getPort(); }
        String ip() { return "https://127.0.0.1:" + this.server.getAddress().getPort(); }

        void handle(final HttpExchange exchange) throws IOException {
            this.requests.incrementAndGet();
            try (exchange) {
                final String path = exchange.getRequestURI().getPath();
                final String redirect = this.redirects.get(path);
                if (redirect != null) {
                    exchange.getResponseHeaders().set("Location", redirect);
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }
                final byte[] body = this.files.get(path);
                if (body == null) { exchange.sendResponseHeaders(404, -1); return; }
                exchange.getResponseHeaders().set("Content-Type", path.endsWith(".m3u8") ? "application/vnd.apple.mpegurl"
                        : path.endsWith(".mpd") ? "application/dash+xml" : "application/octet-stream");
                exchange.getResponseHeaders().set("Connection", "close");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        }

        @Override public void close() { this.server.stop(0); }
    }
}
