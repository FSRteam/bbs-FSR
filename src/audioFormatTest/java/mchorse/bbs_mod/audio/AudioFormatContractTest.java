package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.audio.wav.WaveReader;
import mchorse.bbs_mod.audio.wav.WaveWriter;
import mchorse.bbs_mod.audio.wav.WaveCue;
import mchorse.bbs_mod.audio.wav.WaveList;
import mchorse.bbs_mod.audio.ogg.VorbisReader;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.Pair;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

/** Focused, dependency-free executable contract tests for PCM/WAV boundaries. */
public final class AudioFormatContractTest {
    /* 256-frame, 8 kHz mono and three-channel fixtures generated with
     * libsndfile/Vorbis. The latter verifies that reserved multichannel input
     * is rejected before decoded PCM allocation. */
    private static final String VORBIS_FIXTURES_GZIP_BASE64 =
        "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" +
        "q7x+HvgfciPKiooVAAA=";

    public static void main(String[] args) throws Exception {
        Configuration.DEBUG_MEMORY_ALLOCATOR.set(true);
        formatsAndSamples();
        handwrittenGoldenWavs();
        allEncodingRoundTrips();
        layoutsAndExcerpts();
        waveValidationAndFloatLocations();
        riffAndRoundTrip();
        metadataRoundTrip();
        extensibleAndCoreValidation();
        malformedAndPartialReads();
        decodeLimits();
        writerOwnershipAndPolicies();
        writerFailureCleanup();
        audioReaderDispatchAndClose();
        System.out.println("AudioFormatContractTest: all tests passed");
    }

    private static void formatsAndSamples() {
        check(PcmEncoding.fromWaveFormat(1, 8) == PcmEncoding.PCM_U8, "u8 mapping");
        check(PcmEncoding.fromWaveFormat(1, 24) == PcmEncoding.PCM_S24_LE, "s24 mapping");
        check(PcmEncoding.fromWaveFormat(3, 32) == PcmEncoding.IEEE_FLOAT32_LE, "float mapping");
        expect(IllegalArgumentException.class, () -> PcmEncoding.fromWaveFormat(1, 20));
        check(PcmEncoding.PCM_U8.unsigned() && !PcmEncoding.PCM_U8.signed(), "u8 signedness metadata");
        check(PcmEncoding.PCM_S24_LE.signed() && !PcmEncoding.PCM_S24_LE.unsigned(), "s24 signedness metadata");
        check(PcmEncoding.IEEE_FLOAT32_LE.floatingPoint()
            && !PcmEncoding.IEEE_FLOAT32_LE.signed()
            && !PcmEncoding.IEEE_FLOAT32_LE.unsigned(), "float sample-kind metadata");
        check(PcmEncoding.PCM_S32_LE.byteOrder() == ByteOrder.LITTLE_ENDIAN, "PCM byte order metadata");
        check(PcmEncoding.PCM_U8.openAlCompatible() && PcmEncoding.PCM_S16_LE.openAlCompatible(),
            "OpenAL core-compatible metadata");
        check(!PcmEncoding.PCM_S24_LE.openAlCompatible()
            && !PcmEncoding.IEEE_FLOAT32_LE.openAlCompatible(), "OpenAL conversion-required metadata");
        check(Arrays.stream(PcmEncoding.values()).allMatch(PcmEncoding::exportSupported),
            "all supported PCM encodings are WAV-exportable");
        check(Math.abs(PcmSamples.readNormalized(PcmEncoding.PCM_U8, new byte[]{(byte) 128}, 0)) < 1e-9, "u8 zero");
        check(PcmSamples.readNormalized(PcmEncoding.PCM_S24_LE, new byte[]{0, 0, (byte) 0x80}, 0) < -0.99, "s24 sign");
        check(PcmSamples.readNormalized(PcmEncoding.IEEE_FLOAT32_LE,
            new byte[]{0, 0, 0, 64}, 0) == 2.0, "finite float decode preserves out-of-range value");
        byte[] out = new byte[4];
        PcmSamples.writeNormalized(PcmEncoding.PCM_S32_LE, out, 0, -1);
        check(Arrays.equals(out, new byte[]{0, 0, 0, (byte) 0x80}), "s32 minimum");
        PcmSamples.writeNormalized(PcmEncoding.IEEE_FLOAT32_LE, out, 0, 2.0);
        check(PcmSamples.readNormalized(PcmEncoding.IEEE_FLOAT32_LE, out, 0) == 1.0,
            "float encoding clamps at write boundary");
        check(ChannelLayout.normalizeExport(null) == ChannelLayout.MONO, "missing layout");
        check(ChannelLayout.normalizeExport("stereo") == ChannelLayout.STEREO, "stereo layout");
        check(ChannelLayout.normalizeExport("5.1") == ChannelLayout.MONO, "reserved layout fallback");
    }

    private static void handwrittenGoldenWavs() throws Exception {
        assertGolden("u8 mono", 1, 8, 1,
            new byte[]{0, (byte) 128, (byte) 255}, PcmEncoding.PCM_U8,
            new double[]{-1.0, 0.0, 0.9921875});
        assertGolden("u8 stereo", 1, 8, 2,
            new byte[]{0, (byte) 255, (byte) 128, 64}, PcmEncoding.PCM_U8,
            new double[]{-1.0, 0.9921875, 0.0, -0.5});
        assertGolden("s16 mono", 1, 16, 1,
            new byte[]{0, (byte) 128, 0, 0, (byte) 255, 127}, PcmEncoding.PCM_S16_LE,
            new double[]{-1.0, 0.0, 0.999969482421875});
        assertGolden("s16 stereo", 1, 16, 2,
            new byte[]{0, (byte) 128, (byte) 255, 127, 0, 64, 0, (byte) 192}, PcmEncoding.PCM_S16_LE,
            new double[]{-1.0, 0.999969482421875, 0.5, -0.5});
        assertGolden("s24 mono", 1, 24, 1,
            new byte[]{0, 0, (byte) 128, 0, 0, 0, (byte) 255, (byte) 255, 127}, PcmEncoding.PCM_S24_LE,
            new double[]{-1.0, 0.0, 0.9999998807907104});
        assertGolden("s24 stereo", 1, 24, 2,
            new byte[]{0, 0, (byte) 128, (byte) 255, (byte) 255, 127,
                0, 0, 64, 0, 0, (byte) 192}, PcmEncoding.PCM_S24_LE,
            new double[]{-1.0, 0.9999998807907104, 0.5, -0.5});
        assertGolden("s32 mono", 1, 32, 1,
            new byte[]{0, 0, 0, (byte) 128, 0, 0, 0, 0, (byte) 255, (byte) 255, (byte) 255, 127},
            PcmEncoding.PCM_S32_LE, new double[]{-1.0, 0.0, 0.9999999995343387});
        assertGolden("s32 stereo", 1, 32, 2,
            new byte[]{0, 0, 0, (byte) 128, (byte) 255, (byte) 255, (byte) 255, 127,
                0, 0, 0, 64, 0, 0, 0, (byte) 192},
            PcmEncoding.PCM_S32_LE, new double[]{-1.0, 0.9999999995343387, 0.5, -0.5});
        assertGolden("float mono", 3, 32, 1,
            new byte[]{0, 0, (byte) 128, (byte) 191, 0, 0, 0, 0, 0, 0, 0, 63},
            PcmEncoding.IEEE_FLOAT32_LE, new double[]{-1.0, 0.0, 0.5});
        assertGolden("float stereo", 3, 32, 2,
            new byte[]{0, 0, (byte) 128, 63, 0, 0, 0, (byte) 191,
                0, 0, 0, 63, 0, 0, 0, (byte) 191},
            PcmEncoding.IEEE_FLOAT32_LE, new double[]{1.0, -0.5, 0.5, -0.5});
    }

    private static void layoutsAndExcerpts() {
        byte[] pcm = new byte[8];
        PcmSamples.writeNormalized(PcmEncoding.PCM_S16_LE, pcm, 0, 1);
        PcmSamples.writeNormalized(PcmEncoding.PCM_S16_LE, pcm, 2, -1);
        PcmSamples.writeNormalized(PcmEncoding.PCM_S16_LE, pcm, 4, .25);
        PcmSamples.writeNormalized(PcmEncoding.PCM_S16_LE, pcm, 6, .75);
        Wave stereo = new Wave(new PcmFormat(PcmEncoding.PCM_S16_LE, ChannelLayout.STEREO, 4), pcm);
        check(stereo.getFrameCount() == 2, "frame count");
        Wave mono = stereo.convertLayout(ChannelLayout.MONO);
        check(mono.getFrameCount() == 2 && mono.getFormat().layout() == ChannelLayout.MONO, "explicit downmix");
        Wave duplicated = mono.convertLayout(ChannelLayout.STEREO);
        check(PcmSamples.readNormalized(duplicated, 0, 0) == PcmSamples.readNormalized(mono, 0, 0)
            && PcmSamples.readNormalized(duplicated, 0, 1) == PcmSamples.readNormalized(mono, 0, 0),
            "file layout conversion duplicates mono at unity");
        Wave excerpt = stereo.excerpt(.25f, .5f);
        check(excerpt.getFrameCount() == 1 && excerpt.getFormat().channels() == 2, "frame excerpt preserves stereo");
        check(stereo.convertTo16().getFormat().layout() == ChannelLayout.STEREO, "legacy conversion preserves layout");
        check(stereo.excerpt(.5f, .25f).getFrameCount() == 1, "reversed excerpt bounds");
        check(stereo.excerpt(-10f, 10f).getFrameCount() == 2, "clamped excerpt bounds");
        check(stereo.excerpt(.25f, .25f).getFrameCount() == 0, "empty excerpt");
        check(stereo.excerpt(.1f, .1f).getFrameCount() == 0, "fractional empty excerpt");
        expect(IllegalArgumentException.class, () -> stereo.convertLayout(ChannelLayout.SURROUND_5_1));

        Wave wideFloat = new Wave(new PcmFormat(PcmEncoding.IEEE_FLOAT32_LE, ChannelLayout.STEREO, 4),
            new byte[]{0, 0, 0, 64, 0, 0, 0, (byte) 191});
        Wave wideFloatMono = wideFloat.convertLayout(ChannelLayout.MONO);
        check(Math.abs(PcmSamples.readNormalized(wideFloatMono, 0, 0) - 0.75) < 1e-9,
            "float downmix averages before encoding-time clamp");
    }

    private static void waveValidationAndFloatLocations() {
        Wave nullData = new Wave(new PcmFormat(PcmEncoding.PCM_S16_LE, ChannelLayout.MONO, 8000),
            new byte[]{0, 0});
        nullData.data = null;
        IllegalStateException nullError = expect(IllegalStateException.class, nullData::getFormat);
        check(nullError.getMessage().contains("PCM data is null"), "null Wave.data has deterministic error");

        Wave nan = new Wave(new PcmFormat(PcmEncoding.IEEE_FLOAT32_LE, ChannelLayout.STEREO, 8000),
            new byte[]{0, 0, 0, 0, 0, 0, (byte) 192, 127});
        IllegalArgumentException directNanError = expect(IllegalArgumentException.class,
            () -> PcmSamples.readNormalized(nan, 0, 1));
        checkSampleLocation(directNanError, 0, 1, "direct float read location");
        IllegalArgumentException nanError = expect(IllegalArgumentException.class, nan::convertTo16);
        checkSampleLocation(nanError, 0, 1, "NaN conversion location");

        Wave infinity = new Wave(new PcmFormat(PcmEncoding.IEEE_FLOAT32_LE, ChannelLayout.MONO, 8000),
            new byte[]{0, 0, (byte) 128, 127});
        IllegalArgumentException infinityError = expect(IllegalArgumentException.class, infinity::convertTo16);
        checkSampleLocation(infinityError, 0, 0, "infinity conversion location");
    }

    private static void allEncodingRoundTrips() throws Exception {
        for (PcmEncoding encoding : PcmEncoding.values()) {
            for (ChannelLayout layout : new ChannelLayout[]{ChannelLayout.MONO, ChannelLayout.STEREO}) {
                PcmFormat format = new PcmFormat(encoding, layout, 44100);
                byte[] pcm = new byte[format.bytesPerFrame() * 2];
                int samples = layout.channels() * 2;
                for (int i = 0; i < samples; i++) {
                    PcmSamples.writeNormalized(encoding, pcm, i * format.bytesPerSample(), i % 2 == 0 ? -.75 : .5);
                }
                Wave source = new Wave(format, pcm);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                WaveWriter.write(output, source);
                byte[] encoded = output.toByteArray();
                if (encoding.floatingPoint()) {
                    check(uint32(encoded, 16) == 18, "float WAVEFORMATEX size");
                    check(matches(encoded, 38, "fact") && uint32(encoded, 42) == 4
                        && uint32(encoded, 46) == 2, "float fact frame count");
                }
                Wave decoded = new WaveReader().read(new ByteArrayInputStream(encoded));
                check(decoded.getFormat().equals(format), "format roundtrip " + encoding + "/" + layout);
                check(decoded.getFrameCount() == 2, "frame roundtrip " + encoding + "/" + layout);
                check(Math.abs(PcmSamples.readNormalized(decoded, 0, 0) + .75) < .000001,
                    "negative sample roundtrip " + encoding);
            }
        }
        expect(IllegalArgumentException.class, () -> PcmSamples.writeNormalized(
            PcmEncoding.IEEE_FLOAT32_LE, new byte[4], 0, Double.NaN));
    }

    private static void riffAndRoundTrip() throws Exception {
        byte[] data = new byte[]{0, (byte) 255, 127};
        Wave wave = new Wave(new PcmFormat(PcmEncoding.PCM_U8, ChannelLayout.MONO, 8000), data);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WaveWriter.write(bytes, wave);
        byte[] wav = bytes.toByteArray();
        check(wav[0] == 'R' && wav[8] == 'W', "RIFF signature");
        Wave decoded = new WaveReader().read(new ByteArrayInputStream(wav));
        check(Arrays.equals(decoded.data, data), "writer/read data roundtrip");
        check(decoded.getFormat().encoding() == PcmEncoding.PCM_U8, "roundtrip encoding");
        check(uint32(wav, 4) == wav.length - 8L, "RIFF size includes odd data pad");
        check(wav[wav.length - 1] == 0, "odd data pad byte");
    }

    private static void metadataRoundTrip() throws Exception {
        Wave wave = new Wave(new PcmFormat(PcmEncoding.PCM_S16_LE, ChannelLayout.MONO, 8000), new byte[]{0, 0});
        WaveList list = new WaveList("INFO");
        list.entries.add(new Pair<>("INAM", "abc"));
        wave.lists.add(list);
        WaveCue cue = new WaveCue();
        cue.id = 7;
        cue.position = 1;
        cue.dataChunkID = fourCC("data");
        cue.chunkStart = 2;
        cue.blockStart = 3;
        cue.sampleStart = 4;
        wave.cues.add(cue);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WaveWriter.write(output, wave);
        Wave decoded = new WaveReader().read(new ByteArrayInputStream(output.toByteArray()));
        check(decoded.lists.size() == 1 && decoded.lists.get(0).entries.size() == 1, "LIST roundtrip");
        check("abc".equals(decoded.lists.get(0).entries.get(0).b), "LIST value roundtrip");
        check(decoded.cues.size() == 1, "cue count roundtrip");
        WaveCue decodedCue = decoded.cues.get(0);
        check(decodedCue.id == 7 && decodedCue.position == 1
            && decodedCue.dataChunkID == fourCC("data")
            && decodedCue.chunkStart == 2 && decodedCue.blockStart == 3
            && decodedCue.sampleStart == 4, "all cue fields roundtrip");

        Wave excerptSource = new Wave(new PcmFormat(PcmEncoding.PCM_S16_LE, ChannelLayout.MONO, 4),
            new byte[8]);
        for (int i = 0; i < 4; i++) {
            WaveCue excerptCue = new WaveCue();
            excerptCue.id = 20 + i;
            excerptCue.position = 10 + i;
            excerptCue.dataChunkID = fourCC("data");
            excerptCue.chunkStart = 30 + i;
            excerptCue.blockStart = 40 + i;
            excerptCue.sampleStart = i;
            excerptSource.cues.add(excerptCue);
        }
        Wave excerpt = excerptSource.excerpt(.25f, .75f);
        check(excerpt.cues.size() == 2, "excerpt keeps only cues in half-open frame range");
        check(excerpt.cues.get(0).id == 21 && excerpt.cues.get(0).position == 10
            && excerpt.cues.get(0).sampleStart == 0
            && excerpt.cues.get(0).chunkStart == 31 && excerpt.cues.get(0).blockStart == 41,
            "first excerpt cue is rebased with fields preserved");
        check(excerpt.cues.get(1).id == 22 && excerpt.cues.get(1).position == 11
            && excerpt.cues.get(1).sampleStart == 1
            && excerpt.cues.get(1).dataChunkID == fourCC("data"),
            "second excerpt cue is rebased with fields preserved");
        check(Math.abs(excerptSource.getCues()[1] - 0.25F) < 0.0001F,
            "cue time uses sample offset rather than playlist position");

        byte[] base = rawWav(1, 16, 1, new byte[0], new byte[]{0, 0});
        byte[] fmtChunk = Arrays.copyOfRange(base, 12, 36);
        byte[] dataChunk = Arrays.copyOfRange(base, 36, base.length);
        byte[] binary = new byte[]{1, 0, 0, 0, (byte) 255, (byte) 128, 0};
        ByteArrayOutputStream adtl = new ByteArrayOutputStream();
        adtl.write("adtl".getBytes(StandardCharsets.US_ASCII));
        adtl.write(chunk("labl", binary));
        Wave binaryList = new WaveReader().read(new ByteArrayInputStream(
            riff(fmtChunk, chunk("LIST", adtl.toByteArray()), dataChunk)));
        check(binaryList.lists.size() == 1 && "adtl".equals(binaryList.lists.get(0).type),
            "binary LIST type is preserved");
        ByteArrayOutputStream binaryOutput = new ByteArrayOutputStream();
        WaveWriter.write(binaryOutput, binaryList);
        byte[] encodedBinaryList = binaryOutput.toByteArray();
        int labelOffset = indexOf(encodedBinaryList, "labl".getBytes(StandardCharsets.US_ASCII));
        check(labelOffset >= 0 && Arrays.equals(binary,
            Arrays.copyOfRange(encodedBinaryList, labelOffset + 8, labelOffset + 8 + binary.length)),
            "non-INFO LIST payload roundtrips byte-for-byte");
        binaryList.lists.get(0).entries.add(new Pair<>("note", "\u0100"));
        expect(IllegalArgumentException.class, () -> WaveWriter.write(new ByteArrayOutputStream(), binaryList));

        List<String> metadataDiagnostics = new ArrayList<>();
        byte[] malformedList = chunk("LIST", new byte[]{'I', 'N', 'F', 'O', 1});
        ByteArrayOutputStream malformedCuePayload = new ByteArrayOutputStream();
        write32(malformedCuePayload, 1);
        byte[] malformedCue = chunk("cue ", malformedCuePayload.toByteArray());
        Wave metadataRecovery = new WaveReader(AudioDecodeLimits.DEFAULT, "metadata.wav",
            metadataDiagnostics::add).read(new ByteArrayInputStream(
                riff(fmtChunk, malformedList, malformedCue, dataChunk)));
        check(metadataRecovery.getFrameCount() == 1 && metadataDiagnostics.size() == 1,
            "malformed optional metadata is bounded to one diagnostic and does not hide audio");
        check(metadataDiagnostics.get(0).contains("metadata.wav"),
            "metadata diagnostic identifies its source");
    }

    private static void extensibleAndCoreValidation() throws Exception {
        byte[] pcmGuid = new byte[]{1, 0, 0, 0, 0, 0, 16, 0, (byte) 128, 0, 0, (byte) 170, 0, 56, (byte) 155, 113};
        byte[] valid = extensibleWav(2, 16, 3, pcmGuid, new byte[]{0, 0, 0, 0});
        Wave wave = new WaveReader().read(new ByteArrayInputStream(valid));
        check(wave.getFormat().layout() == ChannelLayout.STEREO, "extensible stereo mask");
        check(new WaveReader().read(new ByteArrayInputStream(
            extensibleWav(2, 16, 3, pcmGuid, new byte[]{0, 0, 0, 0}, 23, new byte[]{9})))
            .getFrameCount() == 1, "declared extensible fmt tail");
        expect(MalformedAudioException.class, () -> new WaveReader().read(new ByteArrayInputStream(
            extensibleWav(2, 16, 3, pcmGuid, new byte[]{0, 0, 0, 0}, 22, new byte[]{9}))));
        expect(UnsupportedAudioFormatException.class,
            () -> new WaveReader().read(new ByteArrayInputStream(extensibleWav(2, 16, 4, pcmGuid, new byte[]{0, 0, 0, 0}))));
        byte[] badGuid = pcmGuid.clone();
        badGuid[0] = 2;
        expect(UnsupportedAudioFormatException.class,
            () -> new WaveReader().read(new ByteArrayInputStream(extensibleWav(2, 16, 3, badGuid, new byte[]{0, 0, 0, 0}))));

        byte[] base = minimalWav(PcmEncoding.PCM_S16_LE, 1, new byte[]{0, 0});
        byte[] badAlign = base.clone();
        badAlign[32] = 1;
        expect(MalformedAudioException.class, () -> new WaveReader().read(new ByteArrayInputStream(badAlign)));
        byte[] badRate = base.clone();
        badRate[28] = 1;
        badRate[29] = badRate[30] = badRate[31] = 0;
        expect(MalformedAudioException.class, () -> new WaveReader().read(new ByteArrayInputStream(badRate)));

        byte[] fmt = Arrays.copyOfRange(base, 12, 36);
        byte[] data = Arrays.copyOfRange(base, 36, base.length);
        byte[] padded = riff(fmt, chunk("JUNK", new byte[]{9}), data);
        check(new WaveReader().read(new ByteArrayInputStream(padded)).getFrameCount() == 1, "unknown odd chunk padding");
        expect(MalformedAudioException.class, () -> new WaveReader().read(new ByteArrayInputStream(riff(fmt, data, data))));
        expect(MalformedAudioException.class, () -> new WaveReader().read(new ByteArrayInputStream(riff(data))));

        expect(MalformedAudioException.class, () -> new WaveReader().read(new ByteArrayInputStream(
            rawWav(1, 16, 1, new byte[]{0}, new byte[]{0, 0}))));
        expect(MalformedAudioException.class, () -> new WaveReader().read(new ByteArrayInputStream(
            rawWav(3, 32, 1, new byte[]{0}, new byte[]{0, 0, 0, 0}))));
        check(new WaveReader().read(new ByteArrayInputStream(
            rawWav(1, 16, 1, new byte[]{0, 0}, new byte[]{0, 0}))).getFrameCount() == 1,
            "18-byte WAVEFORMATEX with empty extension");
        expect(MalformedAudioException.class, () -> new WaveReader().read(new ByteArrayInputStream(
            rawWav(1, 16, 1, new byte[]{1, 0}, new byte[]{0, 0}))));
        expect(MalformedAudioException.class, () -> new WaveReader().read(new ByteArrayInputStream(
            rawWav(1, 16, 1, new byte[]{2, 0, 9}, new byte[]{0, 0}))));
        expect(MalformedAudioException.class, () -> new WaveReader().read(new ByteArrayInputStream(
            rawWav(1, 16, 1, new byte[]{0, 0, 9}, new byte[]{0, 0}))));
        check(new WaveReader().read(new ByteArrayInputStream(
            rawWav(1, 16, 1, new byte[]{1, 0, 9}, new byte[]{0, 0}))).getFrameCount() == 1,
            "declared WAVEFORMATEX extension tail");
    }

    private static void malformedAndPartialReads() throws Exception {
        byte[] valid = minimalWav(PcmEncoding.PCM_S16_LE, 1, new byte[]{0, 0});
        WaveReader reader = new WaveReader();
        expect(MalformedAudioException.class,
            () -> reader.read(new ByteArrayInputStream(Arrays.copyOf(valid, valid.length - 1))));
        expect(MalformedAudioException.class,
            () -> reader.read(new ByteArrayInputStream(Arrays.copyOf(valid, 12))));
        InputStream oneByte = new InputStream() {
            int i;
            public int read() { return i < valid.length ? valid[i++] & 255 : -1; }
            public int read(byte[] b, int o, int l) {
                if (i >= valid.length) return -1;
                b[o] = valid[i++];
                return 1;
            }
        };
        check(reader.read(oneByte).getFrameCount() == 1, "partial-read stream");
        byte[] wrongSignature = valid.clone();
        wrongSignature[0] = 'R'; wrongSignature[1] = 'I'; wrongSignature[2] = 'F'; wrongSignature[3] = 'X';
        expect(MalformedAudioException.class, () -> reader.read(new ByteArrayInputStream(wrongSignature)));
    }

    private static void decodeLimits() throws Exception {
        byte[] valid = minimalWav(PcmEncoding.PCM_S16_LE, 1, new byte[]{0, 0});
        AudioDecodeLimits limits = new AudioDecodeLimits(128, 64, 1, 1);
        expect(AudioDecodeLimitException.class,
            () -> new WaveReader(limits).read(new ByteArrayInputStream(valid)));
    }

    private static void writerOwnershipAndPolicies() throws Exception {
        Wave wave = new Wave(new PcmFormat(PcmEncoding.PCM_U8, ChannelLayout.MONO, 8000), new byte[]{1});
        TrackingStream stream = new TrackingStream();
        WaveWriter.write(stream, wave);
        check(!stream.closed, "writer does not close caller stream");
        check(stream.flushes == 1, "writer flushes caller stream exactly once");
        check(uint32(stream.toByteArray(), 16) == 16
            && indexOf(stream.toByteArray(), "fact".getBytes(StandardCharsets.US_ASCII)) < 0,
            "integer WAV keeps canonical 16-byte fmt without fact");
        check(AudioImportPolicy.SOURCE.buildFfmpegArguments("in", "out").stream().noneMatch("-ac"::equals), "source import omits -ac");
        check(AudioImportPolicy.MONO.buildFfmpegArguments("in", "out").contains("1"), "mono import uses -ac 1");
        check(AudioImportPolicy.STEREO.buildFfmpegArguments("in", "out").contains("2"), "stereo import uses -ac 2");
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        WaveWriter.writeHeader(header, new PcmFormat(PcmEncoding.IEEE_FLOAT32_LE, ChannelLayout.STEREO, 48000), 8);
        byte[] floatHeader = header.toByteArray();
        check((floatHeader[20] & 255) == 3 && uint32(floatHeader, 16) == 18
            && floatHeader[36] == 0 && floatHeader[37] == 0, "float writer WAVEFORMATEX");
        check(matches(floatHeader, 38, "fact") && uint32(floatHeader, 42) == 4
            && uint32(floatHeader, 46) == 1 && matches(floatHeader, 50, "data"),
            "float streaming header carries fact frame count");
        check(uint32(floatHeader, 4) == 58, "float streaming RIFF size includes fmt extension and fact");
    }

    private static void writerFailureCleanup() throws Exception {
        Path root = Files.createTempDirectory("bbs-wave-writer-");

        try {
            Path target = Files.createDirectory(root.resolve("blocked.wav"));
            Path marker = Files.write(target.resolve("keep.txt"), new byte[]{1});
            Wave wave = new Wave(new PcmFormat(PcmEncoding.PCM_U8, ChannelLayout.MONO, 8000),
                new byte[]{1});

            expect(IOException.class, () -> WaveWriter.write(target.toFile(), wave));
            check(Files.exists(marker), "failed file publication preserves existing destination");

            try (Stream<Path> children = Files.list(root)) {
                check(children.noneMatch(path -> path.getFileName().toString().startsWith(".blocked.wav.")),
                    "failed file publication removes owned temporary output");
            }
        } finally {
            if (Files.exists(root)) {
                Path[] cleanup;
                try (Stream<Path> paths = Files.walk(root)) {
                    cleanup = paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
                }
                for (Path path : cleanup) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void audioReaderDispatchAndClose() throws Exception {
        byte[] wavBytes = rawWav(1, 16, 1, new byte[0], new byte[]{0, 0});
        TrackingAssetProvider wavProvider = new TrackingAssetProvider(wavBytes);
        Wave wav = AudioReader.read(wavProvider, new Link("test", "fixture.WAV"));
        check(wav.getFormat().encoding() == PcmEncoding.PCM_S16_LE, "AudioReader dispatches uppercase WAV");
        check(wavProvider.last != null && wavProvider.last.closed, "AudioReader closes successful WAV stream");

        TrackingAssetProvider malformedProvider = new TrackingAssetProvider(
            Arrays.copyOf(wavBytes, wavBytes.length - 1));
        expect(MalformedAudioException.class,
            () -> AudioReader.read(malformedProvider, new Link("test", "broken.wav")));
        check(malformedProvider.last != null && malformedProvider.last.closed,
            "AudioReader closes failed WAV stream");

        TrackingAssetProvider unsupportedProvider = new TrackingAssetProvider(wavBytes);
        expect(UnsupportedAudioFormatException.class,
            () -> AudioReader.read(unsupportedProvider, new Link("test", "fixture.mp3")));
        check(unsupportedProvider.opens == 0, "AudioReader rejects unsupported extension before opening asset");

        byte[] oggBytes;
        try (InputStream resource = AudioFormatContractTest.class.getResourceAsStream("/assets/bbs/sounds/click.ogg")) {
            check(resource != null, "bundled Vorbis fixture is available");
            oggBytes = resource.readAllBytes();
        }

        expect(AudioDecodeLimitException.class, () -> VorbisReader.read(new Link("test", "limited.ogg"),
            new ByteArrayInputStream(oggBytes), new AudioDecodeLimits(64, 64, 1024, 1000)));

        TrackingAssetProvider oggProvider = new TrackingAssetProvider(oggBytes);
        Wave ogg = AudioReader.read(oggProvider, new Link("test", "fixture.ogg"));
        check(ogg.getFormat().encoding() == PcmEncoding.PCM_S16_LE, "AudioReader dispatches Vorbis to PCM16");
        check(ogg.getFormat().sampleRate() == 44100 && ogg.numChannels == 2,
            "bundled Vorbis fixture preserves stereo sample rate");
        check(ogg.getFrameCount() == 81627, "bundled Vorbis fixture frame count");
        check(Math.abs(ogg.getDuration() - (81627F / 44100F)) < 0.000001F,
            "bundled Vorbis fixture duration");
        check(Math.abs(PcmSamples.readNormalized(ogg, 1051, 0) - (-0.343719482421875)) < 1e-9
            && Math.abs(PcmSamples.readNormalized(ogg, 1051, 1) - (-0.00628662109375)) < 1e-9,
            "bundled Vorbis fixture preserves L/R identity");
        check(oggProvider.last != null && oggProvider.last.closed, "AudioReader closes successful OGG stream");

        byte[][] generatedVorbis = generatedVorbisFixtures();
        Wave mono = VorbisReader.read(new Link("test", "mono.ogg"),
            new ByteArrayInputStream(generatedVorbis[0]));
        check(mono.getFormat().layout() == ChannelLayout.MONO && mono.getFormat().sampleRate() == 8000,
            "generated mono Vorbis preserves its layout and rate");
        check(mono.getFrameCount() == 256, "generated mono Vorbis frame count");
        expect(UnsupportedAudioFormatException.class, () -> VorbisReader.read(
            new Link("test", "three-channel.ogg"), new ByteArrayInputStream(generatedVorbis[1])));

        long nativeBefore = vorbisNativeBytes();
        for (int i = 0; i < 3; i++)
        {
            VorbisReader.read(new Link("test", "mono-repeat.ogg"),
                new ByteArrayInputStream(generatedVorbis[0]));
            expect(UnsupportedAudioFormatException.class, () -> VorbisReader.read(
                new Link("test", "three-repeat.ogg"), new ByteArrayInputStream(generatedVorbis[1])));
            expect(MalformedAudioException.class, () -> VorbisReader.read(
                new Link("test", "truncated-repeat.ogg"),
                new ByteArrayInputStream(Arrays.copyOf(generatedVorbis[0], 64))));
        }
        check(vorbisNativeBytes() == nativeBefore,
            "Vorbis encoded/sample native allocations are released on every path");

        TrackingAssetProvider truncatedOggProvider = new TrackingAssetProvider(
            Arrays.copyOf(oggBytes, Math.max(32, oggBytes.length / 2)));
        expect(MalformedAudioException.class,
            () -> AudioReader.read(truncatedOggProvider, new Link("test", "truncated.ogg")));
        check(truncatedOggProvider.last != null && truncatedOggProvider.last.closed,
            "AudioReader closes non-empty truncated OGG stream");

        TrackingAssetProvider emptyOggProvider = new TrackingAssetProvider(new byte[0]);
        MalformedAudioException emptyOgg = expect(MalformedAudioException.class,
            () -> AudioReader.read(emptyOggProvider, new Link("test", "empty.ogg")));
        check(emptyOgg.getMessage().contains("Vorbis stream is empty"), "OGG extension selects Vorbis decoder");
        check(emptyOggProvider.last != null && emptyOggProvider.last.closed,
            "AudioReader closes failed OGG stream");
    }

    private static void assertGolden(String label, int tag, int bits, int channels, byte[] data,
        PcmEncoding encoding, double[] expected) throws Exception {
        Wave decoded = new WaveReader().read(new ByteArrayInputStream(
            rawWav(tag, bits, channels, new byte[0], data)));
        check(decoded.getFormat().encoding() == encoding, label + " encoding");
        check(decoded.getFormat().channels() == channels, label + " channels");
        check(decoded.getFrameCount() == expected.length / channels, label + " frame count");
        check(Arrays.equals(decoded.data, data), label + " raw payload");

        for (int i = 0; i < expected.length; i++) {
            double actual = PcmSamples.readNormalized(encoding, decoded.data, i * encoding.bytesPerSample());
            check(Math.abs(actual - expected[i]) < 1e-7, label + " sample " + i + " expected "
                + expected[i] + " got " + actual);
        }
    }

    private static byte[] rawWav(int tag, int bits, int channels, byte[] fmtTail, byte[] data) throws IOException {
        ByteArrayOutputStream fmt = new ByteArrayOutputStream();
        int bytesPerSample = bits / 8;
        write16(fmt, tag);
        write16(fmt, channels);
        write32(fmt, 8000);
        write32(fmt, 8000L * channels * bytesPerSample);
        write16(fmt, channels * bytesPerSample);
        write16(fmt, bits);
        fmt.write(fmtTail);
        return riff(chunk("fmt ", fmt.toByteArray()), chunk("data", data));
    }

    private static byte[] extensibleWav(int channels, int bits, long mask, byte[] guid, byte[] data) throws IOException {
        return extensibleWav(channels, bits, mask, guid, data, 22, new byte[0]);
    }

    private static byte[] extensibleWav(int channels, int bits, long mask, byte[] guid, byte[] data,
        int extensionSize, byte[] extensionTail) throws IOException {
        ByteArrayOutputStream fmt = new ByteArrayOutputStream();
        write16(fmt, 0xfffe); write16(fmt, channels); write32(fmt, 8000);
        int bytes = bits / 8;
        write32(fmt, 8000L * channels * bytes); write16(fmt, channels * bytes); write16(fmt, bits);
        write16(fmt, extensionSize); write16(fmt, bits); write32(fmt, mask); fmt.write(guid); fmt.write(extensionTail);
        return riff(chunk("fmt ", fmt.toByteArray()), chunk("data", data));
    }

    private static byte[] riff(byte[]... chunks) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write("WAVE".getBytes(StandardCharsets.US_ASCII));
        for (byte[] chunk : chunks) payload.write(chunk);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        write32(out, payload.size());
        out.write(payload.toByteArray());
        return out.toByteArray();
    }

    private static byte[] chunk(String id, byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(id.getBytes(StandardCharsets.US_ASCII));
        write32(out, payload.length);
        out.write(payload);
        if ((payload.length & 1) != 0) out.write(0);
        return out.toByteArray();
    }

    private static void write16(OutputStream out, long value) throws IOException {
        out.write((int) value & 255); out.write((int) (value >>> 8) & 255);
    }
    private static void write32(OutputStream out, long value) throws IOException {
        out.write((int) value & 255); out.write((int) (value >>> 8) & 255);
        out.write((int) (value >>> 16) & 255); out.write((int) (value >>> 24) & 255);
    }
    private static long uint32(byte[] data, int offset) {
        return (data[offset] & 255L) | (data[offset + 1] & 255L) << 8
            | (data[offset + 2] & 255L) << 16 | (data[offset + 3] & 255L) << 24;
    }
    private static int fourCC(String id) {
        return (id.charAt(0) & 255) | (id.charAt(1) & 255) << 8
            | (id.charAt(2) & 255) << 16 | (id.charAt(3) & 255) << 24;
    }
    private static boolean matches(byte[] data, int offset, String value) {
        byte[] expected = value.getBytes(StandardCharsets.US_ASCII);
        return offset >= 0 && offset <= data.length - expected.length
            && Arrays.equals(expected, Arrays.copyOfRange(data, offset, offset + expected.length));
    }
    private static int indexOf(byte[] data, byte[] value) {
        for (int i = 0; i <= data.length - value.length; i++) {
            boolean match = true;
            for (int j = 0; j < value.length; j++) {
                if (data[i + j] != value[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    private static byte[][] generatedVorbisFixtures() throws IOException
    {
        byte[] packed;

        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(
            Base64.getDecoder().decode(VORBIS_FIXTURES_GZIP_BASE64))))
        {
            packed = input.readAllBytes();
        }

        ByteBuffer bytes = ByteBuffer.wrap(packed);
        int monoLength = bytes.getInt();
        byte[] mono = new byte[monoLength];
        bytes.get(mono);
        byte[] three = new byte[bytes.remaining()];
        bytes.get(three);

        return new byte[][]{mono, three};
    }

    private static long vorbisNativeBytes()
    {
        AtomicLong total = new AtomicLong();
        MemoryUtil.memReport((address, size, threadId, threadName, stackTrace) -> {
            for (StackTraceElement element : stackTrace)
            {
                if (element.getClassName().equals(VorbisReader.class.getName()))
                {
                    total.addAndGet(size);
                    break;
                }
            }
        });

        return total.get();
    }

    private static byte[] minimalWav(PcmEncoding encoding, int channels, byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WaveWriter.write(out, new Wave(new PcmFormat(encoding, ChannelLayout.fromChannelCount(channels), 8000), data));
        return out.toByteArray();
    }
    private static void checkSampleLocation(IllegalArgumentException error, int frame, int channel, String label) {
        String message = error.getMessage();
        check(message != null && message.contains("frame") && message.contains(Integer.toString(frame))
            && message.contains("channel") && message.contains(Integer.toString(channel)), label + ": " + message);
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private static <T extends Throwable> T expect(Class<T> type, Throwing action) {
        try { action.run(); } catch (Throwable e) { if (type.isInstance(e)) return type.cast(e); throw new AssertionError("wrong exception: " + e, e); }
        throw new AssertionError("expected " + type.getSimpleName());
    }
    private interface Throwing { void run() throws Exception; }
    private static final class TrackingStream extends ByteArrayOutputStream {
        boolean closed;
        int flushes;
        @Override public void flush() throws IOException { flushes++; super.flush(); }
        @Override public void close() { closed = true; }
    }
    private static final class TrackingAssetProvider extends AssetProvider {
        private final byte[] bytes;
        int opens;
        TrackingInputStream last;

        private TrackingAssetProvider(byte[] bytes) { this.bytes = bytes; }

        @Override
        public InputStream getAsset(Link link) {
            this.opens++;
            this.last = new TrackingInputStream(this.bytes);
            return this.last;
        }
    }
    private static final class TrackingInputStream extends ByteArrayInputStream {
        boolean closed;

        private TrackingInputStream(byte[] bytes) { super(bytes); }

        @Override
        public void close() throws IOException {
            this.closed = true;
            super.close();
        }
    }
}
