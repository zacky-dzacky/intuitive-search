"""
Minimal asyncio HTTP load generator with keep-alive and percentile reporting.

No third-party deps. Each worker holds one persistent connection and issues
requests serially, so `concurrency` == in-flight requests == open sockets.

  python load.py --url http://localhost:8099/api/search --total 10000 \
                 --concurrency 200 --method POST --body '{"query":"trf"}'
"""

import argparse
import asyncio
import json
import statistics
import time
from collections import Counter
from urllib.parse import urlparse


class Worker:
    def __init__(self, host, port, request_bytes, expect_close=False):
        self.host = host
        self.port = port
        self.request = request_bytes
        self.reader = None
        self.writer = None

    def set_request(self, request_bytes):
        self.request = request_bytes

    async def connect(self):
        self.reader, self.writer = await asyncio.open_connection(self.host, self.port)

    async def close(self):
        if self.writer is not None:
            try:
                self.writer.close()
                await self.writer.wait_closed()
            except Exception:
                pass
            self.writer = None

    async def one(self):
        """Send one request, read one full response. Returns (status, body_len)."""
        if self.writer is None:
            await self.connect()
        self.writer.write(self.request)
        await self.writer.drain()

        # Status line
        line = await self.reader.readuntil(b"\r\n")
        status = int(line.split(b" ")[1])

        # Headers
        length = None
        chunked = False
        close_after = False
        while True:
            header = await self.reader.readuntil(b"\r\n")
            if header == b"\r\n":
                break
            lower = header.lower()
            if lower.startswith(b"content-length:"):
                length = int(header.split(b":", 1)[1].strip())
            elif lower.startswith(b"transfer-encoding:") and b"chunked" in lower:
                chunked = True
            elif lower.startswith(b"connection:") and b"close" in lower:
                close_after = True

        # Body
        body_len = 0
        if chunked:
            while True:
                size_line = await self.reader.readuntil(b"\r\n")
                size = int(size_line.strip().split(b";")[0], 16)
                if size == 0:
                    await self.reader.readuntil(b"\r\n")
                    break
                chunk = await self.reader.readexactly(size)
                body_len += len(chunk)
                await self.reader.readexactly(2)
        elif length:
            body = await self.reader.readexactly(length)
            body_len = len(body)

        if close_after:
            await self.close()
        return status, body_len


async def run(url, method, body, total, concurrency, warmup):
    parsed = urlparse(url)
    host = parsed.hostname
    port = parsed.port or 80
    path = parsed.path + (("?" + parsed.query) if parsed.query else "")

    def build(seq):
        """Render one request. `{i}` in the path/body is replaced per request,
        which is how we defeat any server-side per-query cache."""
        p = path.replace("{i}", str(seq))
        b = body.replace("{i}", str(seq)) if body else ""
        payload = b.encode()
        head = [
            f"{method} {p} HTTP/1.1",
            f"Host: {host}:{port}",
            "Connection: keep-alive",
            "Accept: application/json",
        ]
        if payload:
            head.append("Content-Type: application/json")
            head.append(f"Content-Length: {len(payload)}")
        return ("\r\n".join(head) + "\r\n\r\n").encode() + payload

    varies = "{i}" in path or "{i}" in body
    request_bytes = build(0)

    remaining = total
    lock = asyncio.Lock()
    latencies = []
    statuses = Counter()
    errors = Counter()

    async def take():
        """Claim one request slot; returns its sequence number or None."""
        nonlocal remaining
        async with lock:
            if remaining <= 0:
                return None
            remaining -= 1
            return total - remaining

    async def worker():
        w = Worker(host, port, request_bytes)
        try:
            await w.connect()
            # Warm the connection without recording it.
            for _ in range(warmup):
                try:
                    await w.one()
                except Exception:
                    break
            while (seq := await take()) is not None:
                if varies:
                    w.set_request(build(seq))
                started = time.perf_counter()
                try:
                    status, _ = await w.one()
                    latencies.append((time.perf_counter() - started) * 1000)
                    statuses[status] += 1
                except Exception as exc:
                    errors[type(exc).__name__] += 1
                    await w.close()
        finally:
            await w.close()

    started_at = time.perf_counter()
    await asyncio.gather(*[worker() for _ in range(concurrency)])
    elapsed = time.perf_counter() - started_at

    ok = sum(v for k, v in statuses.items() if 200 <= k < 300)
    result = {
        "url": url,
        "concurrency": concurrency,
        "requested": total,
        "completed": sum(statuses.values()),
        "ok": ok,
        "non_2xx": sum(v for k, v in statuses.items() if not 200 <= k < 300),
        "errors": dict(errors),
        "statuses": dict(statuses),
        "wall_s": round(elapsed, 2),
        "rps": round(sum(statuses.values()) / elapsed, 1) if elapsed else 0,
    }
    if latencies:
        latencies.sort()

        def pct(p):
            idx = min(len(latencies) - 1, int(round(p / 100 * len(latencies))) - 1)
            return round(latencies[max(idx, 0)], 1)

        result["latency_ms"] = {
            "min": round(latencies[0], 1),
            "p50": pct(50),
            "p90": pct(90),
            "p95": pct(95),
            "p99": pct(99),
            "max": round(latencies[-1], 1),
            "mean": round(statistics.fmean(latencies), 1),
        }
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--method", default="GET")
    ap.add_argument("--body", default="")
    ap.add_argument("--total", type=int, default=10000)
    ap.add_argument("--concurrency", type=int, default=100)
    ap.add_argument("--warmup", type=int, default=1)
    ap.add_argument("--label", default="")
    args = ap.parse_args()

    res = asyncio.run(run(args.url, args.method, args.body,
                          args.total, args.concurrency, args.warmup))
    if args.label:
        res["label"] = args.label
    print(json.dumps(res))


if __name__ == "__main__":
    main()
