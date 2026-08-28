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

Two concrete MCP servers are provided on top of the core whiteboard API:

- **Gogo Shell MCP server** — exposes Apache Felix Gogo shell commands as MCP
  tools (`execute_gogo`, `list_commands`) so a client can inspect bundles, DS
  components, services and configuration in a running framework.

  ::: warning Development use only
  The Gogo server grants command execution in the OSGi runtime and is intended
  for development and debugging. Do not deploy it in production. See the
  [Security guide](./02-security).
  :::

- **EMF Model MCP server** — eleven tools to build, populate, validate and
  serialize EMF model instances in session-scoped datasets with replayable
  recipes, behind a deny-all EPackage/EClass allow-list.

## Modules

| Bundle | Purpose |
|--------|---------|
| `org.eclipse.fennec.mcp.api` | Core whiteboard API: `MCPServer`, `MCPTool`, `MCPToolProvider`, `MCPResourceProvider`, `MCPPromptProvider`; abstract bases `AbstractMCPTool`, `AbstractHttpMCPServer`; the `McpAuthenticationFilter`. |
| `org.eclipse.fennec.mcp.gogo.tools` | Gogo tool implementations: `ExecuteGogoTool`, `ListCommandsTool`. |
| `org.eclipse.fennec.mcp.emf.tools` | EMF model tools (build/validate/serialize EMF instances). |
| `org.eclipse.fennec.mcp.tool.provider` | Whiteboard aggregator collecting `MCPTool` services into an `MCPToolProvider`. |
| `org.eclipse.fennec.mcp.http.component` | HTTP transport as an OSGi HTTP Whiteboard servlet. |
| `org.eclipse.fennec.mcp.gogo.runtime` | Activator wiring the Gogo MCP server lifecycle (with a `launch.bndrun`). |
| `org.eclipse.fennec.mcp.gogo.runtime.config` | Default OSGi Configurator config (HTTP port 8088, servlets at `/mcp/gogo` and `/mcp/emf`). |
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
