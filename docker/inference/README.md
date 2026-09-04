# Metamodel-inference MCP image

Self-contained runtime for the **`/mcp/inference`** endpoint: the 20-tool set an agent uses to
infer an Ecore metamodel from sample payloads and hand it to a Model Atlas — discover the
family and its conventions, author the package, validate it against every sample, register
and publish.

Published by CI as `docker.io/eclipsefennec/emf.osgi-mcp` and
`ghcr.io/eclipse-fennec/emf.osgi-mcp`, tagged **`inference-snapshot`** / **`inference-latest`**
plus `inference-<bundle version>` — the variant is in the tag because this repo can produce
more than one runtime image (see `.github/workflows/reusable-container.yml`).

It does **not** serve `/mcp/emf`. That endpoint belongs to
`org.eclipse.fennec.mcp.emf.runtime`, and the two config bundles define the same singleton
PIDs (`EMFModelGuard`, `EMFPackageRegistry`, `MCPAnnotationVisibility`) and bind the same
port, so they are alternative images and not layers. See `docs/metamodel-inference.md`.

## Content layout

The build context expects a `content/` directory (git-ignored, staged by the manual steps
below):

```
content/inference.runtime_docker.jar   bnd-exported executable runtime
```

That is the whole context — unlike the event.atlas image there are no mounted model
directories, because this runtime authors metamodels rather than reading them off disk.

## Ports

| Port | What |
|---|---|
| 8099 | HTTP — the `inference` whiteboard instance, serving the single servlet `/mcp/inference` |

The framework's own HTTP service is not configured, so nothing else binds. `/mcp/inference`
speaks MCP over HTTP and answers `POST`: there is nothing to open in a browser.

## Authentication

**A token is mandatory in a container.** `McpAuthenticationFilter` has two modes:

- **`auth.token` set** — every request must carry `Authorization: Bearer <token>`.
- **`auth.token` empty** — only *direct loopback* callers are served, and a loopback request
  carrying `X-Forwarded-For` or `Forwarded` (the shape any tunnel produces) is refused too.

Inside a container the second mode is useless rather than dangerous: the endpoint binds
`0.0.0.0`, and a request from the host or another container arrives from the docker gateway
address, not from loopback, so **every** call gets `401`. Set `MCP_INFERENCE_AUTH_TOKEN`.
Exposing the endpoint and giving it a token are one step, not two.

If event.atlas is the caller, this value must equal `METAMODEL_MCP_TOKEN` in that repo's
`secrets.bndrun`, whose `METAMODEL_MCP_URL` points at this servlet.

## Reaching the Model Atlas

`post_to_model_atlas` publishes to an atlas this runtime does not host, and the discovery
tools read that same atlas's EPackages. One variable configures both directions:
`MODEL_ATLAS_BASE_URI`, the REST base **including** the `/atlas/rest` suffix.

> **A blank base.uri or scope does not merely disable publishing.**
> `ModelAtlasPublisher` validates its configuration at activation and refuses to activate on
> a blank `base.uri` or `scope`. The publisher never activating means `post_to_model_atlas`
> is never registered, which leaves `MCPToolProvider~inference` at 19 of its required 20
> tools — and **`/mcp/inference` then does not come up at all**, with nothing on stdout
> saying why. The `:?` forms in the compose file exist to turn that into a readable `up`
> failure instead.

Three ways to point it somewhere:

- **A deployed atlas** — what the example compose file assumes. Put its REST base in `.env`.
- **A local model.atlas container** — bring up `docker/dockercompose/docker-compose-jena.yml`
  from the [model.atlas](https://github.com/eclipse-fennec/model.atlas) repo (it owns 8080 and
  carries the `configs/jena.json` that defines `ScopeService~jena` and the registries), then
  set `MODEL_ATLAS_BASE_URI=http://host.docker.internal:8080/atlas/rest` and declare
  `extra_hosts: ["host.docker.internal:host-gateway"]` on the service.
- **Same compose network** — add the atlas as a service and use
  `http://<service-name>:8080/atlas/rest`. It needs that `jena.json` mounted at
  `/opt/modelatlas/runtime/load` to have a scope to publish into.

## Configuration

Everything deployment-specific is a `$[env:…]` placeholder resolved at
configuration-delivery time by `org.apache.felix.configadmin.plugin.interpolation`, so one
image serves every environment. The values live in three bundles — `inference.config`
(endpoint + bind host), `emf.runtime.config.atlas` (the read client) and the model.atlas
project's `model.atlas.mcp.config` (the publisher).

| Variable | Default | What |
|---|---|---|
| `MCP_INFERENCE_AUTH_TOKEN` | empty | Bearer token guarding `/mcp/inference`. Empty means loopback-only, i.e. `401` for everything in a container — see [Authentication](#authentication) |
| `MCP_INFERENCE_HTTP_HOST` | `127.0.0.1`, **`0.0.0.0` in the image** | whiteboard bind address; set in the Dockerfile, not something a deployment normally touches |
| `MODEL_ATLAS_BASE_URI` | `http://localhost:8080/atlas/rest` | Atlas REST base, read **and** publish. No usable default in a container |
| `MODEL_ATLAS_PUBLISHING_SCOPE` | empty | Atlas scope published into (`jena` in the standard deployment). Blank ⇒ the endpoint does not come up |
| `MODEL_ATLAS_PUBLISHING_STAGE` | `draft` | publication stage; should stay a draft stage |
| `MODEL_ATLAS_OVERWRITE` | `false` | whether an existing draft is replaced |
| `MCP_ATLAS_PUBLISH_ALLOWLIST` | empty | `\|`-separated namespace rules, each exact or a `prefix*`. Deny-all: blank publishes nothing |

`MCP_ATLAS_PUBLISH_ALLOWLIST` is pipe-separated and not comma-separated because a
comma-separated value is split by bnd on the `-runvm` path a local run shares with this. It is
declared `type=String[];delimiter=|` in `publisher.json` — the `type` directive is what makes
the plugin convert at all, so `delimiter` alone would arrive as one joined String.

### What is baked in, and what that costs

`EMFModelGuard` and `EMFPackageRegistry` — the allow-lists deciding which namespaces an
inference run may author and register — are **literal** in `inference.config`'s
`emf_base.json`, not environment-driven. The shipped lists admit the LoRaWAN namespaces and
`https://fennec.eclipse.org/event.atlas/inferred*`.

A deployment authoring under different namespaces has to change that file and rebuild, or
carry a derived image. This is deliberate for now: they are deny-all security lists, and an
allow-list that a stray environment variable can widen is a weaker guarantee than one that
takes a rebuild. `MCP_ATLAS_PUBLISH_ALLOWLIST` is separate and *is* environment-driven — it
governs publishing, one step further along, and the publisher config it belongs to lives in
another repo.

## Building locally

```bash
./gradlew :org.eclipse.fennec.mcp.inference.runtime:export.inference.runtime_docker
mkdir -p docker/inference/content
cp org.eclipse.fennec.mcp.inference.runtime/generated/distributions/executable/inference.runtime_docker.jar \
   docker/inference/content/
docker build -t fennec-mcp-inference:local docker/inference/
```

The exported jar also runs outside docker, which is the quickest way to check a config change
(it binds loopback there, so no token is needed):

```bash
java -Dgosh.args=--nointeractive -jar \
  org.eclipse.fennec.mcp.inference.runtime/generated/distributions/executable/inference.runtime_docker.jar
```

Re-resolve after changing `-runrequires`. `inference.runtime_docker.bndrun` is kept in sync
with `launch.bndrun` — identical requirements, therefore an identical `-runbundles` — so a
change to one belongs in both.

## Running

```bash
cp docker/inference/.env.example docker/inference/.env    # then fill it in
docker compose -f docker/inference/docker-compose.example.yml up
```

Or without compose:

```bash
docker run --rm -p 8099:8099 \
  -e MCP_INFERENCE_AUTH_TOKEN=... \
  -e MODEL_ATLAS_BASE_URI=https://atlas.example.org/atlas/rest \
  -e MODEL_ATLAS_PUBLISHING_SCOPE=jena \
  -e 'MCP_ATLAS_PUBLISH_ALLOWLIST=https://fennec.eclipse.org/event.atlas/inferred*' \
  fennec-mcp-inference:local
```

### Smoke test

The image is distroless and has no shell, so probe it from the host. An MCP `initialize` is
the real check — it proves the servlet, the filter and the tool provider all came up:

```bash
curl -sS -X POST http://localhost:8099/mcp/inference \
  -H "Authorization: Bearer $MCP_INFERENCE_AUTH_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":"2025-06-18","capabilities":{},
        "clientInfo":{"name":"smoke","version":"1"}}}'
```

| Symptom | Cause |
|---|---|
| `401` | no or wrong token — a container never gets the loopback exemption |
| connection refused, container running | `MCP_INFERENCE_HTTP_HOST` not `0.0.0.0`, so the servlet bound loopback inside the container |
| connection refused, and the log shows no servlet | one of the 20 tools is missing — most often `post_to_model_atlas`, because the publisher refused to activate on a blank `base.uri` or `scope` |
| `tools/list` returns 20 tools but publishing is refused | `MCP_ATLAS_PUBLISH_ALLOWLIST` admits no rule for that namespace |
