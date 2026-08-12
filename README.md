# tracy

Logs for Ktor services, shaped for a coding agent to read.

A KMP plugin inside your service, a single self-contained native server with SQLite + FTS5,
end-to-end W3C `traceparent`, and **MCP as the primary read path** — so the agent debugging your
bug asks for the trace instead of being handed a dashboard link.

Same niche as [katcher](https://github.com/youndie/katcher) (crashes) and metrik (metrics):
between "`kubectl logs` is enough" and "let's run Loki + Promtail + Grafana". One binary,
one database file, no JVM in production.

> **Status: working, not yet in production.** The agent, the server, storage, traces and the MCP
> endpoint are written and covered by 279 tests; the image builds and runs, and the whole loop —
> a batch in, SQLite, a read back over MCP — is exercised in a container. What has *not* happened
> yet: a deploy to a cluster and a real service sending real logs. Numbers below about volume are
> arithmetic, not measurements, and they are labelled as such.
>
> The design, the verified facts behind it and the plan are in [`docs/`](docs/README.md) and
> [`BACKLOG.md`](BACKLOG.md) — both in Russian.

## What it does

- **Structured logging** where the message template and the data are separated at write time, so
  what the developer wrote and what a caller supplied never get confused later.
- **End-to-end traces** — you have a `traceId`, you get the whole chain: a tree of spans with
  durations plus every log line of that request, across every service, in one call. Correlation
  travels as a standard `traceparent` header, so tracy interoperates with anything else that
  propagates it.
- **Business entity lookup** — support never brings you a `traceId`; they bring an `order_id`.
  Mark a field as an entity key and you get the whole history of that entity across services and
  across separate traces — including the parts whose log bodies were sampled away.
- **Search and grouping** — FTS5 full-text search, time-window queries across services, and exact
  event frequencies over time, inside the same native binary.
- **MCP access** — a handful of read-only tools with a hard context budget, a two-phase content
  release and a static screen over untrusted text.

## What it is not

- **Not an APM.** Spans exist, but only at the boundaries — one per incoming request, one per
  outgoing HTTP call, plus whatever you wrap in `withSpan` yourself. There is no auto-instrumentation
  of database drivers, caches or queues, and no aggregate analytics over spans: tracy shows you
  *one trace*, not statistics about traces. Time nobody instrumented shows up as unattributed,
  rather than silently disappearing.
- **Not a log pipeline.** tracy does not replace your stdout logs — it runs alongside them, on
  purpose: an in-process buffer cannot survive a `SIGKILL`, and stdout can.
- **Not a complete capture on native.** Measured, not assumed: on Kotlin/Native tracy sees what
  you write through its API or through kotlin-logging. Ktor's own logger and `println` go to
  stdout and nowhere else. On the JVM the SLF4J appender does see framework and library logs.
  Since framework logging is around 97% of the volume in practice, this is mostly the noise an
  `INFO` floor would drop anyway — but it is a boundary, and you should know where it runs.
- **Not multi-tenant.** One installation belongs to one team. Isolation means a second
  installation — which is cheap, because it is one binary and one file.
- **Not an unlimited store.** Retention is bounded by both age and file size, and sampling is on
  by default. A service at 100 rps writing two lines per request produces roughly 5 GB of logs
  per day; sampled and stored, that comes to roughly 145 MB per day, or a month of history in
  about 4.3 GB. What *is* kept regardless of sampling: everything at `WARN` and above, every
  entity reference, and exact per-template counters — so "how often" and "was this entity touched"
  stay answerable even when the log body is gone.
- **Not a live tail.** For watching a service right after a deploy, `kubectl logs -f` is better
  than anything layered on top of batching, SQLite and an FTS index — and tracy deliberately
  leaves your stdout untouched so that keeps working.
- **Not an audit log of record.** You can investigate with it, but sampling, retention limits,
  size-based eviction and redaction all work against audit guarantees. Anything that must be
  provably complete belongs somewhere else.

## Quick start

Run the server. It is one binary and one file; the only required setting is the ingest key,
because a log collector that quietly started without one is indistinguishable from a healthy one
until the first incident.

```bash
TRACY_INGEST_KEY=dev-key TRACY_DB_PATH=/tmp/tracy.db ./server.kexe
```

Or from the image, which is what a deploy uses:

```bash
docker run -p 8080:8080 -e TRACY_INGEST_KEY=dev-key ghcr.io/youndie/tracy:0.1.0
```

Add the agent to a Ktor service:

```kotlin
val config = AgentConfig(
    service = "orders-api",
    apiKey = ingestKey,              // from your own configuration; there is no System.getenv on native
    endpoint = "https://tracy.example",
    instanceId = podName,            // HOSTNAME in a cluster: a record is traceable back to a restart
)
val tracy = TracyAgent(config, clock = { Clock.System.now().toEpochMilliseconds() })

// Nothing leaves the process until this runs: the agent buffers, the delivery loop sends.
TracyDelivery(tracy, config).start(this)

install(Tracy) { agent = tracy }  // incoming spans, trace context, tail sampling
```

The client plugin belongs to the `HttpClient` you make outgoing calls with — that is what makes
the chain continue on the other side:

```kotlin
val http = HttpClient {
    install(TracyClient) { agent = tracy }  // outgoing calls carry `traceparent`
}
```

Then log. The message is a constant you wrote; the values go into fields, and that separation is
what keeps a caller's input out of the template table later. Logging is `suspend` by design — it
runs inside your request, and the trace context lives in the coroutine, because Kotlin/Native has
no MDC and no `ThreadContextElement`:

```kotlin
val log = tracy.logger("OrdersRouting")

post("/orders") {
    val order = createOrder(call.receive())

    log.info("order created") { field("orderId", order.id, indexed = true) }

    call.respond(order)
}
```

`indexed = true` makes `orderId` an entity key — that is what later answers "show me everything
that ever happened to order 12345", across services and across separate traces.

Point a coding agent at it:

```bash
claude mcp add --transport http tracy https://tracy.example/mcp \
  --header "Authorization: Bearer $TRACY_MCP_TOKEN"
```

With no `TRACY_MCP_TOKEN` set on the server there is no MCP endpoint at all — the feature is off
by default rather than open by default.

## Requirements

Kotlin 2.4.10, Ktor 3.5.2. Targets: `jvm`, `linuxX64`, `linuxArm64`, `macosArm64`.

## Documentation

- [`docs/README.md`](docs/README.md) — layer map
- [`docs/research/research-architecture.md`](docs/research/research-architecture.md) — verified
  facts, decisions and risks. **Start here.**
- [`BACKLOG.md`](BACKLOG.md) — milestones M0…M8

## License

MIT — see [`LICENSE`](LICENSE).
