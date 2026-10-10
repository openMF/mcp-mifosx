# Routing Matrix

Decide the route from the customer's stated need. When in doubt, ask directly and neutrally. Every question or inquiry goes to Operations.

## Customer signal → route

| Customer signal | Route (`route` value) | Advisor action |
|---|---|---|
| Wants to invest / save / deposit money / earn returns | Investment Commercial Advisor (`INVESTMENT_ADVISOR`) | Capture approximate amount, desired term, and whether already a customer. Transfer with context. |
| Wants a credit / loan / financing / liquidity | Credit Analyst / Commercial Advisor (`CREDIT_ANALYST`) | Capture approximate amount, purpose of the loan, and formal income status. Transfer with context. |
| Has a question, inquiry, clarification or request (products, requirements, status, processes, documents, terms, rates, etc.) | Operations department (`OPERATIONS`) | Do not resolve the question. Capture the reason for the inquiry and transfer to Operations with context. |
| Asks about both products (investment and credit) | Investment or Credit first, according to customer priority | Ask which need is most urgent and route to that path first. If additional questions arise, route them to Operations. |
| Existing customer asking about an active investment or credit / status | Operations department (`OPERATIONS`) | Do not give status or details. Route to Operations for follow-up and inquiry resolution. |
| Complaint, formal claim, or non-financial issue | Operations / Customer Service department (`OPERATIONS`), and CONDUSEF if applicable | Do not resolve formal complaints. Log and route according to protocol to Operations. |

## Signal keywords (non-exhaustive)

- **Investment:** invest, investment, save, savings, deposit, returns, yield, interest earned, Promissory Note, pagaré, CEDE, +D60, "put my money", "grow my money".
- **Credit:** loan, credit, financing, borrow, liquidity, cash advance, préstamo, crédito, financiamiento.
- **Operations:** what is, how much, which documents, requirements, status, balance, maturity, when, why, how does, complaint, claim, problem, error, refund.

A message that mixes a need with a question (for example "I want to invest, what is your rate?") is an investment need **and** a question: profile for investment, do not answer the rate, and state that the specialist or Operations will provide verified figures.

## Recommended routing phrases

- **Investment:** "I'd be happy to connect you with our Investment Commercial Advisor to simulate current options based on your desired amount and term. Does that sound good?"
- **Credit:** "I'd be happy to connect you with our Credit Analyst to review requirements and initial pre-analysis with you. Does that sound good?"
- **Operations (questions):** "To best answer your question, I'll transfer you to our Operations team. They will provide accurate information. Does that sound good?"

## Agentic roadmap context

- **Phase 1 (Reception + Profiling + Routing):** the Customer Service Advisor receives the customer via voice, WhatsApp or chat, captures structured data, and decides the path (Investment, Credit or Operations).
- **Phase 2 (Specialist or Operations):** the Investment Commercial Advisor simulates and guides, the Credit Analyst conducts pre-analysis, or Operations answers questions and inquiries.
- **Phase 3 (Formalization):** the respective commercial executive formalizes the request. The Customer Service Advisor does not participate in formalization.
