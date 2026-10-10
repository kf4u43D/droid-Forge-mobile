#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace androforge {

class PatchEngine;

// Forge 1.7.1 main/tuning.h and os/midihost.cpp. The official upload protocol
// is send-only: there is no defined read request, acknowledgement or SD command.
inline constexpr std::size_t kMaxDeploymentBytes = 64000;
inline constexpr std::size_t kMaxDeploymentLineBytes = 63;
inline constexpr std::size_t kActivationEnvelopeBytes = 6;
inline constexpr const char* kDroidPatchFilename = "droid.ini";

// Equivalent to the default (uncompressed) toDeployString format: controllers
// first, then enabled circuits/assignments, LF lines, no annotations or comments.
// ASCII atom spellings are canonicalized; numeric precision is preserved rather
// than rounded through a floating point conversion. Display text stays exact.
// No cable renaming, short-name compression or jack-table deduplication is used.
// This does not establish firmware/hardware compatibility. Invalid, incomplete,
// unknown active assignments, or oversized documents throw invalid_argument.
// Neither successful preparation nor any exception changes engine/history.
std::string prepareDeployment(const PatchEngine& engine);

// F0 00 66 66 'P' + prepared ASCII patch + F7. No macOS lost-byte padding is
// applicable to Android. Reject size/line limits and unsafe MIDI/control bytes
// before allocating the final frame. This is framing, not patch validation.
std::vector<std::uint8_t> encodeActivation(const std::string& deployment);

} // namespace androforge
