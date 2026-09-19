"""
Correctness checks for the query-embedding path.

Latency is measured separately by the load matrix; this asks whether the
vectors are *right*:

  1. shape/normalisation match what the pgvector column and cosine operator
     assume
  2. the query side applies the bge retrieval prefix and the document side
     does not (bge is asymmetric — getting this backwards silently degrades
     recall with no error anywhere)
  3. the vectors stored by precompute_embeddings.py are the same convention
     the live service produces for documents
  4. the prefix actually improves retrieval on this registry
  5. the LRU cache returns the identical vector, not a stale/aliased one
"""

import json
import math
import subprocess
import urllib.request

EMB = "http://localhost:8000"
PSQL = ["docker", "compose", "exec", "-T", "postgres",
        "psql", "-U", "bank", "-d", "banksearch", "-tAF,", "-c"]


def embed(text, is_query):
    body = json.dumps({"text": text, "is_query": is_query}).encode()
    req = urllib.request.Request(EMB + "/embed", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)["embedding"]


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def norm(a):
    return math.sqrt(dot(a, a))


def psql(sql):
    out = subprocess.run(PSQL + [sql], capture_output=True, text=True,
                         cwd="/Users/zackysyarief/intuitive_search")
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return [line for line in out.stdout.strip().splitlines() if line]


def check(label, ok, detail=""):
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}" + (f"  {detail}" if detail else ""))
    return ok


results = []
print("\n1. Shape and normalisation")
q = embed("transfer money to mom", True)
results.append(check("384 dimensions (matches VECTOR(384))", len(q) == 384, f"got {len(q)}"))
results.append(check("unit L2 norm (cosine == dot product)",
                     abs(norm(q) - 1.0) < 1e-3, f"norm={norm(q):.6f}"))

print("\n2. Query/document asymmetry (bge retrieval prefix)")
as_query = embed("transfer money", True)
as_doc = embed("transfer money", False)
sim = dot(as_query, as_doc)
results.append(check("is_query=true and is_query=false differ",
                     sim < 0.999, f"cosine={sim:.4f}"))

print("\n3. Stored vectors use the document convention")
# Rebuild one feature's document exactly as precompute_embeddings.py does.
# Fetched as JSON: the keyword arrays contain commas, so any delimited psql
# output would be ambiguous.
row = json.loads(psql("""SELECT row_to_json(t) FROM (
                           SELECT display_name,
                                  array_to_string(keywords, ', ') AS kw,
                                  array_to_string(aliases, ', ')  AS al,
                                  description
                           FROM features WHERE feature_id='transfer') t;""")[0])
doc = ". ".join(p for p in [row["display_name"], row["kw"], row["al"],
                            row["description"]] if p).strip()
stored = json.loads(psql("SELECT embedding FROM features WHERE feature_id='transfer';")[0])
recomputed_doc = embed(doc, False)
recomputed_query = embed(doc, True)
sim_doc = dot(stored, recomputed_doc)
sim_query = dot(stored, recomputed_query)
results.append(check("stored vector matches is_query=false re-embedding",
                     sim_doc > 0.999, f"cosine={sim_doc:.5f}"))
results.append(check("stored vector is NOT the prefixed (query) form",
                     sim_query < sim_doc, f"query-form cosine={sim_query:.5f}"))

print("\n4. Does the prefix actually improve retrieval?")
probes = [
    ("i want to send cash to my mother", "transfer"),
    ("show me a pdf of last month's account activity", "download_e_statement"),
    ("how much money do i have", "check_balance"),
    ("my card was stolen", "block_card"),
    ("where is my money going each month", "spending_insights"),
]


def top1(vec):
    literal = "[" + ",".join(f"{x:.6f}" for x in vec) + "]"
    return psql(f"""SELECT feature_id FROM features
                    WHERE embedding IS NOT NULL
                    ORDER BY embedding <=> '{literal}'::vector LIMIT 1;""")[0]


with_prefix = sum(top1(embed(text, True)) == want for text, want in probes)
without_prefix = sum(top1(embed(text, False)) == want for text, want in probes)
for text, want in probes:
    got_q = top1(embed(text, True))
    print(f"       {'ok ' if got_q == want else 'MISS'}  {text!r:52} -> {got_q}")
results.append(check(f"prefixed queries retrieve correctly "
                     f"({with_prefix}/{len(probes)} vs {without_prefix}/{len(probes)} unprefixed)",
                     with_prefix >= without_prefix))

print("\n5. Cache returns identical vectors")
a = embed("a stable repeated typeahead prefix", True)
b = embed("a stable repeated typeahead prefix", True)
results.append(check("repeat request is bit-identical", a == b))

print("\n6. Batch endpoint agrees with single")
body = json.dumps({"texts": ["transfer money"], "is_query": False}).encode()
req = urllib.request.Request(EMB + "/embed/batch", data=body,
                             headers={"Content-Type": "application/json"})
with urllib.request.urlopen(req, timeout=30) as r:
    batched = json.load(r)["embeddings"][0]
results.append(check("batch == single for the same text",
                     dot(batched, as_doc) > 0.9999, f"cosine={dot(batched, as_doc):.6f}"))

print(f"\n{sum(results)}/{len(results)} checks passed")
raise SystemExit(0 if all(results) else 1)
