// The detach watermark: <table_dir>/.detach holds one boundary id W, and a segment is detached —
// excluded from every directory-resolved query — iff its packed segment id (encode_segment_id) is
// <= W. Because ids are order-preserving, the detached set is always a contiguous tail-of-table
// prefix of the listing, and because rdb_detach refuses to detach the newest segment, everything
// detachable is sealed by the writer's rotation invariant. The file is a reader-side sidecar: it is
// not part of the segment byte contract, the writer never reads it, and its name can never match
// the segment naming regex. See docs/segment-reclaim.md.
#pragma once

#include <cstdint>
#include <string>

#include "table_dir.hpp"

namespace arena {

inline constexpr const char* DETACH_FILENAME = ".detach";

// True and sets `watermark` when <table_dir>/.detach exists and parses; false when the file is
// absent (nothing detached). A file that exists but is malformed throws FormatError — it must
// never read as "no watermark", because that would silently re-attach everything.
bool read_detach_watermark(const std::string& table_dir, std::int64_t& watermark);

// Publishes W atomically: write <table_dir>/.detach.tmp.<pid>, fsync, rename(2) over .detach —
// the same idiom the writer uses to publish segments, so a crash leaves either the old or the new
// watermark, both valid states. Throws FormatError on any I/O failure.
void write_detach_watermark(const std::string& table_dir, std::int64_t watermark);

// Removes the watermark (re-attaching everything). An absent file is success.
void clear_detach_watermark(const std::string& table_dir);

// True iff `name` is detached under watermark W: its name encodes and the id is <= W. A name that
// does not encode is never detached — the scans error on such a name at bind regardless.
bool segment_is_detached(const SegmentName& name, std::int64_t watermark);

}  // namespace arena
