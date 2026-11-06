# Образ для пайплайна публикации (см. .github/workflows/publish.yml).
# Приложение — Android: его артефакт (APK/AAB) собирается в CI и не является
# Docker-образом. Этот образ — контейнер сборки / будущий серверный компонент;
# заменить на настоящую сборку при появлении серверной части.
FROM alpine:3.20

LABEL org.opencontainers.image.source="https://github.com/Droidje/vexto"

RUN echo "vexto-placeholder" > /app-marker
CMD ["cat", "/app-marker"]
