// Minimal DuckDB host for testing the arena extension, built against libduckdb.so (the same shared
// library duckdb_jdbc / the Flight server use). The CLI binary is statically linked and exports no
// symbols, so a standalone loadable can't resolve against it; libduckdb.so exports the full API, so
// a loadable extension dlopened by it resolves cleanly. Runs each argv as SQL, printing SELECT
// results as TSV; exits non-zero on the first error.
#include "duckdb.h"

#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>

static const char *string_ptr(duckdb_string_t s) {
    return duckdb_string_is_inlined(s) ? s.value.inlined.inlined : s.value.pointer.ptr;
}

static void print_int128(__int128 v) {
    if (v < 0) { printf("-"); }
    unsigned __int128 u = v < 0 ? (unsigned __int128)(-v) : (unsigned __int128)v;
    char buf[40];
    int i = sizeof(buf);
    buf[--i] = '\0';
    do { buf[--i] = (char)('0' + (int)(u % 10)); u /= 10; } while (u);
    printf("%s", buf + i);
}

static void print_cell(duckdb_type type, duckdb_vector vec, void *data, idx_t row) {
    uint64_t *validity = duckdb_vector_get_validity(vec);
    if (validity && !duckdb_validity_row_is_valid(validity, row)) {
        printf("NULL");
        return;
    }
    switch (type) {
    case DUCKDB_TYPE_BOOLEAN: printf("%s", ((bool *)data)[row] ? "true" : "false"); break;
    case DUCKDB_TYPE_TINYINT: printf("%d", ((int8_t *)data)[row]); break;
    case DUCKDB_TYPE_SMALLINT: printf("%d", ((int16_t *)data)[row]); break;
    case DUCKDB_TYPE_INTEGER: printf("%d", ((int32_t *)data)[row]); break;
    case DUCKDB_TYPE_BIGINT: printf("%" PRId64, ((int64_t *)data)[row]); break;
    case DUCKDB_TYPE_UTINYINT: printf("%u", ((uint8_t *)data)[row]); break;
    case DUCKDB_TYPE_USMALLINT: printf("%u", ((uint16_t *)data)[row]); break;
    case DUCKDB_TYPE_UINTEGER: printf("%u", ((uint32_t *)data)[row]); break;
    case DUCKDB_TYPE_UBIGINT: printf("%" PRIu64, ((uint64_t *)data)[row]); break;
    case DUCKDB_TYPE_FLOAT: printf("%g", ((float *)data)[row]); break;
    case DUCKDB_TYPE_DOUBLE: printf("%g", ((double *)data)[row]); break;
    case DUCKDB_TYPE_HUGEINT: {
        duckdb_hugeint h = ((duckdb_hugeint *)data)[row];
        print_int128(((__int128)h.upper << 64) | h.lower);
        break;
    }
    case DUCKDB_TYPE_VARCHAR: {
        duckdb_string_t s = ((duckdb_string_t *)data)[row];
        printf("%.*s", (int)s.value.inlined.length, string_ptr(s));
        break;
    }
    default: printf("<type %d>", (int)type);
    }
}

static void print_result(duckdb_result *res) {
    idx_t cols = duckdb_column_count(res);
    for (idx_t c = 0; c < cols; c++) {
        printf("%s%s", c ? "\t" : "", duckdb_column_name(res, c));
    }
    printf("\n");
    duckdb_data_chunk chunk;
    while ((chunk = duckdb_fetch_chunk(*res)) != NULL) {
        idx_t n = duckdb_data_chunk_get_size(chunk);
        for (idx_t r = 0; r < n; r++) {
            for (idx_t c = 0; c < cols; c++) {
                duckdb_vector vec = duckdb_data_chunk_get_vector(chunk, c);
                print_cell(duckdb_column_type(res, c), vec, duckdb_vector_get_data(vec), r);
                printf(c + 1 < cols ? "\t" : "\n");
            }
        }
        duckdb_destroy_data_chunk(&chunk);
    }
}

int main(int argc, char **argv) {
    duckdb_config config;
    duckdb_create_config(&config);
    duckdb_set_config(config, "allow_unsigned_extensions", "true");

    duckdb_database db;
    char *err = NULL;
    if (duckdb_open_ext(NULL, &db, config, &err) != DuckDBSuccess) {
        fprintf(stderr, "open failed: %s\n", err ? err : "?");
        return 1;
    }
    duckdb_destroy_config(&config);

    duckdb_connection con;
    duckdb_connect(db, &con);

    int rc = 0;
    for (int i = 1; i < argc && rc == 0; i++) {
        duckdb_result res;
        if (duckdb_query(con, argv[i], &res) != DuckDBSuccess) {
            fprintf(stderr, "query error: %s\n", duckdb_result_error(&res));
            rc = 2;
        } else if (duckdb_column_count(&res) > 0) {
            print_result(&res);
        }
        duckdb_destroy_result(&res);
    }

    duckdb_disconnect(&con);
    duckdb_close(&db);
    return rc;
}
