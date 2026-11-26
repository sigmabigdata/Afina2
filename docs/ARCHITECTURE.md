# Архитектура Afina2

## Принципы

1. **API-first**: весь функционал доступен через REST `/api/v1`; SPA не зависит от серверных шаблонов.
2. **Сертифицированное ядро**: ОС, СУБД, JDK, веб-сервер и СКЗИ — сертифицированные компоненты; UI-библиотеки не выполняют криптографических и защитных функций.
3. **Криптография только в сертифицированном провайдере**: подпись в браузере — плагин КриптоПро, проверка на сервере — JCSP. BouncyCastle — только ASN.1/CMS-структуры.
4. **Один домен, cookie-сессии**: SPA и API на одном origin за Angie; CSRF-токен, SameSite, CSP.
5. **Аудит всего**: каждое действие пользователя/админа пишется в audit_events.

## Компоненты

```
Browser (React SPA)
  ├─ КриптоПро CAdES plugin (подпись)
  └─ fetch /api/v1/* (cookie session)

Angie (TLS, CSP, static SPA)
  └─ Spring Boot API
       ├─ auth (magic-link, сертификат)
       ├─ documents/folders/signatures
       ├─ templates / PDF visualization
       ├─ Госключ (orders, status, package)
       ├─ CRL / truststore / verification
       └─ audit, settings, monitoring
  └─ PostgreSQL 15
```

## Ключевые сценарии

### Подписание

1. SPA получает файл документа (`GET /api/v1/documents/{id}/content`).
2. Плагин КриптоПро формирует detached CAdES-BES по ГОСТ.
3. SPA отправляет base64-подпись на `POST /api/v1/documents/{id}/signatures`.
4. Сервер: JCSP-проверка, цепочка, CRL, аудит, визуализация, уведомление.

### Госключ

1. `POST /api/v1/gosklyuch/orders` (один или пакет документов).
2. Сервер собирает `req.xml` + ZIP, подписывает пакет УКЭП организации (JCSP) и отправляет в ЕПГУ.
3. Планировщик/кнопка обновляет статус, сохраняет `.sig`, применяет после проверки.

### Админка

REST-таблицы: клиенты, документы, заявки, аудит, CRL, настройки.
Опасные операции (restore, cleanup) — только admin + подтверждение + аудит.

## Аутентификация

- клиент: magic-link на email (одноразовый токен);
- сотрудник/админ: сертификат УКЭП (challenge → подпись → сессия);
- SPA: cookie `HttpOnly`, `Secure`, `SameSite=Strict`, CSRF-cookie;
- rate limit на login, IP whitelist для админки.

## Дизайн-система

Токены и компоненты — `docs/DESIGN_SYSTEM.md`. Ant Design ThemeConfig + собственные
CSS-переменные; тёмная/светлая тема; адаптив от 360px.
