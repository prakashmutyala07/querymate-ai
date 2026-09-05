# PII Security Flow

This diagram shows how the classes in this package cooperate during one chat request.

```mermaid
flowchart TD
    Browser[Browser / User Message]
    Coordinator[ChatCoordinator]
    Guard[SensitiveDataGuard]
    Session[PrivacySession<br/>request-scoped coordinator]
    Detector[PiiDetector<br/>email, phone, name detection]
    Vault[RequestTokenVault<br/>raw PII lives only here]
    Egress[SensitiveEgressFirewall<br/>transport egress choke point]
    LLM[LLM / ChatClient]
    Boundary[SecureMcpToolCallback<br/>secure tool boundary]
    Intent[ToolCallIntent<br/>parsed tool request]
    Policy[DataDisclosurePolicy<br/>allowlist + entity.field policy]
    DAB[DAB / SQL Server]
    Processor[ToolResultProcessor<br/>parse once + protect rows]
    UI[UiDisclosureService<br/>browser masking policy]
    Memory[Sanitized chat memory]

    Browser --> Coordinator
    Coordinator --> Guard
    Guard --> Session

    Session --> Detector
    Detector --> Session
    Session --> Vault
    Vault --> ProtectedInput[Protected user input]

    ProtectedInput --> Egress
    Egress --> LLM

    LLM --> Boundary
    Boundary --> Intent
    Boundary --> Policy
    Boundary --> Vault
    Vault --> RestoredFilter[Only USER_INPUT tokens restored for tools]
    RestoredFilter --> DAB

    DAB --> RawResult[Raw tool result]
    RawResult --> Processor
    Processor --> Policy
    Processor --> Vault
    Processor --> ProtectedResult[Protected tool result]
    ProtectedResult --> Egress
    Egress --> LLM

    LLM --> StructuredAnswer[Structured model answer]
    StructuredAnswer --> Session
    Session --> Vault
    Session --> UI
    UI --> SafeResponse[Browser-safe response]
    SafeResponse --> Browser

    Session --> Memory
    Session --> DestroyVault[Request ends: vault destroyed]
```

## Responsibility Map

```mermaid
classDiagram
    class SensitiveDataGuard {
      +newSession()
      +protectedFields()
    }

    class PrivacySession {
      +protectInput()
      +wrap()
      +protectOutput()
      +toUiResponse()
      +close()
    }

    class PiiDetector {
      +detect()
    }

    class RequestTokenVault {
      +protectText()
      +protectKnown()
      +resolveUserInputTokensForTool()
      +tokensLeakedIn()
      +displayProtectedValues()
      +close()
    }

    class SensitiveEgressFirewall {
      +intercept()
    }

    class SecureMcpToolCallback {
      +call()
    }

    class DataDisclosurePolicy {
      +validateToolCall()
      +decide()
    }

    class ToolResultProcessor {
      +process()
    }

    class UiDisclosureService {
      +toUiResponse()
    }

    SensitiveDataGuard --> PiiDetector
    SensitiveDataGuard --> DataDisclosurePolicy
    SensitiveDataGuard --> PrivacySession

    PrivacySession --> RequestTokenVault
    PrivacySession --> ToolResultProcessor
    PrivacySession --> UiDisclosureService
    PrivacySession --> PiiDetector
    PrivacySession --> DataDisclosurePolicy

    SensitiveEgressFirewall --> PrivacySession
    SecureMcpToolCallback --> PrivacySession
    SecureMcpToolCallback --> ToolResultProcessor
    ToolResultProcessor --> DataDisclosurePolicy
    ToolResultProcessor --> RequestTokenVault
    UiDisclosureService --> RequestTokenVault
```

## Quick Read

- `SensitiveDataGuard` is the Spring facade. It owns shared config and creates a new `PrivacySession`.
- `PrivacySession` is the hub for one request. It owns the request vault and wires tool/UI processing.
- `PiiDetector` only detects sensitive spans. It does not store raw values.
- `RequestTokenVault` stores raw PII for the current request and mints namespaced tokens.
- `SensitiveEgressFirewall` is the last network guard before the LLM provider.
- `SecureMcpToolCallback` is the only path from the model to DAB tools.
- `DataDisclosurePolicy` decides what tool calls and fields are allowed.
- `ToolResultProcessor` sanitizes DAB results before the model sees them.
- `UiDisclosureService` masks values for final browser display.
