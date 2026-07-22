# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Eclipse Fennec MCP** — A lightweight, reactive MCP (Model Context Protocol) server framework for OSGi environments. Enables AI/LLM clients to interact with live OSGi runtimes via the MCP protocol. Built with Java 21, OSGi Declarative Services, and Project Reactor.

The current concrete use case is exposing Apache Felix Gogo shell commands as MCP tools over HTTP.

## Build Commands

```bash
./gradlew build                                       # Build everything + run tests
./gradlew clean build                                 # Clean build
./gradlew :org.eclipse.fennec.mcp.gogo.tools:test    # Run tests for a single module
./gradlew codeCoverageReport                          # JaCoCo coverage report (HTML + XML)
./gradlew perfTest                                    # Run @Tag("perf") benchmarks only
./gradlew release                                     # Release build (CI only)
```

**Requirements:** Java 21, Gradle wrapper included (bnd 7.2.1+ workspace plugin)

## Build System

- **Gradle** with **bnd workspace plugin** (`biz.aQute.bnd.workspace`) — each module is an OSGi bundle project
- Central bnd config: `cnf/build.bnd` (libraries, repos, base version 0.1.0, Java 21 target)
- Maven coordinates under group `org.eclipse.fennec.mcp`
- Workspace library definitions in `org.eclipse.fennec.mcp.workspace.library/`
- `@Tag("perf")` tests are excluded from normal builds; run via `./gradlew perfTest`

## Architecture

### Module Dependency Flow

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

### Module Purposes

| Module | Purpose |
|--------|---------|
| `org.eclipse.fennec.mcp.api` | Core interfaces: `MCPServer`, `MCPTool`, `MCPToolProvider`, `MCPResourceProvider`, `MCPPromptProvider`. Abstract bases: `AbstractMCPTool` (schema loading), `AbstractHttpMCPServer` (servlet lifecycle). |
| `org.eclipse.fennec.mcp.gogo.tools` | Concrete tool implementations: `ExecuteGogoTool` (runs Gogo commands), `ListCommandsTool` (discovers available commands). |
| `org.eclipse.fennec.mcp.emf.tools` | EMF model tools (27 MCP tools): build/validate/serialize EMF instances **and** author Ecore metamodels (EPackage/EClass/EDataType/EEnum/EAnnotation/EOperation/features + full generics), register them into a session-local `PackageRegistry` (so their classes become instantiable), and round-trip via hardened inline-XMI import. Session-scoped datasets with replayable recipes. Deny-all allow-lists on `EMFModelGuard` PID, registration policy + LRU cap on `EMFPackageRegistry` PID, caps on `EMFDatasetRegistry` PID. See `docs/emf-metamodel-authoring.md`. |
| `org.eclipse.fennec.mcp.tool.provider` | Whiteboard aggregator: collects `MCPTool` services, converts to MCP `AsyncToolSpecification` objects. 1-minute timeout per tool execution. |
| `org.eclipse.fennec.mcp.http.component` | HTTP transport: DS component extending `AbstractHttpMCPServer`, registered via OSGi HTTP Whiteboard as a servlet. |
| `org.eclipse.fennec.mcp.gogo.runtime` | Activator component that triggers the MCP server lifecycle for Gogo integration. Has `launch.bndrun` for local runs. |
| `org.eclipse.fennec.mcp.gogo.runtime.config` | Resource-only bundle with OSGi Configurator JSON (HTTP port 8088, servlet at `/mcp/gogo`). |
| `org.eclipse.fennec.mcp.workspace.library` | bnd workspace library: centralizes Maven dependency coordinates and runtime requirements. |

### Key Design Patterns

- **OSGi Whiteboard Pattern**: MCPTool services are discovered and aggregated by MCPToolProvider; MCPToolProvider services are consumed by HttpMCPServerComponent. LDAP filters select specific services (`tools_target`, `toolProviders_target`).
- **Reactive execution**: All tool execution returns `Mono<CallToolResult>` via Project Reactor, with bounded-elastic scheduling and timeout management.
- **Template Method**: `AbstractHttpMCPServer` defines MCP server initialization; subclasses configure capabilities.
- **OSGi Configurator**: Factory configurations use tilde notation (`component~instance`), defined in `gogo.runtime.config/configs/configuration.json`.

### Adding a New MCP Tool

1. Create a DS `@Component` implementing `MCPTool` (or extending `AbstractMCPTool`)
2. Set service properties: `tool.name`, `tool.description`
3. Add an LDAP filter match in the Configurator JSON (`tools.target` in `MCPToolProvider~` config)
4. Update `tools.cardinality.minimum` if needed
5. See `docs/development-guide.md` for full walkthrough

### External Dependencies

- `io.modelcontextprotocol.sdk` (1.1.1+) — MCP protocol SDK
- `io.projectreactor:reactor-core` (3.7.0+) — Reactive streams
- Jackson 3.x (`tools.jackson.core`) — JSON serialization
- `org.eclipse.fennec.codec.*` — JSON schema and EMF codec
- Apache Felix Gogo Runtime — Shell command processor

### Forbidden Dependencies

No Eclipse Platform (`org.eclipse.core.*`, `org.eclipse.ui.*`), no Xtext, no Guava, no Apache Commons.

## Code Conventions

- **Java 21** features encouraged (sealed interfaces, records, enhanced switch)
- **EPL-2.0 license header** on all Java files (enforced by license CI check via Apache SkyWalking Eyes)
- Explicit imports only (no star imports, no fully-qualified class names in code)
- Package-private by default; only `public` for API surface
- `src-gen/` folders contain generated code — never hand-edit
- Source in `src/` directories, tests in `test/` directories within each module
- DS components use `@Component`, `@Designate` (metatype), `@Reference` annotations
- Configuration interfaces use OSGi metatype annotations with `@ObjectClassDefinition`

## Testing

- **JUnit 5** + Mockito + AssertJ
- `@Tag("perf")` for benchmarks (excluded from normal build, run via `perfTest` task)
- OSGi integration tests when DS wiring verification is needed

## CI/CD

- **GitHub Actions**: `build.yml` (all branches except main/snapshot + PRs), `snapshot.yml` (snapshot branch), `release.yml` (main branch, GPG-signed, deploys to Central Sonatype), `license.yml` (header verification)
- **SonarCloud**: project key `eclipse-fennec_emf.osgi-mcp`
- Branch strategy: `main` -> release, `snapshot` -> snapshot deploy, other branches -> build only (skips `testOSGi`)
