# Развёртывание Afina2

Стек: **Angie (SPA + reverse proxy) → Spring Boot 3 (API) → PostgreSQL 15**.
Сертифицируемый контур: Astra Linux SE 1.8, Axiom JDK Pro 17, Angie,
PostgreSQL, КриптоПро CSP/JCSP.

## 1. Локальный запуск (разработка)

```bash
# бэкенд (H2 в памяти, демо-данные, dev-вход)
cd backend
APP_SEED_ENABLED=true APP_DEV_LOGIN_ENABLED=true \
  mvn spring-boot:run

# фронтенд
cd frontend
npm install && npm run dev     # http://localhost:5173, /api → :8080
```

Демо-вход: `http://localhost:5173/api/v1/auth/dev-login?email=client@afina.local`
(клиент) и `...?email=admin@afina.local` (администратор).

> `APP_DEV_LOGIN_ENABLED=true` включает вход без пароля — **только для разработки**
> и демонстрационного стенда. В production профиле эндпоинт отсутствует.

## 2. Стек в Docker

```bash
cp deploy/.env.example deploy/.env    # заполнить пароли и адрес
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
curl -fsS http://localhost/api/v1/health
```

Сервисы:

| Контейнер | Порт | Назначение |
|---|---|---|
| `afina2-web` | 80/443 | Angie: SPA + прокси `/api` |
| `afina2-backend` | внутренний 8080 | REST API, подпись, Госключ |
| `afina2-postgres` | внутренний 5432 | PostgreSQL 15 (наружу не публикуется) |

Тома: `pgdata`, `documents`, `backups`, `logs`, `crls`.

## 3. Развёртывание на сервере

```bash
./deploy/scripts/deploy.sh root@server /opt/afina2
```

Скрипт соберёт артефакты, скопирует их и поднимет стек, затем дождётся
`/api/v1/health`.

## 4. Сертифицированный контур

1. Заменить базовые образы (build args):
   ```bash
   docker compose -f deploy/docker-compose.yml build \
     --build-arg BASE_IMAGE=registry.company.ru/astra:1.8_jdk17 \
     --build-arg POSTGRES_IMAGE=... \
     --build-arg ANGIE_IMAGE=...
   ```
2. Установить КриптоПро CSP в образ бэкенда (JCSP-лицензия — через переменную
   окружения, не в git).
3. Смонтировать:
   - `/app/certs` — truststore с корневыми сертификатами УЦ (только чтение);
   - `/app/secrets` — `admins.env` (CN|СНИЛС администраторов) и ключи
     шифрования (создаются автоматически при первом старте — **сразу сделайте
     резервную копию**);
   - `/app/crls` — списки отзыва (обновляются автоматически).
4. Включить TLS: положить сертификат и ключ в `deploy/certs/tls` и добавить
   `listen 443 ssl` в `angie.conf` (или использовать внешний балансировщик).
5. `SECURE_COOKIES=true` и `APP_BASE_URL=https://…` обязательны.

## 5. Эксплуатация

| Задача | Команда |
|---|---|
| Логи | `docker logs -f afina2-backend` |
| Статус | `curl -s http://localhost/api/v1/health` |
| Резервная копия | админка → «Резервные копии» или `POST /api/v1/admin/backups` |
| Обновление | `./deploy/scripts/deploy.sh root@server` (данные в томах сохраняются) |
| Проверка API | `./backend/scripts/smoke.sh http://localhost` (нужны демо-данные) |

## 6. Безопасность

- Админка ограничена `ADMIN_IP_WHITELIST` (CIDR поддерживается).
- CSP без `unsafe-eval`; плагин КриптоПро разрешён с `cryptopro.ru`.
- CSRF — double-submit cookie (`XSRF-TOKEN` → `X-XSRF-TOKEN`).
- Пароли БД и JCSP — только в `.env`/secrets, не в git.
- Ключи шифрования файлов и ПДн создаются в `/app/secrets/keys`;
  без них восстановление данных невозможно — выгружайте их через
  админку («Ключи шифрования», ZIP с паролем).
