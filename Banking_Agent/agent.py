from typing import Annotated
from typing_extensions import TypedDict
from langgraph.graph.message import add_messages
from langgraph.graph import StateGraph, START, END
from langgraph.prebuilt import ToolNode, tools_condition
from langchain_ollama import ChatOllama

# 1. Import your dynamic router
from tools.registry import router

# Combine all tools into a single master list for the Execution Node.
# (The LLM won't see all of these at once, but the execution node needs 
# access to all of them so it can run whatever the LLM selects).
all_tools = (
    router.domain_map["clients"] + 
    router.domain_map["loans"] + 
    router.domain_map["savings"]
)

# 2. Define the Agent's Memory (State)
class AgentState(TypedDict):
    messages: Annotated[list, add_messages]

# 3. Initialize the Local SLM (Ollama)
# temperature=0 ensures strict, deterministic, bank-grade responses
llm = ChatOllama(model="llama3.1", temperature=0)

# 4. Define the Chatbot Node with Dynamic Context Routing
def chatbot_node(state: AgentState):
    """Intercepts the prompt, routes the intent, and asks the LLM."""
    last_message = state["messages"][-1]
    
    # If the message is from the human, we route it to find the right tools
    if last_message.type == "human":
        user_query = last_message.content
        print(f"\\n🧠 [Router] Analyzing intent for: '{user_query}'...")
        
        # Ask the registry which tools we need based on keywords
        active_tools = router.route_intent(user_query)
        tool_names = [tool.name for tool in active_tools]
        print(f"🚦 [Router] Loading {len(active_tools)} tools for this turn: {tool_names}")
        
        # MAGIC TRICK: We strictly bind ONLY the relevant tools to the LLM
        llm_with_tools = llm.bind_tools(active_tools)
        
    else:
        # If the last message was a tool result, we just give it all tools 
        # so it can formulate its final response
        llm_with_tools = llm.bind_tools(all_tools)

    print("🤖 [LLM] Processing with Ollama...")
    response = llm_with_tools.invoke(state["messages"])
    return {"messages": [response]}

# 5. Build the Graph State Machine
graph_builder = StateGraph(AgentState)

# Add the nodes
graph_builder.add_node("chatbot", chatbot_node)
graph_builder.add_node("tools", ToolNode(tools=all_tools))

# Define the execution flow (The Cyclic Loop)
graph_builder.add_edge(START, "chatbot")
graph_builder.add_conditional_edges("chatbot", tools_condition)
graph_builder.add_edge("tools", "chatbot")

# Compile the final banking copilot!
banking_copilot = graph_builder.compile()

# ==========================================
# 🚀 6. Test the Brain!
# ==========================================
if __name__ == "__main__":
    print("\\n" + "="*50)
    print("🏦 Mifos Local Banking Copilot Activated")
    print("Model: llama3.1 (Ollama)")
    print("Status: Dynamic Intent Routing Online")
    print("="*50)
    print("(Type 'quit' or 'exit' to stop)\\n")
    
    # Initialize an empty list of messages to act as our continuous memory
    chat_history = []
    
    while True:
        user_input = input("👤 Teller: ")
        if user_input.lower() in ["quit", "exit", "q"]:
            print("Shutting down Copilot...")
            break
            
        # Add user input to history
        chat_history.append(("human", user_input))
        
        # Stream the graph's execution
        events = banking_copilot.stream(
            {"messages": chat_history}, 
            stream_mode="values"
        )
        
        for event in events:
            # Check the latest message in the state
            latest_msg = event["messages"][-1]
            
            # We only print the final English response to the human 
            # (We hide the raw JSON tool calls from the UI)
            if latest_msg.type == "ai" and not latest_msg.tool_calls:
                print(f"\\n🤖 Copilot: {latest_msg.content}\\n")
                
                # Update our chat history with the complete state
                chat_history = event["messages"]