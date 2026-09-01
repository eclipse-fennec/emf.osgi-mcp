# Metamodel Authoring with the EMF MCP Tools

`org.eclipse.fennec.mcp.emf.tools` can, in addition to editing *instances*,
**author and modify Ecore metamodels** (`EPackage` / `EClass` / `EDataType` /
`EEnum` / `EAnnotation` / `EOperation` / features, incl. full generics),
register them into a session-local EPackage registry so their classes become
instantiable, and round-trip both metamodels and instances through inline XMI.

## Tools

| Group | Tools |
|-------|-------|
| Author | `create_epackage`, `add_eclass`, `add_edatatype`, `add_eenum`, `add_eenum_literal`, `add_eattribute`, `add_ereference`, `add_eoperation`, `add_eparameter`, `add_eannotation`, `add_etypeparameter` |
| Register | `register_package`, `unregister_package`, `list_registry` |
| Import | `import_ecore`, `import_instances` |
| Export | `export_dataset`, `export_package` |
| (existing, now session-aware) | `create_instance`, `create_from_json`, `modify_feature`, `list_metamodel`, `describe_eclass`, … |

Type references are `<nsURI>#//<Name>` (built-in Ecore datatypes such as
`http://www.eclipse.org/emf/2002/Ecore#//EString`, or a registered package),
`#//<Name>` for a sibling classifier of the package being authored (which no
registry can resolve while that package is unregistered), or a dataset-local
`objectId` of a classifier authored in the same dataset. Generics
use a recursive `GenericType` shape (`classifier` | `typeParameter` |
`typeArguments` | `upperBound` | `lowerBound`) via `eGenericType` /
`eGenericSuperTypes` / `eGenericExceptions`.

## Reading a metamodel in full: `export_package`

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

## Composite authoring: the whole package in one call

An agent pays an iteration per tool call, and the authoring tools chain on ids:
`datasetId` → `packageObjectId` → `classObjectId` → features. A two-class
package cost nine iterations that way, eight of them spent waiting for an id
rather than doing work. So `create_epackage` and `add_eclass` take their
children inline:

| Tool | Nested arrays |
|------|---------------|
| `create_epackage` | `eClassifiers` — each entry an `eClass` of `EClass` (default), `EEnum` or `EDataType`, otherwise the arguments of `add_eclass` / `add_eenum` / `add_edatatype` minus `datasetId`/`packageObjectId` |
| `add_eclass` (standalone, and each `EClass` entry above) | `eAttributes`, `eReferences`, `eAnnotations` — the arguments of `add_eattribute` / `add_ereference` / `add_eannotation` minus `datasetId` and the owner id |
| `EEnum` entry | `eLiterals` — the arguments of `add_eenum_literal` minus `datasetId`/`eenumObjectId` |

```json
{
  "datasetId": "ds1", "name": "draginolse01",
  "nsURI": "https://example.org/dragino/lse01", "nsPrefix": "lse01",
  "eClassifiers": [
    { "name": "Uplink",
      "eSuperTypes": ["https://eclipse.org/fennec/lorawan#//UplinkMessage"],
      "eAnnotations": [ { "source": "https://…/discriminator", "details": { "port": "85" } } ],
      "eAttributes": [ { "name": "batV", "eType": "…Ecore#//EDouble" } ],
      "eReferences": [ { "name": "object", "eType": "#//DecodedObject", "containment": true } ] },
    { "name": "DecodedObject", "eAttributes": [ … ] }
  ]
}
```

Four properties make this safe to rely on:

- **Order-independent.** Classifiers are all created and attached first, then
  their type references are wired in a second pass, so `Uplink` may point at
  `DecodedObject` before `DecodedObject` is declared.
- **All-or-nothing.** Nothing is registered in the dataset until the whole tree
  is built. A failure part-way leaves the dataset exactly as it was — correct
  the payload and call again; there is no half-built package to clean up.
- **Located errors.** A nested failure names the element by array index and
  name: `eClassifiers[1] 'Uplink': eAttributes[0] 'batV': …`.
- **Addressable results.** The call returns `created`, every nested element as
  `objectId` / `type` / `name`, so a follow-up `modify_feature` needs no
  `inspect_dataset` first.

Omitting the arrays leaves both tools behaving exactly as before, and the
standalone `add_eattribute` / `add_ereference` / `add_eannotation` remain the
way to extend a package that already exists. `eOpposite` and `eKeys` take
dataset objectIds, so an opposite pair spanning one composite call is closed
afterwards with `modify_feature`. Nested `eOperations` are not supported.

## End-to-end walkthrough

1. `create_dataset` → a metamodel dataset.
2. `create_epackage` (name / nsURI / nsPrefix) with the package's
   `eClassifiers` inline → `packageObjectId` plus the `created` ids.
3. Only if the model grows afterwards: `add_eclass` (itself composite),
   `add_eattribute` (`eType=…Ecore#//EString`), `add_ereference` (`eType` = a
   dataset objectId or a `#//` ref), …
4. `register_package` → validates (`Diagnostician`), enforces dynamic-only and
   the nsURI policy, registers a frozen copy; the classes are now instantiable.
5. `export_dataset` (`format=xmi`) → the `.ecore` of the dataset, or
   `export_package` (`nsURI=…`) → the `.ecore` of the registered package alone.
6. `create_instance` (`eClass=<nsURI>#//<Class>`) + `modify_feature` in a new
   dataset → instances; `export_dataset` → instance XMI.
7. Next prompt: `import_ecore` (paste the `.ecore`) then `import_instances`
   (paste the instance XMI, with its `nsURI`) to keep editing.

## Configuration (`EMFPackageRegistry` PID)

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
