#include "MidiFileParser.h"
#include <algorithm>
#include <fstream>
#include <cstring>

namespace {

// Read VLQ from buffer at given position, advance position
bool readVlqFromBuffer(const uint8_t* data, size_t dataSize, size_t& pos, uint32_t& out) {
    out = 0;
    for (int i = 0; i < 4; i++) {
        if (pos >= dataSize) return false;
        uint8_t b = data[pos++];
        out = (out << 7) | (b & 0x7F);
        if ((b & 0x80) == 0) return true;
    }
    return true;
}

}  // namespace

MidiFileParser::MidiFileParser() {}
MidiFileParser::~MidiFileParser() {}

bool MidiFileParser::parse(const char* filePath,
                           std::vector<RecordedMidiEvent>& outEvents,
                           std::vector<std::pair<int64_t, uint32_t>>& outTempoMap,
                           std::vector<std::pair<int64_t, std::pair<int, int>>>& outTimeSignatures,
                           int* outTicksPerBeat) {
    // Clear outputs
    outEvents.clear();
    outTempoMap.clear();
    outTimeSignatures.clear();

    // Read entire file into memory
    std::ifstream file(filePath, std::ios::binary);
    if (!file.is_open()) {
        return false;
    }

    // Get file size
    file.seekg(0, std::ios::end);
    size_t fileSize = static_cast<size_t>(file.tellg());
    file.seekg(0, std::ios::beg);

    if (fileSize < 14) {
        return false;
    }

    mFileData.resize(fileSize);
    if (!file.read(reinterpret_cast<char*>(mFileData.data()), fileSize)) {
        return false;
    }
    file.close();

    mTicksPerBeat = 480;  // default

    // Parse header chunk: MThd
    if (mFileData[0] != 'M' || mFileData[1] != 'T' ||
        mFileData[2] != 'h' || mFileData[3] != 'd') {
        return false;
    }

    uint32_t headerLen = (static_cast<uint32_t>(mFileData[4]) << 24) |
                         (static_cast<uint32_t>(mFileData[5]) << 16) |
                         (static_cast<uint32_t>(mFileData[6]) << 8) |
                         static_cast<uint32_t>(mFileData[7]);
    if (headerLen != 6) {
        return false;
    }

    uint16_t format = (static_cast<uint16_t>(mFileData[8]) << 8) |
                      static_cast<uint16_t>(mFileData[9]);
    uint16_t numTracks = (static_cast<uint16_t>(mFileData[10]) << 8) |
                         static_cast<uint16_t>(mFileData[11]);
    uint16_t tpb = (static_cast<uint16_t>(mFileData[12]) << 8) |
                   static_cast<uint16_t>(mFileData[13]);
    mTicksPerBeat = tpb;

    if (format > 2) {
        return false;
    }

    // Parse all track chunks for MIDI events
    size_t pos = 14;
    for (uint16_t i = 0; i < numTracks && pos < mFileData.size(); i++) {
        if (pos + 8 > mFileData.size()) break;

        if (mFileData[pos] != 'M' || mFileData[pos + 1] != 'T' ||
            mFileData[pos + 2] != 'r' || mFileData[pos + 3] != 'k') {
            pos++;
            continue;
        }

        uint32_t trackLen = (static_cast<uint32_t>(mFileData[pos + 4]) << 24) |
                            (static_cast<uint32_t>(mFileData[pos + 5]) << 16) |
                            (static_cast<uint32_t>(mFileData[pos + 6]) << 8) |
                            static_cast<uint32_t>(mFileData[pos + 7]);

        size_t trackDataStart = pos + 8;
        size_t trackDataEnd = trackDataStart + trackLen;
        if (trackDataEnd > mFileData.size()) break;

        std::vector<RecordedMidiEvent> trackEvents;
        if (!parseTrackEvents(mFileData, trackDataStart, trackDataEnd,
                              trackEvents, mTicksPerBeat)) {
            return false;
        }

        for (auto& evt : trackEvents) {
            evt.trackId = static_cast<uint8_t>(i);
            outEvents.push_back(evt);
        }

        pos = trackDataEnd;
    }

    // Sort events by tick
    std::stable_sort(outEvents.begin(), outEvents.end(),
              [](const RecordedMidiEvent& a, const RecordedMidiEvent& b) {
                  return a.tick < b.tick;
              });

    // Re-scan tracks for tempo map (meta 0x51)
    pos = 14;
    for (uint16_t i = 0; i < numTracks && pos < mFileData.size(); i++) {
        if (pos + 8 > mFileData.size()) break;
        if (mFileData[pos] != 'M' || mFileData[pos + 1] != 'T' ||
            mFileData[pos + 2] != 'r' || mFileData[pos + 3] != 'k') {
            pos++;
            continue;
        }
        uint32_t trackLen = (static_cast<uint32_t>(mFileData[pos + 4]) << 24) |
                            (static_cast<uint32_t>(mFileData[pos + 5]) << 16) |
                            (static_cast<uint32_t>(mFileData[pos + 6]) << 8) |
                            static_cast<uint32_t>(mFileData[pos + 7]);
        size_t trackDataStart = pos + 8;
        size_t trackDataEnd = trackDataStart + trackLen;
        if (trackDataEnd > mFileData.size()) break;

        int64_t absTick = 0;
        size_t p = trackDataStart;
        uint8_t runningStatus = 0;

        while (p < trackDataEnd) {
            uint32_t delta = 0;
            if (!readVlqFromBuffer(mFileData.data(), trackDataEnd, p, delta)) break;
            absTick += static_cast<int64_t>(delta);

            if (p >= trackDataEnd) break;

            uint8_t byte = mFileData[p];
            if (byte < 0x80) {
                byte = runningStatus;
            } else {
                p++;
                if (byte < 0xF0) {
                    runningStatus = byte;
                }
            }

            if (byte == 0xFF) {
                if (p + 1 >= trackDataEnd) break;
                uint8_t metaType = mFileData[p];
                p += 1;

                uint32_t len = 0;
                if (!readVlqFromBuffer(mFileData.data(), trackDataEnd, p, len)) break;
                if (p + len > trackDataEnd) break;

                if (metaType == 0x51 && len == 3) {
                    uint32_t microseconds = 0;
                    if (p + 3 <= trackDataEnd) {
                        microseconds = (static_cast<uint32_t>(mFileData[p]) << 16) |
                                       (static_cast<uint32_t>(mFileData[p + 1]) << 8) |
                                       static_cast<uint32_t>(mFileData[p + 2]);
                        outTempoMap.push_back({absTick, microseconds});
                    }
                    p += len;
                } else {
                    p += len;
                }
            } else if (byte >= 0x80 && byte < 0xF0) {
                p += ((byte & 0xF0) == 0xC0 || (byte & 0xF0) == 0xD0) ? 1 : 2;
            } else if (byte >= 0xF0) {
                p++;
                if (byte == 0xF0 || byte == 0xF7) {
                    uint32_t len = 0;
                    if (!readVlqFromBuffer(mFileData.data(), trackDataEnd, p, len)) break;
                    if (p + len > trackDataEnd) break;
                    p += len;
                } else if (byte == 0xF1) {
                    uint32_t len = 0;
                    if (!readVlqFromBuffer(mFileData.data(), trackDataEnd, p, len)) break;
                    if (p + len > trackDataEnd) break;
                    p += len;
                } else if (byte == 0xF2) {
                    p += 2;
                } else if (byte == 0xF3) {
                    p += 1;
                }
            }
        }
        pos = trackDataEnd;
    }

    // Sort tempo map by tick
    std::sort(outTempoMap.begin(), outTempoMap.end(),
              [](const auto& a, const auto& b) { return a.first < b.first; });

    if (outTempoMap.empty()) {
        outTempoMap.push_back({0, 500000});
    }

    // Re-scan tracks for time signatures (meta 0x58)
    pos = 14;
    for (uint16_t i = 0; i < numTracks && pos < mFileData.size(); i++) {
        if (pos + 8 > mFileData.size()) break;
        if (mFileData[pos] != 'M' || mFileData[pos + 1] != 'T' ||
            mFileData[pos + 2] != 'r' || mFileData[pos + 3] != 'k') {
            pos++;
            continue;
        }
        uint32_t trackLen = (static_cast<uint32_t>(mFileData[pos + 4]) << 24) |
                            (static_cast<uint32_t>(mFileData[pos + 5]) << 16) |
                            (static_cast<uint32_t>(mFileData[pos + 6]) << 8) |
                            static_cast<uint32_t>(mFileData[pos + 7]);
        size_t trackDataStart = pos + 8;
        size_t trackDataEnd = trackDataStart + trackLen;
        if (trackDataEnd > mFileData.size()) break;

        int64_t absTick = 0;
        size_t p = trackDataStart;
        uint8_t runningStatus = 0;

        while (p < trackDataEnd) {
            uint32_t delta = 0;
            if (!readVlqFromBuffer(mFileData.data(), trackDataEnd, p, delta)) break;
            absTick += static_cast<int64_t>(delta);

            if (p >= trackDataEnd) break;

            uint8_t byte = mFileData[p];
            if (byte < 0x80) {
                byte = runningStatus;
            } else {
                p++;
                if (byte < 0xF0) {
                    runningStatus = byte;
                }
            }

            if (byte == 0xFF) {
                if (p + 1 >= trackDataEnd) break;
                uint8_t metaType = mFileData[p];
                p += 1;

                uint32_t len = 0;
                if (!readVlqFromBuffer(mFileData.data(), trackDataEnd, p, len)) break;
                if (p + len > trackDataEnd) break;

                if (metaType == 0x58 && len >= 4) {
                    int numerator = static_cast<int>(mFileData[p]);
                    uint8_t denominator = mFileData[p + 1];
                    int denomValue = 1 << denominator;
                    outTimeSignatures.push_back({absTick, {numerator, denomValue}});
                    p += len;
                } else {
                    p += len;
                }
            } else if (byte >= 0x80 && byte < 0xF0) {
                p += ((byte & 0xF0) == 0xC0 || (byte & 0xF0) == 0xD0) ? 1 : 2;
            } else if (byte >= 0xF0) {
                p++;
                if (byte == 0xF0 || byte == 0xF7) {
                    uint32_t len = 0;
                    if (!readVlqFromBuffer(mFileData.data(), trackDataEnd, p, len)) break;
                    if (p + len > trackDataEnd) break;
                    p += len;
                } else if (byte == 0xF1) {
                    uint32_t len = 0;
                    if (!readVlqFromBuffer(mFileData.data(), trackDataEnd, p, len)) break;
                    if (p + len > trackDataEnd) break;
                    p += len;
                } else if (byte == 0xF2) {
                    p += 2;
                } else if (byte == 0xF3) {
                    p += 1;
                }
            }
        }
        pos = trackDataEnd;
    }

    // Sort time signatures by tick
    std::sort(outTimeSignatures.begin(), outTimeSignatures.end(),
              [](const auto& a, const auto& b) { return a.first < b.first; });

    if (outTimeSignatures.empty()) {
        outTimeSignatures.push_back({0, {4, 4}});
    }

    if (outTicksPerBeat) {
        *outTicksPerBeat = mTicksPerBeat;
    }

    return true;
}

bool MidiFileParser::readByte(uint8_t& out) {
    if (mPos >= mFileData.size()) return false;
    out = mFileData[mPos++];
    return true;
}

bool MidiFileParser::readUint32(uint32_t& out) {
    uint8_t b0, b1, b2, b3;
    if (!readByte(b0) || !readByte(b1) || !readByte(b2) || !readByte(b3)) return false;
    out = (static_cast<uint32_t>(b0) << 24) |
          (static_cast<uint32_t>(b1) << 16) |
          (static_cast<uint32_t>(b2) << 8) |
          static_cast<uint32_t>(b3);
    return true;
}

bool MidiFileParser::readVlq(uint32_t& out) {
    out = 0;
    for (int i = 0; i < 4; i++) {
        uint8_t b;
        if (!readByte(b)) return false;
        out = (out << 7) | (b & 0x7F);
        if ((b & 0x80) == 0) return true;
    }
    return false;
}

bool MidiFileParser::parseTrack(std::vector<uint8_t>& trackData,
                                 std::vector<RecordedMidiEvent>& outEvents,
                                 int ticksPerBeat) {
    return true;
}

bool MidiFileParser::parseTrackEvents(std::vector<uint8_t>& trackData,
                                       size_t dataStart,
                                       size_t dataEnd,
                                       std::vector<RecordedMidiEvent>& outEvents,
                                       int ticksPerBeat) {
    (void)ticksPerBeat;  // Used when converting ticks to time
    int64_t absTick = 0;
    size_t p = dataStart;
    uint8_t runningStatus = 0;

    while (p < dataEnd) {
        uint32_t delta = 0;
        if (!readVlqFromBuffer(trackData.data(), dataEnd, p, delta)) return false;
        absTick += static_cast<int64_t>(delta);

        if (p >= dataEnd) break;

        uint8_t byte = trackData[p];
        if (byte < 0x80) {
            byte = runningStatus;
        } else {
            p++;
            if (byte < 0xF0) {
                runningStatus = byte;
            }
        }

        if (byte == 0xFF) {
            if (p + 1 >= dataEnd) break;
            uint8_t metaType = trackData[p];
            p += 1;

            uint32_t len = 0;
            if (!readVlqFromBuffer(trackData.data(), dataEnd, p, len)) break;
            if (p + len > dataEnd) break;

            if (metaType == 0x2F) {
                p += len;
                break;
            }

            p += len;
        } else if (byte >= 0x80 && byte < 0xF0) {
            uint8_t nData = ((byte & 0xF0) == 0xC0 || (byte & 0xF0) == 0xD0) ? 1 : 2;
            if (p + nData > dataEnd) break;
            processEvent(byte, trackData, p, absTick, 0, outEvents);
            p += nData;
        } else if (byte >= 0xF0) {
            p++;
            if (byte == 0xF0 || byte == 0xF7) {
                uint32_t len = 0;
                if (!readVlqFromBuffer(trackData.data(), dataEnd, p, len)) break;
                if (p + len > dataEnd) break;
                p += len;
            } else if (byte == 0xF1) {
                uint32_t len = 0;
                if (!readVlqFromBuffer(trackData.data(), dataEnd, p, len)) break;
                if (p + len > dataEnd) break;
                p += len;
            } else if (byte == 0xF2) {
                p += 2;
            } else if (byte == 0xF3) {
                p += 1;
            }
        }
    }

    return true;
}

bool MidiFileParser::processEvent(uint8_t statusByte,
                                   const std::vector<uint8_t>& eventData,
                                   size_t dataPos,
                                   int64_t absoluteTick,
                                   uint8_t trackId,
                                   std::vector<RecordedMidiEvent>& outEvents) {
    uint8_t type = statusByte & 0xF0;

    RecordedMidiEvent evt;
    evt.tick = absoluteTick;
    evt.status = statusByte;
    evt.data1 = 0;
    evt.data2 = 0;
    evt.trackId = trackId;

    if (type == 0x80 || type == 0x90 || type == 0xA0 || type == 0xB0) {
        if (dataPos + 1 < eventData.size()) {
            evt.data1 = eventData[dataPos];
            evt.data2 = eventData[dataPos + 1];
            outEvents.push_back(evt);
        }
    } else if (type == 0xC0 || type == 0xD0) {
        if (dataPos < eventData.size()) {
            evt.data1 = eventData[dataPos];
            outEvents.push_back(evt);
        }
    } else if (type == 0xE0) {
        if (dataPos + 1 < eventData.size()) {
            evt.data1 = eventData[dataPos];
            evt.data2 = eventData[dataPos + 1];
            outEvents.push_back(evt);
        }
    }

    return true;
}