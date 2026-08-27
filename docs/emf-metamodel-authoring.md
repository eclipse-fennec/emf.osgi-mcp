# Metamodel Authoring with the EMF MCP Tools

> Status: **implemented** (v1). `org.eclipse.fennec.mcp.emf.tools` now, in
> addition to editing *instances*, can **author and modify Ecore metamodels**
> (`EPackage` / `EClass` / `EDataType` / `EEnum` / `EAnnotation` / `EOperation` /
> features, incl. full generics), register them into a session-local EPackage
> registry so their classes become instantiable, and round-trip both metamodels
> and instances through inline XMI. GenModel emission remains a backlog item.
>
> This document keeps the original design rationale; see
> **[Implementation](#0-implementation-v1)** for the delivered tools, the
> end-to-end walkthrough and the required configuration.

## 0. Implementation (v1)

### Tools

| Group | Tools |
|-------|-------|
| Author | `create_epackage`, `add_eclass`, `add_edatatype`, `add_eenum`, `add_eenum_literal`, `add_eattribute`, `add_ereference`, `add_eoperation`, `add_eparameter`, `add_eannotation`, `add_etypeparameter` |
| Register | `register_package`, `unregister_package`, `list_registry` |
| Import | `import_ecore`, `import_instances` |
| Export | `export_dataset`, `export_package` |
| (existing, now session-aware) | `create_instance`, `create_from_json`, `modify_feature`, `list_metamodel`, `describe_eclass`, … |

Type references are `<nsURI>#//<Name>` (built-in Ecore datatypes such as
`http://www.eclipse.org/emf/2002/Ecore#//EString`, or a registered package) or a
dataset-local `objectId` of a classifier authored in the same dataset. Generics
use a recursive `GenericType` shape (`classifier` | `typeParameter` |
`typeArguments` | `upperBound` | `lowerBound`) via `eGenericType` /
`eGenericSuperTypes` / `eGenericExceptions`.

### Reading a metamodel in full: `export_package`

`describe_eclass` reports a class's *shape*, never its source, and three things
fall through the gap:

- **EAnnotations are invisible.** The describer emits name, package, `abstract`,
  supertypes, documentation and features — no annotations. So an existing
  model's codec configuration cannot be seen, let alone copied: not the
  `typeMapping/{mapId}` source, not `ExtendedMetaData` wire names.
- **Abstract classes are unreachable.** `list_metamodel` filters them out and
  `describe_eclass` throws on them — yet an abstract base is exactly what a new
  model needs to extend.
- **Supertypes come back as bare names**, with no nsURI, so the
  `<nsURI>#//<Name>` that `add_eclass`'s `eSuperTypes` needs cannot be built
  from them.

`export_package(nsURI)` returns the whole `.ecore`, which carries all three
correctly. It is symmetric with `import_ecore`: inline XMI out, inline XMI in.

**Cross-package references.** A class extending a class in another package is
written as an external `href="<nsURI>#//<Name>"` and the referenced package is
*not* inlined. Getting this right needs two things EMF does not do by default:
each package is placed in a resource keyed by its **namespace URI** (a frozen
registry package has no resource at all, which is what made a package-only
`export_dataset` fail with *"… is not contained in a resource"*), and href
**deresolution is disabled** (otherwise two packages sharing a host serialize as
a bare relative segment, `lorawan#//UplinkMessage`, that no importer can
resolve). A document with external references will therefore not re-import into
a *fresh* session — `import_ecore` seeds no packages and rejects unresolved
ones. That is expected: import the referenced package first.

**Which packages can be exported.** A package registered by the current session
(`register_package` / `import_ecore`) exports as-is — it already passed the
registration policy, and handing an agent back its own model discloses nothing.
An OSGi-registered package must be on `epackage.allowlist` **and** every one of
its EClasses on `eclass.allowlist`: a `.ecore` is the whole package and cannot
be filtered the way a structured describer can. A partial allow-list is refused
with a *count* of withheld classes, never their names.

This is the widest-disclosure tool in the bundle — annotations may carry
persistence and codec details — which is an argument for keeping
`epackage.allowlist` to the packages actually needed, not for withholding the
tool. It is the "read narrow to copy" half of the division of labour with
[`org.eclipse.fennec.mcp.metadata.tools`](metadata-discovery-tools.md), which
queries wide to *locate* a model.

### End-to-end walkthrough

1. `create_dataset` → a metamodel dataset.
2. `create_epackage` (name / nsURI / nsPrefix) → `packageObjectId`.
3. `add_eclass`, `add_eattribute` (`eType=…Ecore#//EString`), `add_ereference`
   (`eType` = a dataset objectId or `#//` ref), … build the model.
4. `register_package` → validates (`Diagnostician`), enforces dynamic-only and
   the nsURI policy, registers a frozen copy; the classes are now instantiable.
5. `export_dataset` (`format=xmi`) → the `.ecore` of the dataset, or
   `export_package` (`nsURI=…`) → the `.ecore` of the registered package alone.
6. `create_instance` (`eClass=<nsURI>#//<Class>`) + `modify_feature` in a new
   dataset → instances; `export_dataset` → instance XMI.
7. Next prompt: `import_ecore` (paste the `.ecore`) then `import_instances`
   (paste the instance XMI, with its `nsURI`) to keep editing.

### Configuration (`EMFPackageRegistry` PID)

Security-by-default, deny-all. To allow registration an admin sets the nsURI
allow-list (a single `*` allows all non-reserved namespaces; entries may end
with `*`):

```json
"EMFPackageRegistry": {
    "nsuri.allowlist": ["http://example.org/*"],
    "nsuri.denylist": [],
    "max.models:int": 100
}
```

Reserved namespaces (Ecore, XMLType, GenModel) can never be registered. The cap
evicts the least-recently-modified package. Registered packages must be
**dynamic** (no `instanceClassName`/`instanceTypeName`). Imported XMI is never
dereferenced externally (no href/URL/file loading; DOCTYPE rejected).

## 1. Context

The `org.eclipse.fennec.mcp.emf.tools` bundle already provides a complete
session-based EMF editing harness over MCP:

| Capability | Where |
|------------|-------|
| Session-scoped datasets (with caps) | `core/DatasetRegistry`, `core/Dataset` |
| Create instances via the model's factory | `CreateInstanceTool`, `core/ModelOperations` (`eClass.getEPackage().getEFactoryInstance().create(eClass)`) |
| Set attributes and cross-references (type-checked) | `ModifyFeatureTool`, `core/ModelOperations#toFeatureValue` |
| Delete with reference cleanup | `core/ModelOperations` (`EcoreUtil.UsageCrossReferencer`) |
| Validate | `core/ValidationReports` (`Diagnostician.INSTANCE.validate`) |
| Serialize to XMI / JSON | `ExportDatasetTool`, `core/Exports` (`XMIResourceImpl`) |
| Replayable recipes | `core/RecipeOp`, `ReplayRecipeTool` |
| Deny-all security guard, registry-only resolution | `core/ModelGuard` |

This is the expensive half of an "EMF editor as an MCP server" and it is done.

## 2. The reflective insight

Ecore is self-describing: `EPackage`, `EClass`, `EAttribute`, `EReference` are
themselves `EClass`es in the registered Ecore package
(`http://www.eclipse.org/emf/2002/Ecore`). Therefore **authoring a metamodel is
just creating instances of Ecore's EClasses**, and because export is XMI and an
`.ecore` file *is* the XMI serialization of those objects, the existing
`ExportDatasetTool` already produces a valid `.ecore`.

Concretely, "make a package with three EClasses, one abstract" maps to:

1. Allow-list `http://www.eclipse.org/emf/2002/Ecore` on the `EMFModelGuard` PID.
2. `create_instance EClass=EPackage` → set `name` / `nsURI` / `nsPrefix`.
3. `create_instance EClass=EClass` (×3); set `abstract=true` on one (a plain boolean attribute).
4. Wire containment/cross-refs: `EPackage.eClassifiers → the EClasses`, `EClass.eSuperTypes`, …
5. `export_dataset format=xmi validate=true` → a validated `.ecore`.

Steps 1–5 work **today** for structure and for references between objects that
live in the dataset. Two gaps block real-world metamodels.

## 3. Extension 1 — Resolve `eType` (and other refs) against the registry

### Problem

Almost every feature needs an `eType` pointing at a built-in Ecore datatype
(`EString`, `EInt`, `EBoolean`, …) or at an already-registered `EClassifier`.
`ModelOperations#toFeatureValue` (lines 264–277) resolves reference values
**only dataset-locally**:

```java
if (feature instanceof EReference reference) {
    if (!(value instanceof String refId)) { ... }
    EObject target = dataset.requireObject(refId);      // <-- dataset-local only
    if (!((EClass) reference.getEType()).isInstance(target)) { ... }
    return target;
}
```

`EString` lives in the Ecore package in the OSGi registry, not in the dataset —
so `EAttribute.eType = EString` cannot be expressed. Without this, no attribute
can carry a primitive type.

### Change

In `toFeatureValue`, when the reference value is a **class-reference identifier**
(contains `ModelGuard.CLASS_REF_SEPARATOR` = `#//`, e.g.
`http://www.eclipse.org/emf/2002/Ecore#//EString`), resolve it through
`ModelGuard` against the **registry** instead of `dataset.requireObject`:

- Extend `ModelGuard` to resolve an `EClassifier` (not just `EClass`) by
  `<nsURI>#//<Name>`, enforcing the existing allow-list. Built-in datatypes
  become available by allow-listing the Ecore package.
- Keep the type check via `reference.getEType().isInstance(target)` (works for
  `eType`, whose declared type is `EClassifier`).
- Fall back to the current dataset-local resolution when the value is a plain
  dataset object id.

### Invariants preserved

- **Registry-only resolution** — still no URI dereferencing, no on-demand
  loading (`ModelGuard` Javadoc / SSRF protection is unchanged).
- **Deny-all** — a registry classifier is usable only if its package is
  allow-listed.
- **Recipes** — `RecipeOp` for a `set`/`add` that targets a registry classifier
  must record the class-reference identifier (not a dataset id) so
  `ReplayRecipeTool` reproduces it deterministically.

### Effort

Small and localized: one resolution branch in `ModelOperations#toFeatureValue`,
one resolver method on `ModelGuard`, a recipe-encoding tweak, tests in
`ModelOperationsTest` / `ModelGuardTest`.

## 4. Extension 2 — Emit a matching GenModel

### Problem

`ExportDatasetTool` produces `.ecore` (and JSON) only. Downstream code
generation (e.g. Gecko EMF codegen) needs the matching **`.genmodel`**, itself a
cross-resource XMI document that references the `.ecore`.

### Change

Add GenModel emission — either a new `format=genmodel` on `ExportDatasetTool` or
a dedicated `GenerateGenModelTool`:

1. Build `GenModel` / `GenPackage` objects referencing the authored `EPackage`.
2. Place `.ecore` and `.genmodel` in one `ResourceSet` with proper resource
   URIs so the cross-resource `href` from genmodel → ecore resolves on save.
3. Apply convention defaults (configurable): `complianceLevel`,
   `oSGiCompatible=true`, `basePackage`, copyright/license header.

### Notes / risk

- Adds a dependency on the GenModel metamodel
  (`org.eclipse.emf.codegen.ecore`); its package must be registered and
  allow-listed like any other.
- Cross-resource serialization is more involved than single-document `.ecore`
  export (proper URIs + proxy resolution across the two resources) — this is the
  main complexity here, not the object construction.

### Effort

Medium: new dependency, GenModel object construction, two-resource save,
config-driven defaults, round-trip test (author → ecore + genmodel → resolve).

## 5. Optional ergonomics layer

Reflective authoring via `create_instance` / `modify_feature` is verbose. Once
Extensions 1–2 land, a thin sugar toolset (`create_package`, `add_class`
with `abstract`/`interface`/`supertypes`, `add_attribute`, `add_reference` with
`bounds`/`opposite`, `serialize`) can wrap common sequences. Pure convenience —
it reuses the same datasets, guard, validation, XMI and recipes; not an enabler.

## 6. Prerequisites (configuration)

For reflective authoring the `EMFModelGuard` allow-list must include the Ecore
package and the authoring EClasses used, e.g. `EPackage`, `EClass`,
`EAttribute`, `EReference`, `EEnum`, `EEnumLiteral`, `EAnnotation`,
`EStringToStringMapEntry` (the last two for GenModel annotations embedded in the
`.ecore`). This is admin-owned config, consistent with the deny-all model.

## 7. Backlog

Status: 🔴 TODO · 🟡 IN PROGRESS · 🟢 DONE

| Status | Prio | Item | Description | Delivered |
|--------|------|------|-------------|-----------|
| 🟢 | P0 | **Registry classifier resolution** | Reference values of the form `<nsURI>#//<Name>` resolve via a per-call `ClassifierResolver` in `ModelOperations#toFeatureValue`; enables `eType`, `eSuperTypes`, `eOpposite` to point at built-in/registered classifiers | M1 (#6) |
| 🟢 | P0 | **ModelGuard classifier resolution** | `ModelGuard.requireAllowedClassifier` resolves any `EClassifier` (incl. abstract, `EDataType`, `EEnum`) registry-only; `resolverFor(session)` also consults the session-local registry | M1 (#6), M3 (#8) |
| 🟢 | P0 | **Recipe encoding for registry refs** | `RecipeOp.ref` carries either an `o<N>` id or a `#//` classifier ref (disambiguated by the separator), so replay reproduces registry-targeted features | M1 (#6) |
| 🔴 | P1 | **GenModel emission** | `format=genmodel` / `GenerateGenModelTool`: build GenModel/GenPackage, two-resource XMI save, convention defaults (complianceLevel, oSGiCompatible, basePackage, header) | — (backlog; new dep `org.eclipse.emf.codegen.ecore`) |
| 🟢 | P1 | **Ecore authoring guide + sample config** | This document (§0) + the `EMFPackageRegistry` sample config and end-to-end walkthrough | M7 (#12) |
| 🟢 | P2 | **Ergonomic authoring tools** | `create_epackage` / `add_eclass` / `add_edatatype` / `add_eenum(+literal)` / `add_eattribute` / `add_ereference` / `add_eoperation(+parameter)` / `add_eannotation` / `add_etypeparameter` + full generics | M4 (#9), M5 (#10) |
| 🟢 | P2 | **Round-trip: load existing .ecore** | `import_ecore` / `import_instances` on a hardened, registry-only loader (no href deref, no DOCTYPE, size-capped, unresolved-proxy reject) | M6 (#11) |

Beyond the original sketch, v1 also added a **session-local `PackageRegistry`**
(PID `EMFPackageRegistry`) with a deny-all nsURI policy, LRU cap and
dynamic-only enforcement (M2 #7), and a **trust bridge** making registered
packages instantiable without an eclass allow-list entry (M3 #8).

## 8. Non-goals / honest limits

- This automates metamodel **mechanics**, not **design** — a faithful executor
  produces a valid-but-wrong model if instructed wrongly.
- "Valid by construction" covers structural correctness only; semantic checks
  still run through `Diagnostician` before serialization.
- Loading arbitrary `.ecore` from agent-supplied URIs stays out of scope by
  design (SSRF/file-read protection); any import path must remain registry- or
  admin-mediated.
