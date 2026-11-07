package com.vexto.reflect;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LineShimmerColorTest {
    @Test public void wavePeakIsBlueAndMovesAlongTheLine() {
        int middle = LineShimmerColor.at(50, 100, 0.5f);
        int edge = LineShimmerColor.at(0, 100, 0.5f);
        assertEquals(LineShimmerColor.PEAK, middle);
        assertEquals(LineShimmerColor.REST, edge);
        assertTrue(blue(LineShimmerColor.at(8, 100, 0.2f)) > blue(LineShimmerColor.at(90, 100, 0.2f)));
    }

    @Test public void waveHasNotReachedTheMiddleYet() {
        assertEquals(LineShimmerColor.REST, LineShimmerColor.at(40, 100, 0f));
    }

    private static int blue(int color) { return color & 0xff; }
}
