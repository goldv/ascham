#!/usr/bin/env python3
"""Run a DuckDB query with the arena extension loaded, using the standard `duckdb` Python library.

The arena extension is built self-contained (it statically links DuckDB), so it loads like any
regular extension — `con.load_extension(path)` with no dlopen tricks. This project pins
`duckdb==1.5.5` so the library version matches the DuckDB the extension was linked against; a
mismatch would be rejected at LOAD time.

Run it through uv (which provisions the pinned duckdb automatically):

    uv run arena_query.py "SELECT sym, i64, ts FROM arena_scan('../../conformance/golden/all_types.bin')"
    uv run arena_query.py --arena-dir /dev/shm/ito "SELECT sym, count(*) FROM quotes GROUP BY sym"

By default results render with DuckDB's native table formatter (correct for every type, including
nanosecond timestamps). Use --format csv/tsv for delimited output.
"""
import argparse
import os
import sys

import duckdb


def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    default_ext = os.path.normpath(os.path.join(here, "..", "build", "arena.duckdb_extension"))

    ap = argparse.ArgumentParser(description="Run a DuckDB query with the arena extension loaded.")
    ap.add_argument("sql", help="SQL to run (results printed to stdout)")
    ap.add_argument("--ext", default=default_ext,
                    help="path to arena.duckdb_extension (default: <repo>/build/arena.duckdb_extension)")
    ap.add_argument("--arena-dir", help="SET arena_dir=<dir> before the query (enables table-name scans)")
    ap.add_argument("--format", choices=["table", "csv", "tsv"], default="table")
    args = ap.parse_args(argv)

    ext = os.path.abspath(args.ext)
    if not os.path.exists(ext):
        sys.exit(f"extension not found at {ext} (build it: ../scripts/build_extension.sh)")

    try:
        con = duckdb.connect(config={"allow_unsigned_extensions": "true"})
        con.load_extension(ext)
        if args.arena_dir:
            con.execute("SET arena_dir = '{}'".format(args.arena_dir.replace("'", "''")))

        if args.format == "table":
            print(con.sql(args.sql))
        else:
            sep = "\t" if args.format == "tsv" else ","
            # Cast every column to VARCHAR in SQL so DuckDB renders each value (preserving e.g.
            # nanosecond timestamps, which Python's datetime would truncate to microseconds).
            try:
                rel = con.sql(f"SELECT COLUMNS(*)::VARCHAR FROM ({args.sql}) AS __arena_q")
                cols, rows = rel.columns, rel.fetchall()
            except duckdb.Error:  # not wrappable (e.g. a non-SELECT statement) — run raw
                rel = con.sql(args.sql)
                cols = rel.columns
                rows = [tuple(None if c is None else str(c) for c in r) for r in rel.fetchall()]
            print(sep.join(cols))
            for row in rows:
                print(sep.join("" if c is None else c for c in row))
    except duckdb.Error as e:
        sys.exit(f"error: {e}")


if __name__ == "__main__":
    main()
