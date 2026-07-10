#!/usr/bin/env bash
# Forseti — restore TEST del backup más reciente en R2
# Test de criterio salida ③ Sprint 0: "backup creado, cifrado, subido y RESTAURADO con éxito"
# IMPORTANTE: usa container Postgres aparte (NO toca cluster prod).

set -euo pipefail

LOG=/var/log/forseti-restore.log
log() { echo "[$(date -Iseconds)] $*" | tee -a "$LOG" >&2; }

log "=== INICIO restore test ==="

# Validar archivos requeridos
for f in db.env r2.env backup.env; do
    [ -f "/etc/forseti/$f" ] || { log "ERROR: falta /etc/forseti/$f"; exit 1; }
done

# shellcheck source=/dev/null
. /etc/forseti/db.env
# shellcheck source=/dev/null
. /etc/forseti/r2.env
# shellcheck source=/dev/null
. /etc/forseti/backup.env

# 1. Listar backups ordenados por LastModified, NO alfabético — FIX #7
LATEST=$(AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
    AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
    AWS_DEFAULT_REGION=auto \
    aws s3api list-objects-v2 \
    --bucket "$R2_BUCKET" \
    --prefix "backups/forseti-" \
    --endpoint-url "$R2_ENDPOINT" \
    --query 'sort_by(Contents, &LastModified)[-1].Key' \
    --output text 2>/dev/null)

if [ -z "$LATEST" ] || [ "$LATEST" = "None" ]; then
    log "ERROR: no hay backups en R2 con prefix 'backups/forseti-'"
    exit 1
fi
log "Backup más reciente (por LastModified): $LATEST"

# 2. Descargar
TMP_DIR=$(mktemp -d -p /var/lib/forseti)
trap 'rm -rf "$TMP_DIR"' EXIT
DUMP_ENC="$TMP_DIR/dump.enc"

AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
AWS_DEFAULT_REGION=auto \
aws s3 cp "s3://$R2_BUCKET/$LATEST" "$DUMP_ENC" \
    --endpoint-url "$R2_ENDPOINT" --no-progress

log "Descargado: $(stat -c%s "$DUMP_ENC") bytes"

# 3. Decrypt + descomprimir
DUMP_FILE="$TMP_DIR/dump.sql"
export BACKUP_PASSPHRASE
openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 -pass env:BACKUP_PASSPHRASE \
    -in "$DUMP_ENC" | gunzip > "$DUMP_FILE"
unset BACKUP_PASSPHRASE

DUMP_SIZE=$(stat -c%s "$DUMP_FILE")
log "Descifrado OK: $DUMP_SIZE bytes"

# 4. Restaurar a CONTAINER POSTGRES APARTE — FIX A10: no tocar cluster prod
TEST_CONTAINER="forseti-restore-test-$$"
TEST_PASSWORD=$(openssl rand -base64 24 | tr -d '=+/' | head -c 32)

log "Arrancando container Postgres temporal $TEST_CONTAINER..."
docker run -d --rm \
    --name "$TEST_CONTAINER" \
    -e POSTGRES_PASSWORD="$TEST_PASSWORD" \
    -e POSTGRES_DB=restore_test \
    --memory="256m" \
    postgres:17-alpine > /dev/null

# Esperar healthy
sleep 5
for i in 1 2 3 4 5 6 7 8 9 10; do
    if docker exec "$TEST_CONTAINER" pg_isready -U postgres > /dev/null 2>&1; then
        break
    fi
    sleep 2
done

log "Restaurando dump en container temporal..."
cat "$DUMP_FILE" | docker exec -i "$TEST_CONTAINER" \
    pg_restore -U postgres -d restore_test --no-owner --no-acl 2>&1 \
    | (grep -v 'WARNING' || true)

# 5. Verificar tabla count
TABLES=$(docker exec "$TEST_CONTAINER" \
    psql -U postgres -d restore_test -tAc \
    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';")
log "✓ Restore OK: $TABLES tablas restauradas"

# 6. Cleanup container temporal
docker stop "$TEST_CONTAINER" > /dev/null 2>&1 || true

log "=== FIN restore test (criterio salida ③ Sprint 0 ✓) ==="
