# What the agent costs a browser app — pre-registration

Open question 1 of [research-clients](../../docs/research/research-clients.md): how much the agent's
core adds to the `.wasm` of a Compose app in the browser. Written **before** the first measurement and
committed on its own, so the threshold cannot move to meet the number.

## What is measured

The same sample app (`samples/compose-app`) built twice as a production browser distribution
(`wasmJsBrowserDistribution`): once with the agent, once with `-Ptracy.agent=false`, where the agent
calls compile to nothing and the dependency is absent. The number is the difference in the **sum of all
`.wasm` files after gzip -9** — what a browser downloads on the first visit. JS glue is listed beside it
and not counted.

## Threshold

**The agent passes if it adds at most 10 % to the app's gzipped `.wasm`.** Above that, a page pays
more for being observed than an observability library is worth, and the agent needs a slimmer browser
build before it is recommended for one.

One run per variant: the build is deterministic, so a second run measures the same bytes.

## Result — 2026-10-07: passes, +5.0 %

Measured on a Mac, Kotlin 2.4.20, Compose Multiplatform 1.12.1, agent at `4bfb423` (with the
background flush), `wasmJsBrowserDistribution` of each variant from clean, `gzip -9`:

| | the app's `.wasm` | skiko `.wasm` | **all `.wasm`, gzip** | `app.js`, gzip |
|---|---|---|---|---|
| without the agent | 576 165 B | 3 324 704 B | **3 900 869 B** | 58 190 B |
| with the agent | 772 075 B | 3 324 704 B | **4 096 779 B** | 59 641 B |
| difference | +195 910 B | 0 | **+195 910 B (+5.0 %)** | +1 451 B |

**Under the 10 % threshold: the agent passes.**

What the threshold did not ask, written down so it is not lost: against the app's **own** `.wasm`,
without skiko, the agent is +34 % (576 → 772 KB gzipped). For this sample skiko is 85 % of what a
first visit downloads, so the total hides the agent. An app whose own code is much larger than this
one's will see a smaller share; an app that one day loads skiko lazily (the wasm-chunks question) will
see a larger one. The 191 KiB is ktor-client-js, kotlinx-serialization and kotlin-logging as much as the
agent's own code, and it is the number to compare against if a slimmer browser build is ever wanted.
