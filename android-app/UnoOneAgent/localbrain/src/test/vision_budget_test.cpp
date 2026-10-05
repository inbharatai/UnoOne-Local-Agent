#include "../main/cpp/vision_budget.h"
#include <cassert>
#include <limits>
#include <initializer_list>
constexpr bool budgetTests() {
    using namespace vision_budget;
    static_assert(dimensions(768,768)); static_assert(dimensions(32,768));
    static_assert(!dimensions(769,768)); static_assert(!dimensions(768,769));
    static_assert(!dimensions(31,768)); static_assert(!dimensions(768,0));
    static_assert(!dimensions(-1,768)); static_assert(!dimensions(2147483647,2147483647));
    static_assert(encoded(1)); static_assert(encoded(maxEncodedBytes));
    static_assert(!encoded(0)); static_assert(!encoded(maxEncodedBytes + 1));
    static_assert(tokens(1792,256,2048)); static_assert(!tokens(1793,256,2048));
    static_assert(!tokens(0,1,2048)); static_assert(!tokens(1,0,2048));
    static_assert(!tokens(std::numeric_limits<std::size_t>::max(),256,2048));
    static_assert(!tokens(1,2048,2048));
    // Exhaustively establish Qwen's nearest-28/32 alignment cannot escape dimensions.
    for (int n=32;n<=768;++n) for (int factor : {28,32}) {
        int rounded = ((n + factor/2) / factor) * factor;
        if (!(rounded > 0 && rounded <= 768)) return false;
    }
    return true;
}
static_assert(budgetTests());
int main() {
    assert(budgetTests());
    for (int w=32;w<=768;++w) for (int h=32;h<=768;++h) {
        assert(vision_budget::encoder(w,h,32,65536));
        assert(vision_budget::encoder(w,h,28,3136));
    }
    assert(vision_budget::resized(32,32,32,65536) == std::make_pair(256,256));
    assert(vision_budget::resized(256,256,32,65536) == std::make_pair(256,256));
    assert(vision_budget::resized(32,768,32,65536).second > 768);
    assert(!vision_budget::encoder(32,32,32,589825));
    assert(!vision_budget::encoder(32,32,0,65536));
    return 0;
}
