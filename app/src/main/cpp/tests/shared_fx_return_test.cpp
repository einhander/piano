#include "../engine/SharedFxReturn.h"
#include <cassert>

int main() {
    float track[12]{};
    // Simulates render segment beginning at absolute frame 2; return pointer is
    // already advanced to that frame, so indexing into it must start at zero.
    const float segment[] = {1, 2, 3, 4, 5, 6, 7, 8};
    addSharedFxReturn(track, segment, 2, 4);
    for (int i = 0; i < 4; ++i) {
        assert(track[(i + 2) * 2] == segment[i * 2]);
        assert(track[(i + 2) * 2 + 1] == segment[i * 2 + 1]);
    }
    assert(track[0] == 0 && track[2] == 0);
}
