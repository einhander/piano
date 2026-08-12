#pragma once

#include <cstdint>
#include <vector>
#include <string>

// Use RecordedMidiEvent from MidiRecorder.h — include it here
#include "engine/MidiRecorder.h"

// Standard MIDI File parser
// Parses format 0, 1, 2 SMF files
// Returns events sorted by tick, plus tempo map and time signatures
class MidiFileParser {
public:
    MidiFileParser();
    ~MidiFileParser();

    // Parse MIDI file from disk
    // filePath: absolute path to .mid file
    // outEvents: filled with RecordedMidiEvent sorted by tick
    // outTempoMap: filled with (tick, microsecondsPerQuarter) pairs
    // outTimeSignatures: filled with (tick, numerator, denominator) pairs
    // denominator is the power-of-2 denominator (e.g., 2 = 8th note)
    bool parse(const char* filePath,
               std::vector<RecordedMidiEvent>& outEvents,
               std::vector<std::pair<int64_t, uint32_t>>& outTempoMap,
               std::vector<std::pair<int64_t, std::pair<int, int>>>& outTimeSignatures);

private:
    // Read a single byte
    bool readByte(uint8_t& out);

    // Read a 32-bit big-endian integer
    bool readUint32(uint32_t& out);

    // Read a variable-length quantity (4 bytes max, 7 bits each)
    bool readVlq(uint32_t& out);

    // Parse a single track chunk
    bool parseTrack(std::vector<uint8_t>& trackData,
                    std::vector<RecordedMidiEvent>& outEvents,
                    int ticksPerBeat);

    // Parse events within a track
    bool parseTrackEvents(std::vector<uint8_t>& trackData,
                          size_t dataStart,
                          size_t dataEnd,
                          std::vector<RecordedMidiEvent>& outEvents,
                          int ticksPerBeat);

    // Process a single MIDI event
    bool processEvent(uint8_t statusByte,
                      const std::vector<uint8_t>& eventData,
                      size_t dataPos,
                      int64_t absoluteTick,
                      uint8_t trackId,
                      std::vector<RecordedMidiEvent>& outEvents);

    // Process a meta event
    bool processMetaEvent(uint8_t eventType,
                          std::vector<uint8_t>& eventData,
                          int64_t absoluteTick,
                          uint8_t trackId,
                          std::vector<RecordedMidiEvent>& outEvents);

    // File data (we read entire file into memory for simplicity)
    std::vector<uint8_t> mFileData;
    size_t mPos = 0;
    int mTicksPerBeat = 480;  // default
};