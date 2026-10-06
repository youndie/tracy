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

## Result

Not measured yet.
