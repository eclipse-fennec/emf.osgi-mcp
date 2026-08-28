# ServiceClient → MCP tool bridge

`org.eclipse.fennec.mcp.service.tools` turns every operation of a
`ServiceClient` (the protocol-agnostic client face of the emf.util repo —
implemented by the SOAP, OpenAPI and gRPC clients) into an MCP tool: an
imported WSDL, OpenAPI document or Protobuf descriptor becomes a set of
AI-usable tools through configuration alone.

## How it works

- The `ServiceClientToolBridge` (factory config, PID `ServiceClientToolBridge`)
  binds `ServiceClient` services selected by the `clients.target` filter and
  registers one `MCPTool` service per allow-listed `ServiceOperation`.
- **Schemas** are generated once at registration from the operation's
  request/response `EClass` via the Fennec JSON Schema codec (refs inlined);
  operations without a request type get an empty-object schema.
- **Execution**: argument map → request `EObject` (codec JSON), →
  `ServiceClient.invoke(...)` → response `EObject` → JSON without type
  discriminator. `ServiceInvocationException` becomes an `isError` result with
  the message only.
- The operations' EPackages are announced to the codec `MetadataWhiteboard`
  (and retracted on unbind) so the JSON conversion works for dynamically
  imported models.
- Tool names are `<client>_<operation>` (sanitized to `[a-z0-9_-]`); the
  client name comes from `tools.prefix`, else the client's `name` service
  property, else the config instance name. Duplicates are logged and skipped.

## End-to-end example

Three factory configurations take an OpenAPI document to callable MCP tools —
no code involved. First the **emf.util side**: the `OpenApiServiceClient`
component (bundle `org.eclipse.fennec.openapi.osgi`) imports the document and
publishes a `ServiceClient` service:

```json
"OpenApiServiceClient~petstore": {
    "name": "petstore",
    "documentUrl": "https://petstore3.swagger.io/api/v3/openapi.json",
    "baseUri": "https://petstore3.swagger.io/api/v3",
    "format": "json"
}
```

Then the bridge and its dedicated tool provider (next section). Given a
document with

```json
"paths": {
  "/ping": {
    "get": {
      "operationId": "ping",
      "responses": { "200": { "content": { "application/json": {
        "schema": { "$ref": "#/components/schemas/Pong" } } } } }
    }
  }
},
"components": { "schemas": {
  "Pong": { "type": "object", "properties": { "message": { "type": "string" } } } } }
```

the bridge publishes a tool named **`petstore_ping`** whose output schema
describes `Pong` (`{"type":"object","properties":{"message":{"type":"string"}}}`,
generated from the imported EClass). An agent calling

```json
{ "method": "tools/call", "params": { "name": "petstore_ping", "arguments": {} } }
```

receives the service's JSON response as text content: `{"message":"pong"}`.
Arguments work the same way in reverse: the tool's input schema is generated
from the operation's request EClass (for OpenAPI, path/query/header/body
parameters flattened into one object), and the argument map is converted to
the request object before invocation. This exact flow — embedded HTTP stub,
real `OpenApiServiceClient`, bridge, tool call — is verified by
`org.eclipse.fennec.mcp.service.tools.tests` in a running Felix.

## Deny-all configuration

Nothing is bridged until both filters are configured:

```json
"ServiceClientToolBridge~petstore": {
    "clients.target": "(name=petstore)",
    "operations.allow": ["get*", "findPetsByStatus"]
},
"MCPToolProvider~serviceTools": {
    "name": "service_bridge_tool_provider",
    "description": "Tools bridged from imported service clients",
    "tools.target": "(tool.namespace=service-bridge)",
    "tools.cardinality.minimum:int": 0
},
"HttpMCPServerComponent~myServer": {
    "…": "…",
    "toolProviders.target": "(|(name=emf_model_tool_provider)(name=service_bridge_tool_provider))"
}
```

The dedicated `MCPToolProvider~serviceTools` instance is required because the
regular provider configs enumerate tool names in their LDAP filter — bridged
tools are matched by the `tool.namespace=service-bridge` marker instead, with
`cardinality.minimum` 0 (tools appear with ConfigAdmin lifecycle).

## Configuration reference (`ServiceClientToolBridge` PID, factory)

| Property | Type | Default | Description |
|---|---|---|---|
| `clients.target` | String (LDAP filter) | — | Selects the bridged `ServiceClient` services, e.g. `(name=petstore)`. Standard DS reference target; without it every `ServiceClient` in the runtime is bound — set it. |
| `operations.allow` | String[] | `[]` (deny all) | Operation names to expose: exact match or prefix with trailing `*` (e.g. `get*`). Empty = nothing is bridged. |
| `tools.prefix` | String | `""` | Overrides the tool-name prefix. Empty = the client's `name` service property, fallback: the config instance name (tail after `~`). |

Generated tool service properties: `tool.name` (`<prefix>_<operation>`,
sanitized to `[a-z0-9_-]`), `tool.description`, `tool.namespace=service-bridge`.

## Runtime deployment

Bundles required in addition to the MCP core stack (`mcp.api`,
`tool.provider`, `http.component`):

- `org.eclipse.fennec.mcp.service.tools` (the bridge)
- the client implementation, e.g. `org.eclipse.fennec.openapi.osgi` + `org.eclipse.fennec.openapi.client` + `org.eclipse.fennec.openapi.ecore`
- the codec pipeline: `org.eclipse.fennec.codec`, `org.eclipse.fennec.codec.metadata`, `org.eclipse.fennec.codec.jsonschema`, `org.eclipse.fennec.emf.osgi.metadata` (+ `org.eclipse.fennec.codec.openapi` for OpenAPI imports)
- `org.eclipse.fennec.emf.osgi.component.minimal` (ResourceSetFactory)

The `test.bndrun` of `org.eclipse.fennec.mcp.service.tools.tests` is a
resolvable, working reference for the exact closure.

## Troubleshooting

- **Tool does not appear**: check (1) `operations.allow` is non-empty and
  matches, (2) `clients.target` matches the client's service properties,
  (3) a `MCPToolProvider~…` instance with `tools.target=(tool.namespace=service-bridge)`
  exists and the server's `toolProviders.target` includes it.
- **Duplicate name skipped** (see the log): two operations sanitized to the
  same tool name — disambiguate via `tools.prefix`.
- **Execution fails with a codec error**: the metadata bridge is optional —
  make sure `org.eclipse.fennec.emf.osgi.metadata` is deployed so the imported
  packages can be announced to the `MetadataWhiteboard`.

## Dynamics (`notifications/tools/list_changed`)

Tool changes after server start are propagated live: the tool provider's tool
reference is dynamic and notifies the server (`MCPToolProvider.onToolsChanged`),
which diffs and calls `McpAsyncServer.addTool/removeTool` — connected MCP
clients receive `notifications/tools/list_changed` without reconnecting.
Creating or deleting a `ServiceClientToolBridge~…`/client config at runtime is
therefore immediately visible to agents.

## Security

Authentication against the remote service stays inside the `ServiceClient`
(the OpenAPI client handles apiKey/basic/bearer/clientCredentials); the bridge
never sees credentials. Endpoint authentication is the MCP server's concern
(see the security section in the [development guide](development-guide.md)).
