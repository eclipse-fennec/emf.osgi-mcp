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

### Option B: Configuration Admin (Programmatic)

Use any OSGi Configuration Admin mechanism (FileInstall, REST, Gogo commands, etc.) to create the same factory configurations at runtime.

### Configuration Reference

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
| `has.tool.capability` | boolean | no | `false` | Advertise tool capability to clients |
| `has.prompt.capability` | boolean | no | `false` | Advertise prompt capability to clients |
| `has.resource.capability` | boolean | no | `false` | Advertise resource capability to clients |
| `server.instructions` | String | no | — | Instructions shown to MCP clients |
| `toolProviders.target` | String | no | — | LDAP filter selecting `MCPToolProvider` services |
| `toolProviders.cardinality.minimum` | int | no | `1` | Minimum number of tool providers before the server activates |

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

This allows flexible composition: multiple tool providers can collect different tool subsets, and multiple servers can expose different providers on different endpoints.

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
