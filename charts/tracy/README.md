# charts/tracy

Helm chart for the tracy server. The chart is here, in the product repository; the values for a
particular environment live wherever that environment is described, and are passed with `-f`.

```bash
helm upgrade --install tracy ./charts/tracy \
  -f my-values.yaml \
  --set ingest.key="$TRACY_INGEST_KEY" \
  --set mcp.token="$TRACY_MCP_TOKEN" \
  --namespace tracy --create-namespace --atomic
```

## What is a hard failure, and why

Two values make the render fail rather than deploy something that looks alive:

| Value | Why it is fatal |
|---|---|
| `ingest.key` | the server refuses to start without `TRACY_INGEST_KEY`, so an empty value deploys a crash-looping pod. A log collector that quietly started without a key is indistinguishable from a healthy one until the first incident |
| `hostname` | an empty value renders ``Host(`` )`` rules that match nothing, and the MCP transport refuses every request as an unexpected `Host`. Both failures are silent |

`mcp.token` is deliberately different. Empty means no Secret, no environment variable, no MCP
endpoint mounted and **no ingress bypass** — the feature is off rather than open, and the bypass
cannot come into existence without the authentication that replaces it.

## The two routes that skip forward-auth

`/ingest` and `/mcp` are reached by machines holding no browser session, so the forward-auth
middleware would reject them before tracy saw the request. Each bypasses it and authenticates
itself instead — `X-Tracy-Key` for ingest, a bearer token for MCP.

Everything else, including `/api/**` and `/health`, goes through the proxy. tracy has no login of
its own and trusts the proxy's headers, which is exactly why it must never be reachable without
one.

## Why one replica and `Recreate`

One writer, one volume. Two pods would open the same SQLite file, and WAL across two processes on
a network volume is how a database gets corrupted rather than how it stays available. `Recreate`
means the old pod is gone before the new one starts, which is also why the deploy should use
`--atomic`: a failed rollout without a rollback leaves nothing accepting logs.

## Storage

`db.maxBytes` is kept below `db.size` on purpose. A full volume is a write failure; an exceeded
budget is a planned drop of the oldest day. The gap between them is the margin in which the
server gets to make that choice.
