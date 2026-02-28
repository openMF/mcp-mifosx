import asyncio
import os
import sys
from typing import Annotated, TypedDict

from langchain_ollama import ChatOllama
from langgraph.prebuilt import create_react_agent
from langgraph.checkpoint.memory import MemorySaver
from langchain_mcp_adapters.tools import load_mcp_tools
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

# 1. Configuration
MODEL_NAME = "llama3.1"
MCP_SERVER_SCRIPT = os.path.join(os.path.dirname(__file__), "mcp_server.py")

# 2. Initialize LLM
llm = ChatOllama(model=MODEL_NAME, temperature=0)

async def main():
    # 3. Setup MCP Server Parameters
    server_params = StdioServerParameters(
        command=sys.executable,
        args=[MCP_SERVER_SCRIPT],
    )

    print("\n" + "="*50)
    print("🏦 Mifos Banking Agent (LangChain-MCP)")
    print(f"Model: {MODEL_NAME}")
    print("Status: Connecting to MCP Server...")
    print("="*50)

    async with stdio_client(server_params) as (read, write):
        async with ClientSession(read, write) as session:
            # Initialize the session
            await session.initialize()
            
            # 4. Load tools from MCP Server
            print("📦 Loading tools from MCP server...")
            tools = await load_mcp_tools(session)
            print(f"✅ Loaded {len(tools)} tools.")

            # 5. Create the Agent
            memory = MemorySaver()
            agent = create_react_agent(
                llm, 
                tools, 
                checkpointer=memory,
                prompt=(
                    "You are the autonomous AI Banking System Administrator for Mifos X. "
                    "You are equipped with direct backoffice access to lending and savings infrastructure. "
                    "Your objective is to execute the user's instructions autonomously, efficiently, and with total precision.\n\n"
                    "OPERATIONAL DIRECTIVES:\n"
                    "1. Always resolve identities first: Use 'search_clients' to determine the underlying Client ID before executing client-specific operations.\n"
                    "2. Chain operations seamlessly: If an operation (e.g., 'create_new_loan') returns an ID, automatically inject that ID into subsequent necessary actions (e.g., 'approve_disburse_loan').\n"
                    "3. Perform entity discovery: If tasked with modifying an existing product (e.g., applying fees), but the specific account/loan ID is unknown, resolve it via 'get_client_accts'.\n"
                    "4. Operate silently: Never request intermediate identifiers from the user. Retrieve all necessary parameters autonomously.\n"
                    "5. ANTI-HALLUCINATION PROTOCOL: NEVER guess, assume, or hallucinate identifiers (Client IDs, Loan IDs, etc.). You MUST execute the required tool and wait to extract the precise real ID from its observation before proceeding to the next tool.\n"
                    "6. Execute sequentially: If a tool requires an ID from a previous step, DO NOT output hypothetical JSON calls for both tools at once. Call the first tool, wait for the system to return the ID, and then call the second tool.\n"
                    "7. Conclude with a comprehensive brief: Upon completing a workflow, issue a professional summary outlining the actions taken and the resulting system state.\n\n"
                    "EXAMPLE OPERATIONAL WORKFLOW - 'Initiate and disburse a [Amount] loan over [Months] months for [Client Name]':\n"
                    "  - Action: search_clients('[Client Name]') -> Observation: Client ID X\n"
                    "  - Action: create_new_loan(X, [Amount], [Months], 1) -> Observation: Loan ID Y\n"
                    "  - Action: approve_disburse_loan(Y) -> Observation: Success\n"
                    "  - Response: 'The loan request for [Amount] over [Months] months has been successfully originated, approved, and disbursed for [Client Name] under Loan ID Y.'"
                )
            )

            # 6. Interaction Loop
            print("\n(Type 'quit' or 'exit' to stop)\n")
            
            config = {"configurable": {"thread_id": "banking-session-1"}}
            
            while True:
                try:
                    user_input = input("👤 Teller: ")
                    if user_input.lower() in ["quit", "exit", "q"]:
                        print("Shutting down Agent...")
                        break
                    
                    if not user_input.strip():
                        continue

                    # Stream the agent's response
                    async for chunk in agent.astream(
                        {"messages": [("human", user_input)]},
                        config=config,
                        stream_mode="values"
                    ):
                        # Get the last message
                        message = chunk["messages"][-1]
                        
                        # Only print AI messages that aren't tool calls
                        if message.type == "ai" and not message.tool_calls and message.content:
                            print(f"\n🤖 Agent: {message.content}\n")
                            
                except KeyboardInterrupt:
                    break
                except Exception as e:
                    print(f"❌ Error: {e}")

if __name__ == "__main__":
    asyncio.run(main())