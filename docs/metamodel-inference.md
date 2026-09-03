# Metamodel inference

The inference feature is a second, **task-scoped** MCP endpoint — `/mcp/inference` —
for one job: take sample payloads of an unknown format, work out the Ecore
metamodel behind them, and hand that metamodel to a model atlas as a stored
schema. It is the only feature in this project whose bundles span **two
repositories**, and the only one that writes to a system outside the runtime.

If you are looking for the full 37-tool authoring surface, that is `/mcp/emf`
(see [emf-metamodel-authoring.md](emf-metamodel-authoring.md)). This document is
about standing the inference feature up and understanding what it will and will
not let an agent do.

## Why a separate endpoint

`/mcp/emf` exposes everything: instance manipulation, import/replay, operations,
generics, dataset management. That is right for a human-driven authoring session
and wrong for an autonomous inference run, where every extra tool is another way
to wander off task.

`MCPToolProvider~inference` therefore filters **21** tools out of the available
set:

| Source bundle | Count | Tools |
|---|---|---|
| `mcp.emf.tools` | 16 | `list_metamodel`, `describe_eclass`, `export_package`, `create_dataset`, `create_epackage`, `add_eclass`, `add_eattribute`, `add_ereference`, `add_eannotation`, `add_eenum`, `add_eenum_literal`, `modify_feature`, `create_from_json`, `export_dataset`, `register_package`, `list_registry` |
| `mcp.metadata.tools` | 4 | `list_annotation_sources`, `find_classes_by_annotation`, `find_features_by_annotation`, `describe_aspects` |
| `model.atlas.mcp.tools` | 1 | `post_to_model_atlas` |

Deliberately omitted: instance manipulation (`create_instance`,
`delete_instance`), dataset plumbing (`inspect_dataset`, `manage_dataset`,
`replay_recipe`), import/replay (`import_ecore`, `import_instances`), operations
and generics (`add_eoperation`, `add_eparameter`, `add_etypeparameter`),
`add_edatatype`, `unregister_package`, and the five broader discovery tools.

The intended flow is encoded in `server.instructions` on the server config:
**discover** the model family through its annotations, **author** the package in
one `create_epackage` call, **validate** it by building an instance from each
sample, then **register** and **publish**.

Neither the metamodel nor the instances have to travel back through the LLM to
be checked or handed over:

- `create_from_json` answers the validation question itself. Its `coverage`
  report names every key of the sample that matched no feature
  (`unmatchedPaths`) and every key whose feature stayed empty
  (`droppedPaths`); `complete: true` means the model covers the sample. The
  agent reads that instead of exporting the XMI and comparing by eye — which
  it did before, per sample, unreliably. `strict: true` turns unmatched keys
  into a refusal. See *Coverage of a JSON load* in the development guide.
- `register_package` takes a `datasetId` and a `packageObjectId`, and
  `post_to_model_atlas` takes only the `nsURI` of an already registered
  package. The `.ecore` is serialized and sent server-side; the agent never
  handles the document, and cannot choose the scope, the stage or whether an
  existing draft is replaced.

`export_dataset` and `export_package` remain in the set for when the agent
genuinely wants a serialization to read — not as the way to find out whether
what it built is correct.

## The two runtimes are alternatives, not layers

`emf.runtime` and `inference.runtime` are separate deployments and cannot both
be launched as-is:

- both config bundles bind Felix HTTP to `127.0.0.1:8099` (under different
  instance names, `emf` and `inference`), so the port collides;
- both define the **singleton** PIDs `EMFModelGuard` and `EMFPackageRegistry`.
  The Configurator does not merge singleton configurations — it applies one
  bundle's wholesale and discards the other, so the effective allow-list would
  depend on which bundle wins.

`configs/emf_base.json` is duplicated verbatim in both config bundles for this
reason, each runtime being self-contained. **Keep the two copies identical.**

## The bundle map

| Bundle | Repo | Contributes |
|---|---|---|
| `org.eclipse.fennec.mcp.inference.runtime` | emf.osgi-mcp | resolution anchor: provides `osgi.implementation=mcp.inference`, requires the whole closure |
| `org.eclipse.fennec.mcp.inference.config` | emf.osgi-mcp | `MCPToolProvider~inference`, `HttpMCPServerComponent~inference`, Felix HTTP `~inference`, and its copy of `EMFModelGuard` / `EMFPackageRegistry` |
| `org.eclipse.fennec.mcp.emf.tools` | emf.osgi-mcp | 16 of the 21 tools |
| `org.eclipse.fennec.mcp.metadata.tools` | emf.osgi-mcp | 4 of the 21 tools |
| `org.eclipse.fennec.model.atlas.mcp.tools` | **model.atlas** | `post_to_model_atlas` and the `ModelAtlasPublisher` service |
| `org.eclipse.fennec.model.atlas.mcp.config` | **model.atlas** | `MCPToolProvider~modelAtlas` and the `ModelAtlasPublisher~publisher` configuration |

Publishing configuration lives in the **model.atlas** project, not here. That is
not an accident of history: deploying `model.atlas.mcp.tools` *is* the
authorization decision to allow publishing at all, so the policy that governs it
belongs beside it. `inference.config` deliberately carries no
`ModelAtlasPublisher` configuration — `ModelAtlasPublisher` is a **factory** PID,
so a second configuration would activate a *second* publisher, and
`PostToModelAtlasTool`'s mandatory unary reference would bind an arbitrary one of
them, silently picking up the wrong scope, stage or allow-list.

## Turning the feature on

One line in a `bndrun`:

```
-runrequires: \
	osgi.implementation;filter:='(osgi.implementation=mcp.inference)'
```

Everything else follows from `MCPServerActivator`, which carries the closure as
manifest requirements:

```java
@Capability(namespace = IMPLEMENTATION_NAMESPACE, name = "mcp.inference", version = "1.0")
@Requirements({
    @Requirement(namespace = IDENTITY_NAMESPACE, name = "org.eclipse.fennec.mcp.emf.tools"),
    @Requirement(namespace = IDENTITY_NAMESPACE, name = "org.eclipse.fennec.mcp.metadata.tools"),
    @Requirement(namespace = IDENTITY_NAMESPACE, name = "org.eclipse.fennec.mcp.inference.runtime"),
    @Requirement(namespace = IDENTITY_NAMESPACE, name = "org.eclipse.fennec.mcp.inference.config"),
    @Requirement(namespace = IDENTITY_NAMESPACE, name = "org.eclipse.fennec.model.atlas.mcp.tools"),
    @Requirement(namespace = IDENTITY_NAMESPACE, name = "org.eclipse.fennec.model.atlas.mcp.config")
})
```

That is what makes `~inference`'s hard `tools.cardinality.minimum` of 21 safe:
the tools cannot be missing if the capability resolved. It also has a
consequence worth knowing in advance — **a renamed or missing bundle in that
list is reported against the capability, not against the bundle you asked for:**

```
⇒ osgi.implementation: osgi.implementation=mcp.inference cannot be resolved
        ⇒ Bundle: org.eclipse.fennec.model.atlas.mcp.config cannot be resolved
```

The nesting is the clue. If you did not name that bundle in `-runrequires`, the
requirement is coming from the anchor's compiled manifest, and the fix is in
`MCPServerActivator` — not in the `bndrun`.

The activator's own `MCPServer` reference is targeted at
`(server.name=osgi-emf-inference-mcp-server)`, because a runtime hosting more
than one endpoint publishes more than one `MCPServer` and binding an arbitrary
one would log readiness for the wrong endpoint.

## Configuration

### The endpoint

```json
"HttpMCPServerComponent~inference": {
    "server.name": "osgi-emf-inference-mcp-server",
    "osgi.http.whiteboard.servlet.pattern": "/mcp/inference",
    "osgi.http.whiteboard.target": "(org.apache.felix.http.name=inference)",
    "auth.token": "$[env:MCP_INFERENCE_AUTH_TOKEN;default=$[prop:MCP_INFERENCE_AUTH_TOKEN;default=]]",
    "toolProviders.target": "(name=inference_tool_provider)",
    "toolProviders.cardinality.minimum:int": 1
}
```

`/mcp/inference` has its **own** token, separate from `/mcp/emf`'s
`MCP_EMF_AUTH_TOKEN`: rotating one endpoint should not disturb the other, and
the two expose very different amounts of the runtime. Exposure rules are the
same as for every other endpoint — see [manual/02-security.md](manual/02-security.md).

### The publisher

Configured from `model.atlas.mcp.config`, every value interpolated so one bundle
serves every deployment:

| Property | Env / system property | Default |
|---|---|---|
| `base.uri` | `MODEL_ATLAS_BASE_URI` | none — **must** be set |
| `scope` | `MODEL_ATLAS_PUBLISHING_SCOPE` | none — **must** be set |
| `stage` | `MODEL_ATLAS_PUBLISHING_STAGE` | `draft` |
| `overwrite` | `MODEL_ATLAS_OVERWRITE` | `false` |
| `publish.nsuri.allowlist` | `MCP_ATLAS_PUBLISH_ALLOWLIST` | empty — denies everything |

Keep `stage` a **draft** stage. Promotion to a released stage is a human
decision made in model.atlas, not something an MCP tool should reach. Likewise
`overwrite` stays `false` so a 409 keeps its meaning: the namespace is taken, and
the answer is a free namespace rather than a retry with a flag flipped.

### How interpolation actually works

`$[env:…]` / `$[prop:…]` are **not** resolved by the Configurator. They are
resolved by `org.apache.felix.configadmin.plugin.interpolation` as Configuration
Admin delivers the configuration, which means a runtime must both deploy that
bundle and switch it on:

```
-runproperties: \
	felix.cm.config.plugins=org.apache.felix.configadmin.plugin.interpolation
```

Without it the placeholders arrive verbatim. That fails closed — a literal
`$[env:…]` string matches no real namespace URI — but it is a confusing way to
find out.

Four rules are worth knowing before editing these placeholders:

1. **`type` is mandatory for a list.** `publish.nsuri.allowlist` is a
   `String[]`, and only `type=String[];delimiter=…` produces one. A `delimiter`
   with no `type` is *silently ignored* and you get a single-element array
   holding the whole separated string.
2. **The placeholder must be the entire property value.** A non-String
   replacement is only returned as an object when the placeholder spans the
   whole value; with any surrounding text it is stringified instead, so you get
   one bogus rule reading `[a, b]`.
3. **The separator is `|`, not `,`.** bnd splits `-runvm` and `-runproperties`
   on commas, so a comma-separated allow-list arrives as a truncated list plus a
   stray JVM argument — the second namespace silently not publishable. Use `\|`
   for a literal pipe inside a rule.
4. **`prop:` reads framework properties**, falling back to JVM system
   properties, so `-runvm -D…` works. An exported environment variable always
   wins over a system property.

## The three allow-lists

This is the part most worth reading twice. Three independent lists gate three
different verbs, and they live on three different PIDs:

| PID | Property | Gates | Owned by |
|---|---|---|---|
| `EMFModelGuard` | `epackage.allowlist`, `eclass.allowlist` | which packages/classes the EMF tools may **see and touch** | `inference.config` (singleton) |
| `EMFPackageRegistry` | `nsuri.allowlist`, `nsuri.denylist` | which namespaces may be **registered**, i.e. become instantiable | `inference.config` (singleton) |
| `ModelAtlasPublisher~publisher` | `publish.nsuri.allowlist` | which namespaces may be **published** to the atlas | `model.atlas.mcp.config` (factory) |

All three are **deny-all by default**. All three use the same pattern language:
an exact URI, or a prefix ending in `*`. Matching is anchored on the whole URI
and is never a substring match, so a rule for
`https://eclipse.org/fennec/inference/` cannot admit
`https://evil.example/…/inference/x`.

Because the lists are independent, a namespace can be admitted by one and
refused by the next, and the symptom differs at each stage:

- not in `EMFModelGuard` → the authoring tools cannot see the package at all;
- not in `EMFPackageRegistry` → `register_package` refuses, so `post_to_model_atlas`
  has nothing to publish (registration is its precondition);
- not in `publish.nsuri.allowlist` → the package registers fine and publishing
  is refused.

A namespace has to appear in **all three** to travel the whole path. When you
add a new namespace to an inference deployment, edit all three — and remember
that the first two must be edited identically in *both* copies of
`emf_base.json`.

Reserved namespaces (Ecore, XMLType, GenModel) can never be registered or
shadowed regardless of configuration.

## Fail-closed behaviour

`ModelAtlasPublisher` validates its configuration at activation and refuses to
activate on a blank `base.uri`, `scope`, `stage`, `packages.path` or
`content.type`. This matters more than it first appears:

> A blank `base.uri` or `scope` does not merely disable publishing. The
> publisher does not activate, so `post_to_model_atlas` is never registered,
> so `MCPToolProvider~inference` sits at 20 of its required 21 tools, and
> **`/mcp/inference` does not come up at all.**

The reason to validate rather than tolerate is that an unset environment
variable interpolates to `""`, and a present-but-empty property *overrides* the
annotation default rather than falling back to it. A blank `stage` would
otherwise build `…/schema/stages/` with an empty segment and fail at publish
time as an upstream status no operator can trace back to a property.

An empty `publish.nsuri.allowlist` is different: it activates and logs a
warning, because "deployed but publishing nothing" is a legitimate state.

## Running it locally

```bash
cd org.eclipse.fennec.mcp.inference.runtime
cp secrets.bndrun.template secrets.bndrun    # then fill in the values
```

`secrets.bndrun` is gitignored. It supplies the endpoint token and the three
publisher values as system properties:

```
-runvm.secrets: \
	-DMCP_INFERENCE_AUTH_TOKEN=…,\
	-DMODEL_ATLAS_BASE_URI=http://localhost:8080/atlas/rest,\
	-DMODEL_ATLAS_PUBLISHING_SCOPE=jena,\
	-DMCP_ATLAS_PUBLISH_ALLOWLIST=https://example.org/a/*|https://example.org/b*
```

To confirm the value reached the JVM as **one** argument rather than being split
on a comma:

```bash
jcmd <pid> VM.system_properties | grep MCP_ATLAS_PUBLISH_ALLOWLIST
```

Note that this puts the allow-list on the JVM command line, where `ps` can read
it. That is acceptable for namespace patterns, which are policy rather than
secrets — but do not extend the pattern to the atlas bearer token. That is why
`PublisherConfig.auth_token_env()` *names* an environment variable and the token
is read per request instead of being held in configuration: rotating it needs no
reconfiguration, and it never enters a config store.

To resolve and launch, target the bndrun by name:

```bash
./gradlew :org.eclipse.fennec.mcp.inference.runtime:resolve.launch
./gradlew :org.eclipse.fennec.mcp.inference.runtime:run.launch
```

Use `resolve.launch`, **not** `resolve`: the bnd Gradle plugin globs every
`*.bndrun` in the project and will try to resolve `secrets.bndrun` standalone,
which fails because it is an include-fragment with no `-runfw` or
`-runrequires`. CI never hits this, `secrets.bndrun` being gitignored.

`model.atlas.mcp.tools` and `model.atlas.mcp.config` ship from the **model.atlas**
project and resolve from its published snapshot, declared in `cnf/central.mvn`
alongside every other external dependency — nothing has to be built by hand or
dropped into `cnf/local`. If you need to test an unreleased change to either
bundle, build it in model.atlas, drop the jar into `cnf/local`, re-index that
repository and re-resolve; remember to undo that once the change is published,
so the runtime stops resolving against a jar only your checkout has.

## Symptom to cause

| Symptom | Cause |
|---|---|
| `osgi.implementation=mcp.inference cannot be resolved` naming a bundle you never requested | that identity requirement is compiled into `MCPServerActivator`'s manifest; fix the annotation, rebuild, re-resolve |
| `/mcp/inference` never comes up, no obvious error | one of the 21 tools is missing — most often `post_to_model_atlas`, because the publisher refused to activate on a blank `base.uri` or `scope` |
| `resolve` fails on `secrets.bndrun` | use `resolve.launch` |
| Publishing refused: "namespace is not publishable" | `publish.nsuri.allowlist` — check the pipe separator survived bnd's comma splitting (`jcmd <pid> VM.system_properties \| grep MCP_ATLAS`) |
| Publishing refused: "no package is registered under …" | `register_package` has not accepted it; check `EMFPackageRegistry.nsuri.allowlist` |
| Only the first namespace of the allow-list works | the value was comma-separated, or quoted around the value instead of around the whole `-D` argument, leaving literal `'` in the outer rules |
| Publishing returns 409 | the namespace is taken in that stage and `overwrite` is `false`. Publish under a free namespace; do not retry |
| Publisher activated but nothing is publishable, with a warning in the log | `publish.nsuri.allowlist` resolved empty — the environment variable is unset |
| Two publishers, wrong scope or stage used | a second `ModelAtlasPublisher~…` configuration exists; only `model.atlas.mcp.config` may own that PID |
