#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# Afina2 · развёртывание на сервере
#
#   ./deploy/scripts/deploy.sh user@host [/opt/afina2]
#
# Что делает:
#   1. Собирает backend и frontend локально (mvn package, npm run build).
#   2. Копирует артефакты, deploy-файлы и .env на сервер.
#   3. Поднимает контейнеры (docker compose up -d --build) и ждёт health.
#   4. Проверяет /api/v1/health и главную страницу.
#
# Требования: ssh-доступ, docker + compose plugin на сервере, .env заполнен.
# ─────────────────────────────────────────────────────────────
set -euo pipefail

TARGET="${1:?использование: deploy.sh user@host [remote-dir]}"
REMOTE_DIR="${2:-/opt/afina2}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

G='\033[0;32m'; Y='\033[1;33m'; R='\033[0;31m'; B='\033[1m'; N='\033[0m'
ok()   { printf "${G}✓${N} %s\n" "$*"; }
warn() { printf "${Y}⚠${N} %s\n" "$*"; }
die()  { printf "${R}✗${N} %s\n" "$*"; exit 1; }
step() { printf "\n${B}▶ %s${N}\n" "$*"; }

[ -f "$ROOT/deploy/.env" ] || die "нет deploy/.env (скопируйте deploy/.env.example и заполните)"

step "Сборка backend"
JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/axiomjdk-jdk-17.jdk/Contents/Home}"
( cd "$ROOT/backend" && JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:$PATH" mvn -q -DskipTests package )
ok "backend/target/afina2-backend.jar"

step "Сборка frontend"
( cd "$ROOT/frontend" && export PATH="/opt/homebrew/bin:$PATH" && npm ci --no-audit --no-fund >/dev/null && npm run build >/dev/null )
ok "frontend/dist"

step "Копирование на $TARGET:$REMOTE_DIR"
ssh "$TARGET" "mkdir -p '$REMOTE_DIR'/deploy/scripts '$REMOTE_DIR'/backend/target '$REMOTE_DIR'/frontend"
rsync -az --delete "$ROOT/deploy/" "$TARGET:$REMOTE_DIR/deploy/"
rsync -az "$ROOT/backend/target/afina2-backend.jar" "$TARGET:$REMOTE_DIR/backend/target/"
rsync -az "$ROOT/frontend/dist/" "$TARGET:$REMOTE_DIR/frontend/dist/"
ok "файлы скопированы"

step "Запуск контейнеров"
ssh "$TARGET" "cd '$REMOTE_DIR' && docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build"
ok "контейнеры запущены"

step "Ожидание готовности"
for i in $(seq 1 60); do
    if ssh "$TARGET" "curl -fsS -m 3 http://127.0.0.1/api/v1/health" >/dev/null 2>&1; then
        ok "API отвечает"
        break
    fi
    [ "$i" -eq 60 ] && die "API не поднялся за 120 с — смотрите: ssh $TARGET 'docker logs afina2-backend --tail 100'"
    sleep 2
done

ssh "$TARGET" "curl -fsS -m 5 http://127.0.0.1/api/v1/health" | sed 's/^/  /'
printf '\n%sРазвёрнуто:%s %s\n' "$G" "$N" "${APP_BASE_URL:-http://$TARGET}"
