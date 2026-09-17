import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.global.*;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.ZipFile;

/** Verifies a rebuilt classifier in its native operating system before it becomes a release candidate. */
public final class VerifyFFmpeg {
    public static void main(final String[] args) throws Exception {
        if (args.length != 5 && args.length != 6)
            throw new IllegalArgumentException("Usage: VerifyFFmpeg <version> <libxml2> <openssl> <classifier> <dash> [lgpl|gpl]");
        final String expectedVariant = args.length == 6 ? args[5] : null;
        if (expectedVariant != null && !expectedVariant.equals("lgpl") && !expectedVariant.equals("gpl"))
            throw new IllegalArgumentException("Unknown FFmpeg recipe variant: " + expectedVariant);
        final String version = avutil.av_version_info().getString();
        if (!version.equals(args[0])) throw new IllegalStateException("Unexpected FFmpeg version: " + version);
        avcodec.avcodec_version();
        avformat.avformat_version();
        avdevice.avdevice_version();
        avfilter.avfilter_version();
        swscale.swscale_version();
        swresample.swresample_version();
        final String[] licenses = {avutil.avutil_license().getString(), avcodec.avcodec_license().getString(),
                avformat.avformat_license().getString(), avdevice.avdevice_license().getString(),
                avfilter.avfilter_license().getString(), swscale.swscale_license().getString(),
                swresample.swresample_license().getString()};
        final String[] configurations = {avutil.avutil_configuration().getString(), avcodec.avcodec_configuration().getString(),
                avformat.avformat_configuration().getString(), avdevice.avdevice_configuration().getString(),
                avfilter.avfilter_configuration().getString(), swscale.swscale_configuration().getString(),
                swresample.swresample_configuration().getString()};
        for (int i = 0; i < licenses.length; i++) {
            if (licenses[i] == null || licenses[i].isBlank() || !licenses[0].equals(licenses[i]))
                throw new IllegalStateException("FFmpeg components disagree on their declared license");
            if (configurations[i] == null || configurations[i].isBlank())
                throw new IllegalStateException("FFmpeg component " + i + " has no build configuration");
            if (expectedVariant != null) {
                final var flags = Arrays.asList(configurations[i].trim().split("\\s+"));
                final boolean gpl = expectedVariant.equals("gpl");
                if (!flags.contains("--enable-version3") || !flags.contains("--disable-nonfree")
                        || flags.contains("--enable-nonfree")
                        || flags.contains("--enable-gpl") != gpl
                        || flags.contains("--disable-gpl") == gpl
                        || flags.contains("--enable-libx264") != gpl
                        || flags.contains("--enable-libx265") != gpl) {
                    throw new IllegalStateException("FFmpeg component " + i + " does not match the " + expectedVariant
                            + " recipe: " + configurations[i]);
                }
            }
        }
        if (expectedVariant != null) {
            final String declared = licenses[0].toLowerCase(Locale.ROOT);
            final boolean lesser = declared.contains("lgpl") || declared.contains("lesser general public license");
            final boolean general = declared.contains("gpl") || declared.contains("general public license");
            final boolean gpl = expectedVariant.equals("gpl");
            final var x264 = avcodec.avcodec_find_encoder_by_name("libx264");
            final var x265 = avcodec.avcodec_find_encoder_by_name("libx265");
            if (lesser == gpl || !general
                    || (x264 != null && !x264.isNull()) != gpl
                    || (x265 != null && !x265.isNull()) != gpl) {
                throw new IllegalStateException("FFmpeg license or x264/x265 encoders do not match the " + expectedVariant + " recipe");
            }
        }
        final String[] xml = args[1].split("\\.");
        final String expected = Integer.toString(Integer.parseInt(xml[0]) * 10000 + Integer.parseInt(xml[1]) * 100 + Integer.parseInt(xml[2]));
        try (final var archive = new ZipFile(args[3])) {
            final var files = archive.stream().filter(entry -> entry.getName().substring(entry.getName().lastIndexOf('/') + 1)
                    .matches("(?:lib)?avformat[.-].*(?:dll|dylib|so\\.\\d+)")).toList();
            if (files.size() != 1) throw new IllegalStateException("Expected exactly one libavformat shared library");
            try (final var input = archive.getInputStream(files.get(0))) {
                final String bytes = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
                if (!bytes.contains("\0" + expected + "\0") || bytes.contains("\0" + "20912" + "\0")) {
                    throw new IllegalStateException("libavformat does not identify the patched libxml2 version");
                }
                if (!bytes.contains("OpenSSL " + args[2] + " ")) throw new IllegalStateException("libavformat does not identify the patched OpenSSL version");
            }
        }
        final var format = new AVFormatContext(null);
        try {
            if (avformat.avformat_open_input(format, Path.of(args[4]).toAbsolutePath().toString().replace('\\', '/'), null, null) < 0
                    || avformat.avformat_find_stream_info(format, (PointerPointer<?>) null) < 0 || format.nb_streams() < 1) {
                throw new IllegalStateException("Rebuilt FFmpeg failed the local DASH playback probe");
            }
        } finally {
            avformat.avformat_close_input(format);
        }
        final Path entities = Path.of(args[4]).toAbsolutePath().getParent().resolve("recursive-entities.mpd");
        Files.writeString(entities, "<!DOCTYPE MPD [<!ENTITY a '&b;'><!ENTITY b '&a;'>]><MPD xmlns='urn:mpeg:dash:schema:mpd:2011' profiles='urn:mpeg:dash:profile:isoff-on-demand:2011'>&a;</MPD>");
        final var invalid = new AVFormatContext(null);
        try {
            // SELECT DASH EXPLICITLY SO MALFORMED XML REACHES ITS PARSER EVEN WHEN PROBING RULES CHANGE.
            if (avformat.avformat_open_input(invalid, entities.toString().replace('\\', '/'), avformat.av_find_input_format("dash"), null) >= 0) {
                throw new IllegalStateException("Invalid recursive-entity manifest was accepted");
            }
        } finally {
            avformat.avformat_close_input(invalid);
        }
        for (final int streams: new int[] { 1, 34, 35 }) {
            final var output = new AVFormatContext(null);
            final var buffer = new AVIOContext(null);
            try {
                if (avformat.avformat_alloc_output_context2(output, null, "mpeg", (String) null) < 0)
                    throw new IllegalStateException("MPEG program stream muxer is unavailable");
                if (avformat.avio_open_dyn_buf(buffer) < 0)
                    throw new IllegalStateException("Cannot allocate the MPEG fixture output");
                output.pb(buffer);
                for (int index = 0; index < streams; index++) {
                    final var stream = avformat.avformat_new_stream(output, null);
                    if (stream == null || stream.isNull()) throw new IllegalStateException("Cannot allocate the MPEG fixture stream");
                    stream.time_base().num(1).den(25);
                    if (index < 16) {
                        stream.codecpar().codec_type(avutil.AVMEDIA_TYPE_VIDEO).codec_id(avcodec.AV_CODEC_ID_MPEG2VIDEO)
                                .width(16).height(16).bit_rate(64000);
                    } else {
                        stream.codecpar().codec_type(avutil.AVMEDIA_TYPE_AUDIO).codec_id(avcodec.AV_CODEC_ID_MP2)
                                .sample_rate(44100).bit_rate(64000);
                        avutil.av_channel_layout_default(stream.codecpar().ch_layout(), 2);
                    }
                }
                // MPEG INITIALIZES IN WRITE_HEADER; ITS SYSTEM HEADER FITS 34 STREAMS BEFORE WRITING PACKETS.
                // MIX AUDIO AND VIDEO TO KEEP EACH STREAM ID WITHIN ITS MPEG RANGE.
                final int result = avformat.avformat_write_header(output, (AVDictionary) null);
                if ((result >= 0) != (streams <= 34))
                    throw new IllegalStateException("MPEG system-header bounds failed for " + streams + " streams: " + result);
            } finally {
                if (!buffer.isNull()) {
                    final var data = new BytePointer((Pointer) null);
                    avformat.avio_close_dyn_buf(buffer, data);
                    avutil.av_free(data);
                    output.pb(null);
                }
                avformat.avformat_free_context(output);
            }
        }
        System.out.println("Verified FFmpeg " + version + " (" + licenses[0] + "), libxml2 " + args[1]
                + ", OpenSSL " + args[2] + ", seven JNI components, DASH, malformed XML and MPEG stream-count boundaries");
    }
}
