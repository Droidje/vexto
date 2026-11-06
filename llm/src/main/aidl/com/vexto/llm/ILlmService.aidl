package com.vexto.llm;

import com.vexto.llm.ILlmCallback;

interface ILlmService {
    /** Запускает генерацию; результат и поток токенов приходят в callback. */
    void generate(in Bundle request, ILlmCallback callback);
    void cancel();
    int[] countTokens(in Bundle model, in String[] texts);
    /** Выгружает модель сразу, не дожидаясь таймера простоя. */
    void unload();
}
