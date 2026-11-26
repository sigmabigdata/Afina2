# Afina2

Современный кабинет для работы с документами, УКЭП и «Госключом».
Новая реализация AfinaZ: **React + Ant Design** на фронтенде, Spring Boot API на бэкенде.

> Статус: каркас и дизайн-система. Фронтенд работает на демо-данных,
> бэкенд — заготовка API. Функциональный паритет с AfinaZ — см. `docs/ROADMAP.md`.

## Стек

| Слой | Технологии |
|---|---|
| UI | React 18, TypeScript, Vite, Ant Design 5, Recharts, Manrope |
| API | Spring Boot 3.2, Java 17, Spring Security, JPA, Flyway |
| Данные | PostgreSQL 15 (Astra) |
| Крипто | КриптоПро CSP/JCSP, CAdES-BES, ГОСТ 34.10-2012/34.11-2012 |
| Инфраструктура | Astra Linux SE 1.8, Angie, Docker |

## Запуск

### Frontend

```bash
cd frontend
npm install
npm run dev      # http://localhost:5173
```

### Backend

```bash
cd backend
mvn spring-boot:run   # http://localhost:8080/api/health
```

## Структура

```
frontend/   React SPA: кабинет, документы, подпись, админка
backend/    Spring Boot API
deploy/     Docker/Angie/Compose
docs/       архитектура, сертификация, roadmap, дизайн-система
```

## Документация

- `docs/ARCHITECTURE.md` — архитектура и API-first подход
- `docs/CERTIFICATION.md` — сертифицируемые компоненты и реестр ПО
- `docs/ROADMAP.md` — паритет с AfinaZ и этапы
- `docs/DESIGN_SYSTEM.md` — токены и правила интерфейса
