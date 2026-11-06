package com.vexto.llm;

oneway interface ILlmCallback {
    void onPiece(String piece);
    void onComplete(String text, in Bundle stats);
    void onError(String message);
}
