package com.vexto.reflect;

/** State of the inline AI summary card; absent state hides the card. */
record ReflectSummaryState(String text, String status, int percent, boolean running, boolean retryable) {}
