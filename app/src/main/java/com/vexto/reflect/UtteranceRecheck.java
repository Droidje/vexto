package com.vexto.reflect;

import com.vexto.engine.model.EmotionProbabilities;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Замена реплик после повторного качественного прогона короткого окна.
 * Пустой, чужой или не покрывающий выбранную реплику результат не применяется.
 */
final class UtteranceRecheck {
    static final int PAD_BEFORE_MS = 1_500;
    static final int PAD_AFTER_MS = 1_500;

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

        Piece(int startMs, int endMs, String text, EmotionProbabilities emotion) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
            this.emotion = emotion;
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
        boolean[] replace = replacementSet(original, selectedIndex, windowStartMs, windowEndMs);
        List<Piece> kept = piecesForReplacement(usable, original, replace);
        if (!coversSelected(selected, kept)) return null;
        List<ReflectTranscript.Line> next = new ArrayList<>(original.lines.size() + kept.size());
        for (int index = 0; index < original.lines.size(); index++) {
            if (!replace[index]) next.add(original.lines.get(index));
        }
        for (Piece piece : kept) {
            ReflectTranscript.Line donor = donor(original, replace, piece, selected);
            next.add(new ReflectTranscript.Line(donor.speakerId, piece.startMs, piece.endMs,
                    piece.text.trim(), piece.emotion, donor.speakerName));
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
            usable.add(new Piece(start, end, piece.text, piece.emotion));
        }
        usable.sort(Comparator.comparingInt((Piece piece) -> piece.startMs).thenComparingInt(piece -> piece.endMs));
        return usable;
    }

    /**
     * Выбранная реплика заменяется всегда. Сосед — только если окно накрывает
     * хотя бы половину его длительности. Короткий хвост длинной предыдущей реплики
     * не стирает её целиком.
     */
    private static boolean[] replacementSet(ReflectTranscript original, int selectedIndex,
                                            int windowStartMs, int windowEndMs) {
        boolean[] replace = new boolean[original.lines.size()];
        for (int index = 0; index < original.lines.size(); index++) {
            ReflectTranscript.Line line = original.lines.get(index);
            int covered = overlap(line.startMs, line.endMs, windowStartMs, windowEndMs);
            int duration = line.endMs - line.startMs;
            replace[index] = index == selectedIndex || covered * 2 >= duration;
        }
        return replace;
    }

    /** Фрагмент, который лежит на непереписываемом соседе сильнее, чем на заменяемых репликах, отбрасывается. */
    private static List<Piece> piecesForReplacement(List<Piece> usable, ReflectTranscript original, boolean[] replace) {
        List<Piece> kept = new ArrayList<>();
        for (Piece piece : usable) {
            int inside = 0;
            int outside = 0;
            for (int index = 0; index < original.lines.size(); index++) {
                ReflectTranscript.Line line = original.lines.get(index);
                int covered = overlap(piece.startMs, piece.endMs, line.startMs, line.endMs);
                if (replace[index]) inside += covered;
                else outside += covered;
            }
            if (outside > inside && inside == 0) continue;
            if (outside > inside) continue;
            kept.add(piece);
        }
        return kept;
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
