package com.vexto.reflect;

import java.util.HashMap;
import java.util.Map;

/** Failed inference is not a completed title. Bound retries per source and foreground session. */
final class TitleRetryPolicy {
    private record Failure(String fingerprint, int attempts, long nextAt) {}
    private final Map<String, Failure> failures = new HashMap<>();

    synchronized long delay(String recording, String fingerprint, long now) {
        Failure failure = failures.get(recording);
        if (failure == null || !failure.fingerprint.equals(fingerprint)) return 0;
        if (failure.attempts >= 3) return Long.MAX_VALUE;
        return Math.max(0, failure.nextAt - now);
    }

    synchronized void failed(String recording, String fingerprint, long now) {
        Failure previous = failures.get(recording);
        int count = previous != null && previous.fingerprint.equals(fingerprint) ? previous.attempts + 1 : 1;
        failures.put(recording, new Failure(fingerprint, count, now + (count == 1 ? 10_000 : 60_000)));
    }

    synchronized void succeeded(String recording) { failures.remove(recording); }
    synchronized void resumed() { failures.clear(); }
}
