# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from langchain_core.tools import tool

from tools.mcp_adapter import fineract_client

# --- CODE TABLE READ OPERATIONS ---

@tool
def list_codes():
    """Answers: 'Show me all system codes' or 'What dropdown categories are available?'
    Returns all code definitions (Gender, Client Type, ID Type, etc.)"""
    print("[Tool] Fetching all system codes...")
    return fineract_client.execute_get("codes")

@tool
def get_code_values(code_id: int):
    """Answers: 'Show me the values for code ID 4' or 'What are the gender options?'
    Use list_codes() first to find the code ID, then use this to get its dropdown values."""
    print(f"[Tool] Fetching code values for code #{code_id}...")
    return fineract_client.execute_get(f"codes/{code_id}/codevalues")

@tool
def list_datatables():
    """Answers: 'Show me all data tables' or 'What custom fields are registered?'
    Returns all registered data tables (custom fields, additional data extensions)."""
    print("[Tool] Fetching all datatables...")
    return fineract_client.execute_get("datatables")


# --- DATATABLE CRUD OPERATIONS ---

def _map_column_type(col_type: str) -> str:
    """Map user-friendly column types to Fineract's internal type names."""
    type_map = {
        "string": "String",
        "int": "Integer",
        "integer": "Integer",
        "decimal": "Decimal",
        "boolean": "Boolean",
        "bool": "Boolean",
        "date": "Date",
        "datetime": "DateTime",
        "text": "Text",
        "dropdown": "Dropdown",
    }
    return type_map.get(col_type.lower(), "String")


@tool
def create_datatable(datatable_name: str, apptable_name: str, columns: list):
    """Create a new datatable (custom data extension) attached to a Fineract entity.
    apptable_name: m_client, m_group, m_loan, m_savings_account, m_office, m_center
    columns: list of dicts with keys: name, type, length (optional), mandatory (optional)
    type values: string, int, decimal, boolean, date, datetime, text, dropdown
    Example: [{"name": "meeting_date", "type": "date", "mandatory": true}]"""
    print(f"[Tool] Creating datatable '{datatable_name}' on '{apptable_name}'...")
    payload = {
        "datatableName": datatable_name,
        "apptableName": apptable_name,
        "multiRow": True,
        "columns": [
            {
                "name": col["name"],
                "type": _map_column_type(col.get("type", "string")),
                "length": col.get("length", 100),
                "mandatory": col.get("mandatory", False),
                "code": col.get("code", ""),
            }
            for col in columns
        ],
    }
    return fineract_client.execute_post("datatables", payload)


@tool
def get_datatable_entries(datatable_name: str, entity_id: int):
    """Read all datatable rows for a specific entity (client, group, loan, etc.).
    datatable_name: the registered datatable name (e.g., dt_group_meetings)
    entity_id: the ID of the entity the datatable is attached to"""
    print(f"[Tool] Fetching entries from '{datatable_name}' for entity {entity_id}...")
    return fineract_client.execute_get(f"datatables/{datatable_name}/{entity_id}")


@tool
def create_datatable_entry(datatable_name: str, entity_id: int, data: dict):
    """Add a row to a datatable for a specific entity.
    datatable_name: the registered datatable name
    entity_id: the ID of the entity
    data: dict of column values, e.g. {"meeting_date": "2026-05-15", "status": "scheduled"}
    Date format: yyyy-MM-dd. Include dateFormat and locale in data for date columns."""
    print(f"[Tool] Creating entry in '{datatable_name}' for entity {entity_id}...")
    if "locale" not in data:
        data["locale"] = "en"
    if "dateFormat" not in data:
        data["dateFormat"] = "yyyy-MM-dd"
    return fineract_client.execute_post(f"datatables/{datatable_name}/{entity_id}", data)


@tool
def update_datatable_entry(datatable_name: str, entity_id: int, data: dict):
    """Update a datatable entry for a specific entity.
    For multiRow datatables, updates the entry matching entity_id.
    data: dict of column values to update."""
    print(f"[Tool] Updating entry in '{datatable_name}' for entity {entity_id}...")
    if "locale" not in data:
        data["locale"] = "en"
    if "dateFormat" not in data:
        data["dateFormat"] = "yyyy-MM-dd"
    return fineract_client.execute_put(f"datatables/{datatable_name}/{entity_id}", data)


@tool
def delete_datatable_entry(datatable_name: str, entity_id: int):
    """Delete all datatable entries for a specific entity.
    datatable_name: the registered datatable name
    entity_id: the ID of the entity"""
    print(f"[Tool] Deleting entries from '{datatable_name}' for entity {entity_id}...")
    return fineract_client.execute_delete(f"datatables/{datatable_name}/{entity_id}")
