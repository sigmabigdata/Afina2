# Деплой Afina2

Каркас для локальной сборки. Для продакшена:

1. Runtime-образ бэкенда заменить на Astra Linux SE 1.8 + Axiom JDK Pro 17 +
   КриптоПро CSP/JCSP (см. AfinaZ Dockerfile как эталон).
2. Frontend раздаётся Angie (сертифицирован), статика собирается Node только в build-стадии.
3. TLS — Angie или внешний балансировщик; HSTS, CSP, X-Frame-Options.
4. Секреты — через `.env.prod`/secrets, не в git.
5. Перед публикацией: SBOM, лицензионный аудит, матрица сертификации.

Локально:

```bash
cp .env.example .env
docker compose -f deploy/docker-compose.yml --env-file .env up --build
# http://localhost:8088
```
