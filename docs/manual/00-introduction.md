# Introduction

**Eclipse Fennec MCP** (`org.eclipse.fennec.mcp`) is a lightweight, reactive
[MCP (Model Context Protocol)](https://modelcontextprotocol.io) server framework
for OSGi environments. It enables AI/LLM clients to interact with a **live OSGi
runtime** over the standard MCP protocol via HTTP.

It is built with **Java 21**, **OSGi Declarative Services**, the **OSGi HTTP
Whiteboard**, and **Project Reactor**. Each module is an OSGi bundle (a bnd
project); there is no dependency on the Eclipse Platform, Xtext, Guava or Apache
Commons.

## What ships

Three MCP servers are wired on top of the core whiteboard API, each a different
selection of tools over the same transport:

- **Gogo Shell MCP server** (`/mcp/gogo`) — exposes Apache Felix Gogo shell
  commands as MCP tools (`execute_gogo`, `list_commands`) so a client can
  inspect bundles, DS components, services and configuration in a running
  framework.

  ::: warning Development use only
  The Gogo server grants command execution in the OSGi runtime and is intended
  for development and debugging. Do not deploy it in production. See the
  [Security guide](./02-security).
  :::

- **EMF Model MCP server** (`/mcp/emf`) — 37 tools over EMF: build, populate,
  validate and serialize model *instances* in session-scoped datasets with
  replayable recipes; **author Ecore metamodels** (packages, classes, datatypes,
  enums, operations, annotations and full generics) and register them so their
  classes become instantiable; and **discover** what a runtime already models by
  querying EAnnotations across every registered package. Behind a deny-all
  EPackage/EClass allow-list.

- **Inference MCP server** (`/mcp/inference`) — an **optional feature** on top,
  narrowed to one task: infer a metamodel from sample payloads and hand it to a
  model.atlas stage. It omits instance manipulation, import/replay, operations
  and generics, and carries `server.instructions` describing that workflow. It
  ships as its own pair of bundles and pulls in the publishing tool from the
  [model.atlas](https://github.com/eclipse-fennec/model.atlas) project; the base
  EMF runtime resolves and runs without any of it.

## Modules

| Bundle | Purpose |
|--------|---------|
| `org.eclipse.fennec.mcp.endpoint` | The client half, and the only bundle a pure MCP client needs: `MCPEndpoint` (name + URL of an addressable server) and `RemoteMCPEndpoint` (a factory PID publishing a server hosted elsewhere). Imports nothing but `java.*` — no MCP SDK, no Reactor. |
| `org.eclipse.fennec.mcp.api` | The server half: `MCPServer` (extends `MCPEndpoint`), `MCPTool`, `MCPToolProvider`, `MCPResourceProvider`, `MCPPromptProvider`; abstract bases `AbstractMCPTool`, `AbstractHttpMCPServer`; the `McpAuthenticationFilter`. |
| `org.eclipse.fennec.mcp.gogo.tools` | Gogo tool implementations: `ExecuteGogoTool`, `ListCommandsTool`. |
| `org.eclipse.fennec.mcp.emf.tools` | 28 EMF tools: instance building, Ecore metamodel authoring, session-local package registry, XMI import/export. |
| `org.eclipse.fennec.mcp.metadata.tools` | 9 discovery tools over the EMF metadata layer — locate a model by its EAnnotations across every registered package. |
| `org.eclipse.fennec.mcp.service.tools` | Bridge exposing `ServiceClient` operations (imported SOAP/OpenAPI/gRPC documents) as MCP tools. |
| `org.eclipse.fennec.mcp.auth.jwt` | `McpTokenVerifier` validating JWT bearer tokens offline against an IdP's JWKS. |
| `org.eclipse.fennec.mcp.tool.provider` | Whiteboard aggregator collecting `MCPTool` services into an `MCPToolProvider`. |
| `org.eclipse.fennec.mcp.http.component` | HTTP transport as an OSGi HTTP Whiteboard servlet; publishes both `MCPServer` and `MCPEndpoint`. |
| `org.eclipse.fennec.mcp.gogo.runtime` (`.config`) | The Gogo server: activator plus its Configurator JSON (HTTP port 8088, `/mcp/gogo`), with a `launch.bndrun`. |
| `org.eclipse.fennec.mcp.emf.runtime` (`.config`, `.config.atlas`) | The EMF server: activator, Configurator JSON (HTTP port 8099, `/mcp/emf`), and an optional overlay pointing the runtime at a model.atlas scope for reading. |
| `org.eclipse.fennec.mcp.inference.runtime` (`.config`) | The optional inference feature: a resolution anchor providing `osgi.implementation=mcp.inference`, and the `/mcp/inference` configuration it activates. |
| `org.eclipse.fennec.mcp.workspace.library` | bnd workspace library centralizing Maven dependency coordinates. |

## Build

```bash
./gradlew build                                     # Build everything + run tests
./gradlew :org.eclipse.fennec.mcp.gogo.tools:test   # Tests for a single module
./gradlew codeCoverageReport                        # JaCoCo coverage (HTML + XML)
./gradlew perfTest                                   # @Tag("perf") benchmarks only
```

Requirements: **Java 21** and the bundled Gradle wrapper (bnd 7.2.1+ workspace
plugin).

## Next steps

- [Architecture](./01-architecture) — the whiteboard wiring and reactive model.
- [Development Guide](../development-guide.md) — adding your own MCP tool.
- [Security](./02-security) — the default hardening and how to expose the server safely.
- [Metamodel authoring](../emf-metamodel-authoring.md) and
  [metadata discovery](../metadata-discovery-tools.md) — the EMF tool sets in full.
