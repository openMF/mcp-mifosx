# Mifos X Banking Agent - Python MCP Server

This directory contains the Python-based Model Context Protocol (MCP) server for interacting with the Mifos X backend. It exposes the Fineract backoffice APIs as tools that AI agents (like Claude or Llama) can seamlessly use.

## Distribution

This server is designed to be easily configurable and containerized. All the necessary configuration lies in the `.env` file, and a `Dockerfile` is provided to spin up the image.

### 1. Configuration (.env)

Before using the server, you need to configure your environment variables.
Copy the provided `.env.example` to `.env`:

```bash
cp .env.example .env
```

Open `.env` and fill in the following key variables with your backend's details:

```ini
MIFOSX_BASE_URL=https://localhost:8443/fineract-provider/api/v1
MIFOSX_TENANT_ID=default
MIFOSX_USERNAME=mifos
MIFOSX_PASSWORD=password
```

### 2. Local Setup (Python Environment)

If you prefer to run the server directly on your host machine without Docker:

1. Ensure you have Python 3.11+ installed.
2. Create and activate a virtual environment (optional but recommended):
   ```bash
   python -m venv .venv
   source .venv/bin/activate  # On Windows: .venv\Scripts\activate
   ```
3. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```
4. Start the server (usually orchestrated by an MCP client, but you can test via standard I/O):
   ```bash
   python mcp_server.py
   ```

### 3. Docker Output

You can easily build a Docker image for standard distribution across any environment.

**Build the image:**
```bash
docker build -t mifos-mcp-server .
```

**Run the image (as an MCP Server via StdIO):**
MCP Servers typically communicate over Standard I/O (StdIO). You can run the Docker container dynamically interactively using `docker run -i` to let the AI agent communicate with it:

```bash
docker run -i --env-file .env mifos-mcp-server
```

### Configuring MCP Clients (e.g., Claude Desktop)

To use this server with Claude Desktop or cursor, update your MCP settings file (e.g. `claude_desktop_config.json`) with either the local python path or the Docker command.

**Option A: Local Execution**
```json
{
  "mcpServers": {
    "mifos-banking": {
      "command": "/path/to/your/python",
      "args": [
        "/path/to/mcp-mifosx/Banking_Agent/python/mcp_server.py"
      ],
      "env": {
        "MIFOSX_BASE_URL": "...",
        "MIFOSX_USERNAME": "..."
      }
    }
  }
}
```

**Option B: Docker Execution**
```json
{
  "mcpServers": {
    "mifos-banking": {
      "command": "docker",
      "args": [
        "run",
        "-i",
        "--rm",
        "--env-file",
        "/absolute/path/to/your/.env",
        "mifos-mcp-server"
      ]
    }
  }
}
```

## Agent Testing script

The file `agent.py` contains a sample minimal LangChain-MCP testing script using `ollama` and `llama3.1` (which connects to the local `mcp_server.py`). 
You can run it to interact with the underlying tools autonomously.
