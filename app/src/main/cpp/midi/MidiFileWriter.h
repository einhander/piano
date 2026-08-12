#pragma once

#include <cstdint>
#include <vector>
#include <string>

// Forward declaration — RecordedMidiEvent defined in MidiRecorder.h
struct RecordedMidiEvent;

// Writes Standard MIDI File (SMF) format from RecordedMidiEvent data
class MidiFileWriter {
public:
    MidiFileWriter();
    ~MidiFileWriter();

    // Write MIDI events to a .mid file
    // format: 0 (single track) or 1 (multi-track)
    // ppq: pulses per quarter note (default 960)
    // tempo: microseconds per quarter note (default 500000 = 120 BPM)
    bool write(const char* filePath,
               const std::vector<RecordedMidiEvent>& events,
               int format = 0,
               int ppq = 960,
               uint32_t tempo = 500000);

private:
    void writeUint32(std::vector<uint8_t>& buf, uint32_t value);
    void writeUint16(std::vector<uint8_t>& buf, uint16_t value);
    void writeVlq(std::vector<uint8_t>& buf, uint32_t value);
    void writeHeader(std::vector<uint8_t>& buf, int format, int numTracks, int ppq);
    void writeTrack(std::vector<uint8_t>& buf,
                    const std::vector<RecordedMidiEvent>& events,
                    int ppq, uint32_t tempo);
    void writeMidiEvent(std::vector<uint8_t>& buf,
                        const RecordedMidiEvent& event,
                        int32_t deltaTick);
    void writeMetaEndOfTrack(std::vector<uint8_t>& buf);
    void writeMetaTempo(std::vector<uint8_t>& buf, uint32_t microsecondsPerQuarter);
};