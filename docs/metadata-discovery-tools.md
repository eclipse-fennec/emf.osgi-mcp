# Metadata discovery tools

`org.eclipse.fennec.mcp.metadata.tools` exposes the Fennec EMF **metadata
layer** (`org.eclipse.fennec.emf.osgi.metadata`) as MCP tools, so an agent can
work out *which* model a payload belongs to instead of being told.

The EMF authoring tools in `org.eclipse.fennec.mcp.emf.tools` all need an exact
name up front — `list_metamodel(nsURI)`, `describe_eclass(<nsURI>#//<Name>)`,
`list_registry()`. That is fine once you know what you are looking at, and
useless when you do not. These nine tools are thin wrappers over
`MetadataIndexReader` and `MetadataRegistry`: **no new indexing**, and no
codec dependency.

## The tools

| `tool.name` | Inputs | What it answers |
|---|---|---|
| `list_annotation_sources` | `nsURI?` | which EAnnotation sources exist here, with their detail keys, hit counts and namespaces |
| `find_classes_by_annotation` | `annotationSource`, `key`, `value?` | every EClass carrying that detail, across all packages |
| `find_features_by_annotation` | `annotationSource`, `key`, `value?` | the same for EAttributes and EReferences |
| `find_operations_by_annotation` | `annotationSource`, `key`, `value?` | the same for EOperations |
| `find_class_by_name` | `className`, `nsURI?` | a bare class name resolved to `<nsURI>#//<Name>` |
| `list_aspects` | — | which aspect type ids are present, and on which element kinds |
| `describe_aspects` | `element`, `aspectTypeId?` | one element's parsed aspect entries, with their **diagnostics** |
| `describe_package_metadata` | `nsURI` | one package: classes, fingerprint, origin, properties, all registered versions |
| `describe_metadata_status` | — | wiring diagnostics: index bound? how many packages? which namespaces? |

### Omitting `value` means "any value"

The three annotation queries take `value` as **optional**, and omitting it
matches *any* value the key happens to carry. That is the whole point:

- `find_classes_by_annotation(source, "typeDiscriminatorPath")` with no value
  returns the abstract class that declares a family's discriminator path —
  a class `list_metamodel` filters out (it lists only concrete classes) and
  `describe_eclass` refuses (`requireAllowedEClass` rejects abstract). This
  tool is the only route to it.
- `find_classes_by_annotation(source, "typeDiscriminator")` with no value
  returns every sibling and the value each one claims; with a value, it
  returns the one that claims it — so **an empty result is the proof that a
  discriminator value is still free**.

Each hit echoes back the annotation that matched (`matched.value`), which is
what makes a wildcard query readable in its own output.

## Cold start, end to end

Given a payload and nothing else:

1. `list_annotation_sources` → the vocabularies of this runtime, e.g. source
   `http://eclipse.org/fennec/codec/typeMapping/lorawan` with keys
   `typeDiscriminatorPath` / `typeDiscriminator`. **No string had to be
   guessed.** A wrong annotation source matches nothing *without an error*, so
   this step is worth its call.
2. `find_classes_by_annotation(source, "typeDiscriminatorPath")` → the family
   parent `…/lorawan#//UplinkMessage` and its discriminator path.
3. Resolve that path against the payload → the discriminator value.
4. `find_classes_by_annotation(source, "typeDiscriminator")`, then with the
   value → the siblings, and whether the value is taken.
5. `describe_aspects(<sibling>, "codec")` → how a sibling is configured:
   `mapId`, `discriminatorValue`, `inheritFromParent`, strictness flags — plus
   any `diagnostics`, which is where a provider reports that an aspect
   **failed to build** (a `typeMapping` annotation on the wrong kind of
   element, say). It is reported nowhere else.
6. `export_package(<nsURI>)` in `emf.tools` → the family's full `.ecore`: the
   exact annotation spelling and structure to copy. That is the "read narrow"
   half; these tools were the "query wide" half that found it.
7. From here the `emf.tools` authoring flow takes over: `create_epackage` →
   `add_eclass(eSuperTypes=…#//UplinkMessage)` → … → `register_package`.

Class hits carry `eSuperTypes` as full `<nsURI>#//<Name>` references on
purpose: `describe_eclass` reports supertypes as bare names, from which the
`add_eclass` argument cannot be built.

## Trying it

`org.eclipse.fennec.mcp.test.component` publishes the LoRaWAN fixture models as
OSGi `EPackage` services, which is what makes a blind run possible: the session
starts empty and the agent was told nothing. It is in `launch.bndrun`'s
`-runrequires` for local runs — leave it out of a production runtime.

The two models are deliberately **split across files** (`data/lorawan.ecore` and
`data/em310udl.ecore`), because that is the shape a real distributed
registration has: the family parent in one bundle, device-specific extensions in
others. `em310udl.ecore` names its supertype as
`https://eclipse.org/fennec/lorawan#//UplinkMessage`, and the publisher re-keys
each resource to its namespace URI after loading so that reference resolves with
no I/O. An unresolved proxy fails activation rather than leaving a class that
silently has no supertype.

## What the tools see

Two populations arrive through one API and are **not** separated:

- **OSGi services** — every `EPackage` registered as a service reaches
  `MetadataServiceComponent.addEPackage(EPackage, Map)`.
- **MCP sessions** — `PackageRegistry` (in `emf.tools`) announces every
  session registration to the `MetadataWhiteboard`, so a package the agent
  imported with `import_ecore` shows up here too.

Every hit is marked with an `origin` of `osgi-service` or `session`, derived
from whether the registration carried a `service.id` property. Nothing is
filtered out silently.

What the metadata layer holds is a **frozen copy** (`PackageRegistry.register`
does `EcoreUtil.copy`), so the `EClass` instances reachable here are not the
ones in an authoring dataset. These tools therefore return references, never
object identity.

Registration is keyed by model *version*, not by namespace, so one nsURI can
hold several versions at once. The query tools de-duplicate on the rendered
reference; `describe_package_metadata` is what tells versions apart.

## Aspects are generic

An `AspectEntry` is a type id plus an arbitrary EMF payload contributed by
whichever `MetadataHandler` is deployed — today typically `codec`, more as
providers are added. `describe_aspects` renders content by walking the
payload's `EClass` reflectively rather than through the Fennec codec: a
reflective walk needs no dependency on the aspect's own EPackage and keeps
working for aspect types that did not exist when the tool was written. A
`transientContent` payload is not EMF and cannot be rendered; its presence and
Java class name are reported instead.

## Degradation

`MetadataService.getIndexReader()` returns an `Optional` — the index is bound
`OPTIONAL`/`DYNAMIC`, so it can be absent. Every lookup then returns a clear
*"no metadata index is available in this runtime"* error rather than an empty
result: the two are indistinguishable from the agent's side, and one of them
sends it hunting for a model that is in fact right there.
`describe_metadata_status` never requires the index, so it still answers when
nothing else does.

## Exposure policy

**Query wide to locate, read narrow to copy.**

These tools reach every `EPackage` the metadata layer knows, including ones no
allow-list mentions — `EMFModelGuard`'s `epackage.allowlist` /
`eclass.allowlist` gate `emf.tools`, not this bundle, and the metadata layer's
population is wider than both. What they return is identity and structure:
references, names, flags, annotation keys and values, parsed aspect
configuration. They do not serialize models.

**One policy does apply here: `MCPAnnotationVisibility`.** Annotation content is
the configuration of everything that reads a model, and it is what these tools
are built to surface, so the deny-list has to be honoured in this bundle too:

| Tool | Under a denied annotation source | Under a denied aspect type |
|------|----------------------------------|----------------------------|
| `list_annotation_sources` | the entry is omitted whole — its keys, hit count and namespaces with it, any one of which would confirm the source exists | — |
| `find_classes_by_annotation`, `find_features_by_annotation`, `find_operations_by_annotation` | the query is **refused**, not answered empty: an empty result is indistinguishable from "nothing carries this", which would have the agent reuse the convention | — |
| `describe_aspects` | — | withheld whether asked for by name or not, and the "this element carries no '…' aspect" hint does not name it either |
| `list_aspects`, `describe_metadata_status` | — | omitted from the inventory |

Enforcing it only in `emf.tools` would have been theatre: `describe_eclass`
would have withheld a source while `list_annotation_sources` still enumerated
it and `find_classes_by_annotation` still returned its values. Both lists
default to empty, so a deployment that configures nothing behaves exactly as
before. See [the development guide](development-guide.md).

Full-fidelity reads stay in `emf.tools` and stay allow-listed: `describe_eclass`
and [`export_package`](emf-metamodel-authoring.md), the latter requiring both the
package and every one of its EClasses on the allow-list. If a deployment needs the queries narrowed
too, the shape to add is an `EMFMetadataGuard` config with its own
wildcard-capable `epackage.allowlist` — deliberately not built here, since it
would make the cold-start flow above depend on configuration that has to name
the very packages the agent is trying to discover.

The gap this used to open is now closable from the other side: `EMFModelGuard`'s
lists take `prefix*` and `*` patterns, and `list_metamodel` filters the live
registry, so a package these tools *find* need no longer be one `emf.tools`
cannot *read*. A deployment that mirrors a scope can allow-list the scope's
namespace prefix once instead of naming each package as it arrives — see
[the security section of the development guide](development-guide.md).

## Wiring

These nine have a tool provider of their own,
`MCPToolProvider~emfMetadata` (`name=emf_metadata_tool_provider`), in
`org.eclipse.fennec.mcp.emf.runtime.config/configs/configuration.json`, next to
`~emfModel` for the EMF tools and `~modelAtlas` for publishing, whose tool
ships with the [model.atlas](https://github.com/eclipse-fennec/model.atlas)
project rather than from here. The `/mcp/emf` server's `toolProviders.target`
ORs the three names together; the `~modelAtlas` entry is inert unless that
bundle is deployed.

Add each new `tool.name` to the matching provider's `tools.target` **and bump
that provider's `tools.cardinality.minimum` to match** — an unsatisfied minimum
silently prevents the whole MCP server from activating. It is `9` here, and `28`
on `~emfModel`.

The `org.eclipse.fennec.emf.osgi.metadata` import is **mandatory** here (unlike
in `emf.tools`, which declares it `resolution:=optional`): the bundle is a view
onto that layer, so a runtime without it should fail to resolve rather than
start nine tools that can never answer.
