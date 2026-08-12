// encode_segment_id / decode_segment_id / segment_basename: round-trips, ordering equivalence with
// SegmentName's comparator, and every rejection class the filename regex lets through.
#include <string>
#include <vector>

#include "format/table_dir.hpp"
#include "test_framework.hpp"

namespace {

arena::SegmentName make_name(int ymd, int hhmm, int cycle, int seq) {
    arena::SegmentName n;
    n.year_month_day = ymd;
    n.start_hhmm = hhmm;
    n.cycle_minutes = cycle;
    n.sequence = seq;
    return n;
}

std::int64_t encode_ok(const arena::SegmentName& n) {
    std::int64_t id = 0;
    std::string error;
    if (!arena::encode_segment_id(n, id, error)) {
        testfw::fail("encode failed: " + error, __FILE__, __LINE__);
    }
    return id;
}

void check_rejected(const arena::SegmentName& n) {
    std::int64_t id = 0;
    std::string error;
    CHECK(!arena::encode_segment_id(n, id, error));
    CHECK(!error.empty());
}

}  // namespace

TEST(segment_id_round_trip) {
    const std::vector<arena::SegmentName> cases = {
        make_name(19700101, 0, 1440, 0),      // epoch start, daily
        make_name(20260101, 0, 1440, 3),      // daily fixture shape
        make_name(20260101, 0, 720, 0),       // midnight sub-day: NOT the daily form
        make_name(20260101, 1200, 720, 0),    // subday fixture shape
        make_name(20260810, 930, 5, 12),      // 5-minute roll cycle
        make_name(20261231, 2359, 1, 32767),  // max time-of-day, min cycle, max sequence
        make_name(99991231, 2359, 65535, 0),  // last encodable day, max cycle
    };
    for (const auto& n : cases) {
        std::int64_t id = encode_ok(n);
        CHECK(id >= 0);
        arena::SegmentName back;
        CHECK(arena::decode_segment_id(id, back));
        CHECK_EQ(back.year_month_day, n.year_month_day);
        CHECK_EQ(back.start_hhmm, n.start_hhmm);
        CHECK_EQ(back.cycle_minutes, n.cycle_minutes);
        CHECK_EQ(back.sequence, n.sequence);
        // basename → parse → encode is the same id (canonical spelling round-trips too).
        arena::SegmentName reparsed;
        CHECK(arena::parse_segment_name(arena::segment_basename(back), reparsed));
        CHECK_EQ(encode_ok(reparsed), id);
    }
}

TEST(segment_id_ordering_matches_comparator) {
    // A matrix crossing every field, in strictly increasing SegmentName order when flattened the
    // way operator< orders: (ymd, hhmm, cycle, seq).
    std::vector<arena::SegmentName> ordered;
    for (int ymd : {19700101, 20251231, 20260101, 20260102}) {
        for (int hhmm : {0, 5, 930, 1200, 2359}) {
            for (int cycle : {1, 5, 720, 1440}) {
                for (int seq : {0, 1, 9, 10, 32767}) {
                    ordered.push_back(make_name(ymd, hhmm, cycle, seq));
                }
            }
        }
    }
    for (std::size_t i = 0; i + 1 < ordered.size(); ++i) {
        CHECK(ordered[i] < ordered[i + 1]);  // the matrix really is comparator-ordered
        CHECK(encode_ok(ordered[i]) < encode_ok(ordered[i + 1]));
    }
}

TEST(segment_id_rejects_out_of_range_fields) {
    check_rejected(make_name(0, 0, 1440, 0));           // unparsed name
    check_rejected(make_name(20261301, 0, 1440, 0));    // month 13
    check_rejected(make_name(20260100, 0, 1440, 0));    // day 0
    check_rejected(make_name(20260230, 0, 1440, 0));    // Feb 30
    check_rejected(make_name(19691231, 0, 1440, 0));    // before the epoch
    check_rejected(make_name(20260101, 2400, 5, 0));    // hour 24
    check_rejected(make_name(20260101, 1260, 5, 0));    // minute 60
    check_rejected(make_name(20260101, 0, 0, 0));       // cycle 0
    check_rejected(make_name(20260101, 0, 65536, 0));   // cycle over 16 bits
    check_rejected(make_name(20260101, 0, 1440, 32768));  // sequence over 15 bits
}

TEST(segment_id_decode_rejects_malformed) {
    arena::SegmentName out;
    CHECK(!arena::decode_segment_id(-1, out));
    CHECK(!arena::decode_segment_id(0, out));  // cycle bits are zero — no encode produces this
    // Year past 9999: encode's dates come from 8-digit names, so this id is outside encode's range.
    std::int64_t past_9999 = (std::int64_t{0xFFFFFFFF} << 31) | (std::int64_t{1440} << 15);
    CHECK(!arena::decode_segment_id(past_9999, out));
}

TEST(segment_basename_forms) {
    CHECK_EQ(arena::segment_basename(make_name(20260101, 0, 1440, 3)), std::string("20260101.3.ascham"));
    CHECK_EQ(arena::segment_basename(make_name(20260101, 1200, 720, 0)),
             std::string("20260101.1200.720m.0.ascham"));
    // Midnight sub-day keeps the long form: it is a different roll cycle than daily.
    CHECK_EQ(arena::segment_basename(make_name(20260101, 0, 720, 0)),
             std::string("20260101.0000.720m.0.ascham"));
}
