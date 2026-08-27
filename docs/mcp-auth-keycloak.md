# Securing an MCP endpoint with Keycloak-minted JWTs

This walkthrough configures Keycloak so that MCP agents (Claude Code, other AI clients,
CI jobs) authenticate against the MCP HTTP endpoint with **per-client, expiring bearer
tokens** instead of the shared static `auth.token`. Server-side validation is done by the
`org.eclipse.fennec.mcp.auth.jwt` bundle (see the security section in the
[development guide](development-guide.md)) — offline against the realm's JWKS, no
Keycloak round-trip per request.

MCP agents are non-interactive machine clients, so the natural OAuth2 flow is
**client credentials** with a Keycloak *service account* per agent.

## 1. Realm

Create a realm, e.g. `mcp` (Admin Console → *Manage realms* → *Create realm*).

Everything the verifier needs is published under the realm's OIDC discovery document:

```
https://<keycloak-host>/realms/mcp/.well-known/openid-configuration
```

Note two values from it — copy them **exactly** (the verifier matches the issuer
character by character):

| Discovery field | Used as |
|---|---|
| `issuer` (e.g. `https://<keycloak-host>/realms/mcp`) | `issuer` in the verifier config |
| `jwks_uri` (e.g. `…/realms/mcp/protocol/openid-connect/certs`) | `jwks.url` in the verifier config |

## 2. One client per agent

Admin Console → *Clients* → *Create client*, for example `claude-agent-1`:

- **Client type**: OpenID Connect
- **Client authentication**: On (confidential client — this creates the client secret)
- **Authentication flow**: enable *Service accounts roles* only; Standard flow and
  Direct access grants can stay off (no human login involved)

Under *Credentials* you find the **client secret** the agent uses to obtain tokens.

> One client per agent is the whole point: revoking a single agent = disabling its
> client in Keycloak; no other agent is affected. The token's `sub` claim (the
> service-account user id) becomes the `McpPrincipal.clientId` on the MCP side.

## 3. Audience mapper (the step everyone forgets)

By default Keycloak does **not** put your API's name into the `aud` claim of a
client-credentials token — a token minted for *any* purpose in the realm would pass an
issuer-only check. To make tokens explicitly scoped to the MCP endpoint:

1. *Client scopes* → *Create client scope*: name `mcp-endpoint`, type *Default*, protocol *openid-connect*
2. Inside the scope: *Mappers* → *Configure a new mapper* → **Audience**
   - *Included Custom Audience*: `mcp-endpoint`
   - *Add to access token*: On
3. On each agent client: *Client scopes* → *Add client scope* → `mcp-endpoint` (Default)

Then set `"audience": "mcp-endpoint"` in the verifier config, and only tokens carrying
that audience are accepted. (Reference: [Audience support](https://www.keycloak.org/docs/latest/server_admin/#_audience_hardcoded)
in the Keycloak Server Administration Guide — the mapper above is the "hardcoded audience"
variant; service accounts are documented under
[Service accounts](https://www.keycloak.org/docs/latest/server_admin/#_service_accounts).)

## 4. Token lifespan

Realm settings → *Sessions*/*Tokens* → **Access Token Lifespan** (default 5 minutes,
often raised to 15–60 minutes for agent workloads). This is the expiry the verifier
enforces via `exp` — a leaked token dies by itself, which is exactly what the static
`auth.token` could not do.

## 5. Verifier + server configuration

```json
"JwtTokenVerifier~keycloak": {
    "jwks.url": "https://<keycloak-host>/realms/mcp/protocol/openid-connect/certs",
    "issuer": "https://<keycloak-host>/realms/mcp",
    "audience": "mcp-endpoint",
    "verifier.name": "keycloak"
},
"HttpMCPServerComponent~myServer": {
    "…": "…",
    "verifier.target": "(verifier.name=keycloak)"
}
```

Deploy `org.eclipse.fennec.mcp.auth.jwt` and `com.nimbusds.nimbus-jose-jwt` in the
runtime alongside the MCP bundles.

## 6. Agent side: obtain and use a token

```bash
# fetch a token (client credentials grant)
TOKEN=$(curl -s -X POST \
  "https://<keycloak-host>/realms/mcp/protocol/openid-connect/token" \
  -d "grant_type=client_credentials" \
  -d "client_id=claude-agent-1" \
  -d "client_secret=<secret>" | jq -r .access_token)

# call the MCP endpoint
curl -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     https://<mcp-host>:8099/mcp/emf -d '{"jsonrpc":"2.0","id":1,"method":"ping"}'
```

MCP clients that support custom headers (e.g. Claude Code via
`claude mcp add --transport http --header "Authorization: Bearer <token>"`) pass the
token the same way; the agent is responsible for refreshing it before `exp`.

## 7. What the verifier enforces

| Claim / property | Check |
|---|---|
| Signature | against the realm JWKS (`kid`-matched), algorithms `RS256`/`ES256` by default |
| `iss` | exact match with the configured `issuer` |
| `aud` | must contain the configured `audience` (empty config = not enforced) |
| `exp` / `nbf` | with configurable clock skew (default 30 s) |
| `sub` | required — becomes `McpPrincipal.clientId` |
| `scope` | optional — space-separated, exposed as `McpPrincipal.scopes` |

Any verification failure → HTTP 401 from the servlet filter; the request never reaches
the MCP servlet.
