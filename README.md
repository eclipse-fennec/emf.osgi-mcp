# Eclipse Fennec MCP

Lightweight MCP (Model Context Protocol) server framework for OSGi environments. Enables AI/LLM clients to interact with live OSGi runtimes via the standardized MCP protocol.

Includes a ready-to-use **Gogo Shell MCP Server** that exposes Apache Felix Gogo commands as MCP tools over HTTP.

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
| `org.eclipse.fennec.mcp.api` | Core whiteboard API — `MCPServer`, `MCPTool`, `MCPToolProvider` interfaces and abstract bases |
| `org.eclipse.fennec.mcp.gogo.tools` | Gogo MCP tools: `ExecuteGogoTool`, `ListCommandsTool` |
| `org.eclipse.fennec.mcp.emf.tools` | EMF model MCP tools: build, validate and serialize EMF instances for allow-listed EClasses (deny-all by default) |
| `org.eclipse.fennec.mcp.tool.provider` | Whiteboard aggregator collecting `MCPTool` services into `MCPToolProvider` |
| `org.eclipse.fennec.mcp.http.component` | HTTP transport via OSGi HTTP Whiteboard servlet |
| `org.eclipse.fennec.mcp.gogo.runtime` | Activator wiring the Gogo MCP server lifecycle |
| `org.eclipse.fennec.mcp.gogo.runtime.config` | Default OSGi Configurator config (port 8088, path `/mcp/gogo`) |
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

1. `MCPTool` services are registered by tool bundles (e.g., `gogo.tools`)
2. `MCPToolProvider` aggregates tools via whiteboard, filtered by LDAP target
3. `HttpMCPServerComponent` consumes tool providers and exposes them as an MCP HTTP endpoint

Tool execution is fully reactive via Project Reactor (`Mono<CallToolResult>`), with configurable timeouts and bounded-elastic scheduling.

```
MCPTool services → MCPToolProvider (aggregator) → HttpMCPServerComponent (HTTP servlet)
```

## Documentation

- **[Development Guide](docs/development-guide.md)** — How to write custom MCP tools, configure tool providers and HTTP servers, LDAP filter chaining, structured EMF output

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
