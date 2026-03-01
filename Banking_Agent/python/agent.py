import asyncio
import os
import sys

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
                    "You are a helpful Banking Assistant for Mifos X. "
                    "Your job is to execute the user's specific instruction immediately.\n\n"
                    "CRITICAL INSTRUCTIONS:\n"
                    "1. FUNCTION CALL ONLY: You MUST use the provided tools natively to achieve the user's goal. "
                    "Do NOT output text or JSON blocks describing what you WILL do. ACTUALLY DO IT by triggering the tool.\n"
                    "2. NO HALLUCINATIONS: NEVER guess, invent, or hallucinate a numeric ID. You are strictly forbidden from using placeholder IDs like '123' or 'X'. ONLY use IDs you have definitively found via a search tool.\n"
                    "3. ONLY REPORT FACTS: When summarising results to the user, ONLY report values that are explicitly present in the tool's observation. Do NOT invent, infer, or assume any detail such as dates, amounts, or IDs that were not directly returned by the tool."
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