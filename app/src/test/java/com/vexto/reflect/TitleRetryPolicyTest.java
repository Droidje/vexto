package com.vexto.reflect;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TitleRetryPolicyTest {
    @Test public void retriesBackOffAndStopAfterThreeFailuresWithoutBlockingOtherSources() {
        var policy = new TitleRetryPolicy();
        policy.failed("first.wav", "a", 100);
        assertEquals(10_000, policy.delay("first.wav", "a", 100));
        assertEquals(0, policy.delay("second.wav", "a", 100));
        assertEquals(0, policy.delay("first.wav", "a", 10_100));
        policy.failed("first.wav", "a", 10_100);
        assertEquals(60_000, policy.delay("first.wav", "a", 10_100));
        policy.failed("first.wav", "a", 70_100);
        assertEquals(Long.MAX_VALUE, policy.delay("first.wav", "a", 1_000_000));
        assertEquals(0, policy.delay("first.wav", "corrected-text", 70_100));
        policy.resumed();
        assertEquals(0, policy.delay("first.wav", "a", 70_100));
    }
}
