# Plan & Requirements — EMF Model Tools (`org.eclipse.fennec.mcp.emf.tools`)

> Status: **IMPLEMENTED** (v1, 2026-06-12) — module `org.eclipse.fennec.mcp.emf.tools`.
> Design decisions in §13, security findings in §14. Config PIDs in the implementation:
> `EMFModelGuard` (allow-lists), `EMFDatasetRegistry` (caps) — see §9 note.
> Owner: tbd · Created: 2026-06-12

## 1. Goal

Provide a set of MCP tools that let an LLM agent **create EMF model instances**
(`EObject`s) for any registered `EClass`/`EPackage`, **set individual structural
features** incrementally, **validate** against the metamodel, and get the result
back as **XMI** (and optionally JSON).

### Why (vs. data faker)

We already generate test/sample data with a data faker. Faker is strong at
*volume* and statistical spread, but weak at expressing *semantic and relational
constraints*. A natural-language prompt expresses those far better:

> "An invoice whose net + VAT equals gross, dated in Q3, customer is a returning
> premium customer with at least 3 prior orders."

Such conditions are awkward in faker config but trivial in a prompt. The intended
sweet spot is therefore **constrained seeds / golden fixtures / edge cases**, not
bulk generation. A good hybrid: the agent produces a small set of constrained
instances (or a template), and the faker scales the volume around them.

**Non-goal:** replacing the faker for high-volume generation (too slow / costly /
non-deterministic for thousands of rows).

## 1a. Security model — security-by-default (cross-cutting)

The system follows **security-by-default**: nothing is exposed or instantiable
unless explicitly allow-listed. This is a hard requirement, not a convenience
filter, and it is enforced at **two levels**:

- **EPackage allow-list** — `epackage.allowlist` (list of nsURIs). **Default:
  deny-all** (empty/absent ⇒ no package is visible or usable). Ecore and internal
  packages are *never* implicitly included.
- **EClass allow-list** — `eclass.allowlist` (list of EClass identifiers, e.g.
  `<nsURI>#//<ClassName>`). **Default: deny-all** (empty/absent ⇒ no class is
  instantiable, even within an allow-listed package).

**Effective rule:** an `EClass` may be discovered and instantiated **only if its
EPackage is allow-listed AND the EClass itself is allow-listed.** Both checks must
pass; neither defaults to permissive.

**Enforcement points (every tool that names a package/class re-checks):**
- `list_epackages` → only allow-listed packages.
- `list_eclasses` / `describe_eclass` → only allow-listed classes in allow-listed packages.
- `create_instance` / `create_from_json` → reject (MCP `isError`) any non-allow-listed `EClass`.
- `set_feature` / `add_to_feature` with a containment sub-object of a new type →
  the sub-type must also be allow-listed.
- `replay_recipe` → re-validates **every** operation against the *current*
  allow-list; a recipe cannot bypass the policy (a class denied now is rejected
  even if it was allowed when the recipe was recorded).

**The allow-list is a code-trust boundary, not just data scoping (see §14.3):**
allow-listing an `EPackage` means its generated `EFactory` and datatype-conversion
code runs **in-process**. The allow-list is therefore strictly **admin-owned**
configuration and must never be influenced by an agent/prompt.

**Resource caps (see §14.4):** every builder path enforces hard limits — max
objects per dataset, max graph depth, max collection size, max recipe length, max
JSON payload size — plus a **global** memory cap across all sessions.

> Convenience option (deferred decision, §13.5): allow a wildcard entry in
> `eclass.allowlist` (e.g. `<nsURI>#//*`) to permit all classes of an explicitly
> allow-listed package — while keeping the global default deny-all. Off unless
> requested.

## 2. Module

| | |
|---|---|
| Bundle symbolic name | `org.eclipse.fennec.mcp.emf.tools` |
| Placement | top-level module dir (peer of `org.eclipse.fennec.mcp.gogo.tools`) |
| Layout | `src/` for code, `test/` for tests, only a `bnd.bnd` (bnd-driven, no per-module `build.gradle`) |
| Workingset | `emf` |

### `bnd.bnd` (draft)

```properties
Bundle-Name: Eclipse Fennec EMF Model MCP Tools
Bundle-Description: MCP tools to create, populate, validate and serialize EMF model instances

-workingset: emf
-library: enableEMF

-buildpath: \
	io.modelcontextprotocol.sdk.mcp-core;version=latest,\
	io.projectreactor.reactor-core;version=latest,\
	reactive-streams;version=latest,\
	org.eclipse.emf.common;version=latest,\
	org.eclipse.emf.ecore;version=latest,\
	org.eclipse.emf.ecore.xmi;version=latest,\
	org.eclipse.fennec.codec.api;version=latest,\
	org.eclipse.fennec.model.metadata;version=latest,\
	org.eclipse.fennec.mcp.api;version=snapshot
```

EMF runtime (`emf.common/ecore/ecore.xmi`), the codec, and
`org.eclipse.fennec.emf.osgi.component.minimal` (ResourceSet + EPackage registry)
are already declared in `org.eclipse.fennec.mcp.workspace.library/required.bndrun`.

## 3. Architecture — fits the existing whiteboard

Same pattern as the gogo tools, no framework changes needed:

```
EMF MCPTool services ──(whiteboard, LDAP tools.target)──> MCPToolProvider ──> HttpMCPServerComponent (servlet, e.g. /mcp/emf)
```

Each tool is a DS `@Component(service = MCPTool.class, property="tool.name=...")`,
discovered by an `MCPToolProvider` instance selected via Configurator JSON.

### Shared services injected into the tools

- `ResourceSetFactory` (gecko/Fennec EMF OSGi) — to create a working `ResourceSet`
  with the OSGi EPackage registry already wired in.
- The OSGi-registered `EPackage` services — for discovery (`list_epackages`).

Reuse from `org.eclipse.fennec.mcp.api`:
- `AbstractMCPTool.loadSchema(eClassUri, resourceSet)` → JSON Schema of an `EClass`
  (for `describe_eclass`).
- `AbstractMCPTool.saveEObjectToString(eObject, resourceSet)` → JSON output.
- `StructuredOutputStorageHelper.loadEObject(eClass|uri, map, resourceSet)` →
  JSON-map → `EObject` (powers the declarative single-shot path).

## 4. State model — Datasets as the unit of work

Modifying `EObject`s durably across multiple tool calls requires the objects to
**survive between calls** (the `MCPToolProvider` enforces a ~1 min per-call
timeout, so state cannot live inside one `execute()`). A single prompt also
typically produces **many** instances (test-data generation), and that whole set
must persist and stay modifiable. The unit of state is therefore a **Dataset**,
not a loose object — even a single object is just a one-instance Dataset.

```
DatasetRegistry  (session-scoped — keyed by McpAsyncServerExchange.sessionId())
  └── Dataset (datasetId)
        ├── ResourceSet           one per dataset → intra-dataset cross-refs resolve
        ├── objectId → EObject    the object cache
        ├── recipe                ordered operation log (§4a)
        └── metadata              seed, created-at, allowed-class context
```

### Lifecycle & identity (decided — §13)

- **Session-scoped** (§13.7): the `DatasetRegistry` is keyed off
  **`McpAsyncServerExchange.sessionId()`** (stable per-session `String`, mcp-core
  1.1.1 ✅). Datasets are auto-cleaned on disconnect; no cross-client visibility
  (aligns with security-by-default). Cross-session/persisted datasets are deferred.
- **Composite object id** (§13.8): an object's full handle is
  **`datasetId` + `objectId`** (e.g. `<datasetId>/<objectId>`). We always generate
  `objectId` ourselves because we cannot assume the `EClass` has an ID feature.
  Namespacing per dataset keeps refs unambiguous and maps onto XMI fragments.
- TTL + max-datasets/-objects eviction to bound abandoned sessions.
- Thread-safe (`ConcurrentHashMap`) — reactive execution is multi-threaded.

### Mutation modes (decided — both, §13.9)

1. **In-place modify** — `set_feature`/`unset_feature`/`add_to_feature`/
   `remove_from_feature` on existing objects; `create_instance`/`delete_instance`
   to grow/shrink the dataset.
2. **Regenerate (clear + rebuild)** — `regenerate_dataset` deterministically
   **replays the recipe** (§4a) with the same or a new `seed`, rather than
   re-prompting the LLM. Reproducibility and incremental edit from one mechanism.

On top of this we also keep a **declarative single-shot** path (almost free via the
codec): one call with a full JSON graph → a dataset → XMI, for simple cases.

## 4a. Reproducibility / seed

LLM output itself is non-deterministic, so reproducibility is **not** achieved by
re-prompting. Instead it is anchored in two deterministic artifacts:

1. **Build recipe (replayable operation log).** Each **dataset** records every
   mutating call as an ordered, serializable recipe (a small JSON: list of
   `{op, objectId, eClassUri, feature, value}`). The recipe can be:
   - returned to the agent (`get_recipe`) / persisted alongside the XMI, and
   - **replayed** — `regenerate_dataset` (rebuild the same dataset in place) or the
     stateless `replay_recipe` (build a fresh dataset) — to regenerate
     byte-identical XMI without involving the LLM again.

   The recipe is the reproducible seed — the model build, not the prose.

2. **Seed for randomized fills.** Any tool that introduces randomness (e.g. a
   future faker-backed `fill_remaining` helper that completes unset features)
   accepts an explicit `seed` parameter, so the same recipe + same seed yields the
   same data. Pure `set_feature`/`create_from_json` are already deterministic.

This also enables the intended hybrid with the faker: the agent defines the
*constrained* part as a recipe; a seeded faller fills the rest reproducibly.

## 5. Tool catalogue

**Consolidated to 11 tools** (down from a flat 18) to keep the MCP surface and the
agent's context lean. Mutations return only lightweight acks — never serialized
payload (§7a).

### Discovery (read-only)

| Tool | Input | Output | EMF API |
|---|---|---|---|
| `list_metamodel` | `{nsURI?}` | no `nsURI` ⇒ allow-listed EPackages `{nsURI, name}`; with `nsURI` ⇒ its allow-listed concrete EClasses | `EPackage` services / `getEClassifiers()` |
| `describe_eclass` | `{eClassUri}` | JSON Schema + feature table (name, type, lower/upper bound, required, many, containment vs. ref, enum literals, default) | `loadSchema(uri, rs)` + `getEAllStructuralFeatures()` |

### Dataset

| Tool | Input | Behavior |
|---|---|---|
| `create_dataset` | `{seed?}` | empty dataset → `{datasetId}` |
| `inspect_dataset` | `{datasetId?, includeRecipe?}` | no id ⇒ list datasets (id, #objects, types); with id ⇒ objects (`objectId`, eClass) + per-object **validity summary** + optional recipe (cheap, no payload) |
| `manage_dataset` | `{datasetId, action: regenerate\|clear\|delete, seed?}` | `regenerate` = deterministic recipe replay (§4a); `clear`; `delete` |
| `export_dataset` | `{datasetId, format: xmi\|json, validate?}` | serialize roots (§7); inline if small, else MCP-Resource handle (§7a); optional full `Diagnostician` report |

### Instance build / modify (within a dataset)

| Tool | Input | Behavior |
|---|---|---|
| `create_instance` | `{datasetId, eClassUri}` | `EFactory.create` (allow-list checked) → `{objectId}` |
| `modify_feature` | `{datasetId, objectId, feature, action: set\|unset\|add\|remove, value?, index?}` | one tool: `set` (single-valued), `unset`, `add`/`remove` (many-valued); coerce/resolve §6 |
| `delete_instance` | `{datasetId, objectId}` | remove an object from the dataset |

### Declarative (stateless one-shot)

| Tool | Input | Behavior |
|---|---|---|
| `create_from_json` | `{eClassUri, data: {...}, seed?, format: xmi\|json}` | `StructuredOutputStorageHelper.loadEObject` → new dataset → validate → serialize (§7a) |
| `replay_recipe` | `{recipe: [...], seed?, format: xmi\|json}` | deterministically replay a recipe into a fresh dataset → serialize, no LLM (§4a) |

> **Consolidation applied:** `list_epackages`+`list_eclasses` → `list_metamodel`;
> `list/describe_dataset`+`get_recipe` → `inspect_dataset`;
> `regenerate/clear/delete_dataset` → `manage_dataset {action}`;
> `set/unset/add/remove_feature` → `modify_feature {action}`;
> `validate_dataset` folded into `inspect_dataset` (summary) + `export_dataset {validate}`.

## 6. Type coercion & reference resolution

- **EAttribute** — incoming JSON value is coerced to the feature's `EDataType` via
  `EcoreUtil.createFromString(eDataType, literal)` (handles enums, dates, numbers,
  booleans, custom datatypes). Reject with a clear error if conversion fails.
- **EReference (containment)** — accept either an inline JSON sub-object (build it
  recursively and register it too) or an existing `childObjectId`.
- **EReference (cross-ref)** — **v1: resolve only by `objectId`** within the same
  dataset's registry (§13.3). Cross-*dataset* refs and model-level ID/URI
  resolution are deferred to a later iteration.
- **Multiplicity** — enforce `many` (list) vs. single; honor `upperBound`.
- Errors are returned as MCP `isError(true)` results with a human-readable message
  so the agent can self-correct.

## 7. Serialization

- **XMI** — `serialize_dataset` puts **all root (non-contained) objects of the
  dataset** into one `XMIResourceImpl` (`<datasetId>.xmi` in the dataset's
  `ResourceSet`), `resource.save(out, options)`, return the UTF-8 string.
  Cross-refs between objects of the same dataset resolve as intra-document
  fragments (the composite `objectId`s). **Decision (§13.4):** cross-*dataset*
  refs (deferred anyway, §6) would become `href`s; bundling other datasets is out.
- **JSON** — reuse `AbstractMCPTool.saveEObjectToString` (Fennec codec).
- Encoding UTF-8; pretty-printed for agent readability.

## 7a. Payload return & size limits

MCP tool results are text content, so a serialized model is returned as a **string
inside the JSON result**. The real exhaustion risk is not the wire but the
**agent's context** (a few hundred KB of XMI is tens of thousands of tokens) and
the tool-side heap / 1-min call timeout. Strategy:

- **Mutations never return payload.** `create_instance`, `modify_feature`,
  `delete_instance`, `manage_dataset` return only lightweight acks (ids, counts,
  validity flag). Serialization is *explicit* via `export_dataset` only. This
  removes the bulk of the pressure by construction.
- **Inline only below a cap.** `export.maxInlineBytes` (config, default ~64 KB).
  At/under the cap, `export_dataset` returns the XMI/JSON string directly.
- **Above the cap → MCP-Resource handle (decided, §13.10).** The serialization is
  registered as an MCP **Resource** via the existing `MCPResourceProvider` API
  under a stable URI (e.g. `mcp://emf/dataset/<datasetId>.xmi`). `export_dataset`
  then returns a small descriptor — `{resourceUri, byteSize, rootCount, eClassCounts}`
  — and the client/agent reads it **out-of-band** via `resources/read` (with range
  support), so the blob never lands in the tool result or the agent's context
  unless explicitly fetched.
- **Server-side guards.** Bound dataset size via the registry eviction (§4); cap
  total serialized bytes to fail fast instead of OOM; for over-cap exports, stream
  to the resource rather than holding a giant in-memory string. Cap the number of
  `Diagnostician` findings returned inline and summarize the remainder.
- **Paging deferred.** Per-root paging of `export_dataset` is intentionally *not*
  in v1 (the resource handle covers large payloads); revisit only if needed.

## 8. Validation

- `org.eclipse.emf.ecore.util.Diagnostician.INSTANCE.validate(eObject)`.
- Map the `Diagnostic` tree to a structured result: `severity`, `message`,
  offending feature/object, child diagnostics.
- Used both as its own tool and automatically before serialization in
  `create_from_json` (return validation errors instead of invalid XMI).

## 9. Configuration (Configurator JSON)

**Decision (§13.2): a dedicated `/mcp/emf` MCP server instance** (own provider +
own `HttpMCPServerComponent`), separate from gogo. Reuses the same HTTP port 8088.

**As implemented** (see `gogo.runtime.config/configs/configuration.json`): the
allow-lists live on their own admin-owned PID `EMFModelGuard` (a dedicated guard
component referenced by every tool — the generic `MCPToolProvider` stays
EMF-agnostic), and the resource caps live on `EMFDatasetRegistry`:

```json
"EMFModelGuard": {
  "epackage.allowlist": ["<nsURI-1>"],
  "eclass.allowlist": ["<nsURI-1>#//ClassA", "<nsURI-1>#//ClassB"]
},
"EMFDatasetRegistry": {
  "max.datasets.per.session:int": 16,
  "max.objects.per.dataset:int": 10000,
  "max.inline.export.bytes:int": 65536
},
"MCPToolProvider~emfModel": {
  "name": "emf_model_tool_provider",
  "tools.target": "(|(tool.name=list_metamodel)(tool.name=describe_eclass)(tool.name=create_dataset)(tool.name=inspect_dataset)(tool.name=manage_dataset)(tool.name=export_dataset)(tool.name=create_instance)(tool.name=modify_feature)(tool.name=delete_instance)(tool.name=create_from_json)(tool.name=replay_recipe))",
  "tools.cardinality.minimum:int": 11
},
"HttpMCPServerComponent~emfModel": {
  "server.name": "osgi-emf-mcp-server",
  "osgi.http.whiteboard.servlet.pattern": "/mcp/emf",
  "osgi.http.whiteboard.target": "(org.apache.felix.http.name=gogo)",
  "has.tool.capability": true,
  "toolProviders.target": "(name=emf_model_tool_provider)",
  "toolProviders.cardinality.minimum:int": 1
}
```

**Allow-lists (§1a, §13.5) — security-by-default / deny-all:** `EMFModelGuard`
holds `epackage.allowlist` (nsURIs) **and** `eclass.allowlist` (EClass
identifiers). Both default to **deny-all**: an empty/absent list exposes nothing.
A class is only usable when its package *and* the class itself are listed. Ecore
and other framework packages are never implicitly included. The guard component
uses `configurationPolicy = REQUIRE` — without an admin-provided configuration
the EMF tools do not even activate.

> Note: the `org.apache.felix.http.name=gogo` target reuses the existing HTTP
> instance on port 8088 — only the servlet pattern (`/mcp/emf`) differs. No new
> `org.apache.felix.http~emf` instance is needed unless we later want a separate port.

## 10. Testing

- **Unit (JUnit 5 + Mockito + AssertJ):** coercion of each `EDataType` kind,
  reference resolution, validation report mapping, XMI output shape. Use a small
  in-test Ecore (dynamic `EPackage`) so no codegen is needed.
- **Security (deny-all):** empty allow-lists ⇒ discovery returns nothing and
  `create_*` is rejected; package-listed-but-class-not ⇒ rejected; both listed ⇒
  allowed; `replay_recipe` of a now-denied class ⇒ rejected. (§1a is the spec.)
- **Reproducibility:** a recorded recipe replayed via `regenerate_dataset` /
  `replay_recipe` (same seed) yields byte-identical XMI; in-place modify then
  re-serialize reflects the change.
- **OSGi integration:** DS wiring + ResourceSet injection + a full dataset
  round-trip (create_dataset → create_instance → set → add → validate → serialize)
  over the registered EPackages, incl. session-scoped cleanup on disconnect.
- Follow EPL-2.0 header + explicit-imports conventions (license CI check).

## 11. Phased implementation

1. **Phase 1 — Discovery (read-only).** `list_epackages`, `list_eclasses`,
   `describe_eclass`. Lowest risk, immediately useful, no session state.
2. **Phase 2 — Declarative single-shot.** `create_from_json` (→ a dataset) +
   `export_dataset` (with `validate`). Reuses the codec; proves end-to-end value
   fast and introduces the Dataset abstraction + payload handling (§7a) minimally.
3. **Phase 3 — Stateful datasets + reproducibility.** Session-scoped
   `DatasetRegistry`/`Dataset` (composite ids, operation log), `create_dataset` /
   `inspect_dataset` / `manage_dataset` + instance ops (`create_instance` /
   `modify_feature` / `delete_instance`) + `replay_recipe`. The main new
   architecture piece (session lifecycle + composite identity + recipe replay).
4. **Phase 4 — Wiring & docs.** Configurator JSON (dedicated `/mcp/emf`,
   non-SSE responses, `epackage.allowlist`), README/dev-guide section,
   integration tests.

> **Security is not a separate phase.** The §14 mitigations are built into each
> phase as it lands: 14.1/14.3 + deny-all in Phase 1 (discovery/allow-list),
> 14.2/14.8 in Phase 2 (codec input + no payload logging), 14.4/14.5/14.6 in
> Phase 3 (caps, quotas, ownership-checked session/resource ids).

## 12. Out of scope (for now)

- Bulk generation (delegated to the faker).
- Persisting models anywhere other than returning them to the agent.
- Modifying/loading *existing* model files (could be a later "edit" toolset).
- Dynamic metamodel editing (creating EClasses/EPackages).

## 13. Resolved decisions (2026-06-12)

1. **Session key** ✅ — `McpAsyncServerExchange.sessionId()` (stable per-session
   `String`, mcp-core 1.1.1). Keys `ModelBuildSession`.
2. **Transport** ✅ — **dedicated `/mcp/emf` MCP server** (own provider + own
   `HttpMCPServerComponent`), reusing HTTP port 8088. See §9.
3. **Cross-ref resolution** ✅ — **v1: only by session `instanceId`.** Model-ID
   resolution deferred. See §6.
4. **Multi-root XMI** ✅ — serialize the addressed instance's containment tree;
   refs to other roots become `href`s. Bundling deferred. See §7.
5. **Scope / security** ✅ — **security-by-default, deny-all** at two levels:
   `epackage.allowlist` (nsURIs) **and** `eclass.allowlist` (EClass ids), both
   default-empty ⇒ nothing exposed. A class needs *both* its package and itself
   allow-listed. Enforced at every tool. See §1a, §9. (EClass wildcard per package
   = deferred convenience option.)
6. **Reproducibility / seed** ✅ — **yes, required.** Anchored in a per-dataset
   replayable build recipe (`get_recipe` / `regenerate_dataset` / `replay_recipe`)
   + a `seed` param for any randomized fill. See §4a.
7. **Dataset lifecycle** ✅ — **session-scoped** `DatasetRegistry` keyed by
   `sessionId`; auto-clean on disconnect, no cross-client visibility. Cross-session
   / persisted datasets deferred. See §4.
8. **Object identity** ✅ — **composite `datasetId` + `objectId`**, `objectId`
   always self-generated (no assumption of an ID feature). See §4.
9. **Mutation modes** ✅ — **both**: in-place modify (set/unset/add/remove/
   delete) *and* `manage_dataset {action:regenerate}` via deterministic recipe
   replay. See §4.
10. **Large-payload return** ✅ — **MCP-Resource handle**: inline below
    `export.maxInlineBytes` (~64 KB), otherwise serialize to an MCP Resource
    (`MCPResourceProvider`) and return a `{resourceUri, byteSize, …}` descriptor;
    client reads out-of-band. Mutations never return payload. Paging deferred.
    See §7a. *Impl note:* the `/mcp/emf` server must advertise the **resource
    capability** (alongside `has.tool.capability`) so `resources/read` works.

## 14. Security considerations

### Deployment context

The live server runs **behind an APISIX gateway** that provides **authentication**,
**TLS termination**, and **request rate-limiting**. The transport is plain
**Streamable HTTP — no SSE**: every tool call is a discrete HTTP POST that APISIX
sees and can rate-limit individually (session continuity via the `Mcp-Session-Id`
header). The server must be configured for non-SSE (`application/json`) responses.

APISIX covers the **perimeter** only. The risks below are EMF-/OSGi-/MCP-specific
and **must be handled inside the tool** — the gateway cannot see them.

### Findings & required mitigations

| # | Finding | Severity | Mitigation (requirement) |
|---|---|---|---|
| 14.1 | **EMF URI resolution → SSRF + arbitrary file read.** `ResourceSet` can dereference `file:`, `http:` (internal SSRF behind the gateway), `platform:` URIs from agent-supplied values (`eClassUri`, refs). | High | On-demand loading **off** (`getEObject(uri, false)`); resolve EClasses **only** from the package registry/allow-list; sandboxed `URIConverter` with a scheme whitelist (in-memory only); no external resource loading. |
| 14.2 | **XXE / entity expansion on XMI input.** Output-only today (safe), but any future XMI/XML *import* path is exposed. | Med (future) | Policy now: any XML/XMI parser configured with DTDs + external entities **disabled**; bounded entity expansion. |
| 14.3 | **Allow-list = code-execution trust.** `EFactory.create` / `EcoreUtil.createFromString` run a package's generated factory/datatype code in-process. | High | Allow-list is **admin-only** config, never agent/prompt-influenced (§1a). Treat allow-listing a package as trusting its code. |
| 14.4 | **Single-request DoS** that rate-limiting can't catch — one huge `create_from_json`/`replay_recipe` builds millions of objects → OOM in one request. | High | Hard caps: max objects/dataset, max graph depth, max collection size, max recipe length, max JSON payload; **global** memory cap across all sessions; fail fast over cap. |
| 14.5 | **MCP rate-limit gap.** (Largely mitigated by no-SSE: each tool call = one POST APISIX rate-limits.) Residual: a single call can still be expensive (→ 14.4). | Low (was Med) | App-level per-session quota (operations/session, datasets/session) as defense-in-depth; rely on 14.4 caps for per-call cost. |
| 14.6 | **IDOR on datasets & resource handles.** Guessable/spoofable `sessionId`/`datasetId` → cross-principal read/modify; `mcp://emf/dataset/<id>.xmi` exposed. | High | Server-generated, unguessable ids; `resources/read` **ownership check**; bind dataset/resource access to the APISIX-authenticated principal (auth header → principal), not just the MCP session; deregister resources on session end. |
| 14.7 | **Authorization granularity.** Allow-list is global per server, not per principal — multi-tenant gap. | Med | Documented limitation for v1; per-principal authz (principal → allowed metamodels) deferred but design leaves room (principal already available from APISIX header). |
| 14.8 | **Data sensitivity & leaks.** In-memory data, recipes, XMI may carry sensitive generated values. | Med | **No payload logging** (remove the commented `System.out.println` pattern in `AbstractMCPTool.loadSchema`); sanitize errors returned to the agent (no raw `getMessage()`/stack traces — cf. the gogo tool); in-memory only (persistence is out of scope, §12). |
| 14.9 | **Blast radius — shared OSGi runtime with the Gogo shell server** (which can run shell/manage bundles). Magnifies 14.3. | Med | Dedicated provider + endpoint already separates the tools; consider a **dedicated HTTP instance/port** for `/mcp/emf` with its own APISIX route + stricter policy; least-privilege for the bundle. |

### Input validation (cross-cutting)

- Validate `eClassUri` / `feature` strictly against the metamodel; reject unknowns.
- `datasetId`/`objectId` are **server-generated** only — never accept them as the
  basis for resource-URI construction from the agent (prevents path traversal in
  `resourceUri`).
- Coercion failures (`createFromString`) return clean, non-leaking errors.
