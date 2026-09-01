# Eclipse Fennec MCP

Lightweight MCP (Model Context Protocol) server framework for OSGi environments. Enables AI/LLM clients to interact with live OSGi runtimes via the standardized MCP protocol.

Includes a ready-to-use **Gogo Shell MCP Server** that exposes Apache Felix Gogo commands as MCP tools over HTTP, and an **EMF Model MCP Server** that discovers the metamodels a runtime already carries, authors new Ecore metamodels and builds, validates and serializes model instances.

> 🛑 **Development use only.** The Gogo Shell MCP Server is a **development- and
> debugging-time tool**. The `execute_gogo` tool runs arbitrary Gogo shell
> commands in the live OSGi runtime — effectively remote code execution for
> anyone who can reach the endpoint. **Do not deploy it in production systems.**
>
> ⚠️ **Security.** Even for local use it is protected on two levels: the shipped
> default config binds the listener to `127.0.0.1` (localhost only), and an
> `McpAuthenticationFilter` rejects every non-loopback request unless an
> `auth.token` is configured (then a matching `Authorization: Bearer <token>`
> header is required). **Never expose the endpoint beyond localhost without
> setting a strong `auth.token`.** See the [development guide](docs/development-guide.md#option-a-osgi-configurator-declarative) for details.

## Modules

| Bundle | Description |
|--------|-------------|
| `org.eclipse.fennec.mcp.endpoint` | Client half — `MCPEndpoint` and `RemoteMCPEndpoint`; imports `java.*` only, so reaching a server costs no MCP SDK closure |
| `org.eclipse.fennec.mcp.api` | Server half — `MCPServer`, `MCPTool`, `MCPToolProvider` interfaces, abstract bases and `McpAuthenticationFilter` |
| `org.eclipse.fennec.mcp.gogo.tools` | Gogo MCP tools: `ExecuteGogoTool`, `ListCommandsTool` |
| `org.eclipse.fennec.mcp.emf.tools` | 28 EMF tools — build/validate/serialize instances, author Ecore metamodels, session-local package registry, XMI import/export (deny-all by default) |
| `org.eclipse.fennec.mcp.metadata.tools` | 9 discovery tools over the EMF metadata layer — find a model by its EAnnotations across every registered package |
| `org.eclipse.fennec.mcp.service.tools` | Bridge exposing `ServiceClient` operations (SOAP/OpenAPI/gRPC imports) as MCP tools |
| `org.eclipse.fennec.mcp.auth.jwt` | `McpTokenVerifier` validating JWT bearer tokens offline against a JWKS endpoint |
| `org.eclipse.fennec.mcp.tool.provider` | Whiteboard aggregator collecting `MCPTool` services into `MCPToolProvider` |
| `org.eclipse.fennec.mcp.http.component` | HTTP transport via OSGi HTTP Whiteboard servlet |
| `org.eclipse.fennec.mcp.gogo.runtime` (`.config`) | The Gogo server — activator and Configurator config (port 8088, `/mcp/gogo`) |
| `org.eclipse.fennec.mcp.emf.runtime` (`.config`, `.config.atlas`) | The EMF server (port 8099, `/mcp/emf`), with an optional overlay for reading model.atlas-hosted packages |
| `org.eclipse.fennec.mcp.inference.runtime` (`.config`) | Optional metamodel-inference feature (`/mcp/inference`) — a resolution anchor plus its configuration |
| `org.eclipse.fennec.mcp.workspace.library` | bnd workspace library with Maven dependency coordinates |

## Build

```bash
# Build all
./gradlew build

# Run tests for a single module
./gradlew :org.eclipse.fennec.mcp.gogo.tools:test

# Coverage report
./gradlew codeCoverageReport
```

**Requirements:** Java 21, Gradle wrapper included

## Architecture

The framework uses the **OSGi Whiteboard Pattern**:

1. `MCPTool` services are registered by tool bundles (e.g., `gogo.tools`, `emf.tools`)
2. `MCPToolProvider` aggregates tools via whiteboard, filtered by LDAP target
3. `HttpMCPServerComponent` consumes tool providers and exposes them as an MCP HTTP endpoint

Because selection is by filter, one runtime can serve several endpoints from the
same tools — the inference runtime does exactly that, exposing the full tool set
at `/mcp/emf` and a task-scoped subset at `/mcp/inference`. Note that
`tools.cardinality.minimum` is a hard gate: an unmet minimum prevents the
component from activating rather than degrading it.

Optional features are packaged as **resolution anchors**: `inference.runtime`
provides `osgi.implementation=mcp.inference` and requires everything that feature
needs, so a runtime opts in with one `-runrequires` line rather than a bundle
list.

Tool execution is fully reactive via Project Reactor (`Mono<CallToolResult>`), with configurable timeouts and bounded-elastic scheduling.

```
MCPTool services → MCPToolProvider (aggregator) → HttpMCPServerComponent (HTTP servlet)
```

## Documentation

- **[Development Guide](docs/development-guide.md)** — How to write custom MCP tools, configure tool providers and HTTP servers, LDAP filter chaining, structured EMF output
- **[ServiceClient Bridge](docs/service-client-bridge.md)** — Expose imported SOAP/OpenAPI/gRPC operations (emf.util `ServiceClient`s) as MCP tools via configuration
- **[Metamodel Authoring](docs/emf-metamodel-authoring.md)** — Author Ecore metamodels over MCP: the tool set, composite one-call authoring, the session registry and its configuration
- **[Metadata Discovery](docs/metadata-discovery-tools.md)** — Locate a model by its EAnnotations across every registered package, before you model anything new
- **[Keycloak Authentication](docs/mcp-auth-keycloak.md)** — Per-client expiring JWT bearer tokens for the MCP endpoint

## Extending

To add custom MCP tools:

1. Implement `MCPTool` (or extend `AbstractMCPTool`) as a DS `@Component`
2. The tool is automatically discovered by `MCPToolProvider` via whiteboard
3. Configure tool selection with LDAP filters in `MCPToolProviderConfig.tools_target`

See the [Development Guide](docs/development-guide.md) for a complete walkthrough with examples.

## Developers

* **Mark Hoffmann** (mhoffmann) / [m.hoffmann@datainmotion.com](mailto:m.hoffmann@datainmotion.com) @ [Data In Motion](https://www.datainmotion.com)

## License

**Eclipse Public License 2.0**

## Copyright

Data In Motion Consulting GmbH - All rights reserved

---
Data In Motion Consulting GmbH - [info@datainmotion.com](mailto:info@datainmotion.com) - [www.datainmotion.com](https://www.datainmotion.com)
