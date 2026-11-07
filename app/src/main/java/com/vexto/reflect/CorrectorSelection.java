package com.vexto.reflect;

/** Which transcript lines receive the optional RUPunct corrector. */
final class CorrectorSelection {
    private CorrectorSelection() {}

    static boolean applies(String mode, boolean selfSpeaker) {
        return ReflectPreferences.PUNCTUATION_ALL.equals(mode)
                || (ReflectPreferences.PUNCTUATION_SELF.equals(mode) && selfSpeaker);
    }
}
