#include "androforge/patch_engine.h"

#include <algorithm>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

using androforge::PatchEngine;

namespace {
std::string read(const std::filesystem::path& path) {
    std::ifstream stream(path, std::ios::binary);
    if (!stream) throw std::runtime_error("Cannot read official example: " + path.string());
    return {std::istreambuf_iterator<char>(stream), std::istreambuf_iterator<char>()};
}

void require(bool condition, const char* what, std::size_t& checks) {
    ++checks;
    if (!condition) throw std::runtime_error(what);
}

std::string quoted(const std::string& source) {
    std::string result = "\"";
    const char* hex = "0123456789abcdef";
    for (unsigned char c : source) {
        if (c == '"' || c == '\\') { result += '\\'; result += char(c); }
        else if (c < 32) { result += "\\u00"; result += hex[c >> 4]; result += hex[c & 15]; }
        else result += char(c);
    }
    return result + '"';
}
}

int main(int argc, char** argv) {
    try {
        if (argc < 2 || argc > 3) throw std::runtime_error("Usage: official_examples_test CORPUS_DIRECTORY [REPORT.json]");
        std::vector<std::filesystem::path> paths;
        for (const auto& item : std::filesystem::directory_iterator(argv[1]))
            if (item.is_regular_file() && item.path().extension() == ".ini") paths.push_back(item.path());
        std::sort(paths.begin(), paths.end());
        if (paths.size() != 630) throw std::runtime_error("Expected the complete 630-entry official corpus");
        std::size_t checks = 0;
        std::string report = "{\"schema_version\":1,\"catalogue\":" + PatchEngine::catalogue() + ",\"files\":[";
        bool first = true;
        for (const auto& path : paths) {
            const auto source = read(path);
            PatchEngine engine;
            const auto state = engine.load(source);
            require(engine.source() == source, "Loading changed official bytes", checks);
            require(!engine.canUndo() && !engine.canRedo(), "Loading created edit history", checks);
            require(engine.state() == state, "State was not stable after load", checks);
            PatchEngine reloaded;
            require(reloaded.load(engine.source()) == state, "Export/reload changed parsed state", checks);
            require(reloaded.source() == source, "Export/reload changed official bytes", checks);
            require(engine.replaceSource(source) == state, "No-op source replacement changed parsed state", checks);
            require(!engine.canUndo() && !engine.canRedo(), "No-op import created edit history", checks);
            require(engine.diagnostics().size() <= PatchEngine::kMaxDiagnostics, "Unbounded diagnostics", checks);
            std::size_t previousEnd = 0;
            for (const auto& block : engine.blocks()) {
                require(block.sourceStart >= previousEnd && block.sourceStart <= block.sourceEnd &&
                            block.sourceEnd <= source.size(), "Invalid/overlapping source block ranges", checks);
                previousEnd = block.sourceEnd;
                for (const auto& parameter : block.parameters) {
                    if (!(parameter.sourceStart >= block.sourceEnd &&
                          parameter.sourceEnd <= source.size() &&
                          parameter.sourceStart <= parameter.valueStart &&
                          parameter.valueStart <= parameter.valueEnd &&
                          parameter.valueEnd <= parameter.sourceEnd))
                        throw std::runtime_error(path.filename().string() + ": " + block.name + "." +
                            parameter.name + " invalid ranges block=" + std::to_string(block.sourceStart) +
                            ".." + std::to_string(block.sourceEnd) + " parameter=" +
                            std::to_string(parameter.sourceStart) + ".." + std::to_string(parameter.sourceEnd) +
                            " value=" + std::to_string(parameter.valueStart) + ".." + std::to_string(parameter.valueEnd));
                    require(parameter.sourceStart >= block.sourceEnd &&
                                parameter.sourceEnd <= source.size() &&
                                parameter.sourceStart <= parameter.valueStart &&
                                parameter.valueStart <= parameter.valueEnd &&
                                parameter.valueEnd <= parameter.sourceEnd,
                            "Invalid official parameter byte range", checks);
                    require(source.substr(parameter.valueStart, parameter.valueEnd - parameter.valueStart) ==
                                parameter.value, "Parameter value differs from original official bytes", checks);
                }
            }
            if (!first) report += ',';
            first = false;
            report += "{\"name\":" + quoted(path.filename().string()) +
                ",\"bytes\":" + std::to_string(source.size()) + ",\"roundtrip_exact\":true,\"state\":" + state + '}';
        }
        report += "],\"passed\":true,\"files_count\":" + std::to_string(paths.size()) +
                  ",\"checks\":" + std::to_string(checks) + "}\n";
        if (argc == 3) {
            std::ofstream stream(argv[2], std::ios::binary | std::ios::trunc);
            if (!stream || !(stream << report)) throw std::runtime_error("Cannot write official corpus report");
        }
        std::cout << "Official examples: " << paths.size() << "/" << paths.size()
                  << " exact round trips; " << checks << " checks passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "Official corpus test failed: " << error.what() << '\n';
        return 1;
    }
}
