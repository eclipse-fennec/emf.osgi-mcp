# Architecture

Fennec MCP is organized around the **OSGi Whiteboard pattern**: small Declarative
Services components are discovered and composed at runtime through service
registration and LDAP target filters, rather than wired by a central factory.

## Module dependency flow

```
org.eclipse.fennec.mcp.api               <- Core whiteboard API (interfaces + abstract bases)
    ^           ^           ^
    |           |           |
gogo.tools  tool.provider  http.component
(MCPTool     (Aggregates    (HTTP servlet
 impls)      MCPTools)       transport)
    |           |           |
    +------ gogo.runtime ---+             <- Activator wiring all services
                |
      gogo.runtime.config                 <- OSGi Configurator JSON (resource-only bundle)
```

## The whiteboard chain

1. **Tools.** Each tool is a DS `@Component` publishing the `MCPTool` service with
   `tool.name` / `tool.description` properties. Most extend `AbstractMCPTool`,
   which holds the name, description and JSON input/output schemas.
2. **Aggregation.** `MCPToolProviderImpl` collects all `MCPTool` services matching
   a configured LDAP `tools.target` filter and converts each into an MCP SDK
   `AsyncToolSpecification`. It publishes the aggregate as an `MCPToolProvider`.
3. **Transport.** `HttpMCPServerComponent` (extending `AbstractHttpMCPServer`)
   consumes one or more `MCPToolProvider`s (selected via `toolProviders.target`)
   and registers the MCP transport servlet on the **OSGi HTTP Whiteboard**,
   together with an `McpAuthenticationFilter` guarding the same endpoint.
4. **Wiring.** The `gogo.runtime.config` bundle carries the OSGi **Configurator**
   JSON that instantiates the factory components (`MCPToolProvider~…`,
   `HttpMCPServerComponent~…`) and the Felix HTTP runtime. Factory instances use
   tilde notation (`component~instance`).

Because selection is by LDAP filter, one deployment can expose several
independent MCP endpoints — the shipped config wires both a Gogo server at
`/mcp/gogo` and an EMF server at `/mcp/emf` on the same HTTP runtime.

## Reactive, interruptible execution

Tool execution is fully reactive:

- Every `MCPTool.execute(...)` returns a `Mono<CallToolResult>`.
- `MCPToolProviderImpl` wraps each call with a **per-request timeout** (one
  minute) and schedules it on Reactor's bounded-elastic scheduler.
- Blocking work (e.g. the Gogo `CommandSession.execute`) runs on a **dedicated,
  bounded, interruptible** worker pool. When a call is cancelled — including by
  the request timeout — the worker is interrupted and its session torn down, so a
  slow or runaway command cannot leak a thread or keep buffering. See the
  [Security guide](./02-security) for the concrete resource limits.

## Key design patterns

- **Whiteboard pattern** — services discovered and aggregated by target filters.
- **Template method** — `AbstractHttpMCPServer` defines server initialization and
  servlet/filter registration; subclasses supply capabilities and configuration.
- **OSGi Configurator** — declarative, versioned factory configuration in JSON.

## Adding a tool

See the [Development Guide](../development-guide.md) for a full walkthrough:
create a DS component implementing `MCPTool`, set `tool.name`/`tool.description`,
add an LDAP match in the Configurator JSON, and bump the tool-provider
cardinality.
