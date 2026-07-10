#!/usr/bin/env bash
# Forseti — backup nocturno Postgres → R2
# Corre via cron en el VPS: 0 8 * * * (03:00 hora Ecuador = 08:00 UTC)
# Cifrado: AES-256-CBC + PBKDF2 100k iters (passphrase en /etc/forseti/backup.env)
# Retención: 30 días en R2 (configurar lifecycle rules en el bucket).

set -euo pipefail

LOG=/var/log/forseti-backup.log
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
TMP_DIR=$(mktemp -d -p /var/lib/forseti)
trap 'rm -rf "$TMP_DIR"' EXIT

log() { echo "[$(date -Iseconds)] $*" | tee -a "$LOG" >&2; }

log "=== INICIO backup $TIMESTAMP ==="

# Validar existencia + permisos de cada .env ANTES de cargar (set -e ayuda pero el mensaje es mejor)
for f in db.env r2.env backup.env; do
    if [ ! -f "/etc/forseti/$f" ]; then
        log "ERROR: falta /etc/forseti/$f"
        exit 1
    fi
    perm=$(stat -c%a "/etc/forseti/$f")
    if [ "$perm" != "600" ]; then
        log "ERROR: /etc/forseti/$f permisos=$perm (debe ser 600)"
        exit 1
    fi
done

# shellcheck source=/dev/null
. /etc/forseti/db.env
# shellcheck source=/dev/null
. /etc/forseti/r2.env
# shellcheck source=/dev/null
. /etc/forseti/backup.env

DUMP_FILE="$TMP_DIR/forseti-$TIMESTAMP.sql.gz.enc"

log "Dumping DB $POSTGRES_DB → cifrado AES-256..."
# Pasar passphrase via env var, no via argv (no aparece en `ps aux`)
export BACKUP_PASSPHRASE
PGPASSWORD=$POSTGRES_PASSWORD pg_dump \
    -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" \
    -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    --format=custom --no-owner --no-acl \
    | gzip \
    | openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -salt -pass env:BACKUP_PASSPHRASE \
    > "$DUMP_FILE"
unset BACKUP_PASSPHRASE

DUMP_SIZE=$(stat -c%s "$DUMP_FILE")
log "Dump cifrado: ${DUMP_SIZE} bytes"

# Validación tamaño mínimo — un dump completo (incluso DB vacía con Flyway + extensiones) > 500B
if [ "$DUMP_SIZE" -lt 500 ]; then
    log "ERROR: dump sospechosamente pequeño ($DUMP_SIZE bytes) — pg_dump pudo haber fallado"
    exit 1
fi

log "Subiendo a R2 bucket=$R2_BUCKET..."
AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
AWS_DEFAULT_REGION=auto \
aws s3 cp "$DUMP_FILE" "s3://$R2_BUCKET/backups/forseti-$TIMESTAMP.sql.gz.enc" \
    --endpoint-url "$R2_ENDPOINT" \
    --no-progress

log "✓ Backup subido OK ($DUMP_SIZE bytes)"
log "=== FIN backup ==="
