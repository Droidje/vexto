package com.vexto.reflect;

import java.util.List;

/** Finds the latest starting utterance that still covers the playback position. */
final class ReflectPlaybackTimeline {
    private final List<ReflectTranscript.Line> lines;
    private final int[] maxEnd;

    ReflectPlaybackTimeline(List<ReflectTranscript.Line> lines) {
        this.lines = lines;
        maxEnd = new int[lines.size()];
        int latestEnd = 0;
        for (int index = 0; index < lines.size(); index++) {
            latestEnd = Math.max(latestEnd, lines.get(index).endMs);
            maxEnd[index] = latestEnd;
        }
    }

    int at(int positionMs) {
        int low = 0, high = lines.size();
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (lines.get(middle).startMs <= positionMs) low = middle + 1;
            else high = middle;
        }
        for (int index = low - 1; index >= 0 && maxEnd[index] > positionMs; index--) {
            if (lines.get(index).endMs > positionMs) return index;
        }
        return -1;
    }
}
