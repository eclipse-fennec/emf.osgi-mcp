# Publishing to the model atlas

`org.eclipse.fennec.mcp.model.atlas.tools` adds one MCP tool,
**`post_to_model_atlas`**, that hands a registered `EPackage` to a
[model.atlas](https://github.com/eclipse-fennec/model.atlas) stage.

It exists so that a metamodel an agent inferred in this session can *leave the
runtime* without its XMI ever passing through the LLM. The agent names a
namespace URI; the bundle serializes the package and posts it server-side, and
the agent gets back a receipt.

## What the agent controls, and what it does not

| | Who decides |
|---|---|
| which registered package is published | the agent (`nsURI`) |
| whether that namespace *may* be published | `publish.nsuri.allowlist`, deny-all by default |
| destination scope and stage | configuration |
| whether an existing entry is replaced | configuration (`overwrite`) |
| the serialized document | this bundle — never the agent |

There is deliberately no `scope`, `stage` or `overwrite` parameter. A 409 means
the namespace is taken and the answer is a *different namespace*, not a retry
with a flag flipped.

**Deploying the bundle is the authorization decision.** A runtime that does not
install it cannot publish, which is why the write path is a bundle-private
service rather than a write method on the widely consumed, read-only
`ModelAtlasClient`.

## The endpoint

The create-package call is
`POST {base.uri}/{scope}/schema/stages/{stage}?nsUri=…&name=…&overwrite=…`,
with the `.ecore` document as an `application/xmi` body. That is
`SchemaPackagesResource`, which is `@Path("/{scopeName}/schema")` with the
create method at `@Path("/stages/{stageName}")`; the server deserializes an
`EPackage` from the body and cross-checks it against `nsUri`.

The body is sent as a **string**, not as a live `EPackage` entity: the client
built by `DefaultJakartaRsClientProvider` registers no codec JAX-RS providers,
so an `EPackage` entity could not be written at all. Serializing in-process is
also the point — the bytes that leave the runtime are the bytes this bundle
produced.

`packages.path` exists only so the `schema` segment stays configurable if that
resource ever moves.

## Cross-package supertypes

`EcoreXmi` copies the package **and every package it references** in one
`EcoreUtil.Copier` pass, then puts each copy in a resource keyed by its own
namespace URI. A supertype from another package therefore leaves as
`<nsURI>#//<Name>`, which the atlas can resolve; without it the reference is
either dangling (a package from the session registry has no resource at all) or
a local file path. Foreign packages are **referenced, never inlined** — one
package goes over the wire.

This duplicates `emf.tools`' `Exports.toEcore` on purpose:
`org.eclipse.fennec.mcp.emf.tools.core` is a private package, and widening the
EMF tool bundle's contract so a second bundle can serialize is the wrong trade.

## Errors an agent can act on

No upstream response body ever reaches the agent — it goes to the server log,
where an operator can read it. What comes back instead says whether the agent
can do anything about it:

| upstream | what the agent is told |
|---|---|
| 201 / 200 | receipt: `created` / `updated`, plus nsURI, name, scope, stage, classifier count, byte size |
| 409 | the namespace is taken in that stage — publish under a free one |
| 403 | the existing entry is read-only |
| 400 | one `GET` on the stage path separates *"this runtime is configured for a stage the atlas does not have"* from *"the atlas rejected the package as invalid"* |
| 401 / 407 | credentials rejected; no tool parameter fixes it |
| 415 | the configured content type is a deployment mismatch |
| unreachable | nothing was published, and **stop retrying** |

## Configuration

Factory PID `ModelAtlasPublisher` (tilde notation, e.g.
`ModelAtlasPublisher~emfModel`). The connection half mirrors the read client's
`AtlasClientConfig` property names, so one deployment configures both the same
way; it is re-declared because
`org.eclipse.fennec.model.atlas.rest.client.osgi` exports no packages.

```json
"ModelAtlasPublisher~emfModel": {
    "base.uri": "http://localhost:8080/atlas/rest",
    "scope": "jena",
    "stage": "draft",
    "overwrite": false,
    "publish.nsuri.allowlist": [
        "https://eclipse.org/fennec/inference/*"
    ]
}
```

`publish.nsuri.allowlist` is empty by default, so an unconfigured deployment
publishes nothing. Rules are prefix-anchored on the **whole** URI (a trailing
`*`) or exact — never a substring match, so a rule for
`https://eclipse.org/fennec/inference/` cannot admit
`https://evil.example/…/inference/x`. Activation logs a warning when the list is
empty.

Keep `stage` a **draft** stage. Promotion to a released stage is a human
decision made in model.atlas, not something an MCP tool should reach.

## Wiring it into a runtime

`org.eclipse.fennec.mcp.emf.runtime.config` gives this bundle a tool provider of
its own, `MCPToolProvider~modelAtlas` (`name=model_atlas_tool_provider`), holding
the single tool. The `/mcp/emf` server aggregates all three providers:

```json
"toolProviders.target": "(|(name=emf_model_tool_provider)(name=emf_metadata_tool_provider)(name=model_atlas_tool_provider))",
"toolProviders.cardinality.minimum:int": 2
```

The minimum is **2, not 3**, and that is the whole reason this provider is
separate. When the bundle is absent its provider never activates, so an optional
provider counted in the minimum would keep the entire endpoint down. With one
combined provider the same fact could only be expressed as an off-by-one in a
38-tool `tools.cardinality.minimum` — true, but invisible.

Two things a deployment still has to do:

1. **Configure the publisher.** Without a `ModelAtlasPublisher` configuration
   the component never activates, the tool's mandatory reference stays
   unsatisfied, and the tool simply is not published — which is the correct
   behaviour, not a failure.
2. **Provide a JAX-RS client implementation.** The bundle imports
   `jakarta.ws.rs.client` and calls `ClientBuilder.newBuilder()`; it does not
   ship a provider. `launch.bndrun` for `emf.runtime` does not yet include the
   bundle for exactly this reason.

`server.instructions` in the runtime config is deliberately left unchanged: it
would otherwise advertise a tool to every runtime that does not install this
bundle.
