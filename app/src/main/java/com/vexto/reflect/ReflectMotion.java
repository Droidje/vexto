package com.vexto.reflect;

import android.view.animation.PathInterpolator;

final class ReflectMotion {
    static final PathInterpolator EASE = new PathInterpolator(.2f, .8f, .24f, 1f);

    static final PathInterpolator PAGE = new PathInterpolator(.24f, .55f, .24f, 1f);

    static final PathInterpolator TAB_STRIP = new PathInterpolator(.35f, .12f, .65f, .88f);

    private ReflectMotion() {}
}
