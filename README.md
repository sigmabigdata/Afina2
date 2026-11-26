# Afina2

Кабинет для работы с документами, УКЭП и «Госключом»: React SPA + Spring Boot
REST API. Реализация функционала AfinaZ на современном стеке и дизайне.

## Стек

| Слой | Технологии |
|---|---|
| UI | React 18, TypeScript, Vite 5, Ant Design 5, Recharts, Manrope |
| API | Spring Boot 3.2, Java 17 (Axiom JDK Pro), Spring Security, JPA, Flyway |
| Данные | PostgreSQL 15 (Astra) / H2 для тестов |
| Крипто | КриптоПро CSP + JCSP, CAdES-BES, ГОСТ 34.10-2012 / 34.11-2012 |
| Инфраструктура | Astra Linux SE 1.8, Angie, Docker Compose |

## Возможности

- **Документы**: загрузка, разделы, карточка, предпросмотр PDF, версии,
  статусы, публичная ссылка для проверки подписи.
- **УКЭП**: подписание через плагин КриптоПро (ключ не покидает носитель),
  проверка подписи и цепочки на сервере (JCSP), проверка CRL, применение МЧД,
  PDF-визуализация штампов, справка о подписании, пакетное подписание.
- **Госключ**: отправка одного документа или пакета до 15 файлов, статусы ЕПГУ,
  автоматическое применение полученной подписи.
- **Шаблоны**: HTML → PDF с полями, генерация документов и согласий.
- **Онбординг**: заполнение профиля и подписание согласия на обработку ПДн.
- **Админка**: клиенты и их документы, аудит с выгрузкой CSV, CRL, настройки,
  мониторинг компонентов, резервные копии, управление ключами, МЧД, SSL.
- **Безопасность**: cookie-сессии, CSRF, CSP без `unsafe-eval`, rate limit,
  IP-whitelist админки, шифрование файлов и персональных данных (AES-256-GCM).

## Быстрый старт

```bash
# 1. Backend (H2 в памяти, демо-данные, dev-вход)
cd backend
APP_SEED_ENABLED=true APP_DEV_LOGIN_ENABLED=true mvn spring-boot:run

# 2. Frontend
cd frontend
npm install && npm run dev        # http://localhost:5173

# 3. Вход в демо
open 'http://localhost:5173/api/v1/auth/dev-login?email=client@afina.local'
open 'http://localhost:5173/api/v1/auth/dev-login?email=admin@afina.local'
```

Полный стек в Docker и развёртывание на сервере — `deploy/README.md`.

## Проверка качества

```bash
cd backend
mvn verify                       # тесты + JaCoCo (покрытие ≥ 85 % по бизнес-логике)
./scripts/smoke.sh               # сквозная проверка API на запущенном стенде
./scripts/sbom.sh                # SBOM (CycloneDX) + лицензионный отчёт
```

SonarQube: `mvn sonar:sonar -Dsonar.host.url=… -Dsonar.login=…`
(проект `afina2`, quality gate — см. `docs/CERTIFICATION.md`).

## Структура

```
frontend/   React SPA: кабинет, документы, подпись, админка
backend/    Spring Boot API, миграции, тесты, скрипты
deploy/     Dockerfile, compose, Angie, развёртывание
docs/       архитектура, сертификация, roadmap, дизайн-система
```

## Документация

- `docs/ARCHITECTURE.md` — архитектура, сценарии, безопасность
- `docs/CERTIFICATION.md` — сертифицируемые компоненты, реестр ПО, SBOM
- `docs/ROADMAP.md` — этапы и паритет с AfinaZ
- `docs/DESIGN_SYSTEM.md` — токены и правила интерфейса
- `deploy/README.md` — развёртывание и эксплуатация
