# compose-app

A Compose Multiplatform app writing to tracy the way research-clients describes:

- with a **client key**: it writes only to `app:<name>`, and everything it writes is data;
- with an **action** around what a person did, so the trace starts in the app;
- with a **flush when the app leaves the screen**.

Two buttons: one opens a trace (`order.open`), the other records a screen that drew a placeholder for
an unknown component, with the wire type as an indexed field (`originalType`).

A build of its own. It takes the agent from this checkout through `includeBuild("../..")`, so it always
runs the agent as it is here, not a published one.

## Run it

Start tracy with a client key for the app (from the repository root, as in tracy-server §7), then the
app (from this directory):

```bash
TRACY_INGEST_KEY=dev-key TRACY_CLIENT_KEYS=dev-app-key=compose-app TRACY_DB_PATH=/tmp/tracy.db ./server/build/bin/macosArm64/releaseExecutable/server.kexe
../../gradlew -p . run                            # desktop
../../gradlew -p . wasmJsBrowserDevelopmentRun    # browser
```

Then ask tracy over MCP: `search_spans(service = "app:compose-app")` finds the action, and
`get_entity(key = "originalType", value = "promo-carousel-v3")` finds the degradation.

## Checked by hand — 2026-10-07, browser

The production bundle against a local `server.kexe` (macOS) with `TRACY_CLIENT_KEYS=dev-app-key=compose-app`,
both buttons pressed once. The page sent `OPTIONS /ingest` → 204 and two `POST /ingest` → 202, and the
database held: service `app:compose-app` (the header said `compose-app`; the key decided), a root span
`order.open`, the placeholder warning with `untrusted = 1`, and an entity reference
`originalType = promo-carousel-v3`. Desktop, Android and iOS were not run against a server.

## The size question

`MEASUREMENT.md` records what the agent may add to a browser app's `.wasm` — written before it was
measured — and the result.
