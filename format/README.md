# The ascham format contract

This directory is the authoritative definition of the ascham segment format, split the way Apache
Arrow splits its own: an IDL for the one message-shaped region, prose for everything else.

- **[`segment-format.md`](segment-format.md)** — the format and internals reference: byte layout,
  concurrency protocol, FlatBuffers usage, [regenerating the
  bindings](segment-format.md#regenerating-the-bindings), [adding a language
  binding](segment-format.md#adding-a-language-binding), and [extending the
  format](segment-format.md#extending-the-format).
- **[`Layout.fbs`](Layout.fbs)** — the FlatBuffers IDL for the layout-descriptor region (format v2+).

Both are authoritative; neither may change without following the format-break procedure in
`segment-format.md`. To *use* an implementation rather than change the format, see
[`../docs/java-guide.md`](../docs/java-guide.md) or [`../docs/cpp-guide.md`](../docs/cpp-guide.md).
