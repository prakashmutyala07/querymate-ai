# SQL Read-Only AI Chat POC

## 1. Objective

The objective of this POC is to explore whether users can ask natural language questions through a chatbot UI and receive answers from SQL Server data in a controlled, read-only manner.

The POC uses Spring Boot, Spring AI, Thymeleaf UI, Microsoft Data API Builder (DAB), SQL Server, and an LLM integration to validate this approach.

Microsoft Data API Builder acts as the controlled data access layer between the application and SQL Server. It exposes selected database entities and supports the read-only data access approach required for this POC.

---

## 2. POC Overview

This POC provides a chatbot interface where users can ask database-related questions in natural language.

At a high level, the application receives the user question from the UI, processes it through the Spring Boot backend, interacts with the LLM using Spring AI, invokes approved tools through the guarded MCP boundary when database context is needed, accesses database information through Microsoft DAB, and returns a natural language response to the user.

The LLM helps interpret the request, but it does not directly access DAB, MCP tools, SQL Server, websites, or internal APIs. Tool execution is handled by the Spring Boot application through the guarded MCP tool boundary.

The current focus is on:

- Natural language question answering.
- Read-only access to SQL Server data.
- Controlled database access through Microsoft DAB.
- LLM-based response generation using Spring AI.
- Protecting sensitive data before interacting with external LLM services.
- Evaluating whether this approach can be extended to real project databases.

---

## 3. Technology Components

| Component | Purpose |
|---|---|
| Thymeleaf UI | Provides the chatbot user interface |
| Spring Boot | Hosts the backend application and request handling |
| Spring AI | Integrates the application with LLM providers |
| Microsoft Data API Builder | Acts as the controlled data access layer for SQL Server |
| SQL Server | Source database for the POC |
| Public LLM Provider | Used in the initial phase to validate LLM-based response generation; it has no direct access to DAB, MCP tools, SQL Server, websites, or internal APIs |
| PII Masking / Rehydration Layer | Protects sensitive values before and after LLM interaction |
| CA-GIP LLM-as-a-Service | Planned company LLM service evaluation in Phase 3 |

---

## 4. Phase-wise Implementation

| Phase | Focus Area | Status | Key Outcome |
|---|---|---|---|
| Phase 1 | Chatbot UI with public LLM integration | Completed | Validated the basic chatbot and LLM flow |
| Phase 2 | PII masking and rehydration | Implemented / Current | Reduced sensitive data exposure to the LLM |
| Phase 3 | Unicorn project database integration and CA-GIP LLM-as-a-Service evaluation | Planned | Validate against real project data and company-approved LLM service |

---

## 5. Phase 1: Chatbot UI with Public LLM

Phase 1 focused on proving the basic end-to-end chatbot flow.

In this phase, the application allowed users to ask questions through a chatbot UI. The backend application used Spring AI to interact with a public LLM provider and return natural language responses.

Microsoft DAB and SQL Server were part of the database access flow, helping validate how the application can work with structured data in a controlled manner. DAB remains the controlled data access layer between the application and SQL Server.

### What Was Implemented

- Chatbot UI using Thymeleaf.
- Spring Boot backend for handling user requests.
- Spring AI integration for LLM communication.
- Public LLM integration for response generation.
- SQL Server connectivity.
- Microsoft DAB as the controlled data access layer.
- Initial read-only database query approach.
- Basic natural language response generation.

### Key Outcome

Phase 1 confirmed that the basic idea is technically feasible.

The POC demonstrated that a user can ask a database-related question in natural language and receive a readable response through the chatbot UI.

### Feedback Received

During the Phase 1 demo, the main feedback from architects was related to data privacy and security.

The key concern was that sensitive data or database-derived information may be sent to a public LLM. This concern is important because the future target data may come from internal project systems and may include sensitive business or customer-related information.

This feedback became the main driver for Phase 2.

---

## 6. Phase 2: PII Masking and Rehydration

Phase 2 was introduced to address the sensitive data exposure concern raised during the Phase 1 review.

The main idea is to avoid sending original sensitive values directly to the LLM. Instead, sensitive values are replaced with placeholders before the LLM call. After the LLM returns the response, the application replaces the placeholders with the original values inside the application boundary.

### What Was Implemented

- Identification of sensitive values before LLM interaction.
- Masking of sensitive values using placeholders.
- Sending masked content to the LLM instead of original values.
- Receiving LLM responses with placeholders.
- Rehydrating placeholders with original values inside the application.
- Returning the final rehydrated response to the chatbot UI.

### Example

Original data:

```text
Customer John Smith placed an order for ₹10,000.
```

Masked data sent to LLM:

```text
Customer <CUSTOMER_NAME_1> placed an order for <AMOUNT_1>.
```

LLM response:

```text
<CUSTOMER_NAME_1> has a total order value of <AMOUNT_1>.
```

Final response shown to user:

```text
John Smith has a total order value of ₹10,000.
```

### Key Outcome

Phase 2 improved the security posture of the POC by reducing the sensitive data sent to the LLM.

Sensitive values remain within the application boundary and are restored only after the LLM response is received.

### Important Note

PII masking and rehydration reduces data exposure risk, but it should not be treated as the only security control.

For a production-like setup, this should be combined with read-only database permissions, controlled table and column exposure, authentication, authorization, logging controls, and enterprise-approved LLM usage.

---

## 7. Current High-Level Flow

**Architecture Diagram Placeholder**

> Add architecture diagram here.

Current request flow:

1. User enters a question in the chatbot UI.
2. Spring Boot application receives the request.
3. Application identifies and masks sensitive values before sending content to the LLM.
4. Application uses Spring AI to send the protected prompt or masked context to the LLM.
5. LLM returns text, structured response, or tool request to the application.
6. Application-side `SecureMcpToolCallback` / guardrail invokes approved DAB MCP tools when database context is needed.
7. DAB MCP tools call Microsoft Data API Builder.
8. Microsoft DAB interacts with SQL Server using the configured entities and read-only access rules.
9. Raw sensitive database results are protected/masked before any model continuation.
10. Application rehydrates placeholders with original values inside the application boundary.
11. Final response is shown to the user in the chatbot UI.

---

## 8. Role of Microsoft Data API Builder

Microsoft Data API Builder is an important component in this POC.

It acts as the controlled data access layer between the Spring Boot application and SQL Server.

The LLM does not directly call Microsoft DAB. The Spring Boot application invokes approved DAB MCP tools through the guarded MCP boundary, and those tools call Microsoft DAB.

In this POC, DAB helps with:

- Exposing selected database entities.
- Keeping database access controlled.
- Supporting a read-only data access direction.
- Avoiding direct uncontrolled database access from the chatbot layer.
- Providing a structured way for the application to interact with SQL Server data.

For future phases, DAB configuration will be important for deciding which Unicorn project tables and columns should be exposed to the chatbot.

---

## 9. Security Considerations

The main security concern identified so far is the possibility of sensitive data being sent to an external/public LLM.

The current POC addresses this concern through PII masking and rehydration.

Key security considerations include:

- Database access should remain read-only.
- Only approved tables should be exposed through DAB.
- Sensitive columns should be reviewed before exposure.
- Sensitive values should be masked before LLM calls.
- Rehydration should happen only inside the application.
- The LLM should not directly access DAB, MCP tools, SQL Server, websites, or internal APIs.
- Tool execution should be handled by the Spring Boot application through the guarded MCP tool boundary.
- Logs should avoid storing sensitive raw data unnecessarily.
- LLM credentials and database credentials should not be hardcoded.
- Environment variables or secure secret management should be used.
- Future usage should evaluate company-approved LLM services such as CA-GIP.
- Authentication and authorization requirements need to be reviewed before any production-like usage.

---

## 10. Phase 3 Plan

Phase 3 is the next planned phase of the POC.

The goal of Phase 3 is to move the POC closer to a realistic enterprise use case by connecting it with actual project data and evaluating the company-approved LLM service.

Phase 3 has two main focus areas.

### 10.1 Unicorn Project Database Integration

The POC will be extended to connect with the Unicorn project database and selected Unicorn project tables.

This will help validate whether the chatbot approach can work with a more realistic project database structure.

This phase may include:

- Connecting to the Unicorn project database.
- Selecting which Unicorn tables should be exposed.
- Reviewing which columns should be included or excluded.
- Ensuring the database access remains read-only.
- Updating DAB configuration for selected Unicorn entities.
- Testing natural language questions against Unicorn project data.
- Identifying limitations when working with a larger or more complex schema.

### 10.2 CA-GIP LLM-as-a-Service Evaluation

The POC will also evaluate CA-GIP LLM-as-a-Service as the company-approved LLM option.

This is important because using a company-provided LLM service may better align with internal security, compliance, and data-handling expectations compared to using a public LLM provider.

This phase may include:

- Understanding how to integrate with CA-GIP LLM-as-a-Service.
- Checking whether the current Spring AI integration can support CA-GIP directly or through an adapter/API layer.
- Reviewing authentication and access requirements.
- Testing response quality.
- Testing latency and reliability.
- Understanding data privacy and retention behavior.
- Deciding whether PII masking is still required when using CA-GIP.
- Comparing CA-GIP behavior with the current public LLM provider.

### Expected Outcome of Phase 3

Phase 3 should help answer:

- Can the POC work with Unicorn project database tables?
- Can the chatbot handle more realistic project data?
- Can CA-GIP LLM-as-a-Service replace the current public LLM provider?
- Is the current PII masking and rehydration approach still required with CA-GIP?
- What additional architecture or security changes are needed after the next review?

---

## 11. Demo Plan

The next demo can be presented as a journey from Phase 1 to Phase 3.

### Demo Flow

1. Explain the initial Phase 1 chatbot flow.
2. Show how the user can ask a database-related question.
3. Explain the architect feedback around public LLM data exposure.
4. Show the Phase 2 improvement with PII masking and rehydration.
5. Explain how sensitive values are protected before calling the LLM.
6. Show the final response after rehydration.
7. Explain the Phase 3 plan for Unicorn project database integration.
8. Explain the plan to evaluate CA-GIP LLM-as-a-Service.
9. Capture feedback from architects for the next iteration.

---

## 12. Open Questions

The following items need further review and confirmation:

- Which Unicorn project tables should be included in Phase 3?
- Which columns should be excluded because of sensitivity?
- What read-only permissions are required for the Unicorn database?
- Can CA-GIP LLM-as-a-Service be used as the preferred LLM provider?
- How should the application authenticate with CA-GIP?
- Is PII masking still required when using CA-GIP?
- What application-level authentication and authorization controls are required?
- What logging and audit requirements should be followed?
- Are there any additional architecture constraints from the security or architecture team?

---

## 13. Summary

This POC started as a chatbot UI integrated with Spring Boot, Spring AI, Microsoft DAB, SQL Server, and a public LLM provider.

Phase 1 validated the basic chatbot and LLM-based response generation flow. After the initial demo, architects raised concerns about sensitive data being sent to a public LLM.

To address this, Phase 2 introduced PII masking and rehydration. Sensitive values are masked before sending content to the LLM and restored only inside the application boundary before showing the final response to the user.

The next planned step is Phase 3. In Phase 3, the POC will be extended to connect with the Unicorn project database and selected Unicorn tables. In parallel, CA-GIP LLM-as-a-Service will be evaluated as a company-approved LLM option.

The outcome of Phase 3 and the next architecture review will decide the future direction of the POC.
