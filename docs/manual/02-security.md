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

## Authentication — the `McpAuthenticationFilter`

`AbstractHttpMCPServer` registers an `McpAuthenticationFilter` on the OSGi HTTP
Whiteboard alongside — and **before** — the transport servlet, so the endpoint is
never reachable without its guard (and on shutdown the servlet is removed before
the filter). The filter is **fail-closed for remote callers**:

- If an **`auth.token`** is configured on the `HttpMCPServerComponent~…` config,
  every request must carry a matching `Authorization: Bearer <token>` header
  (compared in constant time).
- If **no token** is configured, only **loopback** callers are allowed; any
  non-loopback request is rejected. So even if an operator binds to all
  interfaces without a token, remote access is refused.

```json
"HttpMCPServerComponent~gogoShell": {
    "…": "…",
    "auth.token": "change-me-to-a-long-random-secret"
}
```

**Never expose the endpoint beyond localhost without setting a strong
`auth.token`.**

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
saved recipe can never bypass it.

## Checklist before exposing a server

- [ ] Keep `org.apache.felix.http.host` on `127.0.0.1`, or front the endpoint
      with an authenticating reverse proxy.
- [ ] Set a strong `auth.token` if the endpoint must be reachable remotely.
- [ ] Do not deploy the Gogo server in production.
- [ ] For the EMF server, allow-list only the metamodels you intend to expose.
