# Состояние развёртывания Afina2

## Стенд

| Параметр | Значение |
|---|---|
| Сервер | `195.206.252.79` (`mail.afinasystems.ru`, `s117905.foxcdn.ru`) |
| ОС хоста | Debian GNU/Linux 10 (buster), x86_64 |
| **ОС приложения** | **Astra Linux SE 1.8** (контейнер бэкенда) |
| **Среда исполнения** | **Axiom JDK Pro 17.0.20.1** (сертифицированная) |
| **СКЗИ** | **КриптоПро CSP 5.0 R2** (проверено `csptest`), библиотеки JCSP 5.0.49800 |
| Веб-сервер | Angie 1.12.3 (TLS, CSP, HTTP/2) |
| БД | PostgreSQL 15, Flyway V1–V30, разделение ролей migrator/app/auditor |
| Каталог | `/opt/afina2` |
| Доступ | **https://afinasystems.ru**, https://www.afinasystems.ru |

## Что настроено

### HTTPS
- Сертификат Let's Encrypt на `afinasystems.ru`, `www.afinasystems.ru`,
  `mail.afinasystems.ru`; продление автоматическое (certbot, webroot через Angie).
- HTTP → HTTPS 301, HSTS, HTTP/2, современные наборы шифров.
- После продления сертификата сервисы перезагружаются хуком
  `/etc/letsencrypt/renewal-hooks/deploy/reload-services.sh`.

### Почта (Postfix на хосте)
- `MAIL_MODE=smtp`, `MAIL_HOST=host.docker.internal:25`, отправитель
  `noreply@afinasystems.ru`.
- Postfix работает как relay, принимает письма от docker-сети
  (`mynetworks = 127.0.0.0/8 172.16.0.0/12`).
- Проверено: тестовое письмо доставлено во внешний ящик (Gmail, 250 OK).

### Вход по сертификату
- Администратор: **Дырул Анатолий Юрьевич**, СНИЛС **18962570627**
  (`/opt/afina2/secrets/admins.env`, права `1001:1001 640`).
- Проверено end-to-end: челлендж → подпись CMS → сессия с ролью `ROLE_ADMIN`.
- Сейчас `APP_AUTH_CERT_LOGIN_REQUIRE_CHAIN=false`: в truststore ещё нет
  корневых сертификатов УЦ. Подпись при этом проверяется криптографически,
  CN и СНИЛС сверяются с admins.env.
- **Чтобы включить строгий режим:** положить корневые и промежуточные
  сертификаты УЦ (`.cer`, DER/PEM) в `/opt/afina2/certs`, затем в
  `deploy/.env` поставить `APP_AUTH_CERT_LOGIN_REQUIRE_CHAIN=true` и
  перезапустить backend. CRL подтягиваются автоматически по CDP.

### Сертифицированный контур
- Бэкенд собран как `afina2/backend:astra`: Astra Linux SE 1.8 + Axiom JDK Pro 17
  + КриптоПро CSP 5.0 R2 (все пакеты установлены, `AcquireContext: OK`).
- Запуск: `docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.astra.yml`
- Требуются каталоги `dist/libs` (JCSP), `dist/axiom` (JDK), `dist/cryptopro` (CSP).
- Для полного соответствия: официальный образ Astra из registry.astralinux.ru.

### PostgreSQL на Astra (подготовлено, переключение — по окну обслуживания)

Образ `afinaz-postgres:15-astra` уже загружен на сервер. Он использует
Debian-кластер (`PGDATA=/var/lib/postgresql/15/main`), поэтому данные нужно
перенести, а не перемонтировать:

```bash
# 1. Бэкап (уже есть: /opt/afina2/backups/pre-astra.sql)
ssh afina2 'docker exec afina2-postgres pg_dump -U afina2 -d afina2 > /opt/afina2/backups/pre-astra.sql'

# 2. Остановить стек и переключить образ + точку монтирования
#    в docker-compose.astra.yml добавить:
#      postgres:
#        image: afinaz-postgres:15-astra
#        volumes: [ "pgdata:/var/lib/postgresql" ]

# 3. Поднять postgres, затем восстановить данные:
ssh afina2 'docker exec -i afina2-postgres psql -U afina2 -d afina2 < /opt/afina2/backups/pre-astra.sql'
```

Проверено, что образ запускается на amd64 и создаёт кластер 15/main.

## Логотип и интерфейс

- Типографический знак: градиентная монограмма «А» + словесный знак «Афина»,
  векторный (SVG/CSS), корректно работает в светлой и тёмной теме.
- Тот же знак используется в футере PDF-визуализаций (`backend/.../static/img/logo.png`).
- favicon — знак «А» на градиенте.

## Демо-режим (временно)

```
APP_SEED_ENABLED=true        # тестовые клиент/админ, разделы, шаблоны, документы
APP_DEV_LOGIN_ENABLED=true   # вход без пароля по ссылке
```

Демо-вход:
- клиент — `https://afinasystems.ru/api/v1/auth/dev-login?email=client@afina.local`
- администратор — `https://afinasystems.ru/api/v1/auth/dev-login?email=admin@afina.local`

Отключить после настройки: `./deploy/scripts/harden.sh root@195.206.252.79`.

## DNS: важное замечание

`afinasystems.ru` и `www.afinasystems.ru` указывают на `195.206.252.79` (корректно).
У части DNS-резолверов (например, 194.67.1.154) закэширован старый адрес
`mail.afinasystems.ru` → `94.103.13.34` (старый выключенный сервер).
Пока кэш не истечёт, открывайте `https://afinasystems.ru`.
Проверить: `dig +short mail.afinasystems.ru @8.8.8.8`.

## Обслуживание

```bash
# логи и статус
ssh afina2 'cd /opt/afina2 && docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.astra.yml logs -f --tail=100 backend'

# проверка API (32 проверки)
./backend/scripts/smoke.sh https://afinasystems.ru

# обновление
./deploy/scripts/deploy.sh root@195.206.252.79
```
