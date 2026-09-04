# Development Guide

This guide explains how to develop custom MCP tools and configure MCP servers in the Eclipse Fennec MCP framework.

## Overview

The framework follows the **OSGi Whiteboard Pattern**:

1. You implement an `MCPTool` and register it as a DS component
2. An `MCPToolProvider` automatically discovers your tool via whiteboard
3. An `HttpMCPServerComponent` collects tool providers and serves them over HTTP

The wiring between these layers is controlled by LDAP target filters in OSGi configuration.

```
Your MCPTool  ──(whiteboard)──>  MCPToolProvider  ──(whiteboard)──>  HttpMCPServerComponent
  @Component                       collects tools                     serves via HTTP
  service=MCPTool                  builds specs                       MCP protocol
```

## Writing a Custom MCP Tool

### Step 1: Create the Bundle

Create a new bnd project with the following `bnd.bnd`:

```properties
Bundle-Name: My Custom MCP Tools

-buildpath: \
    io.modelcontextprotocol.sdk.mcp-core;version=latest,\
    io.projectreactor.reactor-core;version=latest,\
    reactive-streams;version=latest,\
    org.eclipse.fennec.mcp.api;version=snapshot
```

### Step 2: Implement the Tool

Extend `AbstractMCPTool` and register it as a DS `@Component` with `service = MCPTool.class`. Each tool needs a unique `tool.name` service property — this is what the LDAP filter in the tool provider configuration matches against.

```java
package com.example.mcp.tools;

import java.util.Map;

import org.eclipse.fennec.mcp.api.AbstractMCPTool;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

@Component(name = "GreetTool", service = MCPTool.class, property = "tool.name=greet")
public class GreetTool extends AbstractMCPTool {

    @Activate
    void activate() {
        this.name = "greet";
        this.description = "Greet a person by name.";
        this.inputSchema = """
                {
                    "type": "object",
                    "properties": {
                        "name": {
                            "type": "string",
                            "description": "The name of the person to greet"
                        }
                    },
                    "required": ["name"]
                }
                """;
        // outputSchema is optional — set it if you want structured output validation
    }

    @Override
    public Mono<McpSchema.CallToolResult> execute(McpAsyncServerExchange exchange, Map<String, Object> arguments) {
        return Mono.fromCallable(() -> {
            String name = (String) arguments.get("name");
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Hello, " + name + "!")
                    .build();
        }).onErrorResume(e -> Mono.just(
                McpSchema.CallToolResult.builder()
                        .addTextContent("Error: " + e.getMessage())
                        .isError(true)
                        .build()));
    }
}
```

### Key Points

- **`tool.name` property**: Used in LDAP filters to select which tools a provider collects. Choose a unique, descriptive name using snake_case.
- **`inputSchema`**: JSON Schema string defining the tool's input parameters. MCP clients use this to validate arguments before calling the tool.
- **`outputSchema`**: Optional JSON Schema for structured output. Set it when clients need machine-readable output beyond plain text.
- **Reactive execution**: `execute()` must return a `Mono<CallToolResult>`. Use `Mono.fromCallable()` for blocking operations — the tool provider schedules execution on a bounded-elastic thread pool.
- **Error handling**: Always include `.onErrorResume()` to convert exceptions into error results. Unhandled errors will be caught by the tool provider, but with less context.

### Loading Schemas from Files or EMF Models

`AbstractMCPTool` provides utility methods for loading schemas instead of inlining them:

```java
// Load from a JSON file on disk
this.inputSchema = loadSchema("/path/to/input-schema.json");

// Generate from an EMF EClass (requires a ResourceSet with the Fennec JSON Schema codec)
this.inputSchema = loadSchema("platform:/plugin/my.model/model/MyModel.ecore#//MyInputClass", resourceSet);
```

The EMF variant uses the Fennec JSON Schema codec to generate a self-contained schema with all `$ref` entries inlined.

### Injecting OSGi Services

Tools are DS components, so you can inject any OSGi service via `@Reference`:

```java
@Reference
private MyService myService;

@Reference(target = "(db.name=production)")
private DataSource dataSource;
```

## Configuring the MCP Server

The framework uses three layers of configuration, each a factory configuration identified by a PID with tilde notation (`PID~instanceName`).

### Configuration Overview

| PID | Purpose |
|-----|---------|
| `MCPToolProvider~<name>` | Defines which tools to collect and under what name |
| `HttpMCPServerComponent~<name>` | Defines the HTTP endpoint, capabilities, and which providers to use |
| HTTP runtime (e.g. `org.apache.felix.http~<name>`) | Configures the HTTP port and runtime identity |

### Option A: OSGi Configurator (Declarative)

Create a resource-only bundle with a `configuration.json` in a `configs/` folder. This is the recommended approach for pre-packaged server setups.

**`bnd.bnd`:**
```properties
-resourceonly: true
-includeresource: OSGI-INF/configurator/=configs/
Bundle-Name: My MCP Server Configuration
Require-Capability: \
    osgi.extender;filter:='(osgi.extender=osgi.configurator)',\
    osgi.implementation;filter:='(&(osgi.implementation=osgi.cm)(version>=1.6.0)(!(version>=2.0.0)))'
```

**`configs/configuration.json`:**
```json
{
    ":configurator:resource-version": 1,

    "org.apache.felix.http~myserver": {
        "org.osgi.service.http.port:int": 9090,
        "org.apache.felix.http.host": "127.0.0.1",
        "org.apache.felix.http.name": "myserver"
    },

    "MCPToolProvider~myTools": {
        "name": "my_tool_provider",
        "description": "Collects my custom tools",
        "tools.target": "(|(tool.name=greet)(tool.name=my_other_tool))",
        "tools.cardinality.minimum:int": 2
    },

    "HttpMCPServerComponent~myServer": {
        "server.name": "my-mcp-server",
        "server.version": "1.0",
        "osgi.http.whiteboard.servlet.pattern": "/mcp/my-endpoint",
        "osgi.http.whiteboard.target": "(org.apache.felix.http.name=myserver)",
        "has.tool.capability": true,
        "has.prompt.capability": false,
        "has.resource.capability": false,
        "toolProviders.target": "(name=my_tool_provider)",
        "toolProviders.cardinality.minimum:int": 1,
        "server.instructions": "Description for MCP clients on how to use this server."
    }
}
```

> 🛑 **Development use only.** The Gogo Shell MCP Server (`execute_gogo`) is meant
> for development and debugging against a local runtime. It grants arbitrary
> command execution in the OSGi framework and **must not be deployed in
> production systems.** Treat any server that exposes shell-like tools the same
> way.
>
> ⚠️ **Security.** The MCP endpoint has no built-in access control beyond what
> you configure here. The transport dispatches tool arguments straight to the
> registered tools (for the Gogo server, into a live `CommandSession`), so an
> unauthenticated caller who can reach the endpoint can run whatever those tools
> expose. Two mechanisms protect it:
>
> 1. **Bind to loopback.** `org.apache.felix.http.host: 127.0.0.1` (above) keeps
>    the listener on localhost. This is the default in the shipped Gogo config.
> 2. **Authentication token.** Set `auth.token` on the
>    `HttpMCPServerComponent~…` config to require an
>    `Authorization: Bearer <token>` header. When no token is set, the built-in
>    `McpAuthenticationFilter` still rejects every non-loopback request, so
>    exposing the endpoint beyond localhost *requires* configuring a token:
>
>    ```json
>    "HttpMCPServerComponent~myServer": {
>        "…": "…",
>        "auth.token": "change-me-to-a-long-random-secret"
>    }
>    ```
> 3. **Pluggable token verification.** Register a `McpTokenVerifier` service
>    (package `org.eclipse.fennec.mcp.api.auth`) — e.g. validating JWTs against
>    an IdP's JWKS — and select it per endpoint via the standard DS
>    `verifier.target` property on the `HttpMCPServerComponent~…` config. When a
>    verifier is wired it replaces the static token compare; enforcement stays in
>    the servlet filter, and the verified `McpPrincipal` is exposed as the
>    `org.eclipse.fennec.mcp.auth.principal` request attribute. A verifier
>    exception rejects the request (fail-closed). Note the loopback exemption is
>    hardened: a loopback request carrying an `X-Forwarded-For`/`Forwarded`
>    header (i.e. relayed by a local reverse proxy) is treated as remote.
>
>    The `org.eclipse.fennec.mcp.auth.jwt` bundle ships a ready-made verifier
>    validating JWTs offline against an IdP's JWKS (signature, `iss`, `aud`,
>    `exp`/`nbf`, `sub`; the `scope` claim becomes the principal's scopes) —
>    one factory config per identity provider:
>
>    ```json
>    "JwtTokenVerifier~keycloak": {
>        "jwks.url": "https://idp.example.org/realms/mcp/protocol/openid-connect/certs",
>        "issuer": "https://idp.example.org/realms/mcp",
>        "audience": "mcp-endpoint",
>        "verifier.name": "keycloak"
>    },
>    "HttpMCPServerComponent~myServer": {
>        "…": "…",
>        "verifier.target": "(verifier.name=keycloak)"
>    }
>    ```
>
>    Deploy `org.eclipse.fennec.mcp.auth.jwt` together with
>    `com.nimbusds.nimbus-jose-jwt` in the runtime to enable it. A complete
>    IdP-side walkthrough (realm, per-agent service-account clients, the audience
>    mapper, token lifespan, curl examples) is in
>    [mcp-auth-keycloak.md](mcp-auth-keycloak.md).

### Option B: Configuration Admin (Programmatic)

Use any OSGi Configuration Admin mechanism (FileInstall, REST, Gogo commands, etc.) to create the same factory configurations at runtime.

### Configuration Reference

Further PIDs are documented where their feature lives: `JwtTokenVerifier` in
[mcp-auth-keycloak.md](mcp-auth-keycloak.md), `ServiceClientToolBridge` in
[service-client-bridge.md](service-client-bridge.md), the EMF PIDs
(`EMFModelGuard`, `EMFPackageRegistry`, `EMFDatasetRegistry`) in
[emf-metamodel-authoring.md](emf-metamodel-authoring.md).

#### MCPToolProvider

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `name` | String | yes | Unique name for this provider (used in server's `toolProviders.target`) |
| `description` | String | yes | Human-readable description of the tool set |
| `tools.target` | String | yes | LDAP filter selecting `MCPTool` services (e.g. `(tool.name=greet)`) |
| `tools.cardinality.minimum` | int | yes | Minimum number of tools that must be available before the provider activates |

#### HttpMCPServerComponent

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `server.name` | String | no | `my-mcp-server` | Server identity reported to MCP clients |
| `server.full.url` | String | no | — | Complete URL for client discovery |
| `server.version` | String | no | `1.0.0` | Server version reported to clients |
| `osgi.http.whiteboard.servlet.pattern` | String | yes | — | URL path for the MCP endpoint (e.g. `/mcp/tools`) |
| `osgi.http.whiteboard.target` | String | yes | — | LDAP filter targeting the HTTP runtime (e.g. `(org.apache.felix.http.name=myserver)`) |
| `has.tool.capability` | boolean | no | `false` | Announce the tool capability in the `initialize` response (with `listChanged`, since tool changes are pushed). Left `false`, the capability is absent and the server registers no `tools/list` or `tools/call` handler at all — so a client never asks. |
| `has.prompt.capability` | boolean | no | `false` | Announce the prompt capability. Absent when `false`; the base server serves no prompts and claims no `listChanged`. |
| `has.resource.capability` | boolean | no | `false` | Announce the resource capability. Absent when `false`; the base server serves no resources and claims neither `listChanged` nor `subscribe`. |
| `server.instructions` | String | no | — | Instructions shown to MCP clients |
| `keep.alive.interval.seconds` | long | no | `0` (disabled) | Interval for keep-alive pings to active sessions. Disabled by default. Only enable (a positive value, kept below the reverse-proxy read timeout) when clients maintain a long-lived listening SSE stream; otherwise the SDK floods the log with `Stream unavailable for session …`. See [Deploying Behind a Reverse Proxy](#deploying-behind-a-reverse-proxy-sse). |
| `auth.token` | String (password) | no | `""` (empty) | Bearer token required in the `Authorization: Bearer <token>` header. When empty, only loopback callers are permitted and any remote request is rejected. Set a strong token before exposing the endpoint beyond localhost. |
| `toolProviders.target` | String | no | — | LDAP filter selecting `MCPToolProvider` services |
| `toolProviders.cardinality.minimum` | int | no | `1` | Minimum number of tool providers before the server activates |

#### RemoteMCPEndpoint

An MCP server this framework does **not** host, published so a client here can
address it. Factory PID `RemoteMCPEndpoint` (tilde notation):

```json
"RemoteMCPEndpoint~partner": {
    "server.name": "partner-emf-mcp-server",
    "server.url": "https://mcp.example.org/mcp/emf"
}
```

| Property | Type | Required | What it is |
|---|---|---|---|
| `server.name` | String | yes | Server identity, and the property clients filter on |
| `server.url` | String | yes | Complete URL at which the remote server is reachable |

Configuration is the whole implementation: no transport, no servlet, no tool
aggregation, and **no connection attempt at activation**. An endpoint asserts an
address, it does not verify one — failing activation because a remote host was
down at startup would make the wiring less useful, not safer.

#### `MCPEndpoint` vs. `MCPServer`

`MCPServer` extends `MCPEndpoint`, which carries just `getServerName()` and
`getServerFullUrl()`.

- **Bind to `MCPServer`** when you need what the server *serves* — the aggregated
  tool, prompt and resource specifications. Only a hosted server can answer that.
- **Bind to `MCPEndpoint`** when you only need to *reach* a server. Both
  `HttpMCPServerComponent` (which registers both types from one component, with
  unchanged service properties) and `RemoteMCPEndpoint` satisfy it, so the
  consumer is indifferent to which it got — and a client pointed at another host
  no longer needs a local server deployment to satisfy its reference.

`RemoteMCPEndpoint` is deliberately *not* an `MCPServer`: it cannot enumerate the
remote server's tools, and claiming that interface would mean returning empty
lists that read as "this server has no tools" rather than "ask the server
yourself".

The two live in different bundles, and that is the point. `MCPEndpoint` and
`RemoteMCPEndpoint` are in **`org.eclipse.fennec.mcp.endpoint`**, whose manifest
imports nothing but `java.*` and its own package — no MCP SDK, no Reactor, no
Jackson. A consumer that only has to reach a server puts that bundle on its
buildpath and never drags the SDK closure into its runtime; `org.eclipse.fennec.mcp.api`
depends on it and adds the server half.

::: warning Package move
`MCPEndpoint` was previously exported from `org.eclipse.fennec.mcp.api`. Hosting
one package from two bundles would be a split package, so the class moved to
`org.eclipse.fennec.mcp.endpoint`; update the import if you were binding it.
The DS component name is unchanged, so existing `RemoteMCPEndpoint~…` factory
configurations keep working untouched.
:::

### Deploying Behind a Reverse Proxy (SSE)

The HTTP transport is **Streamable HTTP**: responses to the MCP endpoint are served as a
Server-Sent-Events (SSE) stream. Reverse proxies such as nginx and APISIX buffer upstream
responses by default, which stalls the stream — events and keep-alive pings are withheld until
the buffer fills, so the client tears down its listening stream and the server reports the
session as unavailable.

To prevent this, `HttpMCPServerComponent` **automatically** registers an `SseNoBufferingFilter`
on the same endpoint as the transport servlet. The filter sets `X-Accel-Buffering: no` and
`Cache-Control: no-cache` on every response; nginx core (and therefore APISIX) honours
`X-Accel-Buffering` and turns off buffering for that response. **No configuration is required** —
it is always on.

**Keep-alive interplay.** Keep-alive pings are **off by default** (`keep.alive.interval.seconds`
defaults to `0`). The SDK can only ping a session that holds a standalone listening (GET) SSE
stream; clients using plain request/response POST never open one, so enabling keep-alive for such
clients only floods the log with `Stream unavailable for session …`. Enable it **only** when your
clients maintain a long-lived listening stream that needs to be kept warm, and set the interval
**below** the reverse proxy's read/idle timeout so the connection is refreshed before the proxy
drops it.

### LDAP Filter Chaining

The LDAP filters create a chain from tools to server:

```
MCPTool services                MCPToolProvider                   HttpMCPServerComponent
  property:                       config:                           config:
  tool.name=greet       <----   tools.target=                <---- toolProviders.target=
  tool.name=calculate           "(|(tool.name=greet)                "(name=my_provider)"
                                   (tool.name=calculate))"
                                property:
                                  name=my_provider
```

This allows flexible composition: multiple tool providers can collect different tool subsets, and multiple servers can expose different providers on different endpoints. A provider may also be bound by more than one server — all of them are notified when its tool set changes.

Filters can overlap. If two providers behind the *same* server match one tool, the server serves a single copy of it (the first) and logs a warning naming the tool; it does not refuse to start. Worth checking the framework log for after widening a `tools.target`, since the duplicate is otherwise invisible.

## Example: The Gogo MCP Server

The included Gogo integration demonstrates the full pattern:

| Bundle | Role |
|--------|------|
| `org.eclipse.fennec.mcp.gogo.tools` | Two `MCPTool` components: `ExecuteGogoTool` (`tool.name=execute_gogo`) and `ListCommandsTool` (`tool.name=list_commands`) |
| `org.eclipse.fennec.mcp.gogo.runtime.config` | OSGi Configurator bundle wiring the tool provider and HTTP server on port 8088 at `/mcp/gogo` |
| `org.eclipse.fennec.mcp.gogo.runtime` | Activator component ensuring the full dependency chain is resolved |

The configuration in `org.eclipse.fennec.mcp.gogo.runtime.config/configs/configuration.json` shows the complete wiring from tools through provider to HTTP server.

## Creating an Activator Bundle (Optional)

For a self-contained server deployment, create an activator component that references `MCPServer`. This ensures the OSGi resolver pulls in the entire dependency chain:

```java
@Component
@Capability(namespace = ImplementationNamespace.IMPLEMENTATION_NAMESPACE,
        name = "mcp.myfeature", version = "1.0")
@Requirements({
    @Requirement(namespace = IdentityNamespace.IDENTITY_NAMESPACE,
            name = "com.example.mcp.tools"),
})
@RequireMCPServer
public class MyMCPActivator {

    private static final Logger LOG = LoggerFactory.getLogger(MyMCPActivator.class);

    @Reference
    private MCPServer mcpServer;

    @Activate
    void activate() {
        LOG.info("My MCP Server activated: {}", mcpServer);
    }
}
```

The `@Requirement` annotations on the activator declare bundle-level dependencies that the OSGi resolver uses to ensure all required bundles are present in the runtime.

## Structured Output with EMF (Advanced)

For tools that produce structured output conforming to an EMF Ecore model:

1. Define your output schema from an EClass using `loadSchema(eClassUri, resourceSet)` in your tool's `@Activate` method
2. In `execute()`, create the EObject result and serialize it with `saveEObjectToString(eObject, resourceSet)`
3. Implement `StructuredOutputHandler<T>` for post-processing and persistence of tool results on the client side

This enables type-safe round-tripping: the MCP client receives JSON matching the Ecore model, and can deserialize it back via `StructuredOutputStorageHelper.loadEObject()`.

## EMF Model Tools (`org.eclipse.fennec.mcp.emf.tools`)

The `org.eclipse.fennec.mcp.emf.tools` bundle provides 28 MCP tools. They let an agent create, populate, validate and serialize EMF model **instances** for allow-listed EPackages/EClasses (datasets, replayable recipes, deny-all security model), **author Ecore metamodels** and register them into a session-local registry so their classes become instantiable, and round-trip both through inline XMI.

Metamodel authoring has a guide of its own — [Metamodel Authoring](emf-metamodel-authoring.md) — as does the companion discovery bundle `org.eclipse.fennec.mcp.metadata.tools` ([Metadata Discovery](metadata-discovery-tools.md), 9 tools).

### Security: deny-all allow-lists

Nothing is visible or instantiable unless explicitly allow-listed on the admin-owned `EMFModelGuard` PID (`configurationPolicy = REQUIRE` — no config, no tools):

```json
"EMFModelGuard": {
    "epackage.allowlist": ["http://example.org/library"],
    "eclass.allowlist": [
        "http://example.org/library#//Library",
        "http://example.org/library#//Book"
    ]
}
```

A class is usable only if its package **and** the class itself are listed. Allow-listing a package is a code-trust decision: its generated `EFactory` runs in-process.

#### Patterns

Both lists accept an exact entry, a **`prefix*`**, or a bare **`*`** — the same
pattern language as `EMFPackageRegistry`'s `nsuri.allowlist`. Prefix matching is
anchored on the whole string, so a rule for `http://example.org/` is not
satisfied by `http://evil.example/http://example.org/x`. A blank entry is
ignored rather than treated as a wildcard.

```json
"EMFModelGuard": {
    "epackage.allowlist": ["http://example.org/*"],
    "eclass.allowlist": ["http://example.org/library#//*"]
}
```

Two things patterns do **not** change:

- **The lists stay independent.** A package pattern says what may be *seen*,
  never what may be *instantiated*. `"epackage.allowlist": ["*"]` with an empty
  `eclass.allowlist` exposes every package and not one class.
- **Empty still means deny-all**, on either list.

Patterns exist because the registry is not always fully known when the
configuration is written. `allowedPackages()` therefore **filters the live
registry** rather than resolving the allow-list's entries, so a package that
arrives after startup — mirrored from a model.atlas scope, say — is listed and
readable without anyone editing configuration to name it. Before this, the
metadata discovery tools could find such a package while `list_metamodel` and
`describe_eclass` could not read it.

### Annotation visibility

An EAnnotation is where a model keeps the configuration of everything that reads
it — codec type mappings, persistence hints, wire names, deployment detail — so
an agent that can read annotations reads all of that. The `MCPAnnotationVisibility`
PID withholds the sources a deployment does not want handed out:

```json
"MCPAnnotationVisibility": {
    "annotation.source.denylist": ["http://internal.example/persistence/*"],
    "aspect.type.denylist": ["persistence"]
}
```

Both lists speak the same patterns as every other list here — exact, `prefix*`,
bare `*` — via `UriPatterns`, which lives in `mcp.api` because three concerns now
share it. **Both default to empty, which denies nothing:** adding the PID to a
deployment changes no behaviour until an entry is written.

**A deny-list, where packages and classes get allow-lists.** Packages and classes
are a closed, enumerable set, so naming what is permitted is possible and safe.
Annotation sources are open-ended, contributed by whatever is deployed, and the
whole point of the discovery tools is to find conventions nobody wrote down in
advance. An allow-list would hide every unknown-but-harmless source and defeat
the feature.

**Two lists, because an aspect has no source.** A metadata aspect is the *parsed*
form of one or more annotations, and `AspectEntry` carries a type id, content and
diagnostics — not the source it was built from. Denying a source therefore cannot
hide the aspect built from it, and a `codec` aspect is exactly a class's
serialization configuration. Keep the two lists consistent, or `describe_aspects`
hands back what the source list withholds.

**Where it is enforced.** The policy lives in `mcp.api`, the one bundle both tool
bundles depend on, and every path that can disclose an annotation honours it:

| Path | Bundle | Under a denial |
|------|--------|----------------|
| `describe_eclass` | `emf.tools` | the annotation is omitted and `hiddenAnnotations: <n>` is reported — counted, never named, the same rule `export_package` applies to denied classes |
| `export_package` | `emf.tools` | **refused** for an OSGi package carrying a denied source. A `.ecore` carries every annotation and cannot be filtered without ceasing to be the package; stripping them would produce a document that no longer says what it claims and that a re-import would silently flatten. A *session* package is exported regardless — it is the agent's own authored or imported work, so withholding it protects nothing, exactly as the allow-list is not re-checked for those |
| `list_annotation_sources` | `metadata.tools` | the whole entry is omitted, with its keys, hit count and namespaces |
| `find_{classes,features,operations}_by_annotation` | `metadata.tools` | the query is refused; an empty answer would read as "nothing carries this" |
| `describe_aspects`, `list_aspects`, `describe_metadata_status` | `metadata.tools` | denied aspect types are withheld, including from `describe_aspects`'s "available type ids" hint |

The component's configuration policy is deliberately **optional**: the tools bind
`AnnotationVisibility` mandatorily, so a component requiring configuration would
leave a runtime without the PID with no annotation tools at all, rather than with
unrestricted ones.

`describe_eclass` resolves through `ModelGuard.requireAllowedEClassForRead`,
which enforces both allow-lists but permits abstract classes and interfaces —
reading a class is not instantiating one. `requireAllowedEClass` keeps the
abstract rejection for `create_instance` and `create_from_json`. Do not reach
for `requireAllowedClassifier` to describe something: it also permits abstract
classes but enforces **no** class allow-list, because naming a class as a
*type* is not reading it.

Two consequences worth knowing:

- Enumeration is only as complete as the registry's `keySet()`. The literal
  entries of the allow-list are always resolved directly as well, so exact
  configurations cannot regress; only `prefix*` and `*` depend on the registry
  being enumerable.
- With a wide pattern, `list_metamodel` resolves every matching entry, which
  initializes each package's generated code. On a large registry that cost lands
  on the first call. Prefer the narrowest prefix that covers what the agent
  actually needs.

### Tool overview

**Discover and inspect**

| Tool | Purpose |
|------|---------|
| `list_metamodel` | List allow-listed EPackages, or the EClasses of one package |
| `describe_eclass` | Feature table of an EClass (kind, type, multiplicity, enums) |
| `export_package` | Full `.ecore` of a registered package — EAnnotations, abstract classes and supertypes, which `describe_eclass` omits |

**Datasets — the unit of state**

| Tool | Purpose |
|------|---------|
| `create_dataset` | New session-scoped dataset |
| `inspect_dataset` | List datasets / objects, validation summary, build recipe |
| `manage_dataset` | `regenerate` (deterministic recipe replay), `clear`, `delete` |
| `export_dataset` | Serialize all roots as XMI or JSON (inline up to a byte cap) |
| `replay_recipe` | Rebuild a dataset deterministically from a recipe, no LLM |

**Instances**

| Tool | Purpose |
|------|---------|
| `create_instance` | New instance of an allow-listed EClass → `objectId` |
| `modify_feature` | `set`/`unset`/`add`/`remove` one structural feature |
| `delete_instance` | Remove an object incl. containment subtree and references |
| `create_from_json` | Whole instance graph from one JSON payload (via Fennec codec), plus a `coverage` report of what the payload did *not* contribute |

#### Coverage of a JSON load

The codec drops a JSON key that matches no structural feature without saying
so, and `objectCount` cannot stand in for the missing signal: a payload whose
leaf attributes all vanished still produces the expected number of objects.
`create_from_json` therefore returns a `coverage` report next to the ack:

| Field | Meaning |
|-------|---------|
| `complete` | every key landed on a feature that holds a value, and the codec reported nothing |
| `matchedKeys` | keys that resolved to a structural feature |
| `unmatchedCount` / `unmatchedPaths` | keys that resolved to no feature — the model is narrower than the payload |
| `droppedCount` / `droppedPaths` | keys that resolved to a feature which is empty afterwards |
| `unsetFeatureCount` / `unsetFeatures` | `Class.feature` entries no key mentioned — the model is wider than the payload; a hint, not a defect |
| `codecDiagnostics` | messages the codec itself reported for the load |
| `truncated` | a list hit the 50-entry reporting cap; the counts stay exact |

Empty lists are omitted, so a clean load is three short fields. Pass
`strict: true` to have the call refuse — and delete the dataset it had already
built — instead of reporting unmatched keys.

The analysis lives in `core/JsonCoverage` and takes an already-built `EObject`
tree, deliberately independent of the codec: a "the codec dropped this" claim
has to be provably correct, so it is unit-tested against hand-built models
rather than against codec output. It **under-reports by design** in two places:
a key is matched if it equals either the feature's plain name or its
`ExtendedMetaData` wire name (the codec may accept only one, but a false
"unmatched" claim is the one an agent cannot recover from), and an attribute
that fell back to its type default (`0`, `false`) is not flagged as dropped,
being indistinguishable from a deliberate default.

The replay paths (`manage_dataset` regenerate, `replay_recipe`) reload a payload
the server recorded itself, so coverage is not part of their answer — but a
recorded payload that suddenly matches fewer features means the metamodel moved
under the recipe, and `FromJsonSupport.loadAndWarn` logs that server-side.

**Metamodel authoring**

`create_epackage` and `add_eclass` are composite: they take their children
inline, so a whole package is one call rather than one call per element. The
remaining tools extend a package that already exists.

| Tool | Purpose |
|------|---------|
| `create_epackage` | New EPackage, optionally carrying the whole package in `eClassifiers` |
| `add_eclass` | Add an EClass, optionally with nested `eAttributes` / `eReferences` / `eAnnotations` |
| `add_eattribute` | Add an EAttribute; `eType` is `<nsURI>#//<Name>` or a dataset objectId |
| `add_ereference` | Add an EReference — containment, `eOpposite`, `eKeys` |
| `add_edatatype` | Add a dynamic EDataType (registered packages must be dynamic) |
| `add_eenum` / `add_eenum_literal` | Add an EEnum and its literals |
| `add_eoperation` / `add_eparameter` | Add an EOperation and its parameters |
| `add_eannotation` | Attach an EAnnotation (source, string details, references) to any element |
| `add_etypeparameter` | Declare a generic type parameter on an EClass, EDataType or EOperation |

**Session package registry**

| Tool | Purpose |
|------|---------|
| `register_package` | Validate an authored EPackage and register a frozen copy, making its classes instantiable |
| `unregister_package` | Remove a package by nsURI; existing instances stay live |
| `list_registry` | The packages registered in this session, with their instantiable classes |

**Import**

| Tool | Purpose |
|------|---------|
| `import_ecore` | Load an inline `.ecore` into a new dataset and register its packages |
| `import_instances` | Load inline instance XMI whose package is already registered |

Imports are hardened: external references are never dereferenced, the document
must be self-contained, DOCTYPE is rejected and size is capped.

### Runtime introspection (service.changecount pattern)

Two services follow the OSGi service-runtime pattern known from `HttpServiceRuntime`: each is registered
with a `service.changecount` property that is bumped via `ServiceRegistration.setProperties()` whenever its
DTO may have changed — consumers listen for the service-modified event and re-fetch the DTO instead of
polling.

- **`MCPServiceRuntime`** (exported from `org.eclipse.fennec.mcp.api.runtime`, implemented in
  `org.eclipse.fennec.mcp.tool.provider`) describes the whiteboard itself: the active `MCPServer`s (name,
  URL, tool/prompt/resource counts), the `MCPToolProvider`s with the tools they matched, and every
  registered `MCPTool` service. Tools need no code for this — they are discovered as whiteboard services.
- **`EMFToolsServiceRuntime`** (exported from `org.eclipse.fennec.mcp.emf.tools.runtime`) describes the EMF
  domain state: the guard policy (allow-listed EPackages/EClasses), the package registry policy (nsURI
  allow/deny lists, cap), and every session with its datasets (object/recipe counts, timestamps) and
  registered packages.

Dataset create/delete and package register/unregister are additionally logged at INFO.

### Codec metadata bridge

`create_from_json` and JSON export go through the Fennec codec (`CodecResource`), which only accepts packages known to its `MetadataService`. The `EMFPackageRegistry` therefore announces every session-registered package to the `MetadataWhiteboard` (optional dynamic reference — the tools also run without the codec bundles, only the JSON paths need them) and retracts it on unregister, re-register, rekey, cap eviction and session eviction. Note that the metadata service is runtime-global: if two sessions register different packages under the same nsURI, the last registration wins for codec metadata; the session package stores themselves stay isolated.

### Reproducibility

Every mutating call is recorded in the dataset's **recipe**. `inspect_dataset` with `includeRecipe=true` returns it; `replay_recipe` / `manage_dataset {regenerate}` reproduce **byte-identical XMI** without LLM involvement. Replay re-validates every operation against the current allow-list.

### Resource limits

The `EMFDatasetRegistry` PID caps datasets per session, objects per dataset, recipe length, value size, JSON payload size and the inline export byte cap. Exports above the cap are written to the working directory configured via `work.dir` (default: the OS temp directory, below `fennec-mcp-exports`) into a per-session subdirectory, and the tool returns a descriptor with the file path instead of the content. Export files are removed when their dataset is deleted or the owning session is evicted.
