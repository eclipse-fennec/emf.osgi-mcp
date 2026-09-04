# Architecture

Fennec MCP is organized around the **OSGi Whiteboard pattern**: small Declarative
Services components are discovered and composed at runtime through service
registration and LDAP target filters, rather than wired by a central factory.

## Module dependency flow

```
org.eclipse.fennec.mcp.endpoint          <- Client half: MCPEndpoint + RemoteMCPEndpoint
    ^                                       (no MCP SDK, no Reactor — java.* only)
    |
org.eclipse.fennec.mcp.api               <- Server half: whiteboard API + abstract bases
    ^           ^           ^
    |           |           |
tool bundles  tool.provider  http.component
(MCPTool      (Aggregates    (HTTP servlet
 impls)        MCPTools)      transport)
    |           |           |
    +------- runtime -------+             <- Activator wiring all services
                |
          runtime.config                  <- OSGi Configurator JSON (resource-only bundle)
```

The tool bundles are `gogo.tools` (2 tools), `emf.tools` (28), `metadata.tools`
(9) and `service.tools` (a configurable bridge); `auth.jwt` plugs a JWT
`McpTokenVerifier` into the authentication filter. The runtime pair repeats per
feature: `gogo.runtime` (+ `.config`), `emf.runtime` (+ `.config`,
`.config.atlas`) and `inference.runtime` (+ `.config`).

The split at the top is deliberate. A consumer that only needs to *reach* an MCP
server binds `MCPEndpoint` from the lower bundle and never pulls in the MCP SDK
closure; a consumer that needs to read what a server *serves* binds `MCPServer`
from `mcp.api`, which extends it.

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
independent MCP endpoints, and the same tool can appear in more than one of
them. Two providers behind the **same** server may also match one tool; the
server then serves a single specification per name — the first — and logs a
warning naming the tool, rather than refusing to start. A provider bound by
several servers notifies all of them, so none is left serving the tool list it
happened to see when it activated. The shipped configuration uses this twice over: `gogo.runtime.config`
wires a Gogo server at `/mcp/gogo`; `emf.runtime.config` wires `~emfModel` and
`~emfMetadata` behind the full EMF server at `/mcp/emf`; and the optional
`inference.config` adds a third provider and a narrowed, task-scoped server at
`/mcp/inference` on the same HTTP instance.

::: warning Cardinality is a hard gate
Each provider declares `tools.cardinality.minimum` and each server a
`toolProviders.cardinality.minimum`. An unmet minimum does not degrade the
endpoint — it silently prevents the component from activating at all. Adding a
tool to a `tools.target` filter therefore means bumping that provider's minimum
in the same edit.

The corollary shapes how optional features are packaged. `/mcp/emf` names
`model_atlas_tool_provider` in its filter but requires only **2** providers, so
the endpoint comes up whether or not the model.atlas bundles are deployed.
`MCPToolProvider~inference`, by contrast, keeps a hard minimum of 20 including
`post_to_model_atlas` — safe only because its configuration ships in
`inference.config`, which `inference.runtime` requires *together with* the bundle
providing that tool. The resolve guarantees the count.
:::

## Features as resolution anchors

`emf.runtime` and `inference.runtime` contain almost no code: an immediate DS
component that references an `MCPServer` and, more importantly, bundle
annotations that make the feature addressable to the resolver.

```java
@Capability(namespace = IMPLEMENTATION_NAMESPACE, name = "mcp.inference", version = "1.0")
@Requirements({
    @Requirement(namespace = IDENTITY_NAMESPACE, name = "org.eclipse.fennec.mcp.emf.tools"),
    @Requirement(namespace = IDENTITY_NAMESPACE, name = "org.eclipse.fennec.mcp.metadata.tools"),
    @Requirement(namespace = IDENTITY_NAMESPACE, name = "org.eclipse.fennec.mcp.inference.config"),
    @Requirement(namespace = IDENTITY_NAMESPACE, name = "org.eclipse.fennec.model.atlas.mcp.tools"),
    …
})
```

A runtime then turns the whole feature on with one line —
`osgi.implementation;filter:='(osgi.implementation=mcp.inference)'` in
`-runrequires` — instead of a list of bundles somebody has to keep in sync. When
a feature spans repositories, as inference does, this is what keeps the
dependency explicit rather than tribal.

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
