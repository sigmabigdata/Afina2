# Состояние развёртывания Afina2

## Стенд

| Параметр | Значение |
|---|---|
| Сервер | `195.206.252.79` (`s117905.foxcdn.ru`, `mail.afinasystems.ru`) |
| ОС / архитектура | Debian GNU/Linux 10 (buster), x86_64 |
| Docker / Compose | 24.0.9 / v2.24.6 |
| Каталог | `/opt/afina2` |
| Доступ | http://195.206.252.79 |
| Контейнеры | `afina2-postgres`, `afina2-backend`, `afina2-web` (все healthy) |
| Данные | тома `afina2_pgdata`, `afina2_documents`, `afina2_backups`, `afina2_logs`, `afina2_crls`, `afina2_keys` |

## Проверено после развёртывания

- `GET /api/v1/health` — 200, сервис отвечает;
- SPA и клиентские маршруты (`/dashboard`, `/documents`) — 200;
- сквозной smoke-тест API — **32 из 32 проверок** (вход клиента и админа,
  документы, загрузка, шаблоны, Госключ, клиенты, аудит, CRL, настройки,
  мониторинг, бэкапы, МЧД, публичная проверка подписи);
- миграции Flyway применились на PostgreSQL, Hibernate `validate` прошёл;
- заголовки безопасности Angie (CSP, X-Frame-Options, Referrer-Policy) отдаются;
- CSRF-защита: POST без токена → 403; неавторизованный API → 401.

## Что включено временно (демо-доступ)

В `deploy/.env` на сервере:

```
APP_SEED_ENABLED=true        # тестовые клиент/админ, разделы, шаблоны, документы
APP_DEV_LOGIN_ENABLED=true   # вход без пароля по ссылке
SECURE_COOKIES=false         # HTTPS ещё не настроен
ADMIN_IP_WHITELIST=*         # админка доступна с любого IP
```

Демо-вход:

- клиент — `http://195.206.252.79/api/v1/auth/dev-login?email=client@afina.local`
- администратор — `http://195.206.252.79/api/v1/auth/dev-login?email=admin@afina.local`

## Что нужно сделать перед продуктивной эксплуатацией

1. **Домен и TLS.** Направить A-запись домена на `195.206.252.79`, выпустить
   сертификат и загрузить его в админке («Настройки → TLS») либо настроить
   `listen 443 ssl` в `deploy/angie.conf`. После — `APP_BASE_URL=https://…`,
   `SECURE_COOKIES=true`.
2. **SMTP.** Заполнить `MAIL_*` в `deploy/.env` (или в админке «Настройки → Почта»)
   и проверить кнопкой «Тест письма». Сейчас письма пишутся в лог.
3. **Администраторы по сертификату.** Внести `CN|СНИЛС` в
   `/opt/afina2/secrets/admins.env`, перезапустить backend, войти по сертификату.
4. **Отключить демо-режим:**
   ```bash
   ./deploy/scripts/harden.sh root@195.206.252.79
   ```
   Скрипт выключает `APP_SEED_ENABLED` и `APP_DEV_LOGIN_ENABLED`, проверяет,
   что вход без пароля недоступен.
5. **Ограничить админку по IP:** `ADMIN_IP_WHITELIST` в `deploy/.env`.
6. **Резервные копии.** Включить регулярное создание (админка → «Резервные
   копии») и настроить выгрузку за пределы сервера. Ключи шифрования хранятся
   в томе `afina2_keys` — сделать их резервную копию обязательно
   (админка → «Ключи шифрования», ZIP под паролем).
7. **Сертифицированный контур.** Сейчас используется образ
   `eclipse-temurin:17-jre`. Для сдачи с сертифицированными компонентами:
   собрать `deploy/Dockerfile.astra` (Astra Linux SE + Axiom JDK Pro +
   КриптоПро CSP), разложить `dist/axiom` и `dist/cryptopro`, переключить
   `BACKEND_IMAGE`/`BASE_IMAGE`. Требования к ОС хоста — Astra Linux SE.

## Обслуживание

```bash
# обновление (данные в томах сохраняются)
./deploy/scripts/deploy.sh root@195.206.252.79

# логи и статус
ssh root@195.206.252.79 'cd /opt/afina2 && docker compose -f deploy/docker-compose.yml logs -f --tail=100 backend'
ssh root@195.206.252.79 'cd /opt/afina2 && docker compose -f deploy/docker-compose.yml ps'

# проверка API
./backend/scripts/smoke.sh http://195.206.252.79
```
