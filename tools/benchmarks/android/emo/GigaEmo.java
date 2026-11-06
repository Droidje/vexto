import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Standalone CPU ORT benchmark: one 16 kHz mono PCM16 WAV utterance -> four scores.
 * No resampling, ASR changes, speaker identification, or claim of emotion ground truth.
 * Feature contract: GigaAM commit 7447938d791c4f3e643386ee22c33777004293a5.
 */
public final class GigaEmo {
    private static final int RATE = 16000, FFT = 400, HOP = 160, MELS = 64;
    private static final int MAX_SAMPLES = 30 * RATE, MAX_WAV_SAMPLES = 30 * 60 * RATE;
    private static final String[] LABELS = {"angry", "sad", "neutral", "positive"};

    private GigaEmo() {}

    private static int u16(RandomAccessFile in) throws Exception {
        return in.readUnsignedByte() | in.readUnsignedByte() << 8;
    }
    private static long u32(RandomAccessFile in) throws Exception {
        return Integer.toUnsignedLong(in.readUnsignedByte() | in.readUnsignedByte() << 8
            | in.readUnsignedByte() << 16 | in.readUnsignedByte() << 24);
    }
    private static String four(RandomAccessFile in) throws Exception {
        byte[] b = new byte[4]; in.readFully(b); return new String(b, StandardCharsets.US_ASCII);
    }

    static float[] readWav(String path) throws Exception {
        try (RandomAccessFile in = new RandomAccessFile(path, "r")) {
            if (!four(in).equals("RIFF")) throw new IllegalArgumentException("Expected RIFF WAV");
            long riffEnd = u32(in) + 8;
            if (riffEnd > in.length() || !four(in).equals("WAVE"))
                throw new IllegalArgumentException("Truncated or invalid WAV");
            boolean fmt = false; long dataAt = -1, dataBytes = 0;
            while (in.getFilePointer() + 8 <= riffEnd) {
                String kind = four(in); long size = u32(in), start = in.getFilePointer();
                if (size > riffEnd - start) throw new IllegalArgumentException("Truncated WAV chunk");
                if (kind.equals("fmt ")) {
                    if (size < 16 || u16(in) != 1 || u16(in) != 1 || u32(in) != RATE)
                        throw new IllegalArgumentException("Require uncompressed mono PCM16 at 16000 Hz");
                    if (u32(in) != RATE * 2 || u16(in) != 2 || u16(in) != 16)
                        throw new IllegalArgumentException("Invalid PCM16 WAV format");
                    fmt = true;
                } else if (kind.equals("data") && dataAt < 0) { dataAt = start; dataBytes = size; }
                in.seek(start + size + (size & 1));
            }
            if (!fmt || dataAt < 0 || (dataBytes & 1) != 0 || dataBytes <= FFT
                || dataBytes / 2 > MAX_WAV_SAMPLES)
                throw new IllegalArgumentException("Require WAV with >200 samples and <=30 minutes");
            float[] audio = new float[(int)(dataBytes / 2)]; in.seek(dataAt);
            for (int i = 0; i < audio.length; i++) audio[i] = (short)u16(in) / 32768.0f;
            return audio;
        }
    }

    /** Torchaudio defaults: power=2, periodic Hann400, center/reflection200,
     * HTK mel64 over [0,8000], no normalization, ln(clamp(power,1e-9,1e9)).
     * Result is mel-major contiguous [64,T], T=floor(samples/160)+1.
     */
    static float[] logMel(float[] audio) {
        if (audio.length <= FFT / 2 || audio.length > MAX_SAMPLES)
            throw new IllegalArgumentException("Audio length outside frontend contract");
        int bins = FFT / 2 + 1, frames = audio.length / HOP + 1;
        double[][] cos = new double[bins][FFT], sin = new double[bins][FFT];
        double[] window = new double[FFT];
        for (int n = 0; n < FFT; n++) {
            window[n] = (float)(0.5 - 0.5 * Math.cos(2 * Math.PI * n / FFT));
            for (int k = 0; k < bins; k++) {
                double phase = 2 * Math.PI * k * n / FFT;
                cos[k][n] = Math.cos(phase); sin[k][n] = Math.sin(phase);
            }
        }
        float[] hz = new float[MELS + 2];
        float maxMel = (float)(2595 * Math.log10(1 + 8000.0 / 700));
        for (int m = 0; m < hz.length; m++) {
            float mel = maxMel * m / (MELS + 1);
            hz[m] = (float)(700 * (Math.pow(10, mel / 2595.0f) - 1));
        }
        float[][] filter = new float[MELS][bins];
        for (int m = 0; m < MELS; m++) for (int k = 0; k < bins; k++) {
            float freq = (float)RATE * k / FFT;
            filter[m][k] = Math.max(0, Math.min((freq - hz[m]) / (hz[m+1] - hz[m]),
                (hz[m+2] - freq) / (hz[m+2] - hz[m+1])));
        }
        float[] result = new float[MELS * frames];
        double[] segment = new double[FFT]; float[] power = new float[bins];
        for (int t = 0; t < frames; t++) {
            for (int n = 0; n < FFT; n++) {
                int index = t * HOP + n - FFT / 2;
                if (index < 0) index = -index;
                if (index >= audio.length) index = 2 * audio.length - 2 - index;
                segment[n] = (float)(audio[index] * window[n]);
            }
            for (int k = 0; k < bins; k++) {
                double re = 0, im = 0;
                for (int n = 0; n < FFT; n++) {
                    re += segment[n] * cos[k][n]; im -= segment[n] * sin[k][n];
                }
                power[k] = (float)(re * re + im * im);
            }
            for (int m = 0; m < MELS; m++) {
                double value = 0;
                for (int k = 0; k < bins; k++) value += (float)(power[k] * filter[m][k]);
                result[m * frames + t] = (float)Math.log(Math.max(1e-9, Math.min(1e9, value)));
            }
        }
        return result;
    }

    private static Map<String,String> arguments(String[] args) {
        Map<String,String> values = new HashMap<>();
        if (args.length >= 3 && args.length <= 4 && !args[0].startsWith("--")) {
            values.put("--model", args[0]); values.put("--wav", args[1]); values.put("--output", args[2]);
            if (args.length == 4) values.put("--diarization", args[3]); return values;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--frontend-only")) { values.put(args[i], "true"); continue; }
            if (!args[i].matches("--(wav|model|output|threads|features-out|diarization)") || i+1 == args.length)
                throw new IllegalArgumentException("Usage: --wav PCM16.wav --model emo.int8.onnx --output scores.json [--threads 2] [--features-out features.f32] [--frontend-only]");
            if (values.put(args[i], args[++i]) != null) throw new IllegalArgumentException("Duplicate argument");
        }
        if (!values.containsKey("--wav")) throw new IllegalArgumentException("--wav is required");
        if (values.containsKey("--frontend-only")) {
            if (!values.containsKey("--features-out")) throw new IllegalArgumentException("--features-out required");
        } else if (!values.containsKey("--model") || !values.containsKey("--output"))
            throw new IllegalArgumentException("--model and --output required");
        return values;
    }

    static final class Clip {
        final int start, end; final String voice; final Integer speaker;
        Clip(int start, int end, String voice, Integer speaker) {
            this.start=start; this.end=end; this.voice=voice; this.speaker=speaker;
        }
    }
    static List<Clip> selectClips(String path, int samples) throws Exception {
        if (path == null) return java.util.Collections.singletonList(new Clip(0,Math.min(samples,8*RATE),null,null));
        if (new File(path).length() > 16*1024*1024) throw new IllegalArgumentException("Diarization file too large");
        List<Clip> intervals = new ArrayList<>();
        Pattern sherpa = Pattern.compile("^\\s*([0-9.eE+-]+)\\s*--\\s*([0-9.eE+-]+)\\s+(speaker_\\d+)\\s*$");
        for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
            String voice; double start, end; Matcher m = sherpa.matcher(line);
            if (m.matches()) { start=Double.parseDouble(m.group(1)); end=Double.parseDouble(m.group(2)); voice=m.group(3); }
            else if (line.trim().startsWith("SPEAKER ")) {
                String[] f=line.trim().split("\\s+"); if(f.length<8) throw new IllegalArgumentException("Invalid RTTM");
                start=Double.parseDouble(f[3]); end=start+Double.parseDouble(f[4]); voice=f[7];
            } else continue;
            if (!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<=start||end>samples/(double)RATE+0.05)
                throw new IllegalArgumentException("Invalid diarization time");
            int s=(int)Math.ceil(start*RATE), e=Math.min(samples,(int)Math.floor(end*RATE));
            if(e>s) intervals.add(new Clip(s,e,voice,null));
            if(intervals.size()>10000) throw new IllegalArgumentException("Too many diarization intervals");
        }
        if(intervals.isEmpty()) throw new IllegalArgumentException("No RTTM/sherpa diarization intervals");
        intervals.sort(Comparator.comparingInt(c -> c.start));
        LinkedHashSet<String> voices=new LinkedHashSet<>(); for(Clip c:intervals) voices.add(c.voice);
        List<Clip> selected=new ArrayList<>(); int id=0;
        for(String voice:voices) {
            id++; if(id>8) break;
            Clip chosen=null;
            for(Clip c:intervals) {
                if(!c.voice.equals(voice)) continue;
                List<int[]> pieces=new ArrayList<>(); pieces.add(new int[]{c.start,c.end});
                for(Clip other:intervals) {
                    if(other.voice.equals(voice)||other.end<=c.start||other.start>=c.end) continue;
                    List<int[]> remaining=new ArrayList<>();
                    for(int[] p:pieces) {
                        if(other.end<=p[0]||other.start>=p[1]) remaining.add(p);
                        else { if(other.start>p[0]) remaining.add(new int[]{p[0],other.start});
                               if(other.end<p[1]) remaining.add(new int[]{other.end,p[1]}); }
                    }
                    pieces=remaining;
                }
                for(int[] p:pieces) if(p[1]-p[0]>=2*RATE) { chosen=new Clip(p[0],Math.min(p[1],p[0]+8*RATE),voice,id); break; }
                if(chosen!=null) break;
            }
            if(chosen!=null) selected.add(chosen);
        }
        if(selected.isEmpty()) throw new IllegalArgumentException("No nonoverlapping diarized utterance >=2 seconds");
        return selected;
    }

    public static void main(String[] args) throws Exception {
        Map<String,String> a = arguments(args);
        int threads = Integer.parseInt(a.getOrDefault("--threads", "2"));
        if (threads < 1 || threads > 8) throw new IllegalArgumentException("threads must be 1..8");
        long begin = System.nanoTime();
        float[] audio = readWav(a.get("--wav"));
        if (a.containsKey("--frontend-only")) {
            float[] features = logMel(audio); int frames=audio.length/HOP+1;
            ByteBuffer raw = ByteBuffer.allocate(features.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            raw.asFloatBuffer().put(features); Files.write(Paths.get(a.get("--features-out")), raw.array());
            System.out.println("frontend_only frames=" + frames + " mel_bins=64"); return;
        }
        List<Clip> clips=selectClips(a.get("--diarization"),audio.length);
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(threads); options.setInterOpNumThreads(1);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            // CPU is the default provider. Disable arena retention for this optional sequential step.
            options.setCPUArenaAllocator(false); options.setMemoryPatternOptimization(false);
            long loadBegin = System.nanoTime();
            try (OrtSession session = env.createSession(a.get("--model"), options)) {
                long loadEnd = System.nanoTime();
                if (!session.getInputNames().equals(new java.util.HashSet<>(java.util.Arrays.asList("features", "feature_lengths")))
                    || !session.getOutputNames().contains("probs"))
                    throw new IllegalArgumentException("Model does not match GigaAM-Emo contract");
                StringBuilder json=new StringBuilder("{\n  \"source\":\"model_prediction_not_true_emotion\",\n  \"model\":\"GigaAM-Emo-v1-third-party-int8\",\n  \"mode\":\"")
                    .append(a.containsKey("--diarization")?"first_nonoverlapping_utterance_per_speaker":"aggregate_first_8_seconds")
                    .append("\",\n  \"segments\":[");
                double frontendSeconds=0, inferenceSeconds=0; int count=0;
                for(Clip clip:clips) {
                long frontendBegin=System.nanoTime();
                float[] features=logMel(Arrays.copyOfRange(audio,clip.start,clip.end));
                int frames=(clip.end-clip.start)/HOP+1;
                frontendSeconds+=(System.nanoTime()-frontendBegin)/1e9;
                try (OnnxTensor x = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), new long[]{1,MELS,frames});
                     OnnxTensor lens = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{frames}), new long[]{1})) {
                    Map<String,OnnxTensor> inputs = new LinkedHashMap<>(); inputs.put("features",x); inputs.put("feature_lengths",lens);
                    long inferBegin = System.nanoTime();
                    try (OrtSession.Result outputs = session.run(inputs)) {
                        long inferEnd = System.nanoTime();
                        inferenceSeconds+=(inferEnd-inferBegin)/1e9;
                        float[][] batch = (float[][])outputs.get("probs").orElseThrow(() -> new IllegalArgumentException("Missing probs")).getValue();
                        if (batch.length != 1 || batch[0].length != LABELS.length) throw new IllegalArgumentException("Expected probs [1,4]");
                        int best = 0; double sum = 0;
                        for (int i = 0; i < LABELS.length; i++) {
                            float p = batch[0][i];
                            if (!Float.isFinite(p) || p < 0 || p > 1) throw new IllegalArgumentException("Invalid probability");
                            sum += p; if (p > batch[0][best]) best = i;
                        }
                        if (Math.abs(sum-1) > 0.001) throw new IllegalArgumentException("Probabilities do not sum to one");
                        if(count++>0) json.append(',');
                        json.append("\n    {\"speaker_id\":").append(clip.speaker==null?"null":clip.speaker)
                            .append(",\"start_ms\":").append(Math.round(clip.start*1000.0/RATE))
                            .append(",\"end_ms\":").append(Math.round(clip.end*1000.0/RATE))
                            .append(",\"label\":\"").append(LABELS[best]).append("\",\"confidence\":").append(batch[0][best])
                            .append(",\"probabilities\":{");
                        for (int i=0;i<LABELS.length;i++) { if(i>0) json.append(','); json.append('"').append(LABELS[i]).append("\":").append(batch[0][i]); }
                        json.append("},\"frames\":").append(frames).append('}');
                    }
                }
                }
                json.append("\n  ],\n  \"sample_rate\":16000,\n  \"threads\":").append(threads)
                    .append(",\n  \"frontend_wall_s\":").append(frontendSeconds)
                    .append(",\n  \"model_load_wall_s\":").append((loadEnd-loadBegin)/1e9)
                    .append(",\n  \"inference_wall_s\":").append(inferenceSeconds)
                    .append(",\n  \"wall_s\":").append((System.nanoTime()-begin)/1e9).append("\n}\n");
                Files.write(Paths.get(a.get("--output")), json.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
