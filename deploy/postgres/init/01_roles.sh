#!/bin/bash
# Afina2 · инициализация ролей PostgreSQL (разделение привилегий).
#
#   afina_migrator — владелец схемы, DDL (Flyway)
#   afina_app      — только DML (SELECT/INSERT/UPDATE/DELETE)
#   afina_auditor  — только чтение
#
# Скрипт выполняется контейнером postgres при первой инициализации кластера.
# Пароли берутся из переменных окружения контейнера (не хранятся в образе).
set -euo pipefail

DB="${POSTGRES_DB:?POSTGRES_DB не задан}"
MIGRATOR_USER="${DB_MIGRATOR_USER:-afina_migrator}"
MIGRATOR_PASSWORD="${DB_MIGRATOR_PASSWORD:?DB_MIGRATOR_PASSWORD не задан}"
APP_USER="${DB_USER:-afina_app}"
APP_PASSWORD="${DB_PASSWORD:?DB_PASSWORD не задан}"
AUDITOR_USER="${DB_AUDITOR_USER:-afina_auditor}"
AUDITOR_PASSWORD="${DB_AUDITOR_PASSWORD:-}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$DB" <<-SQL
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${MIGRATOR_USER}') THEN
            CREATE ROLE ${MIGRATOR_USER} LOGIN PASSWORD '${MIGRATOR_PASSWORD}';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${APP_USER}') THEN
            CREATE ROLE ${APP_USER} LOGIN PASSWORD '${APP_PASSWORD}';
        END IF;
    END
    \$\$;

    GRANT CONNECT ON DATABASE ${DB} TO ${MIGRATOR_USER}, ${APP_USER};
    GRANT USAGE ON SCHEMA public TO ${MIGRATOR_USER}, ${APP_USER};
    GRANT CREATE ON SCHEMA public TO ${MIGRATOR_USER};

    -- Права на существующие объекты
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${APP_USER};
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${APP_USER};

    -- Права на будущие объекты, которые создаст мигратор
    ALTER DEFAULT PRIVILEGES FOR ROLE ${MIGRATOR_USER} IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${APP_USER};
    ALTER DEFAULT PRIVILEGES FOR ROLE ${MIGRATOR_USER} IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO ${APP_USER};

    REVOKE ALL ON DATABASE ${DB} FROM PUBLIC;
    GRANT CONNECT ON DATABASE ${DB} TO ${MIGRATOR_USER}, ${APP_USER};
SQL

if [ -n "${AUDITOR_PASSWORD}" ]; then
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$DB" <<-SQL
        DO \$\$
        BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${AUDITOR_USER}') THEN
                CREATE ROLE ${AUDITOR_USER} LOGIN PASSWORD '${AUDITOR_PASSWORD}';
            END IF;
        END
        \$\$;
        GRANT CONNECT ON DATABASE ${DB} TO ${AUDITOR_USER};
        GRANT USAGE ON SCHEMA public TO ${AUDITOR_USER};
        GRANT SELECT ON ALL TABLES IN SCHEMA public TO ${AUDITOR_USER};
        ALTER DEFAULT PRIVILEGES FOR ROLE ${MIGRATOR_USER} IN SCHEMA public
            GRANT SELECT ON TABLES TO ${AUDITOR_USER};
SQL
fi

echo "Afina2: роли БД созданы (${MIGRATOR_USER}, ${APP_USER})"
