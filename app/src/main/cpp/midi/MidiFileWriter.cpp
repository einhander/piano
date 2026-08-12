#include "MidiFileWriter.h"
#include "../engine/MidiRecorder.h"

#include <fstream>
#include <algorithm>
#include <cstring>

MidiFileWriter::MidiFileWriter() = default;
MidiFileWriter::~MidiFileWriter() = default;

void MidiFileWriter::writeUint32(std::vector<uint8_t>& buf, uint32_t value) {
    buf.push_back(static_cast<uint8_t>((value >> 24) & 0xFF));
    buf.push_back(static_cast<uint8_t>((value >> 16) & 0xFF));
    buf.push_back(static_cast<uint8_t>((value >> 8) & 0xFF));
    buf.push_back(static_cast<uint8_t>(value & 0xFF));
}

void MidiFileWriter::writeUint16(std::vector<uint8_t>& buf, uint16_t value) {
    buf.push_back(static_cast<uint8_t>((value >> 8) & 0xFF));
    buf.push_back(static_cast<uint8_t>(value & 0xFF));
}

void MidiFileWriter::writeVlq(std::vector<uint8_t>& buf, uint32_t value) {
    // VLQ: 7 bits per byte, MSB is continuation flag
    std::vector<uint8_t> vlqBytes;
    vlqBytes.push_back(value & 0x7F);
    value >>= 7;
    while (value > 0) {
        vlqBytes.push_back(0x80 | (value & 0x7F));
        value >>= 7;
    }
    // Reverse (VLQ is big-endian)
    for (auto it = vlqBytes.rbegin(); it != vlqBytes.rend(); ++it) {
        buf.push_back(*it);
    }
}

void MidiFileWriter::writeHeader(std::vector<uint8_t>& buf, int format, int numTracks, int ppq) {
    // MThd header
    const char headerId[] = "MThd";
    buf.insert(buf.end(), std::begin(headerId), std::end(headerId));
    writeUint32(buf, 6);  // header size
    writeUint16(buf, static_cast<uint16_t>(format));
    writeUint16(buf, static_cast<uint16_t>(numTracks));
    writeUint16(buf, static_cast<uint16_t>(ppq));
}

void MidiFileWriter::writeMetaEndOfTrack(std::vector<uint8_t>& buf) {
    buf.push_back(0xFF);
    buf.push_back(0x2F);  // End of track
    writeVlq(buf, 0);     // Length = 0
}

void MidiFileWriter::writeMetaTempo(std::vector<uint8_t>& buf, uint32_t microsecondsPerQuarter) {
    buf.push_back(0xFF);
    buf.push_back(0x51);  // Set tempo
    buf.push_back(0x03);  // Length = 3
    buf.push_back((microsecondsPerQuarter >> 16) & 0xFF);
    buf.push_back((microsecondsPerQuarter >> 8) & 0xFF);
    buf.push_back(microsecondsPerQuarter & 0xFF);
}

void MidiFileWriter::writeMidiEvent(std::vector<uint8_t>& buf,
                                     const RecordedMidiEvent& event,
                                     int32_t deltaTick) {
    writeVlq(buf, static_cast<uint32_t>(deltaTick));

    // Write status byte
    buf.push_back(event.status);

    // Write data bytes (only if they exist — status byte determines event type)
    uint8_t statusClass = event.status & 0xF0;

    // Note Off (0x80), Note On (0x90), Control Change (0xB0),
    // Program Change (0xC0), Channel Pressure (0xD0), Pitch Bend (0xE0)
    if (statusClass == 0x80 || statusClass == 0x90 ||
        statusClass == 0xB0 || statusClass == 0xE0) {
        buf.push_back(event.data1);
        buf.push_back(event.data2);
    } else if (statusClass == 0xC0 || statusClass == 0xD0) {
        buf.push_back(event.data1);
    }
    // System messages (0xF0) have no data bytes in RecordedMidiEvent format
}

void MidiFileWriter::writeTrack(std::vector<uint8_t>& buf,
                                 const std::vector<RecordedMidiEvent>& events,
                                 int ppq, uint32_t tempo) {
    // Sort events by tick
    std::vector<RecordedMidiEvent> sortedEvents = events;
    std::sort(sortedEvents.begin(), sortedEvents.end(),
        [](const RecordedMidiEvent& a, const RecordedMidiEvent& b) {
            return a.tick < b.tick;
        });

    // Write tempo event at tick 0
    writeMetaTempo(buf, tempo);

    int32_t prevTick = 0;
    for (const auto& event : sortedEvents) {
        int32_t deltaTick = static_cast<int32_t>(event.tick) - prevTick;
        writeMidiEvent(buf, event, deltaTick);
        prevTick = static_cast<int32_t>(event.tick);
    }

    // End of track
    writeMetaEndOfTrack(buf);
}

bool MidiFileWriter::write(const char* filePath,
                            const std::vector<RecordedMidiEvent>& events,
                            int format,
                            int ppq,
                            uint32_t tempo) {
    if (!filePath) return false;

    std::vector<uint8_t> output;

    // Reserve space: header (14 bytes) + track chunk
    int numTracks = (format == 1) ? static_cast<int>(events.size()) : 1;
    output.reserve(14 + 8 + 4096);  // header + track header + estimated track data

    // Write header
    writeHeader(output, format, numTracks, ppq);

    // Write track(s)
    if (format == 0) {
        // Single track with all events
        const char trkId[] = {'M', 'T', 'r', 'k'};
        output.insert(output.end(), trkId, trkId + 4);
        std::vector<uint8_t> trackBuf;
        writeTrack(trackBuf, events, ppq, tempo);
        writeUint32(output, static_cast<uint32_t>(trackBuf.size()));
        output.insert(output.end(), trackBuf.begin(), trackBuf.end());
    } else {
        // Multi-track: one track per event (simplified — each event gets its own track)
        // For proper multi-track, events should be grouped by trackId
        // Here we put all events in a single track for format 1 too
        const char trkId[] = {'M', 'T', 'r', 'k'};
        output.insert(output.end(), trkId, trkId + 4);
        std::vector<uint8_t> trackBuf;
        writeTrack(trackBuf, events, ppq, tempo);
        writeUint32(output, static_cast<uint32_t>(trackBuf.size()));
        output.insert(output.end(), trackBuf.begin(), trackBuf.end());
    }

    // Write to file
    std::ofstream file(filePath, std::ios::binary);
    if (!file.is_open()) return false;
    file.write(reinterpret_cast<const char*>(output.data()),
               static_cast<std::streamsize>(output.size()));
    return file.good();
}