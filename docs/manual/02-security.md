# Security

An MCP server turns network requests into actions inside your runtime. The Gogo
server in particular dispatches tool arguments into a live Felix
`CommandSession`, which is effectively **remote code execution for anyone who can
reach the endpoint**. Fennec MCP is therefore hardened on several independent
layers, and ships safe defaults.

::: danger Development use only
The Gogo Shell MCP server (`execute_gogo`) is a development- and debugging-time
tool. It grants arbitrary command execution in the OSGi framework and **must not
be deployed in production systems.** Treat any server exposing shell-like tools
the same way.
:::

## Network exposure — loopback by default

The shipped Configurator binds the Felix HTTP runtime to `127.0.0.1`:

```json
"org.apache.felix.http~gogo": {
    "org.osgi.service.http.port:int": 8088,
    "org.apache.felix.http.host": "127.0.0.1",
    "org.apache.felix.http.name": "gogo"
}
```

Without `org.apache.felix.http.host` the Jetty default is to bind **all**
interfaces (`0.0.0.0`). Keeping it on localhost means the example runtime is not
reachable from the network out of the box.

The one shipped exception is the **inference container image**. Its
`org.apache.felix.http~inference` host is a `$[env:MCP_INFERENCE_HTTP_HOST]`
placeholder, defaulting to `127.0.0.1` for a local run and set to `0.0.0.0` in
the image: loopback inside a container is reachable only from inside it, so a
container keeping the default would publish a port that answers nothing. That is
exactly why a token is **mandatory** there and not optional — see
[metamodel-inference.md](../metamodel-inference.md#running-it-in-a-container).

## Authentication — the `McpAuthenticationFilter`

`AbstractHttpMCPServer` registers an `McpAuthenticationFilter` on the OSGi HTTP
Whiteboard alongside — and **before** — the transport servlet, so the endpoint is
never reachable without its guard (and on shutdown the servlet is removed before
the filter). The filter is **fail-closed for remote callers**, and checks three
things in this order:

1. If an **`McpTokenVerifier`** is wired, every request must carry an
   `Authorization: Bearer` header that the verifier accepts; the verified
   principal is exposed as a request attribute. A verifier that throws counts as
   rejection — a broken verifier must never open the endpoint. The
   `org.eclipse.fennec.mcp.auth.jwt` bundle supplies one that validates JWTs
   offline against an IdP's JWKS (see the
   [Keycloak guide](../mcp-auth-keycloak.md)).
2. Otherwise, if a non-blank **`auth.token`** is configured on the
   `HttpMCPServerComponent~…` config, every request must carry a matching
   `Authorization: Bearer <token>` header, compared in constant time.
3. If neither is configured, only **loopback** callers are allowed — and only
   when they carry no `X-Forwarded-For` / `Forwarded` header. So even if an
   operator binds to all interfaces without a token, remote access is refused.

```json
"HttpMCPServerComponent~gogoShell": {
    "…": "…",
    "auth.token": "change-me-to-a-long-random-secret"
}
```

::: tip Exposing an endpoint and giving it a token are one step
That third rule is stricter than it first looks. A loopback request carrying a
forwarding header was relayed by a local reverse proxy on behalf of a remote
client — which is exactly the shape any tunnel (ngrok, cloudflared) produces. So
a tunnelled endpoint without a token does not work; it fails closed with
*"Remote access requires a configured authentication token"*.
:::

**Never expose the endpoint beyond localhost without setting a strong
`auth.token`.** Give each endpoint its own value: two servlets on one runtime
usually expose very different amounts of it, and rotating one should not disturb
the other.

### Keeping tokens out of git

The EMF and inference runtimes read each token as
`$[env:NAME;default=$[prop:NAME;default=]]`, resolved by
`org.apache.felix.configadmin.plugin.interpolation`. The values come from a
`secrets.bndrun` that is **not** in git — copy `secrets.bndrun.template` next to
it, fill in the tokens, and `launch.bndrun` picks it up through an optional
include. Each runtime bundle has its own template: `emf.runtime` needs
`MCP_EMF_AUTH_TOKEN`, and `inference.runtime`, which serves both endpoints, needs
that one and `MCP_INFERENCE_AUTH_TOKEN`. An exported environment variable wins over the file, and the empty
default keeps the shipped behaviour (no token, loopback only) for a checkout
without one.

## Resource-exhaustion limits

### Gogo tools

| Limit | Guard |
|-------|-------|
| Output per command | Captured stdout/stderr are capped (`MAX_OUTPUT_BYTES`, 1 MiB); excess is discarded and the result is marked truncated — an oversized command output cannot OOM the runtime. |
| Concurrent commands | A bounded worker pool (`MAX_CONCURRENT_COMMANDS`, 8) runs commands with direct hand-off; excess concurrent calls are rejected ("Too many concurrent Gogo commands") rather than spawning unbounded threads. |
| Hung/runaway commands | Commands run interruptibly; the request timeout cancels the call, interrupts the worker and closes the session. |

### EMF model tools

The EMF tools enforce hard caps via the `EMFDatasetRegistry` configuration
(`DatasetLimits`): maximum datasets per session, objects per dataset, recipe
operations, single-value length, JSON payload bytes, inline export bytes, and an
idle-session TTL. Cap checks are applied atomically with inserts so concurrent
calls on one dataset cannot overshoot them. Datasets are session-scoped with an
ownership check (a session can only address its own datasets) and unguessable
UUID ids.

## Metamodel access — deny-all allow-list

The EMF tools only see and instantiate EPackages/EClasses that are explicitly
allow-listed on the `EMFModelGuard` PID. The defaults are empty, so nothing is
visible until an operator opts specific models in:

```json
"EMFModelGuard": {
    "epackage.allowlist": [],
    "eclass.allowlist": []
}
```

Recipe replay re-validates every operation against the *current* allow-list, so a
saved recipe can never bypass it. Entries may be exact nsURIs, a `prefix*`
pattern or a bare `*`; the package and class lists are independent.

Authoring a metamodel is gated separately. A session-registered package becomes
instantiable only if its namespace passes the `EMFPackageRegistry` PID's own
deny-all `nsuri.allowlist`; reserved namespaces (Ecore, XMLType, GenModel) can
never be registered, registered packages must be dynamic, and a LRU cap bounds
how many a session holds. Imported XMI is never dereferenced externally — no
href, URL or file loading, and DOCTYPE is rejected.

::: warning The discovery tools are deliberately not guarded
`org.eclipse.fennec.mcp.metadata.tools` reaches **every** EPackage the metadata
layer knows, including ones no allow-list mentions. What it returns is identity
and structure — references, names, flags, annotation keys and values — never
serialized model content, and full-fidelity reads stay behind the guard. The
rule is *query wide to locate, read narrow to copy*; if that trade is wrong for
your deployment, do not deploy the bundle. See
[Metadata Discovery](../metadata-discovery-tools.md).
:::

## Checklist before exposing a server

- [ ] Keep `org.apache.felix.http.host` on `127.0.0.1`, or front the endpoint
      with an authenticating reverse proxy. A container binds `0.0.0.0` out of
      necessity — set its `auth.token` in the same step, not later.
- [ ] Set a strong `auth.token` if the endpoint must be reachable remotely.
- [ ] Do not deploy the Gogo server in production.
- [ ] For the EMF server, allow-list only the metamodels you intend to expose,
      and set the `EMFPackageRegistry` namespace allow-list as narrowly as the
      authoring workflow permits.
- [ ] Decide deliberately whether the unguarded discovery tools belong in this
      deployment — deploying the bundle is the decision.
