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
org.eclipse.fennec.mcp.endpoint          <- Client half: MCPEndpoint + RemoteMCPEndpoint (OSGi only, no MCP SDK)
    ^
    |
org.eclipse.fennec.mcp.api               <- Server half: whiteboard API (interfaces + abstract bases, MCP SDK)
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
| `org.eclipse.fennec.mcp.endpoint` | **The client half, and the only bundle a pure MCP client needs.** `MCPEndpoint` (name + URL of an addressable MCP server) and `RemoteMCPEndpoint` (factory PID, `server.name` + `server.url`) publishing a server hosted elsewhere — configuration is the whole implementation, and the URL is never probed. Imports nothing but `java.*`: no MCP SDK, no Reactor, no Jackson, so a consumer that only has to *reach* a server never drags the SDK closure in (#31). |
| `org.eclipse.fennec.mcp.api` | **The server half.** Depends on `mcp.endpoint`. `MCPServer` (extends `MCPEndpoint`, adds the aggregated tool/prompt/resource specs), `MCPTool`, `MCPToolProvider`, `MCPResourceProvider`, `MCPPromptProvider`. Abstract bases: `AbstractMCPTool` (schema loading), `AbstractHttpMCPServer` (servlet lifecycle). Bind to `MCPEndpoint` to merely reach a server, `MCPServer` to read what it serves. |
| `org.eclipse.fennec.mcp.gogo.tools` | Concrete tool implementations: `ExecuteGogoTool` (runs Gogo commands), `ListCommandsTool` (discovers available commands). |
| `org.eclipse.fennec.mcp.emf.tools` | EMF model tools (28 MCP tools): build/validate/serialize EMF instances **and** author Ecore metamodels (EPackage/EClass/EDataType/EEnum/EAnnotation/EOperation/features + full generics), register them into a session-local `PackageRegistry` (so their classes become instantiable), and round-trip via hardened inline-XMI import. `create_epackage` and `add_eclass` are **composite**: they take their children inline (`eClassifiers` / `eAttributes` / `eReferences` / `eAnnotations` / `eLiterals`), wired in a second pass so intra-package `#//<Name>` refs are order-independent, all-or-nothing against the dataset, and returning the `objectId` of every nested element — an agent authors a package in one call instead of one per element. `export_package` emits a registered package's full `.ecore` (annotations, abstract classes, nsURI-based hrefs for cross-package supertypes) — the full-fidelity read `describe_eclass` cannot give. Session-scoped datasets with replayable recipes. Deny-all allow-lists on `EMFModelGuard` PID (exact / `prefix*` / `*` patterns via `NsUriPatterns`, shared with `EMFPackageRegistry`; `allowedPackages()` filters the live registry so late-registered packages match a prefix rule, and the package and class lists stay independent), registration policy + LRU cap on `EMFPackageRegistry` PID, caps on `EMFDatasetRegistry` PID. See `docs/emf-metamodel-authoring.md`. |
| `org.eclipse.fennec.mcp.metadata.tools` | Discovery tools (9) over the EMF metadata layer (`MetadataService` / `MetadataIndexReader`): query classes/features/operations by EAnnotation across every registered package (omitting `value` matches any value for the key), resolve a bare class name, enumerate annotation sources and aspect type ids, read one element's parsed aspects with their diagnostics, and report index wiring. Identity-and-structure results, no guard — "query wide to locate, read narrow to copy". No codec dependency. See `docs/metadata-discovery-tools.md`. |
| `org.eclipse.fennec.mcp.tool.provider` | Whiteboard aggregator: collects `MCPTool` services (dynamic — changes propagate as `notifications/tools/list_changed`), converts to MCP `AsyncToolSpecification` objects. 1-minute timeout per tool execution. Notifies **every** server bound to it, not just the last to activate; servers add and remove their listener (`onToolsChanged` / `removeToolsChangedListener`) around their own lifecycle. |
| `org.eclipse.fennec.mcp.service.tools` | Bridge exposing `ServiceClient` operations (imported SOAP/OpenAPI/gRPC documents from emf.util) as MCP tools. Deny-all (`clients.target` + `operations.allow`), tools carry `tool.namespace=service-bridge`. See `docs/service-client-bridge.md`. |
| `org.eclipse.fennec.mcp.auth.jwt` | `McpTokenVerifier` validating JWTs offline against an IdP's JWKS (Keycloak etc.). See `docs/mcp-auth-keycloak.md`. |
| `org.eclipse.fennec.mcp.http.component` | HTTP transport: `HttpMCPServerComponent` extends `AbstractHttpMCPServer` and is registered via OSGi HTTP Whiteboard as a servlet; it publishes both `MCPServer` and `MCPEndpoint` from one registration, so a client binding to `MCPEndpoint` is satisfied by this or by a `RemoteMCPEndpoint` without knowing which. |
| `org.eclipse.fennec.mcp.gogo.runtime` | Activator component that triggers the MCP server lifecycle for Gogo integration. Has `launch.bndrun` for local runs. |
| `org.eclipse.fennec.mcp.gogo.runtime.config` | Resource-only bundle with OSGi Configurator JSON (HTTP port 8088, servlet at `/mcp/gogo`). |
| `org.eclipse.fennec.mcp.emf.runtime` | The base EMF server runtime — activator providing `osgi.implementation=mcp.emf`. `launch.bndrun` resolves an enclosed runtime: no model.atlas, no JAX-RS, no inference. `secrets.bndrun.template` (copy to an untracked `secrets.bndrun`) feeds `MCP_EMF_AUTH_TOKEN` through configadmin interpolation. |
| `org.eclipse.fennec.mcp.emf.runtime.config` | Configurator JSON for the base EMF runtime: Felix HTTP on 127.0.0.1:8099, `MCPToolProvider~emfModel` (28) and `~emfMetadata` (9), and the `/mcp/emf` server. Its `toolProviders.target` also names `model_atlas_tool_provider`, but the minimum is **2** — the third binds only when the model.atlas bundles are deployed. Owns the singleton PIDs `EMFModelGuard` and `EMFPackageRegistry`, which are deployment-wide and **cannot** be contributed to from another config bundle. |
| `org.eclipse.fennec.mcp.emf.runtime.config.atlas` | Optional overlay pointing the runtime at a model.atlas scope for **reading** EPackages (`rest.client~jena`); needs the `model.atlas.rest.client.*` bundles. Read side only — publishing config lives in the model.atlas project's `model.atlas.mcp.config`. |
| `org.eclipse.fennec.mcp.inference.runtime` | **Resolution anchor for the inference feature.** Provides `osgi.implementation=mcp.inference` and requires the closure it needs: `emf.tools`, `metadata.tools`, `inference.config`, and the model.atlas project's `model.atlas.mcp.tools` + `model.atlas.mcp.config`. One `-runrequires` on the capability turns the feature on, which is what makes `~inference`'s hard minimum of 21 safe. Its `MCPServer` reference is targeted at `(server.name=osgi-emf-inference-mcp-server)` — a runtime hosting both endpoints publishes more than one. Has `launch.bndrun` + `secrets.bndrun.template` for both tokens. |
| `org.eclipse.fennec.mcp.inference.config` | Configurator JSON for the inference feature: `MCPToolProvider~inference` (21) and the `/mcp/inference` server with its own `auth.token` and `server.instructions`. It deliberately carries **no** `ModelAtlasPublisher` config: that PID is owned by `model.atlas.mcp.config` in the model.atlas project, which `inference.runtime` requires by identity. Two configs for one factory PID would activate two publishers, and `PostToModelAtlasTool`'s unary reference would bind an arbitrary one. Its properties come from `MODEL_ATLAS_*` / `MCP_ATLAS_PUBLISH_ALLOWLIST` (env, or system properties via `secrets.bndrun`). |
| `org.eclipse.fennec.mcp.service.tools.tests` | OSGi integration test bundle: an imported OpenAPI document surfaces as a callable MCP tool. Runs under `./gradlew testOSGi`. |
| `org.eclipse.fennec.mcp.http.component.tests` | OSGi integration test bundle for the HTTP endpoint: whiteboard servlet and filters accepted (not in `failedServletDTOs`), one registration publishing `MCPServer` + `MCPEndpoint`, a real MCP client doing `initialize` / `tools/list` / `tools/call` over an ephemeral port, the auth filter and `McpTokenVerifier` over the socket, dynamic tool changes on a live session, and the provider-cardinality gate. Runs under `./gradlew testOSGi`. |
| `org.eclipse.fennec.mcp.workspace.library` | bnd workspace library: centralizes Maven dependency coordinates and runtime requirements. |

### Key Design Patterns

- **OSGi Whiteboard Pattern**: MCPTool services are discovered and aggregated by MCPToolProvider; MCPToolProvider services are consumed by HttpMCPServerComponent. LDAP filters select specific services (`tools_target`, `toolProviders_target`).
- **Reactive execution**: All tool execution returns `Mono<CallToolResult>` via Project Reactor, with bounded-elastic scheduling and timeout management.
- **Template Method**: `AbstractHttpMCPServer` defines MCP server initialization; subclasses configure capabilities.
- **OSGi Configurator**: Factory configurations use tilde notation (`component~instance`), defined in `gogo.runtime.config/configs/configuration.json`.

### Adding a New MCP Tool

1. Create a DS `@Component` implementing `MCPTool` (or extending `AbstractMCPTool`)
2. Set service properties: `tool.name`, `tool.description`
3. Add an LDAP filter match in the Configurator JSON, in the `MCPToolProvider~` config for that concern — `~emfModel` / `~emfMetadata` in `emf.runtime.config`, `~inference` in `inference.config`, `~modelAtlas` in the model.atlas project
4. Bump that provider's `tools.cardinality.minimum` to match
5. See `docs/development-guide.md` for full walkthrough

### External Dependencies

- `io.modelcontextprotocol.sdk` (2.0.0) — MCP protocol SDK, server **and** client (the client half is used by the OSGi endpoint tests)
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

- **JUnit 5** + Mockito + AssertJ; `reactor-test` (`StepVerifier`) where reactive timing matters
- `@Tag("perf")` for benchmarks (excluded from normal build, run via `perfTest` task)
- OSGi integration tests when DS wiring verification is needed — `service.tools.tests` and `http.component.tests`, both under `./gradlew testOSGi`
- An OSGi test project needing a workspace bundle only by identity in its `.bndrun` must declare `-dependson`, or a clean build resolves the runtime before that bundle is built
- The test runtimes blacklist `spifly.dynamic.framework.extension` in favour of `spifly.dynamic.bundle` + a current `org.objectweb.asm`, for the same reason the launch runtimes do: the extension's embedded ASM stops at class file V22 and its weaving hook then kills `felix.http.jetty12` on Java 23+. Without that swap every endpoint test is skipped on a modern JVM (`AbstractMCPServerTest` aborts them with the reason rather than reporting failures)
- `cardinality.minimum` in a Configurator JSON is written `"tools.cardinality.minimum:int"` — the `:int` is a Configurator type hint, so a `Dictionary` built by hand uses the plain key `tools.cardinality.minimum` with an `Integer`

## CI/CD

- **GitHub Actions**: all six workflows are thin callers into the org-wide reusables in [`eclipse-fennec/.github`](https://github.com/eclipse-fennec/.github), SHA-pinned with the version tag as a trailing comment. No workflow carries inline steps — change CI logic there, not here.
  - `build.yml` (all branches except main/snapshot + PRs) → `reusable-verify`
  - `snapshot.yml` (snapshot branch) → `reusable-verify` → `reusable-release` (`do-release: false`) → `reusable-docs`
  - `release.yml` (main branch) → the same chain with `do-release: true` (GPG-signed, deploys to Central Sonatype)
  - `docs.yml` (`workflow_dispatch` only) → `reusable-docs`; `dependency-review.yml` → `reusable-dependency-review`; `scorecard.yml` → `reusable-scorecard` (scan scopes sit on the calling job)
  - `reusable-verify` runs the license header check as the first gating job, then `./gradlew clean build testOSGi` on the Java 21 + 25 matrix and `./gradlew perfTest`. Secrets reach only `reusable-release`, via `secrets: inherit`.
- **SonarCloud**: project key `eclipse-fennec_emf.osgi-mcp`
- Branch strategy: `main` -> release, `snapshot` -> snapshot deploy, other branches -> build only (verify, no publish)
