# arena-duckdb — Python client

A [uv](https://docs.astral.sh/uv/) project that queries the arena DuckDB extension using the
standard `duckdb` Python library. The extension is built self-contained (it statically links
DuckDB), so it loads like any regular extension — no `RTLD_GLOBAL` or ctypes tricks.

`duckdb` is pinned to **1.5.5** in `pyproject.toml` / `uv.lock` to match the DuckDB version the
extension was linked against; a mismatched library would reject the extension at `LOAD` time.

## Usage

Build the extension first (from the parent dir): `DUCKDB=/path/to/duckdb ../scripts/build_extension.sh`.
Then, from this directory, run queries with `uv run` (it provisions the pinned duckdb on first use):

```sh
# Scan a golden segment file
uv run arena_query.py \
    "SELECT sym, i64, ts FROM arena_scan('../../conformance/golden/all_types.bin') WHERE i8 >= 3"

# Query an arena table by name (replacement scan + arena_dir)
uv run arena_query.py --arena-dir /dev/shm/ito \
    "SELECT sym, count(*), max(px) FROM quotes GROUP BY sym"

# Delimited output (nanosecond timestamps preserved)
uv run arena_query.py --format csv "SELECT ts FROM arena_scan('../../conformance/golden/all_types.bin')"
```

Options: `--ext PATH` (extension path, defaults to `../build/arena.duckdb_extension`),
`--arena-dir DIR`, `--format {table,csv,tsv}`.

Or use the library directly — it's just three lines:

```python
import duckdb
con = duckdb.connect(config={"allow_unsigned_extensions": "true"})
con.load_extension("../build/arena.duckdb_extension")
con.sql("SELECT * FROM arena_scan('../../conformance/golden/all_types.bin')").show()
```
