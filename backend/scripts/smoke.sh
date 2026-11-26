#!/usr/bin/env bash
# smoke.sh — сквозная проверка REST API Afina2 на запущенном стенде.
#
# Использование:
#   ./scripts/smoke.sh [BASE_URL]
# По умолчанию http://localhost:8080.
#
# Требует включённых демо-данных и dev-входа:
#   APP_SEED_ENABLED=true APP_DEV_LOGIN_ENABLED=true
set -uo pipefail

BASE="${1:-http://localhost:8080}"
API="$BASE/api/v1"
TMP="$(mktemp -d)"
CLIENT_JAR="$TMP/client.jar"
ADMIN_JAR="$TMP/admin.jar"
PASS=0
FAIL=0

ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; FAIL=$((FAIL+1)); }
step() { printf '\n\033[1m▶ %s\033[0m\n' "$*"; }

check_code() { # url jar expected label
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -b "$2" -c "$2" "$1")
  if [ "$code" = "$3" ]; then ok "$4 ($code)"; else bad "$4: ожидался $3, получен $code"; fi
}

check_json() { # url jar jq-expr label
  local body
  body=$(curl -s -b "$2" -c "$2" "$1")
  if echo "$body" | python3 -c "import json,sys;d=json.load(sys.stdin);assert ($3)" 2>/dev/null; then
    ok "$4"
  else
    bad "$4 — ответ: $(echo "$body" | head -c 200)"
  fi
}

step "Публичные эндпоинты"
check_code "$API/health" /dev/null 200 "health"
check_code "$API/auth/csrf" /dev/null 200 "csrf"
check_code "$API/auth/me" /dev/null 401 "me без сессии → 401"
check_code "$API/documents" /dev/null 401 "documents без сессии → 401"
check_code "$API/public/info/00000000-0000-0000-0000-000000000000" /dev/null 404 "публичная проверка неизвестного UUID → 404"

step "Вход клиента и кабинет"
code=$(curl -s -c "$CLIENT_JAR" -o /dev/null -w '%{http_code}' "$API/auth/dev-login?email=client@afina.local")
[ "$code" = "302" ] && ok "dev-login клиента" || bad "dev-login клиента ($code)"
check_json "$API/auth/me" "$CLIENT_JAR" "d['role']=='ROLE_USER' and d['profileComplete']" "профиль клиента"
check_json "$API/documents?size=50" "$CLIENT_JAR" "d['total']>=1 and len(d['items'])>0" "список документов"
check_json "$API/documents?size=50" "$CLIENT_JAR" "all(x.get('folderName') for x in d['items'])" "у документов есть раздел"
check_json "$API/folders" "$CLIENT_JAR" "len(d)>=1 and 'documentsCount' in d[0]" "разделы"
check_json "$API/templates" "$CLIENT_JAR" "isinstance(d,list)" "шаблоны"
check_json "$API/dashboard" "$CLIENT_JAR" "'documentsTotal' in d and 'activity' in d" "сводка dashboard"
check_json "$API/documents/gosklyuch/orders" "$CLIENT_JAR" "isinstance(d,list)" "заявки Госключа"

DOC_ID=$(curl -s -b "$CLIENT_JAR" "$API/documents?size=1" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['items'][0]['id'])")
check_json "$API/documents/$DOC_ID" "$CLIENT_JAR" "d['id']==$DOC_ID" "карточка документа"
check_code "$API/documents/$DOC_ID/content" "$CLIENT_JAR" 200 "содержимое документа"
check_code "$API/documents/$DOC_ID/download" "$CLIENT_JAR" 200 "скачивание документа"

step "Загрузка документа"
printf 'Договор поставки №%s\nТестовый документ для проверки.\n' "$(date +%s)" > "$TMP/contract.txt"
UP=$(curl -s -b "$CLIENT_JAR" -c "$CLIENT_JAR" -X POST "$API/documents" \
  -H "X-XSRF-TOKEN: $(curl -s -b "$CLIENT_JAR" -c "$CLIENT_JAR" "$API/auth/csrf" \
      | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')" \
  -F "file=@$TMP/contract.txt")
NEW_ID=$(echo "$UP" | python3 -c "import json,sys;print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
if [ -n "$NEW_ID" ]; then ok "загрузка документа (id=$NEW_ID)"; else bad "загрузка: $(echo "$UP" | head -c 200)"; fi

step "Админка"
code=$(curl -s -c "$ADMIN_JAR" -o /dev/null -w '%{http_code}' "$API/auth/dev-login?email=admin@afina.local")
[ "$code" = "302" ] && ok "dev-login администратора" || bad "dev-login администратора ($code)"
check_json "$API/auth/me" "$ADMIN_JAR" "d['role']=='ROLE_ADMIN'" "роль администратора"
check_json "$API/admin/clients?size=50" "$ADMIN_JAR" "d['total']>=1" "список клиентов"
check_json "$API/admin/audit?size=10" "$ADMIN_JAR" "'items' in d" "журнал аудита"
check_json "$API/admin/audit/stats" "$ADMIN_JAR" "'total' in d" "статистика аудита"
check_json "$API/admin/crl" "$ADMIN_JAR" "isinstance(d,list)" "список CRL"
check_json "$API/admin/settings" "$ADMIN_JAR" "isinstance(d,list)" "настройки"
check_json "$API/admin/monitor/checks" "$ADMIN_JAR" "isinstance(d,list)" "проверки компонентов"
check_json "$API/admin/system" "$ADMIN_JAR" "'runtime' in d or 'memory' in d" "сведения о сервере"
check_json "$API/admin/templates" "$ADMIN_JAR" "isinstance(d,list)" "шаблоны админки"
check_json "$API/admin/gosklyuch/status" "$ADMIN_JAR" "'enabled' in d" "статус интеграции Госключа"
check_json "$API/admin/poa" "$ADMIN_JAR" "isinstance(d,list)" "доверенности"
check_json "$API/admin/backups" "$ADMIN_JAR" "isinstance(d,list)" "бэкапы"

CLIENT_ID=$(curl -s -b "$ADMIN_JAR" "$API/admin/clients?size=50" \
  | python3 -c "import json,sys;d=json.load(sys.stdin);print([c['id'] for c in d['items'] if c['role']=='ROLE_USER'][0])")
check_json "$API/admin/clients/$CLIENT_ID" "$ADMIN_JAR" "'client' in d and 'documents' in d" "карточка клиента"

step "Публичная проверка подписи"
UUID=$(curl -s -b "$CLIENT_JAR" "$API/documents/$DOC_ID" | python3 -c "import json,sys;print(json.load(sys.stdin)['publicUuid'])")
check_json "$API/public/info/$UUID" /dev/null "'documentName' in d" "публичная карточка документа"

rm -rf "$TMP"
printf '\n\033[1mИтого:\033[0m успешно %s, ошибок %s\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
