import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded literal n-gram proposals and model-selected IDs; never rewrites ASR. */
final class CandidateKeywords {
    private static final int MAX_CANDIDATES = 24, MAX_ANCHORS = 192, MAX_PHRASE_CHARS = 160;
    private static final Pattern PLACEHOLDERS = Pattern.compile("\\{\\{(RAW_TRANSCRIPT|CANDIDATES_JSON)\\}\\}");
    private static final String[] LABEL_MARKERS = {"спикер", "speaker", "говорящий", "участник", "participant"};
    private static final String LABEL_NUMBERS = "|один|два|три|четыре|пять|шесть|семь|восемь|девять|десять|первый|второй|третий|четвертый|четвёртый|пятый|one|two|three|four|five|six|seven|eight|nine|ten|first|second|third|fourth|fifth|";

    private static final class Token {
        int start, end, index; boolean blocked, lexical;
        Token(int a, int b, int i, boolean block, boolean hasWord) { start = a; end = b; index = i; blocked = block; lexical = hasWord; }
    }
    private static final class Proposal {
        int start, end; String text;
        Proposal(int a, int b, String value) { start = a; end = b; text = value; }
    }

    private static String canonical(String token) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < token.length();) {
            int c = token.codePointAt(i); if (Character.isLetterOrDigit(c)) out.appendCodePoint(c);
            i += Character.charCount(c);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
    private static boolean numberLabel(String token) {
        if (token.isEmpty()) return false;
        if (LABEL_NUMBERS.contains("|" + token + "|")) return true;
        for (int i = 0; i < token.length();) {
            int c = token.codePointAt(i); if (!Character.isDigit(c)) return false; i += Character.charCount(c);
        }
        return true;
    }
    private static boolean marker(String token) {
        for (String word : LABEL_MARKERS) if (word.equals(token)) return true;
        return false;
    }
    private static boolean attachedLabel(String token) {
        for (String word : LABEL_MARKERS)
            if (token.startsWith(word) && numberLabel(token.substring(word.length()))) return true;
        return false;
    }

    private static JSONArray proposals(String raw) throws Exception {
        Matcher counter = VextoPostprocess.WORD.matcher(raw); int wordCount = 0;
        while (counter.find()) wordCount++;
        if (wordCount < 2) return new JSONArray();
        int anchorCount = Math.min(MAX_ANCHORS, wordCount - 1);
        Set<Integer> anchors = new LinkedHashSet<>();
        for (int i = 0; i < anchorCount; i++) anchors.add(anchorCount == 1 ? 0 : (int) ((long) i * (wordCount - 2) / (anchorCount - 1)));
        List<Token> recent = new ArrayList<>(); Map<String, Proposal> unique = new LinkedHashMap<>();
        Matcher words = VextoPostprocess.WORD.matcher(raw); int index = 0; boolean previousMarker = false;
        while (words.find()) {
            String norm = words.end() - words.start() <= MAX_PHRASE_CHARS ? canonical(words.group()) : "";
            boolean bare = marker(norm), blocked = bare || attachedLabel(norm) || (previousMarker && numberLabel(norm));
            previousMarker = bare;
            recent.add(new Token(words.start(), words.end(), index++, blocked, !norm.isEmpty()));
            if (recent.size() > 4) recent.remove(0);
            for (int length = 2; length <= 4 && length <= recent.size(); length++) {
                Token first = recent.get(recent.size() - length), last = recent.get(recent.size() - 1);
                if (!anchors.contains(first.index) || last.end - first.start > MAX_PHRASE_CHARS) continue;
                boolean suitable = true;
                for (int i = recent.size() - length; i < recent.size(); i++)
                    if (recent.get(i).blocked || !recent.get(i).lexical) suitable = false;
                if (!suitable) continue;
                String phrase = raw.substring(first.start, last.end);
                if (VextoPostprocess.verbatim(raw, phrase) && !unique.containsKey(phrase))
                    unique.put(phrase, new Proposal(first.start, last.end, phrase));
            }
        }
        List<Proposal> choices = new ArrayList<>(unique.values());
        Collections.sort(choices, new Comparator<Proposal>() {
            public int compare(Proposal a, Proposal b) { int n = Integer.compare(a.start, b.start); return n != 0 ? n : Integer.compare(a.end, b.end); }
        });
        JSONArray pool = new JSONArray(); int count = Math.min(MAX_CANDIDATES, choices.size());
        for (int i = 0; i < count; i++) {
            int position = count == 1 ? 0 : (int) ((long) i * (choices.size() - 1) / (count - 1));
            pool.put(new JSONObject().put("id", i + 1).put("text", choices.get(position).text));
        }
        return pool;
    }

    static void prompt(String asrPath, String templatePath, String output, String poolPath) throws Exception {
        VextoPostprocess.distinctPaths(new String[]{asrPath, templatePath}, new String[]{output, poolPath});
        String raw = VextoPostprocess.asr(VextoPostprocess.read(asrPath)).getString("text");
        String template = VextoPostprocess.read(templatePath);
        if (!template.contains("{{RAW_TRANSCRIPT}}") || !template.contains("{{CANDIDATES_JSON}}"))
            throw new IllegalArgumentException("Candidate template needs RAW_TRANSCRIPT and CANDIDATES_JSON");
        JSONArray candidates = proposals(raw);
        JSONObject pool = new JSONObject().put("raw_asr_sha256", VextoPostprocess.hash(asrPath)).put("candidates", candidates)
            .put("method", "Uniformly sampled literal 2-4 word windows; label-marker heuristic; not semantic ranking");
        Matcher replacements = PLACEHOLDERS.matcher(template); StringBuffer rendered = new StringBuffer();
        while (replacements.find()) replacements.appendReplacement(rendered, Matcher.quoteReplacement(
            replacements.group(1).equals("RAW_TRANSCRIPT") ? raw : candidates.toString()));
        replacements.appendTail(rendered);
        VextoPostprocess.write(poolPath, pool.toString(2) + "\n");
        VextoPostprocess.write(output, rendered.toString());
    }

    private static LinkedHashMap<Integer, String> validatePool(JSONObject pool, String raw, String sha) throws Exception {
        if (!sha.equals(pool.getString("raw_asr_sha256"))) throw new IllegalArgumentException("Candidate pool belongs to another ASR file");
        JSONArray candidates = pool.getJSONArray("candidates");
        if (candidates.length() > MAX_CANDIDATES) throw new IllegalArgumentException("Candidate pool exceeds 24 items");
        LinkedHashMap<Integer, String> result = new LinkedHashMap<>(); Set<String> phrases = new LinkedHashSet<>();
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject item = candidates.getJSONObject(i); Object value = item.get("id"), text = item.get("text");
            if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 1
                || ((Number) value).longValue() > MAX_CANDIDATES || !(text instanceof String))
                throw new IllegalArgumentException("Candidate pool has invalid ID or text");
            int id = ((Number) value).intValue(); String phrase = (String) text;
            Matcher words = VextoPostprocess.WORD.matcher(phrase); int count = 0;
            while (words.find()) { count++; if (canonical(words.group()).isEmpty()) throw new IllegalArgumentException("Candidate contains punctuation-only unit"); }
            if (count < 2 || count > 4 || phrase.length() > MAX_PHRASE_CHARS || !VextoPostprocess.verbatim(raw, phrase)
                || result.containsKey(id) || !phrases.add(phrase)) throw new IllegalArgumentException("Candidate pool is not unique literal 2-4 word slices");
            result.put(id, phrase);
        }
        return result;
    }

    static void metadata(String asrPath, String poolPath, String llmPath, String output, String auditPath) throws Exception {
        VextoPostprocess.distinctPaths(new String[]{asrPath, poolPath, llmPath}, new String[]{output, auditPath});
        String raw = VextoPostprocess.asr(VextoPostprocess.read(asrPath)).getString("text"), sha = VextoPostprocess.hash(asrPath);
        LinkedHashMap<Integer, String> pool = validatePool(VextoPostprocess.oneObject(VextoPostprocess.read(poolPath)), raw, sha);
        String llm = VextoPostprocess.strip(VextoPostprocess.read(llmPath)), marker = "[end of text]";
        if (llm.endsWith(marker)) llm = VextoPostprocess.strip(llm.substring(0, llm.length() - marker.length()));
        JSONArray accepted = new JSONArray(), selected = new JSONArray(), decisions = new JSONArray(); Set<Integer> seen = new LinkedHashSet<>();
        String parseError = null;
        try {
            JSONObject object = VextoPostprocess.oneObject(llm);
            if (object.length() != 1 || !(object.opt("candidate_ids") instanceof JSONArray)) throw new IllegalArgumentException("Expected only candidate_ids array");
            JSONArray ids = object.getJSONArray("candidate_ids");
            for (int i = 0; i < ids.length(); i++) {
                Object value = ids.get(i); String reason = null; Integer id = null;
                if (!(value instanceof Integer || value instanceof Long)) reason = "not_integer_id";
                else if (((Number) value).longValue() < 1 || ((Number) value).longValue() > MAX_CANDIDATES
                    || !pool.containsKey(((Number) value).intValue())) reason = "unknown_candidate_id";
                else {
                    id = ((Number) value).intValue();
                    if (!seen.add(id)) reason = "duplicate_id";
                    else if (accepted.length() >= 3) reason = "maximum_three_keywords";
                }
                if (reason == null) { accepted.put(pool.get(id)); selected.put(id); }
                decisions.put(new JSONObject().put("index", i).put("value", value).put("accepted", reason == null)
                    .put("reason", reason == null ? JSONObject.NULL : reason));
            }
        } catch (Exception error) { parseError = error.getMessage(); }
        JSONObject audit = new JSONObject().put("raw_asr_sha256", sha).put("candidate_pool_sha256", VextoPostprocess.hash(poolPath))
            .put("llm_stdout_sha256", VextoPostprocess.hash(llmPath)).put("selection_only", true).put("raw_asr_rewritten", false)
            .put("candidate_count", pool.size()).put("selected_candidate_ids", selected).put("accepted_count", accepted.length())
            .put("parse_error", parseError == null ? JSONObject.NULL : parseError).put("decisions", decisions)
            .put("limitations", "Literal n-gram coverage and model selections are heuristics, not a semantic-quality guarantee");
        VextoPostprocess.write(output, new JSONObject().put("keywords", accepted).toString(2) + "\n");
        VextoPostprocess.write(auditPath, audit.toString(2) + "\n");
    }
}
