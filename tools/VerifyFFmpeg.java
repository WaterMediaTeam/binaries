import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.global.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/** Verifies a rebuilt classifier in its native operating system before it becomes a release candidate. */
public final class VerifyFFmpeg {
    public static void main(final String[] args) throws Exception {
        final String version = avutil.av_version_info().getString();
        if (!version.equals(args[0])) throw new IllegalStateException("Unexpected FFmpeg version: " + version);
        avcodec.avcodec_version();
        avformat.avformat_version();
        avdevice.avdevice_version();
        avfilter.avfilter_version();
        swscale.swscale_version();
        swresample.swresample_version();
        if (avcodec.avcodec_find_encoder_by_name("libx264") == null || avcodec.avcodec_find_encoder_by_name("libx265") == null) {
            throw new IllegalStateException("GPL encoders are missing");
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
                    || avformat.avformat_find_stream_info(format, (org.bytedeco.javacpp.PointerPointer) null) < 0 || format.nb_streams() < 1) {
                throw new IllegalStateException("Rebuilt FFmpeg failed the local DASH playback probe");
            }
        } finally {
            avformat.avformat_close_input(format);
        }
        final Path entities = Path.of(args[4]).toAbsolutePath().getParent().resolve("recursive-entities.mpd");
        java.nio.file.Files.writeString(entities, "<!DOCTYPE MPD [<!ENTITY a '&b;'><!ENTITY b '&a;'>]><MPD xmlns='urn:mpeg:dash:schema:mpd:2011' profiles='urn:mpeg:dash:profile:isoff-on-demand:2011'>&a;</MPD>");
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
            try {
                if (avformat.avformat_alloc_output_context2(output, null, "mpeg", (String) null) < 0)
                    throw new IllegalStateException("MPEG program stream muxer is unavailable");
                for (int index = 0; index < streams; index++) {
                    final var stream = avformat.avformat_new_stream(output, null);
                    if (stream == null || stream.isNull()) throw new IllegalStateException("Cannot allocate the MPEG fixture stream");
                    stream.time_base().num(1).den(25);
                    stream.codecpar().codec_type(avutil.AVMEDIA_TYPE_VIDEO).codec_id(avcodec.AV_CODEC_ID_MPEG2VIDEO)
                            .width(16).height(16).bit_rate(64000);
                }
                // THE SYSTEM HEADER FITS 34 STREAMS; INITIALIZATION MUST REJECT THE NEXT BEFORE WRITING PACKETS.
                final int result = avformat.avformat_init_output(output, (AVDictionary) null);
                if ((result >= 0) != (streams <= 34))
                    throw new IllegalStateException("MPEG system-header bounds failed for " + streams + " streams: " + result);
            } finally {
                avformat.avformat_free_context(output);
            }
        }
        System.out.println("Verified FFmpeg " + version + ", libxml2 " + args[1] + ", OpenSSL " + args[2] + ", seven JNI components, GPL encoders, DASH, malformed XML and MPEG stream-count boundaries");
    }
}
