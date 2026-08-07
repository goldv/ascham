Arrow Java has the pieces for this in the `arrow-algorithm` module, but they're per-vector — you assemble the multi-column part yourself with `CompositeVectorComparator`.

```xml
<dependency>
  <groupId>org.apache.arrow</groupId>
  <artifactId>arrow-algorithm</artifactId>
  <version>${arrow.version}</version>
</dependency>
```

## The core recipe

```java
public record SortKey(String column, boolean ascending) {}

@SuppressWarnings({"unchecked", "rawtypes"})
public static VectorValueComparator<ValueVector> rowComparator(
        VectorSchemaRoot root, List<SortKey> keys, boolean stable) {

    List<VectorValueComparator> inner = new ArrayList<>(keys.size() + 1);
    for (SortKey k : keys) {
        ValueVector v = root.getVector(k.column());
        VectorValueComparator cmp = DefaultVectorComparators.createDefaultComparator(v);
        cmp.attachVector(v);                 // binds both sides to the same vector
        inner.add(k.ascending() ? cmp : new ReverseComparator<>(cmp));
    }
    if (stable) inner.add(new RowIndexComparator());   // total order tie-break
    return new CompositeVectorComparator(inner.toArray(new VectorValueComparator[0]));
}
```

`CompositeVectorComparator.compare(i1, i2)` walks the inner comparators and returns on the first non-zero — exactly the lexicographic row comparison you want. Because each inner comparator is already attached to its own vector, the composite's own `attachVector` call is a harmless no-op.

The two helpers:

```java
final class ReverseComparator<V extends ValueVector> extends VectorValueComparator<V> {
    private final VectorValueComparator<V> inner;
    ReverseComparator(VectorValueComparator<V> inner) {
        super(inner.getValueWidth());
        this.inner = inner;
    }
    @Override public int compare(int i1, int i2)        { return -Integer.signum(inner.compare(i1, i2)); }
    @Override public int compareNotNull(int i1, int i2) { return -Integer.signum(inner.compareNotNull(i1, i2)); }
    @Override public VectorValueComparator<V> createNew() { return new ReverseComparator<>(inner.createNew()); }
}

final class RowIndexComparator extends VectorValueComparator<ValueVector> {
    @Override public int compare(int i1, int i2)        { return Integer.compare(i1, i2); }
    @Override public int compareNotNull(int i1, int i2) { return Integer.compare(i1, i2); }
    @Override public VectorValueComparator<ValueVector> createNew() { return new RowIndexComparator(); }
}
```

`signum` matters: several built-in comparators (notably the variable-width byte comparator) return arbitrary magnitudes, and naive negation is wrong at `Integer.MIN_VALUE`.

## Producing the permutation

```java
IntVector indices = new IntVector("sort_idx", allocator);
indices.allocateNew(root.getRowCount());
indices.setValueCount(root.getRowCount());
for (int i = 0; i < root.getRowCount(); i++) indices.set(i, i);

new IndexSorter<ValueVector>().sort(
        root.getVector(keys.get(0).column()),   // vector arg is unused by the composite
        indices,
        rowComparator(root, keys, true));
```

`indices.get(i)` is now the source row for output position `i`. `IndexSorter` is an unstable off-heap quicksort, which is why the `RowIndexComparator` tie-break is worth adding if you care about determinism across runs or replay.

If you'd rather not pay for the off-heap stack and the `IntVector` accessor indirection on every swap, sorting a plain `int[]` with the same comparator and only writing it into an `IntVector` at the end is measurably faster, and lets you use a stable merge sort directly.

## Materializing (if you need to)

The index vector alone is often enough — keep it as a selection vector and let downstream apply it lazily. If you do need a sorted root, gather column-at-a-time rather than row-at-a-time, so the destination writes stay sequential and only the source reads scatter:

```java
for (int c = 0; c < root.getFieldVectors().size(); c++) {
    FieldVector src = root.getVector(c), dst = out.getVector(c);
    dst.allocateNew();
    for (int i = 0; i < rowCount; i++) dst.copyFromSafe(indices.get(i), i, src);
    dst.setValueCount(rowCount);
}
out.setRowCount(rowCount);
```

## Things to watch

- **Null ordering.** The base `VectorValueComparator.compare` puts nulls first. Wrapping in `ReverseComparator` flips that too, so descending gives you nulls-last. If you need nulls-first-on-descending you'll have to override `compare` rather than delegate.
- **Unsupported types.** `createDefaultComparator` throws for lists, structs, and unions. Fixed-width numerics, temporal types, decimals, `VarChar`/`VarBinary` are covered.
- **String keys are the expensive case.** The variable-width comparator does byte-wise `memcmp`-style compares with two offset lookups per row per probe. Given your instrument codes are already globally-stable int32, sorting on the code and joining the label afterwards will be a large win over sorting on the symbol text.
- `arrow-algorithm` is still flagged experimental and hasn't seen much investment; the API above has been stable for a long while, but it's not vectorized. For anything past a few hundred thousand rows in the analytics path, pushing the ORDER BY into DuckDB will beat it comfortably.