#include "androforge/deployment.h"
#include "androforge/patch_engine.h"

#include <algorithm>
#include <cstdlib>
#include <functional>
#include <iostream>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

namespace allocation_fault { long remaining = -1; bool track = false; std::size_t count = 0; }
void* operator new(std::size_t size) {
    if (allocation_fault::track) ++allocation_fault::count;
    if (allocation_fault::remaining >= 0 && allocation_fault::remaining-- == 0) {
        allocation_fault::remaining = -1;
        throw std::bad_alloc();
    }
    if (auto* value = std::malloc(size ? size : 1)) return value;
    throw std::bad_alloc();
}
void* operator new[](std::size_t size) { return ::operator new(size); }
void operator delete(void* value) noexcept { std::free(value); }
void operator delete[](void* value) noexcept { std::free(value); }
void operator delete(void* value, std::size_t) noexcept { std::free(value); }
void operator delete[](void* value, std::size_t) noexcept { std::free(value); }

namespace {
std::size_t checks = 0;
void check(bool passed, const char* expression, int line) {
    ++checks;
    if (!passed) throw std::runtime_error(std::string("Line ") + std::to_string(line) + ": " + expression);
}
#define CHECK(value) check(bool(value), #value, __LINE__)
void rejected(const std::function<void()>& action) {
    bool caught = false;
    try { action(); } catch (const std::invalid_argument&) { caught = true; }
    CHECK(caught);
}

void officialEnvelopeAndLimits() {
    const auto encoded = androforge::encodeActivation("[copy]\ninput=0\noutput=O1\n");
    const std::vector<std::uint8_t> expected{
        0xf0,0x00,0x66,0x66,0x50,'[','c','o','p','y',']',10,
        'i','n','p','u','t','=', '0',10, 'o','u','t','p','u','t','=','O','1',10,0xf7};
    CHECK(encoded == expected);
    CHECK(androforge::encodeActivation("") == std::vector<std::uint8_t>({0xf0,0x00,0x66,0x66,0x50,0xf7}));
    std::string exact;
    const std::string line(63, 'a');
    for (int i = 0; i < 1000; ++i) exact += line + '\n';
    CHECK(exact.size() == androforge::kMaxDeploymentBytes);
    CHECK(androforge::encodeActivation(exact).size() == 64006);
    rejected([&] { androforge::encodeActivation(exact + '\n'); });
    CHECK(androforge::encodeActivation(std::string(63, 'a')).size() == 69);
    rejected([] { androforge::encodeActivation(std::string(64, 'a')); });
    for (const unsigned byte : {0U, 1U, 9U, 13U, 31U, 127U, 128U, 0xf0U, 0xf7U, 255U})
        rejected([&] { androforge::encodeActivation(std::string("[copy]\ninput=") + char(byte)); });
    rejected([] { androforge::encodeActivation("[display]\nheader=\"caf\xc3\xa9\"\n"); });
}

void preserveTextAndControllerOrder() {
    androforge::PatchEngine engine;
    const std::string source = "\xef\xbb\xbf# Titre caf\xc3\xa9 \xf0\x9f\x98\x80\r\n"
        "# REGISTER LABELS:\r\n# P1.1: [Nom] d\xc3\xa9tail\r\n\r\n"
        "[COPY] # circuit\r\n i = p001.0001 * 0.1234567890123456789 + -2V # inline\r\n o = o01\r\n"
        "# minimum = invalid disabled value\r\n# [futurecircuit]\r\n# unknown = foo\r\n"
        "[p2b8]\r\n[display]\r\n header = \" a # _C + 2 \" # commentaire\r\n";
    engine.load(source);
    const auto state = engine.state();
    const auto deployed = androforge::prepareDeployment(engine);
    CHECK(deployed == "[p2b8]\n[copy]\ninput=P1.1*0.1234567890123456789-2V\noutput=O1\n"
                      "[display]\nheader=\" a # _C + 2 \"\n");
    CHECK(engine.source() == source);
    CHECK(engine.state() == state);
    CHECK(!engine.canUndo() && !engine.canRedo());
    CHECK(androforge::prepareDeployment(engine) == deployed);
    CHECK(androforge::encodeActivation(deployed).size() == deployed.size() + 6);
    engine.setParameter(0, "input", "I1 / 3 + -2V");
    engine.undo();
    const auto withRedo = engine.state();
    CHECK(engine.canRedo());
    CHECK(androforge::prepareDeployment(engine) == deployed);
    CHECK(engine.state() == withRedo);
    engine.redo();
    CHECK(androforge::prepareDeployment(engine).find("input=I1/3-2V\n") != std::string::npos);
}

void canonicalArithmeticAndCables() {
    androforge::PatchEngine engine;
    engine.load("[lfo]\nhz=On\nsine=_wire\n[copy]\ninput=_WiRe * 2 + -0.5\noutput=g05\n");
    CHECK(androforge::prepareDeployment(engine) == "[lfo]\nhz=on\nsine=_WIRE\n[copy]\ninput=_WIRE*2-0.5\noutput=G1.5\n");
    engine.load("[copy]\ninput=I1 - P1.1\noutput=O1\n[p2b8]\n");
    CHECK(androforge::prepareDeployment(engine) == "[p2b8]\n[copy]\ninput=-1*P1.1+I1\noutput=O1\n");
    engine.load("# only a title\n");
    CHECK(androforge::prepareDeployment(engine).empty());
}

void incompleteOrUnknownMustNotDeploy() {
    for (const auto& source : std::vector<std::string>{
            "[copy]\ninput =\n", "[copy]\noutput =\n", "[copy]\ninput = I1 / 0\n",
            "[copy]\ninput = \"caf\xc3\xa9\"\n", "[copy]\ninput = I1 / 2V\n",
            "[copy]\ninput = I1 * P1.1 - I2\n", "[copy]\ninput = I1\ninput = I2\n",
            "[copy]\nnewparam = 1\n", "[future]\n", "unrecognized syntax\n",
            "[p2b8]\ninput=1\n", "[copy]\ninput = _MISSING\n", "[copy]\noutput = _NO_INPUT\n",
            "[copy]\ninput=_C\noutput=_C\n[copy]\noutput=_C\n"}) {
        androforge::PatchEngine engine;
        engine.load(source);
        const auto before = engine.state();
        rejected([&] { androforge::prepareDeployment(engine); });
        CHECK(engine.source() == source);
        CHECK(engine.state() == before);
        CHECK(!engine.canUndo() && !engine.canRedo());
    }
    // Disabled invalid/unknown values are retained in the document and skipped.
    androforge::PatchEngine engine;
    engine.load("#[future]\n# newparam=invalid\n[copy]\ninput=0\n# input=invalid\n# unknown=1\n");
    CHECK(androforge::prepareDeployment(engine) == "[copy]\ninput=0\n");
    engine.load("[copy]\ninput=" + std::string(8193, '1') + '\n');
    CHECK(!engine.analysisComplete());
    rejected([&] { androforge::prepareDeployment(engine); });
}

void deploymentSizeIsIndependentOfSourceSize() {
    androforge::PatchEngine engine;
    std::string source(4 * 1024 * 1024 - 16, 'a');
    source = '#' + source + '\n' + "[copy]\ninput=0";
    CHECK(source.size() == androforge::PatchEngine::kMaxSourceBytes);
    engine.load(source);
    CHECK(engine.analysisComplete());
    CHECK(androforge::prepareDeployment(engine) == "[copy]\ninput=0\n");
    CHECK(engine.source() == source);
    const std::string number = "0." + std::string(48, '1');
    const std::string circuit = "[copy]\ninput=" + number + '\n';
    CHECK(circuit.size() == 64);
    source.clear();
    for (int i = 0; i < 1000; ++i) source += circuit;
    engine.load(source);
    const auto deployment = androforge::prepareDeployment(engine);
    CHECK(deployment.size() == 64000);
    CHECK(deployment == source);
    engine.load(source + circuit);
    const auto before = engine.state();
    rejected([&] { androforge::prepareDeployment(engine); });
    CHECK(engine.state() == before);
    engine.load("[copy]\n input = 0." + std::string(55, '1') + '\n');
    CHECK(androforge::prepareDeployment(engine).size() == 71);
    engine.load("[copy]\ninput=0." + std::string(56, '1') + '\n');
    rejected([&] { androforge::prepareDeployment(engine); });
}

void allocationFailureNeverChangesSourceOrHistory() {
    androforge::PatchEngine engine;
    engine.load("[lfo]\nhz=1\nsine=_WIRE\n[copy]\ninput=_WIRE * 2 + 1\noutput=O1\n");
    engine.setParameter(0, "hz", "2");
    engine.undo();
    const auto source = engine.source();
    const auto state = engine.state();
    allocation_fault::track = true;
    allocation_fault::count = 0;
    const auto expected = androforge::prepareDeployment(engine);
    allocation_fault::track = false;
    const auto allocations = allocation_fault::count;
    CHECK(allocations > 0 && allocations < 1000);
    for (std::size_t point = 0; point < allocations; ++point) {
        bool failed = false;
        allocation_fault::remaining = static_cast<long>(point);
        try { (void)androforge::prepareDeployment(engine); } catch (const std::bad_alloc&) { failed = true; }
        allocation_fault::remaining = -1;
        CHECK(failed);
        CHECK(engine.source() == source);
        CHECK(engine.state() == state);
        CHECK(engine.canRedo());
    }
    CHECK(androforge::prepareDeployment(engine) == expected);
    allocation_fault::remaining = 0;
    bool failed = false;
    try { (void)androforge::encodeActivation(expected); } catch (const std::bad_alloc&) { failed = true; }
    allocation_fault::remaining = -1;
    CHECK(failed);
    CHECK(engine.state() == state);
    engine.redo();
    CHECK(engine.blocks().front().parameters.front().value == "2");
}
} // namespace

int main() {
    try {
        officialEnvelopeAndLimits();
        preserveTextAndControllerOrder();
        canonicalArithmeticAndCables();
        incompleteOrUnknownMustNotDeploy();
        deploymentSizeIsIndependentOfSourceSize();
        allocationFailureNeverChangesSourceOrHistory();
        std::cout << "Deployment: " << checks << " checks passed\n";
    } catch (const std::exception& error) {
        allocation_fault::remaining = -1;
        std::cerr << "Deployment test failed: " << error.what() << '\n';
        return 1;
    }
}
