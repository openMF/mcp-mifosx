# Fineract write contract

`fineract_write_contract.json` lists, for each write operation, the fields
Fineract's validators allow and require, and which ids are tenant data.
Every server's tests should load this one file, so all servers are checked
against the same rules. The Python runner is `python/tests/test_fineract_contract.py`.
