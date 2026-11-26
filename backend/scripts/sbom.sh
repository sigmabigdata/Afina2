#!/usr/bin/env bash
# Формирует SBOM (CycloneDX) для бэкенда и фронтенда.
# Артефакты: backend/target/sbom-afina2-backend.json, frontend/sbom-frontend.json
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export PATH="/opt/homebrew/bin:$PATH"

echo "▶ SBOM бэкенда (CycloneDX Maven)"
( cd "$ROOT/backend" && mvn -q -DskipTests org.cyclonedx:cyclonedx-maven-plugin:2.8.0:makeAggregateBom )
echo "  backend/target/sbom-afina2-backend.json"

echo "▶ SBOM фронтенда (npm)"
( cd "$ROOT/frontend" && npm sbom --sbom-format cyclonedx > sbom-frontend.json 2>/dev/null \
    || echo '{"note":"npm sbom недоступен, обновите npm"}' > sbom-frontend.json )
echo "  frontend/sbom-frontend.json"

echo "▶ Лицензионный отчёт"
( cd "$ROOT/backend" && mvn -q -DskipTests license:aggregate-add-third-party )
echo "  backend/target/licenses/THIRD-PARTY.txt"
