#!/bin/sh
# Точка входа контейнера Afina2.
#
# 1. Проверяет обязательные переменные окружения (fail-fast).
# 2. Запускает приложение с JVM-опциями; exec → SIGTERM доходит до JVM,
#    graceful shutdown корректно завершает работу (30 с на дренаж).
set -eu

log() { printf '%s [entrypoint] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "ОШИБКА: $*"; exit 1; }

: "${DB_URL:?DB_URL не задан}"
: "${DB_USER:?DB_USER не задан}"
: "${DB_PASSWORD:?DB_PASSWORD не задан}"
: "${APP_BASE_URL:?APP_BASE_URL не задан}"
: "${SPRING_PROFILES_ACTIVE:=prod}"

export SPRING_PROFILES_ACTIVE

for dir in /app/storage /app/backups /app/logs /app/crls; do
    mkdir -p "$dir" 2>/dev/null || true
    if [ ! -w "$dir" ]; then
        die "нет прав на запись в $dir (проверьте монтирование тома)"
    fi
done

log "Профиль: $SPRING_PROFILES_ACTIVE, БД: ${DB_URL%%\?*}"
log "Запуск Afina2…"
exec java $JAVA_OPTS -jar /app/app.jar "$@"
