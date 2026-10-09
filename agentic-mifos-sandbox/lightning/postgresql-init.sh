#!/bin/bash
# Creates the Lightning database and a dedicated, non-superuser role that
# owns it. Lightning connects as that role, never as postgres.
# Runs once, only when the Postgres data volume is empty.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" \
     -v app_user="$LIGHTNING_APP_DB_USER" -v app_pass="$LIGHTNING_APP_DB_PASSWORD" <<-'EOSQL'
    CREATE ROLE :"app_user" LOGIN PASSWORD :'app_pass'
        NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    CREATE DATABASE lightning OWNER :"app_user";
    \c lightning
    -- Lightning's migrations run CREATE EXTENSION for these; create them
    -- here so the app role never needs elevated privileges.
    CREATE EXTENSION IF NOT EXISTS citext;
    CREATE EXTENSION IF NOT EXISTS pg_trgm;
EOSQL
