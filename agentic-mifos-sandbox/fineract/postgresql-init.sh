#!/bin/bash
# Creates the two databases Fineract needs and a dedicated, non-superuser
# role that owns them. Fineract connects as that role, never as postgres.
# Runs once, only when the Postgres data volume is empty.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" \
     -v app_user="$FINERACT_APP_DB_USER" -v app_pass="$FINERACT_APP_DB_PASSWORD" <<-'EOSQL'
    CREATE ROLE :"app_user" LOGIN PASSWORD :'app_pass'
        NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    CREATE DATABASE fineract_tenants OWNER :"app_user";
    CREATE DATABASE fineract_default OWNER :"app_user";
    \c fineract_default
    -- Fineract's tenant migrations run CREATE EXTENSION "uuid-ossp";
    -- create it here so the app role never needs elevated privileges.
    CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
EOSQL
