import java.io.RandomAccessFile;
import org.json.JSONObject;

/** Restores WAV frame count only; does not claim measured signal alignment. */
final class WaveLength {
    private static final class Wave { long offset, bytes; }
    private static long u32(RandomAccessFile f) throws Exception {
        return (f.readUnsignedByte() | ((long) f.readUnsignedByte() << 8)
            | ((long) f.readUnsignedByte() << 16) | ((long) f.readUnsignedByte() << 24));
    }
    private static int u16(RandomAccessFile f) throws Exception { return f.readUnsignedByte() | (f.readUnsignedByte() << 8); }
    private static String four(RandomAccessFile f) throws Exception { byte[] b = new byte[4]; f.readFully(b); return new String(b, "US-ASCII"); }
    private static void le32(RandomAccessFile f, long value) throws Exception { for (int i = 0; i < 4; i++) f.write((int) (value >> (i * 8)) & 255); }
    private static void le16(RandomAccessFile f, int value) throws Exception { f.write(value & 255); f.write((value >> 8) & 255); }
    private static Wave inspect(RandomAccessFile f) throws Exception {
        if (!four(f).equals("RIFF")) throw new IllegalArgumentException("Expected RIFF WAV");
        long limit = u32(f) + 8;
        if (!four(f).equals("WAVE") || limit > f.length()) throw new IllegalArgumentException("Invalid WAVE size");
        Wave wave = new Wave(); boolean format = false, data = false;
        while (f.getFilePointer() + 8 <= limit) {
            String id = four(f); long size = u32(f), begin = f.getFilePointer();
            if (begin + size > limit) throw new IllegalArgumentException("Truncated WAV chunk");
            if (id.equals("fmt ")) {
                if (format || size < 16 || u16(f) != 1 || u16(f) != 1 || u32(f) != 16000
                    || u32(f) != 32000 || u16(f) != 2 || u16(f) != 16)
                    throw new IllegalArgumentException("Requires PCM16 mono 16000 Hz WAV");
                format = true;
            } else if (id.equals("data")) {
                if (data || size % 2 != 0) throw new IllegalArgumentException("Invalid or repeated WAV data chunk");
                data = true; wave.offset = begin; wave.bytes = size;
            }
            f.seek(begin + size + size % 2);
        }
        if (!format || !data || wave.bytes == 0) throw new IllegalArgumentException("Missing PCM WAV samples");
        return wave;
    }

    static void align(String original, String filtered, String output, String sidecar) throws Exception {
        VextoPostprocess.distinctPaths(new String[]{original, filtered}, new String[]{output, sidecar});
        long originalBytes, filteredBytes;
        try (RandomAccessFile a = new RandomAccessFile(original, "r"); RandomAccessFile b = new RandomAccessFile(filtered, "r")) {
            Wave source = inspect(a), clean = inspect(b);
            originalBytes = source.bytes; filteredBytes = clean.bytes;
            if (originalBytes > 0xffffffffL - 36) throw new IllegalArgumentException("Output exceeds RIFF WAV limit");
            try (RandomAccessFile out = new RandomAccessFile(output, "rw")) {
                out.setLength(0); out.writeBytes("RIFF"); le32(out, originalBytes + 36); out.writeBytes("WAVEfmt ");
                le32(out, 16); le16(out, 1); le16(out, 1); le32(out, 16000); le32(out, 32000);
                le16(out, 2); le16(out, 16); out.writeBytes("data"); le32(out, originalBytes);
                b.seek(clean.offset); byte[] buffer = new byte[65536]; long remaining = Math.min(originalBytes, filteredBytes);
                while (remaining > 0) { int n = (int) Math.min(buffer.length, remaining); b.readFully(buffer, 0, n); out.write(buffer, 0, n); remaining -= n; }
                java.util.Arrays.fill(buffer, (byte) 0); remaining = Math.max(0, originalBytes - filteredBytes);
                while (remaining > 0) { int n = (int) Math.min(buffer.length, remaining); out.write(buffer, 0, n); remaining -= n; }
            }
        }
        JSONObject audit = new JSONObject().put("sample_rate", 16000).put("channels", 1).put("bits_per_sample", 16)
            .put("original_samples", originalBytes / 2).put("filtered_samples", filteredBytes / 2).put("output_samples", originalBytes / 2)
            .put("tail_padding_samples", Math.max(0, originalBytes - filteredBytes) / 2)
            .put("tail_truncated_samples", Math.max(0, filteredBytes - originalBytes) / 2)
            .put("method", "restore_frame_count_by_tail_padding_or_truncation_only")
            .put("sample_count_restored", true).put("alignment_verified", false)
            .put("documented_algorithmic_delay_samples", JSONObject.NULL).put("delay_correction_applied_samples", 0)
            .put("original_sha256", VextoPostprocess.hash(original)).put("filtered_sha256", VextoPostprocess.hash(filtered))
            .put("source_reference", "https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/sherpa-onnx/csrc/offline-speech-denoiser-gtcrn-impl.h")
            .put("limitation", "Original duration restored; no measured delay compensation or sample-accurate waveform alignment. ASR times retain this uncertainty.");
        VextoPostprocess.write(sidecar, audit.toString(2) + "\n");
    }
}
