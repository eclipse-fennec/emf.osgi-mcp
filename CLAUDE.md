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
| `org.eclipse.fennec.mcp.api` | Core interfaces: `MCPEndpoint` (name + URL of an addressable MCP server), `MCPServer` (extends it, adds the aggregated tool/prompt/resource specs), `MCPTool`, `MCPToolProvider`, `MCPResourceProvider`, `MCPPromptProvider`. Abstract bases: `AbstractMCPTool` (schema loading), `AbstractHttpMCPServer` (servlet lifecycle). Bind to `MCPEndpoint` to merely reach a server, `MCPServer` to read what it serves. |
| `org.eclipse.fennec.mcp.gogo.tools` | Concrete tool implementations: `ExecuteGogoTool` (runs Gogo commands), `ListCommandsTool` (discovers available commands). |
| `org.eclipse.fennec.mcp.emf.tools` | EMF model tools (28 MCP tools): build/validate/serialize EMF instances **and** author Ecore metamodels (EPackage/EClass/EDataType/EEnum/EAnnotation/EOperation/features + full generics), register them into a session-local `PackageRegistry` (so their classes become instantiable), and round-trip via hardened inline-XMI import. `export_package` emits a registered package's full `.ecore` (annotations, abstract classes, nsURI-based hrefs for cross-package supertypes) — the full-fidelity read `describe_eclass` cannot give. Session-scoped datasets with replayable recipes. Deny-all allow-lists on `EMFModelGuard` PID (exact / `prefix*` / `*` patterns via `NsUriPatterns`, shared with `EMFPackageRegistry`; `allowedPackages()` filters the live registry so late-registered packages match a prefix rule, and the package and class lists stay independent), registration policy + LRU cap on `EMFPackageRegistry` PID, caps on `EMFDatasetRegistry` PID. See `docs/emf-metamodel-authoring.md`. |
| `org.eclipse.fennec.mcp.metadata.tools` | Discovery tools (9) over the EMF metadata layer (`MetadataService` / `MetadataIndexReader`): query classes/features/operations by EAnnotation across every registered package (omitting `value` matches any value for the key), resolve a bare class name, enumerate annotation sources and aspect type ids, read one element's parsed aspects with their diagnostics, and report index wiring. Identity-and-structure results, no guard — "query wide to locate, read narrow to copy". No codec dependency. See `docs/metadata-discovery-tools.md`. |
| `org.eclipse.fennec.mcp.model.atlas.tools` | One tool (`post_to_model_atlas`) publishing a registered `EPackage` to a model.atlas stage, so an inferred metamodel leaves the runtime without its XMI passing through the LLM. The agent names an nsURI; scope, stage and `overwrite` are configuration, and `publish.nsuri.allowlist` is deny-all. Reaches the package through `MetadataService`, serializes in-bundle (cross-package supertypes as `<nsURI>#//<Name>` hrefs), and posts an `application/xmi` body to `{scope}/schema/stages/{stage}`. No upstream body ever reaches the agent. Deploying the bundle **is** the authorization decision — nothing is exported. Needs a JAX-RS client implementation at runtime. See `docs/model-atlas-publishing.md`. |
| `org.eclipse.fennec.mcp.tool.provider` | Whiteboard aggregator: collects `MCPTool` services (dynamic — changes propagate as `notifications/tools/list_changed`), converts to MCP `AsyncToolSpecification` objects. 1-minute timeout per tool execution. |
| `org.eclipse.fennec.mcp.service.tools` | Bridge exposing `ServiceClient` operations (imported SOAP/OpenAPI/gRPC documents from emf.util) as MCP tools. Deny-all (`clients.target` + `operations.allow`), tools carry `tool.namespace=service-bridge`. See `docs/service-client-bridge.md`. |
| `org.eclipse.fennec.mcp.auth.jwt` | `McpTokenVerifier` validating JWTs offline against an IdP's JWKS (Keycloak etc.). See `docs/mcp-auth-keycloak.md`. |
| `org.eclipse.fennec.mcp.http.component` | HTTP transport: `HttpMCPServerComponent` extends `AbstractHttpMCPServer` and is registered via OSGi HTTP Whiteboard as a servlet; it publishes both `MCPServer` and `MCPEndpoint` from one registration. `RemoteMCPEndpoint` (factory PID, `server.name` + `server.url`) publishes a server hosted elsewhere as an `MCPEndpoint` only — configuration is the whole implementation, and the URL is never probed. |
| `org.eclipse.fennec.mcp.gogo.runtime` | Activator component that triggers the MCP server lifecycle for Gogo integration. Has `launch.bndrun` for local runs. |
| `org.eclipse.fennec.mcp.gogo.runtime.config` | Resource-only bundle with OSGi Configurator JSON (HTTP port 8088, servlet at `/mcp/gogo`). |
| `org.eclipse.fennec.mcp.test.component` | **Test scaffolding, not for production runtimes.** Publishes the split LoRaWAN fixture models (`data/lorawan.ecore` + `data/em310udl.ecore`, shipped via `-includeresource`) as OSGi `EPackage` **and** `EPackageConfigurator` services, so an agent finds them in the runtime without being told they exist — the precondition for a blind discovery run. The cross-file supertype (`em310udl` → `lorawan#//UplinkMessage`) resolves by re-keying each resource to its nsURI after load. |
| `org.eclipse.fennec.mcp.workspace.library` | bnd workspace library: centralizes Maven dependency coordinates and runtime requirements. |

### Key Design Patterns

- **OSGi Whiteboard Pattern**: MCPTool services are discovered and aggregated by MCPToolProvider; MCPToolProvider services are consumed by HttpMCPServerComponent. LDAP filters select specific services (`tools_target`, `toolProviders_target`).
- **Reactive execution**: All tool execution returns `Mono<CallToolResult>` via Project Reactor, with bounded-elastic scheduling and timeout management.
- **Template Method**: `AbstractHttpMCPServer` defines MCP server initialization; subclasses configure capabilities.
- **OSGi Configurator**: Factory configurations use tilde notation (`component~instance`), defined in `gogo.runtime.config/configs/configuration.json`.

### Adding a New MCP Tool

1. Create a DS `@Component` implementing `MCPTool` (or extending `AbstractMCPTool`)
2. Set service properties: `tool.name`, `tool.description`
3. Add an LDAP filter match in the Configurator JSON, in the `MCPToolProvider~` config for that concern — the emf runtime has one per bundle (`~emfModel`, `~emfMetadata`, `~modelAtlas`)
4. Bump that provider's `tools.cardinality.minimum` to match
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

- **GitHub Actions**: all six workflows are thin callers into the org-wide reusables in [`eclipse-fennec/.github`](https://github.com/eclipse-fennec/.github), SHA-pinned with the version tag as a trailing comment. No workflow carries inline steps — change CI logic there, not here.
  - `build.yml` (all branches except main/snapshot + PRs) → `reusable-verify`
  - `snapshot.yml` (snapshot branch) → `reusable-verify` → `reusable-release` (`do-release: false`) → `reusable-docs`
  - `release.yml` (main branch) → the same chain with `do-release: true` (GPG-signed, deploys to Central Sonatype)
  - `docs.yml` (`workflow_dispatch` only) → `reusable-docs`; `dependency-review.yml` → `reusable-dependency-review`; `scorecard.yml` → `reusable-scorecard` (scan scopes sit on the calling job)
  - `reusable-verify` runs the license header check as the first gating job, then `./gradlew clean build testOSGi` on the Java 21 + 25 matrix and `./gradlew perfTest`. Secrets reach only `reusable-release`, via `secrets: inherit`.
- **SonarCloud**: project key `eclipse-fennec_emf.osgi-mcp`
- Branch strategy: `main` -> release, `snapshot` -> snapshot deploy, other branches -> build only (verify, no publish)
