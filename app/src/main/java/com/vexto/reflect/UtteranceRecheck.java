package com.vexto.reflect;

import com.vexto.engine.model.EmotionProbabilities;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Замена выбранной реплики после качественного прогона окна вокруг неё.
 * Соседние реплики не удаляются. Пустой результат и кусок, который нельзя
 * отделить от соседа, не применяются.
 */
final class UtteranceRecheck {
    static final int PAD_BEFORE_MS = 1_500;
    static final int PAD_AFTER_MS = 1_500;
    /** Сколько миллисекунд края окна может задеть соседа, не забирая его реплику. */
    private static final int EDGE_MS = 250;

    static final class Window {
        final int startMs;
        final int endMs;

        Window(int startMs, int endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    /** Времена уже на шкале исходной записи. */
    static final class Piece {
        final int startMs;
        final int endMs;
        final String text;
        final EmotionProbabilities emotion;
        final Boolean overlapDetected;
        final List<TimedWord> words;

        Piece(int startMs, int endMs, String text, EmotionProbabilities emotion) {
            this(startMs, endMs, text, emotion, List.of(), null);
        }

        Piece(int startMs, int endMs, String text, EmotionProbabilities emotion, Boolean overlapDetected) {
            this(startMs, endMs, text, emotion, List.of(), overlapDetected);
        }

        Piece(int startMs, int endMs, String text, EmotionProbabilities emotion, List<TimedWord> words) {
            this(startMs, endMs, text, emotion, words, null);
        }

        Piece(int startMs, int endMs, String text, EmotionProbabilities emotion,
              List<TimedWord> words, Boolean overlapDetected) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
            this.emotion = emotion;
            this.overlapDetected = overlapDetected;
            this.words = words == null ? List.of() : words;
        }
    }

    /** Слово результата на шкале исходной записи. */
    static final class TimedWord {
        final int startMs;
        final int endMs;
        final String text;

        TimedWord(int startMs, int endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    private UtteranceRecheck() {}

    static Window window(int lineStartMs, int lineEndMs, int durationMs) {
        if (durationMs <= 0 || lineEndMs <= lineStartMs || lineStartMs >= durationMs) return null;
        int start = Math.max(0, lineStartMs - PAD_BEFORE_MS);
        int end = Math.min(durationMs, lineEndMs + PAD_AFTER_MS);
        return end > start ? new Window(start, end) : null;
    }

    /**
     * @return новая расшифровка или {@code null}, если исходный текст менять нельзя
     */
    static ReflectTranscript apply(ReflectTranscript original, ReflectTranscript.Line selected,
                                   int windowStartMs, int windowEndMs, List<Piece> pieces) {
        if (original == null || selected == null || windowEndMs <= windowStartMs) return null;
        int selectedIndex = indexOf(original, selected);
        if (selectedIndex < 0) return null;
        if (overlap(selected.startMs, selected.endMs, windowStartMs, windowEndMs) <= 0) return null;
        List<Piece> usable = usablePieces(pieces, windowStartMs, windowEndMs);
        if (usable.isEmpty()) return null;
        boolean[] replace = new boolean[original.lines.size()];
        replace[selectedIndex] = true;
        List<ReflectTranscript.Line> neighbors = new ArrayList<>();
        for (int index = 0; index < original.lines.size(); index++) {
            if (!replace[index]) neighbors.add(original.lines.get(index));
        }
        List<Piece> kept = withoutLeadingDuplicates(piecesForReplacement(usable, original, selected, replace), neighbors);
        if (!coversSelected(selected, kept)) return null;
        List<ReflectTranscript.Line> next = new ArrayList<>(original.lines.size() + kept.size());
        for (ReflectTranscript.Line neighbor : neighbors) {
            ReflectTranscript.Line cleaned = withoutRepeatedFollowingWord(neighbor, kept);
            if (cleaned != null) next.add(cleaned);
        }
        for (Piece piece : kept) {
            ReflectTranscript.Line donor = donor(original, replace, piece, selected);
            next.add(new ReflectTranscript.Line(donor.speakerId, piece.startMs, piece.endMs,
                    piece.text.trim(), piece.emotion, donor.speakerName,
                    piece.overlapDetected != null ? piece.overlapDetected
                            : piece.startMs == donor.startMs && piece.endMs == donor.endMs ? donor.overlapDetected : null));
        }
        ReflectTranscript updated = new ReflectTranscript(next);
        return updated.sameLines(original) ? original : updated;
    }

    private static List<Piece> usablePieces(List<Piece> pieces, int windowStartMs, int windowEndMs) {
        List<Piece> usable = new ArrayList<>();
        if (pieces == null) return usable;
        for (Piece piece : pieces) {
            if (piece == null || piece.text == null || piece.text.trim().isEmpty()) continue;
            int start = Math.max(windowStartMs, piece.startMs);
            int end = Math.min(windowEndMs, piece.endMs);
            if (end <= start) continue;
            usable.add(new Piece(start, end, piece.text, piece.emotion, piece.words,
                    start == piece.startMs && end == piece.endMs ? piece.overlapDetected : null));
        }
        usable.sort(Comparator.comparingInt((Piece piece) -> piece.startMs).thenComparingInt(piece -> piece.endMs));
        return usable;
    }

    /**
     * Окно нужно модели как контекст. Переписывается только выбранная реплика.
     * Кусок соседа отбрасывается. Общий кусок режется по словам; без слов он не применяется,
     * если заходит в соседа дальше чем на {@link #EDGE_MS}.
     */
    private static List<Piece> piecesForReplacement(List<Piece> usable, ReflectTranscript original,
                                                    ReflectTranscript.Line selected, boolean[] replace) {
        List<ReflectTranscript.Line> others = new ArrayList<>();
        for (int index = 0; index < original.lines.size(); index++) {
            if (!replace[index]) others.add(original.lines.get(index));
        }
        List<Piece> kept = new ArrayList<>();
        for (Piece piece : usable) {
            Piece trimmed = trimToSelected(piece, selected, others);
            if (trimmed != null) kept.add(trimmed);
        }
        return kept;
    }

    private static Piece trimToSelected(Piece piece, ReflectTranscript.Line selected,
                                        List<ReflectTranscript.Line> others) {
        if (!piece.words.isEmpty()) return trimByWords(piece, selected, others);
        for (ReflectTranscript.Line other : others) {
            if (overlap(piece.startMs, piece.endMs, other.startMs, other.endMs) > EDGE_MS) return null;
        }
        int start = Math.max(piece.startMs, selected.startMs - EDGE_MS);
        int end = Math.min(piece.endMs, selected.endMs + EDGE_MS);
        if (end <= start || overlap(start, end, selected.startMs, selected.endMs) <= 0) return null;
        return new Piece(start, end, piece.text, piece.emotion,
                start == piece.startMs && end == piece.endMs ? piece.overlapDetected : null);
    }

    private static Piece trimByWords(Piece piece, ReflectTranscript.Line selected,
                                     List<ReflectTranscript.Line> others) {
        List<TimedWord> kept = new ArrayList<>();
        for (TimedWord word : piece.words) {
            if (word == null || word.text == null || word.text.isBlank() || word.endMs <= word.startMs) continue;
            if (belongsToSelected(word.startMs, word.endMs, selected, others)) kept.add(word);
        }
        if (kept.isEmpty()) return null;
        int start = kept.get(0).startMs;
        int end = kept.get(kept.size() - 1).endMs;
        if (end <= start) return null;
        StringBuilder text = new StringBuilder();
        for (TimedWord word : kept) {
            if (text.length() > 0) text.append(' ');
            text.append(word.text.trim());
        }
        if (text.length() == 0) return null;
        return new Piece(start, end, text.toString(), piece.emotion,
                start == piece.startMs && end == piece.endMs ? piece.overlapDetected : null);
    }

    private static boolean belongsToSelected(int startMs, int endMs, ReflectTranscript.Line selected,
                                             List<ReflectTranscript.Line> others) {
        int onSelected = overlap(startMs, endMs, selected.startMs, selected.endMs);
        int onOther = 0;
        for (ReflectTranscript.Line other : others) {
            onOther = Math.max(onOther, overlap(startMs, endMs, other.startMs, other.endMs));
        }
        if (onOther > onSelected) return false;
        if (onSelected > 0) return true;
        int center = startMs + (endMs - startMs) / 2;
        return center >= selected.startMs - EDGE_MS && center <= selected.endMs + EDGE_MS;
    }

    /** Повтор слова предыдущей реплики в начале нового текста убирается из нового текста. */
    private static List<Piece> withoutLeadingDuplicates(List<Piece> kept, List<ReflectTranscript.Line> neighbors) {
        List<Piece> result = new ArrayList<>();
        for (Piece piece : kept) {
            List<String> words = tokens(piece.text);
            for (ReflectTranscript.Line neighbor : neighbors) {
                if (overlap(piece.startMs, piece.endMs, neighbor.startMs, neighbor.endMs) <= 0) continue;
                List<String> earlier = tokens(neighbor.text);
                while (!words.isEmpty() && !earlier.isEmpty()
                        && neighbor.endMs > piece.startMs
                        && sameToken(words.get(0), earlier.get(earlier.size() - 1))) {
                    words.remove(0);
                }
            }
            if (words.isEmpty()) continue;
            result.add(new Piece(piece.startMs, piece.endMs, String.join(" ", words), piece.emotion, piece.overlapDetected));
        }
        return result;
    }

    /**
     * Если новая реплика заканчивается тем же словом, с которого начинается следующая,
     * слово остаётся в новой реплике, а из следующей уходит.
     */
    private static ReflectTranscript.Line withoutRepeatedFollowingWord(ReflectTranscript.Line neighbor, List<Piece> kept) {
        List<String> words = tokens(neighbor.text);
        int start = neighbor.startMs;
        boolean changed = false;
        for (Piece piece : kept) {
            if (overlap(piece.startMs, piece.endMs, start, neighbor.endMs) <= 0) continue;
            List<String> fresh = tokens(piece.text);
            while (!words.isEmpty() && !fresh.isEmpty()
                    && piece.endMs > start
                    && sameToken(fresh.get(fresh.size() - 1), words.get(0))) {
                words.remove(0);
                changed = true;
                if (piece.endMs > start && piece.endMs < neighbor.endMs) start = piece.endMs;
            }
        }
        if (!changed) return neighbor;
        if (words.isEmpty() || start >= neighbor.endMs) return null;
        return new ReflectTranscript.Line(neighbor.speakerId, start, neighbor.endMs, String.join(" ", words),
                neighbor.emotionProbabilities, neighbor.speakerName,
                start == neighbor.startMs ? neighbor.overlapDetected : null);
    }

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) return tokens;
        for (String token : text.trim().split("\\s+")) {
            if (!token.isBlank()) tokens.add(token);
        }
        return tokens;
    }

    private static boolean sameToken(String first, String second) {
        String left = tokenKey(first);
        String right = tokenKey(second);
        return !left.isEmpty() && left.equals(right);
    }

    private static String tokenKey(String token) {
        return token.toLowerCase(Locale.ROOT).replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
    }

    private static boolean coversSelected(ReflectTranscript.Line selected, List<Piece> kept) {
        int duration = selected.endMs - selected.startMs;
        int required = Math.min(duration, Math.max(200, duration / 2));
        int covered = 0;
        for (Piece piece : kept) covered += overlap(piece.startMs, piece.endMs, selected.startMs, selected.endMs);
        return covered >= required;
    }

    private static ReflectTranscript.Line donor(ReflectTranscript original, boolean[] replace,
                                                Piece piece, ReflectTranscript.Line selected) {
        ReflectTranscript.Line best = selected;
        int bestOverlap = 0;
        for (int index = 0; index < original.lines.size(); index++) {
            if (!replace[index]) continue;
            ReflectTranscript.Line line = original.lines.get(index);
            int covered = overlap(piece.startMs, piece.endMs, line.startMs, line.endMs);
            if (covered > bestOverlap) {
                bestOverlap = covered;
                best = line;
            }
        }
        return best;
    }

    private static int indexOf(ReflectTranscript transcript, ReflectTranscript.Line selected) {
        for (int index = 0; index < transcript.lines.size(); index++) {
            ReflectTranscript.Line line = transcript.lines.get(index);
            if (line.startMs == selected.startMs && line.endMs == selected.endMs
                    && line.speakerId.equals(selected.speakerId) && line.text.equals(selected.text)) {
                return index;
            }
        }
        return -1;
    }

    private static int overlap(int start, int end, int otherStart, int otherEnd) {
        return Math.max(0, Math.min(end, otherEnd) - Math.max(start, otherStart));
    }
}
