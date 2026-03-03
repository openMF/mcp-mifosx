# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

import asyncio
import os
import sys
import uuid
from datetime import datetime
from pathlib import Path

from langchain_ollama import ChatOllama
from langgraph.prebuilt import create_react_agent
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
from langchain_mcp_adapters.tools import load_mcp_tools
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

# ─── 1. Configuration ──────────────────────────────────────────────────────────
MODEL_NAME      = "llama3.1"
MCP_SERVER_SCRIPT = os.path.join(os.path.dirname(__file__), "mcp_server.py")

# Persistent memory database — survives restarts
MEMORY_DB_PATH  = Path.home() / ".mifos" / "agent_memory.db"
MEMORY_DB_PATH.parent.mkdir(parents=True, exist_ok=True)

# ─── 2. LLM ────────────────────────────────────────────────────────────────────
llm = ChatOllama(
    model=MODEL_NAME,
    temperature=0,
    num_ctx=4096,        # Keep context tight — forces focus on current step
    num_predict=256,     # Short response window — discourages planning ahead
    repeat_penalty=1.2,  # Penalise repetitive JSON text blocks
)

# ─── 3. System Prompt ──────────────────────────────────────────────────────────
SYSTEM_PROMPT = """You are a Banking Assistant for Mifos X with persistent memory. You have access to tools that talk to a live banking database.

CRITICAL RULES — NEVER BREAK THESE:

1. TOOLS ONLY: Execute every request by calling a tool. NEVER output JSON blocks, code, or text descriptions of what you plan to do. If you cannot find a tool, say so. Otherwise, call it immediately.

2. NO GUESSED IDs: You are FORBIDDEN from using any numeric ID you did not receive from a tool response in this conversation. IDs like 12345, 999, or 0 are HALLUCINATIONS. If you do not have the ID, SEARCH FOR IT FIRST.

3. MANDATORY LOOKUP CHAIN — Follow this exact order whenever a name is given:
   Step 1: Call search_clients(name) to find the client ID.
   Step 2: Call get_client_accts(client_id) to find loan/savings account IDs.
   Step 3: Call the requested tool with the ID from Step 2.
   DO NOT skip steps. DO NOT guess the ID.

4. FACTS ONLY: Only report values that were explicitly returned by a tool. Never infer, assume, or invent dates, amounts, or IDs not present in the tool response.

5. MEMORY: Reuse IDs already established earlier in this conversation. If a client or loan was already looked up, use that ID directly without searching again.
"""


async def main():
    server_params = StdioServerParameters(
        command=sys.executable,
        args=[MCP_SERVER_SCRIPT],
    )

    print("\n" + "="*55)
    print("🏦 Mifos Banking Agent — Context-Aware Edition")
    print(f"   Model  : {MODEL_NAME}")
    print(f"   Memory : {MEMORY_DB_PATH}")
    print("="*55)

    # ── Session setup ─────────────────────────────────────────────────────────
    print("\nSession options:")
    print("  [Enter]      — Start a NEW conversation")
    print("  [session ID] — Resume an existing session")
    raw = input("\nSession ID (or press Enter for new): ").strip()

    if raw:
        thread_id = raw
        print(f"▶ Resuming session: {thread_id}")
    else:
        # Generate a unique ID: date + short UUID for readability
        thread_id = f"{datetime.now().strftime('%Y%m%d')}-{uuid.uuid4().hex[:8]}"
        print(f"▶ New session started: {thread_id}")

    print(f"\n📌 Your session ID is: {thread_id}")
    print("   (Save this to resume the conversation later)\n")

    async with stdio_client(server_params) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()

            print("📦 Loading tools from MCP server...")
            tools = await load_mcp_tools(session)
            print(f"✅ Loaded {len(tools)} tools.")
            print("\nType 'quit' to exit  |  'new' to start a fresh session  |  'id' to print session ID\n")

            # ── SQLite persistent checkpointer ────────────────────────────────
            async with AsyncSqliteSaver.from_conn_string(str(MEMORY_DB_PATH)) as memory:

                agent = create_react_agent(
                    llm,
                    tools,
                    checkpointer=memory,
                    prompt=SYSTEM_PROMPT
                )

                config = {"configurable": {"thread_id": thread_id}}

                while True:
                    try:
                        user_input = input("👤 Teller: ").strip()

                        if not user_input:
                            continue
                        if user_input.lower() in ["quit", "exit", "q"]:
                            print(f"\n✅ Session saved. Resume with: {thread_id}\n")
                            break
                        if user_input.lower() == "id":
                            print(f"📌 Session ID: {thread_id}\n")
                            continue
                        if user_input.lower() == "new":
                            thread_id = f"{datetime.now().strftime('%Y%m%d')}-{uuid.uuid4().hex[:8]}"
                            config = {"configurable": {"thread_id": thread_id}}
                            print(f"🔄 New session started: {thread_id}\n")
                            continue

                        async for chunk in agent.astream(
                            {"messages": [("human", user_input)]},
                            config=config,
                            stream_mode="values"
                        ):
                            message = chunk["messages"][-1]
                            if message.type == "ai" and not message.tool_calls and message.content:
                                print(f"\n🤖 Agent: {message.content}\n")

                    except KeyboardInterrupt:
                        print(f"\n✅ Session saved. Resume with: {thread_id}\n")
                        break
                    except Exception as e:
                        print(f"❌ Error: {e}")


if __name__ == "__main__":
    asyncio.run(main())