// A FIXED INGEST RATE, not a fixed number of writers.
//
// The 14.09 runs used `--vus N`, which does not hold a rate: every writer waits for its own
// response, so a server that slows down receives less, and the load falls with it. Under that
// shape a memory curve cannot be read — the input changed too. `constant-arrival-rate` keeps the
// input constant and reports `dropped_iterations` when the server cannot take it, which is the
// honest signal that the rate was not held rather than a quietly lower one.
import http from "k6/http";
import { check } from "k6";

const BASE = __ENV.BASE;
const KEY = __ENV.KEY;

export const options = {
    scenarios: {
        ingest: {
            executor: "constant-arrival-rate",
            rate: Number(__ENV.RATE || 40),
            timeUnit: "1s",
            duration: __ENV.DURATION || "2h",
            preAllocatedVUs: Number(__ENV.VUS || 40),
            maxVUs: Number(__ENV.MAXVUS || 200),
            gracefulStop: "10s",
        },
    },
};

// A batch of ten records, the shape the agent actually sends: NDJSON, short field names.
function batch(seq) {
    const now = Date.now();
    const lines = [];
    for (let i = 0; i < 10; i++) {
        lines.push(JSON.stringify({
            t: now,
            n: seq * 10 + i,
            l: i % 7 === 0 ? "ERROR" : "INFO",
            g: "LoadRouting",
            m: "order processed",
            f: { orderId: String(1000 + (seq % 500)), step: "checkout" },
        }));
    }
    return lines.join("\n");
}

export default function () {
    const seq = __VU * 1000000 + __ITER;
    const service = "load-" + (__VU % 8);
    const res = http.post(BASE + "/ingest", batch(seq), {
        headers: {
            "X-Tracy-Key": KEY,
            "X-Tracy-Service": service,
            "X-Tracy-Instance": "pod-" + (__VU % 16),
            "X-Tracy-Seq": String(seq),
            "Content-Type": "application/x-ndjson",
        },
    });
    check(res, { accepted: (r) => r.status === 202 || r.status === 200 });

    // One read in ten: the query path allocates differently from the write path.
    if (__ITER % 10 === 0) {
        const until = Date.now() + 3600000;
        const since = Date.now() - 3600000;
        http.get(`${BASE}/api/logs?service=${service}&since=${since}&until=${until}&limit=50`);
    }
}
