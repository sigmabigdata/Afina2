#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# Afina2 · перевод стенда в защищённый режим
#
#   ./deploy/scripts/harden.sh root@host [/opt/afina2]
#
# Выключает демонстрационные возможности (наполнение тестовыми данными и
# вход без пароля) и требует HTTPS для cookie. Запускать после того, как
# настроены SMTP и вход администратора по сертификату.
# ─────────────────────────────────────────────────────────────
set -euo pipefail

TARGET="${1:?использование: harden.sh user@host [remote-dir]}"
REMOTE_DIR="${2:-/opt/afina2}"

echo "▶ Отключаю демо-режим на $TARGET:$REMOTE_DIR"
ssh "$TARGET" bash -s <<EOF
set -euo pipefail
cd "$REMOTE_DIR"
for key in APP_SEED_ENABLED APP_DEV_LOGIN_ENABLED; do
    if grep -q "^\${key}=" deploy/.env; then
        sed -i "s/^\${key}=.*/\${key}=false/" deploy/.env
    else
        echo "\${key}=false" >> deploy/.env
    fi
done
grep -E "^(APP_SEED_ENABLED|APP_DEV_LOGIN_ENABLED|SECURE_COOKIES|APP_BASE_URL)=" deploy/.env
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --force-recreate backend
EOF

echo "▶ Проверка, что dev-login недоступен"
if ssh "$TARGET" "curl -fsS -o /dev/null http://127.0.0.1/api/v1/auth/dev-login?email=x" 2>/dev/null; then
    echo "⚠ dev-login всё ещё отвечает — проверьте конфигурацию"
    exit 1
fi
echo "✓ Стенд в защищённом режиме (dev-login недоступен)"
