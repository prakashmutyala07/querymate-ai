# QueryMate AI

AI assistant that helps users query enterprise data using natural language.

## Verified Setup Choices

- Java: 21
- Spring Boot: 4.1.1
- Spring AI BOM: 2.0.1
- Base package: `com.ai.querymateai`
- MCP transport: Streamable HTTP, configured through `.env`
- Chat provider: Spring AI OpenAI starter, pointed at OpenAI

## Local Configuration

Runtime values are loaded from the local `.env` file via Spring Boot config import.
Copy `.env.example` to `.env` locally and fill in your own values. The real `.env`
file is ignored by git and should not be committed.

Required values:

```properties
OPENAI_API_KEY=...
TOKEN_SECRET_KEY=...
DAB_MCP_BASE_URL=http://localhost:5001
ECOM_MSSQL_CONNECTION_STRING=...
```

Other settings such as the model names, MCP endpoint path, completion limit, temperature, and log level have defaults in `application.yml`; add them to `.env` only when overriding locally.

For a responsive local POC, use the prompt-enforced JSON mode with a 30-second model timeout and leave the primary
retry disabled:

```properties
APP_REQUEST_TIMEOUT=30s
APP_RESPONSE_FORMAT=prompt_json
APP_PRIMARY_RETRY_ENABLED=false
```

The default execution flow is `primary -> fallback`. Set `APP_PRIMARY_RETRY_ENABLED=true` only when the extra latency
of `primary -> retry primary -> fallback` is acceptable. In the default `prompt_json` mode, the schema is supplied in
the prompt and the final response is still parsed into the typed response contract. Set
`APP_RESPONSE_FORMAT=json_schema` only for a model that reliably supports the OpenAI JSON Schema response format.

## Secret Management

- Keep local secrets only in `.env`.
- Commit `.env.example` with placeholder values so other machines know which keys are required.
- Use GitHub Actions repository secrets for CI/CD variables instead of committing keys.
- Rotate `OPENAI_API_KEY` immediately if it is ever pasted into chat, logs, screenshots, or git history.
- Generate `TOKEN_SECRET_KEY` as a long random value and keep it stable for one local environment.
- Supply the SQL setup script's reader password with sqlcmd, for example
  `sqlcmd -v ECOM_DAB_READER_PASSWORD="..." -i dab/ecommerce/setup-ecommerce.sql`.
- Configure `ECOM_MSSQL_CONNECTION_STRING` with `User Id=ecom_dab_reader`; do not run DAB with `sa`
  or another write-capable database identity.

## Current API

Open the chat UI:

```bash
open http://localhost:8080/
```

List DAB MCP tools discovered through Spring AI's Streamable HTTP MCP client:

```bash
curl http://localhost:8080/api/mcp/tools
```

Send a chat request with DAB MCP tools available to the model:

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"List the available database entities.","conversationId":"demo"}'
```

Stream chat progress as browser SSE:

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"List the available database entities.","conversationId":"demo"}'
```

Clear in-memory chat history for a conversation:

```bash
curl -X DELETE http://localhost:8080/api/conversations/demo/memory
```

## Runtime Flow

```text
Browser
  -> PrivacySession
  -> local PII detection
  -> request token vault
  -> LLM egress firewall
  -> Spring AI ChatClient
  -> secure MCP tool boundary
  -> DAB / SQL Server
  -> tool result processor
  -> protected model response
  -> UI disclosure policy
  -> browser response
```

Inbound email, phone, and locally detected person names are tokenized before they reach OpenAI.
Tokens are request-scoped and namespaced, for example `[PII:NAME:AbCdEfGhIjKlMnOp:1]`, so a token
from one chat turn cannot collide with a token from another turn or be reused later as authority.
The token vault tracks provenance: only values that came from the current user input may be
restored into MCP/DAB filters; tokens minted from tool results or model output do not expand access.

DAB tool calls pass through a secure boundary that allowlists tools, rejects model-provided identity
arguments, enforces row and payload limits, and resolves only known current-request tokens. Raw tool
results are parsed once by `ToolResultProcessor`, then protected by an entity.field disclosure
policy. Unknown row columns fail closed by being tokenized or withheld rather than being sent to
the model. Before every OpenAI request, the transport-level egress firewall verifies that no raw
vaulted value is present in the outbound payload.

The final browser response applies a separate UI disclosure policy: email and phone are masked, and
names remain masked unless a future authenticated role check explicitly allows display. Sanitized
turns are stored in memory; the request token vault is destroyed at the end of the request.

This is strong defense-in-depth against accidental PII disclosure to a model provider. It is not yet
an authorization boundary: the POC has no end-user identity, MCP identity propagation, or DAB/SQL
Server row-level security. DAB mutation tools are disabled and database access should use the
`ecom_dab_reader` login created by the setup script. Identity -> MCP -> DAB -> RLS should be treated
as a separate milestone.

## Isolation
