#!/bin/bash
# Creates Conductor's database and a dedicated, non-superuser role that owns it.
# Conductor connects as that role, never as postgres.
# Runs once, only when the Postgres data volume is empty.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" \
     -v app_user="$CONDUCTOR_APP_DB_USER" -v app_pass="$CONDUCTOR_APP_DB_PASSWORD" <<-'EOSQL'
    CREATE ROLE :"app_user" LOGIN PASSWORD :'app_pass'
        NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    CREATE DATABASE conductor OWNER :"app_user";
EOSQL
