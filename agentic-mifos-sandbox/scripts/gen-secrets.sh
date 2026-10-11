#!/usr/bin/env bash
# Creates .env from .env.example (if missing) and fills every empty secret
# with a freshly generated value. Values already set are never changed, so
# it is safe to re-run after .env.example gains new keys.
# Requires: bash, openssl.
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] || cp .env.example .env
# Append keys added to .env.example since .env was created.
while IFS= read -r line; do
  key="${line%%=*}"
  [[ "$line" =~ ^[A-Z0-9_]+= ]] && ! grep -q "^${key}=" .env && echo "$line" >> .env
done < .env.example

password() { openssl rand -hex 24; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
# Lightning worker keys, same shape as `mix lightning.gen_worker_keys`:
# base64 of a PKCS#1 RSA private key, and of its PKCS#1 public key.
openssl genrsa -traditional -out "$tmp/runs.pem" 2048 2>/dev/null
openssl rsa -in "$tmp/runs.pem" -RSAPublicKey_out -out "$tmp/runs.pub.pem" 2>/dev/null

generated() {
  case "$1" in
    FINERACT_DB_ADMIN_PASSWORD|FINERACT_DB_APP_PASSWORD) password ;;
    LIGHTNING_DB_ADMIN_PASSWORD|LIGHTNING_DB_APP_PASSWORD) password ;;
    LIGHTNING_ADMIN_PASSWORD|OPENFN_CALLBACK_SECRET) password ;;
    LIGHTNING_SECRET_KEY_BASE) openssl rand -base64 64 | tr -d '\n' ;;
    LIGHTNING_PRIMARY_ENCRYPTION_KEY|LIGHTNING_WORKER_SECRET) openssl rand -base64 32 ;;
    LIGHTNING_WORKER_RUNS_PRIVATE_KEY) base64 -w0 < "$tmp/runs.pem" ;;
    LIGHTNING_WORKER_PUBLIC_KEY) base64 -w0 < "$tmp/runs.pub.pem" ;;
    *) return 1 ;;
  esac
}

# The two worker keys are a pair: regenerate both or neither.
priv=$(grep -m1 '^LIGHTNING_WORKER_RUNS_PRIVATE_KEY=' .env | cut -d= -f2-)
pub=$(grep -m1 '^LIGHTNING_WORKER_PUBLIC_KEY=' .env | cut -d= -f2-)
if { [ -n "$priv" ] && [ -z "$pub" ]; } || { [ -z "$priv" ] && [ -n "$pub" ]; }; then
  echo "LIGHTNING_WORKER_RUNS_PRIVATE_KEY and LIGHTNING_WORKER_PUBLIC_KEY must be set together; clear both to regenerate." >&2
  exit 1
fi

filled=0
while IFS= read -r line; do
  key="${line%%=*}"
  if [[ "$line" =~ ^[A-Z0-9_]+=$ ]] && value=$(generated "$key"); then
    echo "${key}=${value}"
    filled=$((filled + 1))
  else
    echo "$line"
  fi
done < .env > "$tmp/env"
cat "$tmp/env" > .env
chmod 600 .env
echo "Generated ${filled} value(s) in .env"
