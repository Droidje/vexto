import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Deterministic Android-side postprocessing; no ASR correction or inference. */
public final class VextoPostprocess {
    private static final double TAIL = 0.04, GAP = 0.7, EPS = 1e-9;
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    static final Pattern WORD = Pattern.compile("[^\\s\\p{Z}\\u0085\\u001c-\\u001f]+");
    private static final Pattern DIAR = Pattern.compile("^\\s*([-+0-9.eE]+)\\s*--\\s*([-+0-9.eE]+)\\s+(speaker_\\d+)\\s*$");

    private static final Pattern RTTM = Pattern.compile("^\\s*SPEAKER\\s+\\S+\\s+\\S+\\s+([-+0-9.eE]+)\\s+([-+0-9.eE]+)\\s+\\S+\\s+\\S+\\s+(\\S+)\\s+\\S+\\s+\\S+\\s*$");

    static String read(String path) throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (data.size() + n > MAX_FILE_BYTES) throw new IllegalArgumentException("Input exceeds 16 MiB postprocessing limit");
                data.write(buffer, 0, n);
            }
        }
        String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data.toByteArray())).toString();
        return s.startsWith("\ufeff") ? s.substring(1) : s;
    }

    static void write(String path, String value) throws Exception {
        File file = new File(path); File parent = file.getCanonicalFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IllegalArgumentException("Cannot create output directory");
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
    }

    static String hash(String path) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) sha.update(buffer, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte b : sha.digest()) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }

    private static boolean space(int c) { return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85; }
    static String strip(String s) {
        int first = 0, last = s.length();
        while (first < last && space(s.codePointAt(first))) first += Character.charCount(s.codePointAt(first));
        while (last > first && space(s.codePointBefore(last))) last -= Character.charCount(s.codePointBefore(last));
        return s.substring(first, last);
    }

    static JSONObject oneObject(String text) throws Exception {
        text = strip(text); new JsonSyntax(text).check();
        JSONTokener tokener = new JSONTokener(text); Object value = tokener.nextValue();
        if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw new IllegalArgumentException("Expected exactly one JSON object");
        return (JSONObject) value;
    }

    // Android's JSONTokener also accepts comments, bare strings and trailing
    // separators. Validate JSON grammar first so malformed LLM output fails closed.
    private static final class JsonSyntax {
        final String s; int p;
        JsonSyntax(String value) { s = value; }
        void bad() { throw new IllegalArgumentException("Invalid strict JSON syntax"); }
        void ws() { while (p < s.length() && " \t\r\n".indexOf(s.charAt(p)) >= 0) p++; }
        boolean take(char c) { ws(); if (p < s.length() && s.charAt(p) == c) { p++; return true; } return false; }
        void check() throws Exception { value(0); ws(); if (p != s.length()) bad(); }
        void value(int depth) throws Exception {
            if (depth > 64) bad(); ws(); if (p >= s.length()) bad(); char c = s.charAt(p);
            if (c == '"') { string(); return; }
            if (c == '{') {
                p++; if (take('}')) return;
                java.util.Set<String> keys = new java.util.HashSet<>();
                do { ws(); int keyStart = p; string();
                    if (!keys.add((String) new JSONTokener(s.substring(keyStart, p)).nextValue())) bad();
                    if (!take(':')) bad(); value(depth + 1); if (take('}')) return;
                } while (take(','));
                bad();
            } else if (c == '[') {
                p++; if (take(']')) return;
                do { value(depth + 1); if (take(']')) return; } while (take(',')); bad();
            } else if (s.startsWith("true", p)) p += 4;
            else if (s.startsWith("false", p)) p += 5;
            else if (s.startsWith("null", p)) p += 4;
            else {
                Matcher number = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").matcher(s);
                number.region(p, s.length()); if (!number.lookingAt()) bad(); p = number.end();
            }
        }
        void string() {
            if (p >= s.length() || s.charAt(p++) != '"') bad();
            while (p < s.length()) {
                char c = s.charAt(p++); if (c == '"') return; if (c < 32) bad();
                if (c == '\\') {
                    if (p >= s.length()) bad(); c = s.charAt(p++);
                    if (c == 'u') { for (int i = 0; i < 4; i++) if (p >= s.length() || "0123456789abcdefABCDEF".indexOf(s.charAt(p++)) < 0) bad(); }
                    else if ("\"\\/bfnrt".indexOf(c) < 0) bad();
                }
            }
            bad();
        }
    }

    static JSONObject asr(String stdout) throws Exception {
        JSONObject found = null;
        for (String line : stdout.split("\\r?\\n")) {
            if (!strip(line).startsWith("{")) continue;
            JSONObject value;
            try { value = oneObject(line); } catch (Exception ignored) { continue; }
            if (!(value.opt("text") instanceof String) || !(value.opt("tokens") instanceof JSONArray)
                || !(value.opt("timestamps") instanceof JSONArray)) continue;
            if (found != null) throw new IllegalArgumentException("Multiple ASR result objects are ambiguous");
            found = value;
        }
        if (found == null) throw new IllegalArgumentException("No ASR JSON line with text/tokens/timestamps");
        return found;
    }

    private static double time(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Timestamp must be numeric");
        double n = ((Number) value).doubleValue();
        if (!Double.isFinite(n) || n < 0 || !Double.isFinite(n * 1000) || n * 1000 > Long.MAX_VALUE)
            throw new IllegalArgumentException("Timestamp must be finite, nonnegative and fit int64 milliseconds");
        return n;
    }

    private static final class Span {
        double start, end; String speaker;
        Span(double a, double b, String s) { start = a; end = b; speaker = s; }
    }
    private static final class Word {
        int from, to, first, last; double start, end; String speaker; boolean ambiguous;
        LinkedHashMap<String, Double> overlaps = new LinkedHashMap<>();
    }
    private static final class Group {
        int from, to; double start, end, lastEnd; String speaker;
        Group(Word w) { from = w.from; to = w.to; start = w.start; end = w.end; lastEnd = w.end; speaker = w.speaker; }
    }

    private static List<Span> diarization(String stdout) {
        List<Span> spans = new ArrayList<>();
        for (String line : stdout.split("\\r?\\n")) {
            boolean rttm = strip(line).startsWith("SPEAKER");
            Matcher m = (rttm ? RTTM : DIAR).matcher(line);
            if (!m.matches()) {
                if (rttm || (line.contains("--") && line.contains("speaker_"))) throw new IllegalArgumentException("Malformed diarization interval");
                continue;
            }
            double start = time(Double.valueOf(m.group(1))), value = time(Double.valueOf(m.group(2)));
            double end = rttm ? time(start + value) : value;
            if (end <= start) throw new IllegalArgumentException("Diarization end must exceed start");
            spans.add(new Span(start, end, m.group(3)));
        }
        Collections.sort(spans, new Comparator<Span>() {
            public int compare(Span a, Span b) {
                int n = Double.compare(a.start, b.start); if (n != 0) return n;
                n = Double.compare(a.end, b.end); return n != 0 ? n : a.speaker.compareTo(b.speaker);
            }
        });
        return spans;
    }

    private static List<Word> words(JSONObject result, String text) throws Exception {
        JSONArray ts = result.getJSONArray("tokens"), stamps = result.getJSONArray("timestamps");
        if (ts.length() != stamps.length()) throw new IllegalArgumentException("Token/timestamp counts differ");
        int[] starts = new int[ts.length()], ends = new int[ts.length()]; double[] times = new double[ts.length()];
        StringBuilder joined = new StringBuilder(); double previous = 0;
        for (int i = 0; i < ts.length(); i++) {
            Object token = ts.get(i); if (!(token instanceof String)) throw new IllegalArgumentException("Tokens must be strings");
            starts[i] = joined.length(); joined.append((String) token); ends[i] = joined.length();
            times[i] = time(stamps.get(i));
            if (i > 0 && times[i] < previous) throw new IllegalArgumentException("Token timestamps decreased");
            previous = times[i];
        }
        String all = joined.toString();
        if (!strip(all).equals(strip(text))) throw new IllegalArgumentException("Joined tokens must exactly match stripped raw text");
        int tokenOrigin = 0, textOrigin = 0;
        while (tokenOrigin < all.length() && space(all.codePointAt(tokenOrigin))) tokenOrigin += Character.charCount(all.codePointAt(tokenOrigin));
        while (textOrigin < text.length() && space(text.codePointAt(textOrigin))) textOrigin += Character.charCount(text.codePointAt(textOrigin));
        List<Word> words = new ArrayList<>(); Matcher m = WORD.matcher(text); int tokenIndex = 0;
        while (m.find()) {
            int firstChar = m.start() - textOrigin + tokenOrigin, endChar = m.end() - textOrigin + tokenOrigin;
            while (tokenIndex < ts.length() && ends[tokenIndex] <= firstChar) tokenIndex++;
            if (tokenIndex >= ts.length()) throw new IllegalArgumentException("Cannot map word to token");
            Word w = new Word(); w.from = m.start(); w.to = m.end(); w.first = tokenIndex; w.last = tokenIndex;
            while (w.last + 1 < ts.length() && starts[w.last + 1] < endChar) w.last++;
            w.start = times[w.first]; w.end = time(times[w.last] + TAIL);
            if (!Double.isFinite(w.end) || w.end <= w.start) throw new IllegalArgumentException("Invalid word span");
            words.add(w);
        }
        return words;
    }

    private static void assign(Word word, LinkedHashMap<String, List<Span>> intervals) {
        double best = 0;
        for (Map.Entry<String, List<Span>> entry : intervals.entrySet()) {
            double overlap = 0;
            for (Span span : entry.getValue()) overlap += Math.max(0, Math.min(word.end, span.end) - Math.max(word.start, span.start));
            if (overlap > 0) { word.overlaps.put(entry.getKey(), overlap); best = Math.max(best, overlap); }
        }
        word.speaker = "unknown"; int ties = 0;
        for (Map.Entry<String, Double> entry : word.overlaps.entrySet()) {
            if (Math.abs(entry.getValue() - best) <= EPS) {
                if (ties == 0) word.speaker = entry.getKey(); ties++;
            }
        }
        word.ambiguous = ties > 1;
    }

    private static void export(String asrPath, String diarPath, String output, String provenancePath) throws Exception {
        JSONObject result = asr(read(asrPath)); String text = result.getString("text");
        LinkedHashMap<String, String> mapping = new LinkedHashMap<>();
        LinkedHashMap<String, List<Span>> intervals = new LinkedHashMap<>();
        for (Span span : diarization(read(diarPath))) {
            if (!mapping.containsKey(span.speaker)) mapping.put(span.speaker, "speaker_" + (mapping.size() + 1));
            String id = mapping.get(span.speaker);
            if (!intervals.containsKey(id)) intervals.put(id, new ArrayList<Span>());
            List<Span> union = intervals.get(id);
            if (!union.isEmpty() && span.start <= union.get(union.size() - 1).end)
                union.get(union.size() - 1).end = Math.max(union.get(union.size() - 1).end, span.end);
            else union.add(new Span(span.start, span.end, id));
        }
        List<Word> words = words(result, text); List<Group> groups = new ArrayList<>(); JSONArray wordAudit = new JSONArray();
        for (Word word : words) {
            assign(word, intervals);
            JSONObject audit = new JSONObject();
            audit.put("text", text.substring(word.from, word.to));
            audit.put("char_start", text.codePointCount(0, word.from)); audit.put("char_end", text.codePointCount(0, word.to));
            audit.put("first_token", word.first); audit.put("last_token", word.last);
            audit.put("start_s", word.start); audit.put("end_s", word.end); audit.put("speaker_id", word.speaker);
            audit.put("ambiguous", word.ambiguous); audit.put("overlap_seconds", new JSONObject(word.overlaps)); wordAudit.put(audit);
            Group group = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if (group != null && group.speaker.equals(word.speaker) && word.start - group.lastEnd <= GAP + EPS) {
                group.end = Math.max(group.end, word.end); group.lastEnd = word.end; group.to = word.to;
            } else groups.add(new Group(word));
        }
        JSONArray transcript = new JSONArray(); StringBuilder exportedWords = new StringBuilder();
        for (Group group : groups) {
            JSONObject row = new JSONObject(); row.put("speaker_id", group.speaker);
            row.put("start_ms", (long) Math.floor(group.start * 1000)); row.put("end_ms", (long) Math.ceil(group.end * 1000));
            row.put("text", text.substring(group.from, group.to)); transcript.put(row);
            Matcher m = WORD.matcher(text.substring(group.from, group.to)); while (m.find()) exportedWords.append(m.group()).append('\n');
        }
        StringBuilder sourceWords = new StringBuilder(); Matcher m = WORD.matcher(text);
        while (m.find()) sourceWords.append(m.group()).append('\n');
        if (!sourceWords.toString().equals(exportedWords.toString())) throw new IllegalArgumentException("Export altered lexical sequence");
        JSONObject provenance = new JSONObject(); provenance.put("raw_asr_text", text); provenance.put("speaker_mapping", new JSONObject(mapping));
        provenance.put("timing_method", "first/last contributing token timestamps; fixed 40 ms word tail");
        provenance.put("token_tail_seconds", TAIL); provenance.put("group_pause_seconds", GAP);
        provenance.put("time_rounding", "start floor/end ceil to milliseconds");
        provenance.put("speaker_assignment", "largest union overlap; acoustic-first ID breaks ties");
        provenance.put("limitations", new JSONArray().put("Token emission spans are estimates, not forced alignment.")
            .put("Overlap assignment is not proof of speaker identity.").put("Source whitespace between separate rows remains in raw_asr_text."));
        provenance.put("words", wordAudit); provenance.put("input_sha256", new JSONObject().put("asr", hash(asrPath)).put("diarization", hash(diarPath)));
        write(output, new JSONObject().put("transcript", transcript).toString(2) + "\n");
        write(provenancePath, provenance.toString(2) + "\n");
    }

    private static boolean wordCharacter(int c) {
        int type = Character.getType(c);
        return Character.isLetterOrDigit(c) || c == '_' || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }
    private static boolean hasLetterOrDigit(String value) {
        for (int i = 0; i < value.length();) { int c = value.codePointAt(i); if (Character.isLetterOrDigit(c)) return true; i += Character.charCount(c); }
        return false;
    }
    static boolean verbatim(String source, String phrase) {
        int from = 0, position;
        while ((position = source.indexOf(phrase, from)) >= 0) {
            int end = position + phrase.length();
            if ((position == 0 || !wordCharacter(source.codePointBefore(position)))
                && (end == source.length() || !wordCharacter(source.codePointAt(end)))) return true;
            from = position + 1;
        }
        return false;
    }

    private static void metadata(String asrPath, String llmPath, String output, String auditPath) throws Exception {
        String raw = asr(read(asrPath)).getString("text"), llm = strip(read(llmPath));
        String terminator = "[end of text]";
        if (llm.endsWith(terminator)) llm = strip(llm.substring(0, llm.length() - terminator.length()));
        JSONArray accepted = new JSONArray(), decisions = new JSONArray(); String parseError = null;
        try {
            JSONObject object = oneObject(llm);
            if (object.length() != 1 || !(object.opt("keywords") instanceof JSONArray)) throw new IllegalArgumentException("Expected only a keywords array");
            JSONArray candidates = object.getJSONArray("keywords"); List<String> seen = new ArrayList<>();
            for (int i = 0; i < candidates.length(); i++) {
                Object candidate = candidates.get(i); String reason = null, phrase = candidate instanceof String ? strip((String) candidate) : "";
                int count = 0; Matcher words = WORD.matcher(phrase); while (words.find()) count++;
                if (!(candidate instanceof String)) reason = "not_string";
                else if (count < 1 || count > 5) reason = "word_count_outside_1_to_5";
                else if (!hasLetterOrDigit(phrase)) reason = "no_letters_or_digits";
                else if (!verbatim(raw, phrase)) reason = "not_verbatim_at_unicode_word_boundaries";
                else if (seen.contains(phrase)) reason = "duplicate";
                else if (accepted.length() >= 3) reason = "maximum_three_keywords";
                if (reason == null) { accepted.put(phrase); seen.add(phrase); }
                decisions.put(new JSONObject().put("index", i).put("value", candidate).put("accepted", reason == null)
                    .put("reason", reason == null ? JSONObject.NULL : reason));
            }
        } catch (Exception error) { parseError = error.getMessage(); }
        JSONObject audit = new JSONObject().put("raw_asr_sha256", hash(asrPath)).put("llm_stdout_sha256", hash(llmPath))
            .put("raw_asr_rewritten", false).put("parse_error", parseError == null ? JSONObject.NULL : parseError)
            .put("policy", "At most three verbatim phrases, one to five whitespace-delimited words, Unicode word boundaries")
            .put("decisions", decisions).put("accepted_count", accepted.length());
        write(output, new JSONObject().put("keywords", accepted).toString(2) + "\n"); write(auditPath, audit.toString(2) + "\n");
    }

    static void distinctPaths(String[] inputs, String[] outputs) throws Exception {
        List<String> paths = new ArrayList<>();
        for (String input : inputs) paths.add(new File(input).getCanonicalPath());
        for (String output : outputs) {
            String path = new File(output).getCanonicalPath();
            if (paths.contains(path)) throw new IllegalArgumentException("Outputs must not overwrite inputs or each other");
            paths.add(path);
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length == 4 && args[0].equals("prompt")) {
                distinctPaths(new String[]{args[1], args[2]}, new String[]{args[3]});
                String raw = asr(read(args[1])).getString("text"), template = read(args[2]);
                if (!template.contains("{{RAW_TRANSCRIPT}}")) throw new IllegalArgumentException("Template lacks RAW_TRANSCRIPT placeholder");
                write(args[3], template.replace("{{RAW_TRANSCRIPT}}", raw));
            } else if (args.length == 5 && args[0].equals("prompt-candidates")) {
                CandidateKeywords.prompt(args[1], args[2], args[3], args[4]);
            } else if (args.length == 6 && args[0].equals("metadata-candidates")) {
                CandidateKeywords.metadata(args[1], args[2], args[3], args[4], args[5]);
            } else if (args.length == 5 && args[0].equals("export")) {
                distinctPaths(new String[]{args[1], args[2]}, new String[]{args[3], args[4]}); export(args[1], args[2], args[3], args[4]);
            } else if (args.length == 5 && args[0].equals("metadata")) {
                distinctPaths(new String[]{args[1], args[2]}, new String[]{args[3], args[4]}); metadata(args[1], args[2], args[3], args[4]);
            } else throw new IllegalArgumentException("Usage: prompt ASR TEMPLATE OUTPUT | prompt-candidates ASR TEMPLATE PROMPT POOL | export ASR DIAR JSON PROVENANCE | metadata ASR LLM JSON AUDIT | metadata-candidates ASR POOL LLM JSON AUDIT");
        } catch (Exception error) { System.err.println("Postprocessing failed: " + error.getMessage()); System.exit(2); }
    }
}
