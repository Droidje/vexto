# Заглушка для пайплайна публикации образов (см. .github/workflows/publish.yml).
# Настоящее содержимое появится вместе с приложением: планируется Android —
# его артефакт APK/AAB, а здесь собирается контейнер сборки / будущие
# серверные компоненты. Заменить при появлении настоящего кода.
FROM alpine:3.20

LABEL org.opencontainers.image.source="https://github.com/Droidje/vexto"

RUN echo "vexto-placeholder" > /app-marker
CMD ["cat", "/app-marker"]
