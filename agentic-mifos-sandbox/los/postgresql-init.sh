#!/bin/bash
# Creates the LOS database and a dedicated, non-superuser role that owns it.
# The LOS connects as that role, never as postgres.
# Runs once, only when the Postgres data volume is empty.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" \
     -v app_user="$LOS_APP_DB_USER" -v app_pass="$LOS_APP_DB_PASSWORD" <<-'EOSQL'
    CREATE ROLE :"app_user" LOGIN PASSWORD :'app_pass'
        NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    CREATE DATABASE los OWNER :"app_user";
EOSQL
