"""
Offline batch job: embed every feature in the registry and write the vector
back into `features.embedding` (pgvector).

Run it once after seeding, and again whenever a feature row is added or its
text changes. Nothing in the request path does this.

    python precompute_embeddings.py            # only rows with NULL embedding
    python precompute_embeddings.py --all      # re-embed everything

Env:
    DATABASE_URL      postgresql://bank:bank@localhost:5432/banksearch
    EMBEDDING_MODEL   BAAI/bge-small-en-v1.5
"""

from __future__ import annotations

import argparse
import os
import sys

import psycopg
from sentence_transformers import SentenceTransformer

DATABASE_URL = os.getenv(
    "DATABASE_URL", "postgresql://bank:bank@localhost:5432/banksearch"
)
MODEL_NAME = os.getenv("EMBEDDING_MODEL", "BAAI/bge-small-en-v1.5")
EXPECTED_DIMS = int(os.getenv("EMBEDDING_DIMS", "384"))


def feature_document(row: dict) -> str:
    """
    The text that represents a feature in vector space.

    Keep this in sync with what users actually type: the display name and
    keywords carry most of the signal, the description adds semantic reach
    ("I want to see where my money went" -> spending_insights).
    """
    parts = [
        row["display_name"],
        ", ".join(row["keywords"] or []),
        ", ".join(row["aliases"] or []),
        row["description"] or "",
    ]
    return ". ".join(p for p in parts if p).strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--all",
        action="store_true",
        help="re-embed every feature, not just rows with a NULL embedding",
    )
    parser.add_argument("--batch-size", type=int, default=32)
    args = parser.parse_args()

    where = "" if args.all else "WHERE embedding IS NULL"

    with psycopg.connect(DATABASE_URL) as conn:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                SELECT feature_id, display_name, description, keywords, aliases
                FROM features {where}
                ORDER BY feature_id
                """
            )
            columns = [d.name for d in cur.description]
            rows = [dict(zip(columns, r)) for r in cur.fetchall()]

        if not rows:
            print("Nothing to embed — all features already have vectors.")
            return 0

        print(f"Loading {MODEL_NAME} ...")
        model = SentenceTransformer(MODEL_NAME, device="cpu")
        dims = model.get_sentence_embedding_dimension()
        if dims != EXPECTED_DIMS:
            print(
                f"ERROR: model produces {dims} dims but the `features.embedding` "
                f"column is VECTOR({EXPECTED_DIMS}). Change the column type or "
                f"pick a {EXPECTED_DIMS}-dim model.",
                file=sys.stderr,
            )
            return 2

        docs = [feature_document(r) for r in rows]
        print(f"Embedding {len(docs)} features ...")
        vectors = model.encode(
            docs,
            normalize_embeddings=True,
            batch_size=args.batch_size,
            show_progress_bar=True,
        )

        with conn.cursor() as cur:
            for row, vec in zip(rows, vectors):
                literal = "[" + ",".join(f"{x:.6f}" for x in vec) + "]"
                cur.execute(
                    "UPDATE features SET embedding = %s::vector, updated_at = now() "
                    "WHERE feature_id = %s",
                    (literal, row["feature_id"]),
                )
        conn.commit()

    print(f"Done. {len(rows)} feature embeddings written.")
    print("Reminder: (re)build the ivfflat index -> db/03_indexes.sql")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
