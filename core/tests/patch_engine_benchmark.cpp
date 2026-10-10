#include "androforge/patch_engine.h"

#include <chrono>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <string>

// Reproducible measurement of the JNI-facing engine workload (parse plus state
// serialization). Run on the target device to measure its own engine latency;
// this does not include JNI, disk I/O, or Android view rendering.
int main() {
    std::string source = "# Benchmark fixture\r\n# UTF-8 and CRLF remain intact\r\n\r\n";
    constexpr int blocks = 750;
    for (int i = 0; i < blocks; ++i) {
        // Every cable has one consumer and one producer. A large collection of
        // dangling outputs measures diagnostic truncation instead of the full
        // parse/edit workload and cannot support further structured edits.
        const auto input = "_CV" + std::to_string((i + blocks - 1) % blocks);
        source += "[copy] # signal " + std::to_string(i) + "\r\n    input = " + input +
                  " * 0.5 + 2V\r\n    output = _CV" + std::to_string(i) + "\r\n\r\n";
    }
    androforge::PatchEngine engine;
    engine.load(source);
    if (!engine.analysisComplete() || engine.source() != source) return EXIT_FAILURE;
    constexpr int iterations = 100;
    std::size_t bytes = 0;
    const auto started = std::chrono::steady_clock::now();
    for (int i = 0; i < iterations; ++i) {
        bytes += engine.load(source).size();
        if (!engine.analysisComplete()) return EXIT_FAILURE;
    }
    const auto loaded = std::chrono::steady_clock::now();
    for (int i = 0; i < iterations; ++i) {
        const auto input = "_CV" + std::to_string((i % blocks + blocks - 1) % blocks);
        bytes += engine.setParameter(i % blocks, "input", input + (i % 2 ? " * 0.25 + 2V" : " * 0.75 + 2V")).size();
        if (!engine.analysisComplete()) return EXIT_FAILURE;
    }
    const auto edited = std::chrono::steady_clock::now();
    const auto loadMs = std::chrono::duration<double, std::milli>(loaded - started).count() / iterations;
    const auto editMs = std::chrono::duration<double, std::milli>(edited - loaded).count() / iterations;
    if (engine.blocks().size() != blocks || bytes == 0) return EXIT_FAILURE;
    std::cout << std::fixed << std::setprecision(3)
              << "Engine benchmark, " << source.size() << " source bytes, " << blocks << " circuits, " << iterations << " iterations\n"
              << "load + JSON state: " << loadMs << " ms/op\n"
              << "setParameter + parse + JSON state: " << editMs << " ms/op\n";
    return EXIT_SUCCESS;
}
