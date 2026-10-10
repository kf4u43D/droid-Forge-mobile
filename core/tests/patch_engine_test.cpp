#include "androforge/patch_engine.h"

#include <algorithm>
#include <cstdlib>
#include <functional>
#include <fstream>
#include <iostream>
#include <iterator>
#include <new>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

using androforge::PatchEngine;

namespace allocation_fault {
// Enabled only around the operation under test; no production allocator or
// engine code is modified. A failed allocation is one-shot so unwinding works.
long countdown = -1;
bool tracking = false;
std::size_t count = 0;
}

void* operator new(std::size_t size) {
    if (allocation_fault::tracking) ++allocation_fault::count;
    if (allocation_fault::countdown >= 0 && allocation_fault::countdown-- == 0) {
        allocation_fault::countdown = -1;
        throw std::bad_alloc();
    }
    if (auto* result = std::malloc(size ? size : 1)) return result;
    throw std::bad_alloc();
}
void* operator new[](std::size_t size) { return ::operator new(size); }
void operator delete(void* pointer) noexcept { std::free(pointer); }
void operator delete[](void* pointer) noexcept { std::free(pointer); }
void operator delete(void* pointer, std::size_t) noexcept { std::free(pointer); }
void operator delete[](void* pointer, std::size_t) noexcept { std::free(pointer); }

namespace {
int checks = 0;

void check(bool condition, const char* expression, int line) {
    ++checks;
    if (!condition) throw std::runtime_error(std::string("Line ") + std::to_string(line) + ": " + expression);
}
#define CHECK(condition) check(bool(condition), #condition, __LINE__)

bool diagnostic(const PatchEngine& engine, const std::string& severity, const std::string& text = "") {
    return std::any_of(engine.diagnostics().begin(), engine.diagnostics().end(), [&](const auto& d) {
        return d.severity == severity && (text.empty() || d.message.find(text) != std::string::npos);
    });
}

void rejected(const std::function<void()>& action) {
    bool caught = false;
    try { action(); }
    catch (const std::invalid_argument&) { caught = true; }
    CHECK(caught);
}

const androforge::Parameter* parameter(const PatchEngine& engine, std::size_t block, const std::string& name) {
    const auto& parameters = engine.blocks().at(block).parameters;
    const auto found = std::find_if(parameters.begin(), parameters.end(), [&](const auto& p) { return p.name == name; });
    return found == parameters.end() ? nullptr : &*found;
}

void forgeCircuitCreationDefaults() {
    PatchEngine engine;
    engine.addCircuit("copy");
    CHECK(engine.source() == "[copy]\n    input = 0\n    output =\n");
    CHECK(!parameter(engine, 0, "output")->disabled);
    CHECK(diagnostic(engine, "warning", "non renseignée"));
    engine.undo(); CHECK(engine.source().empty());
    engine.redo(); CHECK(engine.blocks()[0].parameters.size() == 2);
    engine.load(""); engine.addCircuit("lfo");
    CHECK(engine.blocks()[0].parameters.size() == 8);
    CHECK(parameter(engine, 0, "hz")->value == "1");
    CHECK(parameter(engine, 0, "level")->value == "1");
    CHECK(parameter(engine, 0, "bipolar")->value == "0");
    CHECK(parameter(engine, 0, "sine")->value.empty());
    CHECK(!parameter(engine, 0, "rate"));
    engine.load(""); engine.addCircuit("lfo", 2);
    CHECK(engine.blocks()[0].parameters.size() == 2);
    CHECK(parameter(engine, 0, "hz") && parameter(engine, 0, "square"));
    engine.load(""); engine.addCircuit("lfo", 0);
    CHECK(engine.blocks()[0].parameters.size() == 21);
    CHECK(parameter(engine, 0, "rate")->value == "0");
    engine.load(""); engine.addCircuit("lfo", 3);
    CHECK(engine.source() == "[lfo]\n");
    CHECK(engine.blocks()[0].parameters.empty());
    engine.load(""); engine.addCircuit("adc", 2);
    CHECK(engine.blocks()[0].parameters.size() == 5);
    CHECK(parameter(engine, 0, "bit4") && !parameter(engine, 0, "bit5"));
    engine.load(""); engine.addCircuit("calibrator", 0);
    CHECK(parameter(engine, 0, "tune0") && parameter(engine, 0, "tune8"));
    CHECK(!parameter(engine, 0, "tune9"));
    engine.load(""); engine.addCircuit("vcotuner", 0);
    CHECK(parameter(engine, 0, "tuningnote")->value.empty());
    CHECK(parameter(engine, 0, "concertpitch")->value == "440");
    engine.load(""); engine.addCircuit("motorfader", 2);
    CHECK(parameter(engine, 0, "select") && !parameter(engine, 0, "selectat"));
    engine.load(""); engine.addCircuit("motorfader", 1);
    CHECK(parameter(engine, 0, "selectat"));
    const auto before = engine.state(), source = engine.source();
    rejected([&] { engine.addCircuit("copy", -1); });
    rejected([&] { engine.addCircuit("copy", 4); });
    CHECK(engine.state() == before && engine.source() == source);
    engine.load("\xef\xbb\xbf# title\r\n"); engine.addCircuit("copy");
    CHECK(engine.source() == "\xef\xbb\xbf# title\r\n\r\n[copy]\r\n    input = 0\r\n    output =\r\n");
}

void affineCellsPreserveImportedExpressions() {
    auto parts = PatchEngine::decomposeExpression(" I1 * _gain - 2V ");
    CHECK(parts.editable && parts.base == "I1" && parts.factor == "_gain" && parts.offset == "-2V");
    CHECK(PatchEngine::composeExpression(parts.base, parts.factor, parts.offset) == "I1 * _gain - 2V");
    parts = PatchEngine::decomposeExpression("_LEFT - _RIGHT");
    CHECK(parts.editable && parts.base == "-1" && parts.factor == "_RIGHT" && parts.offset == "_LEFT");
    parts = PatchEngine::decomposeExpression("_A / -2 + 3");
    CHECK(parts.editable && parts.factor == "1 / -2" && parts.offset == "3");
    CHECK(PatchEngine::composeExpression(parts.base, parts.factor, parts.offset) == "_A / -2 + 3");
    CHECK(PatchEngine::composeExpression("", "2", "3") == "2 + 3");
    CHECK(PatchEngine::composeExpression("", "1 / 2", "") == "1 / 2");
    CHECK(PatchEngine::composeExpression("", "", "-3") == "-3");
    CHECK(PatchEngine::composeExpression("", "", "").empty());
    CHECK(PatchEngine::decomposeExpression("\"A # _NOT_CABLE\" * 2 + 3").editable);
    CHECK(PatchEngine::decomposeExpression(" _Out ", true).base == "_Out");
    CHECK(!PatchEngine::decomposeExpression("_A * 2", true).editable);
    for (const auto& raw : {"I1 * 2 * 3", "I1 / 0", "I1 / 2V", "I1 * 2 - _NEG", "I1 + (I2)", "future(2)", "I1\n[copy]"})
        CHECK(!PatchEngine::decomposeExpression(raw).editable);
    rejected([] { PatchEngine::composeExpression("I1", "1 / 0", ""); });
    rejected([] { PatchEngine::composeExpression("I1", "1 / 2V", ""); });
    rejected([] { PatchEngine::composeExpression("I1 + 2", "", ""); });
    rejected([] { PatchEngine::composeExpression("I1", "", "3\n[copy]"); });
    rejected([] { PatchEngine::composeExpression("_A", "2", "", true); });
    rejected([] { PatchEngine::composeExpression("1", "", "", true); });
    rejected([] { PatchEngine::composeExpression(std::string(8193, '1'), "", ""); });
    PatchEngine engine;
    const std::string original = "# retained\r\n[copy]\r\n# input  = _LEFT  -  _RIGHT # cell comment\r\noutput = _OUT\r\n";
    engine.load(original);
    engine.setParameterExpression(0, "i", "-1", "_RIGHT", "_LEFT");
    CHECK(engine.source() == original && !engine.canUndo());
    engine.setParameterExpression(0, "input", "I2", "0.5", "-1");
    CHECK(engine.source().find("# input  = I2 * 0.5 - 1 # cell comment") != std::string::npos);
    CHECK(parameter(engine, 0, "input")->disabled);
    engine.undo(); CHECK(engine.source() == original);
    const auto before = engine.state();
    rejected([&] { engine.setParameterExpression(0, "unknown", "I2", "", ""); });
    rejected([&] { engine.setParameterExpression(0, "output", "_OUT", "2", ""); });
    CHECK(engine.state() == before);
    engine.load("[copy]\ninput = I1 * 2 * 3 # unsupported\noutput = O1\n");
    CHECK(engine.source().find("I1 * 2 * 3 # unsupported") != std::string::npos);
    CHECK(engine.state().find("\"editable\":false") != std::string::npos);
}

void connectedParametersCommitTogether() {
    PatchEngine engine;
    const std::string original = "\xef\xbb\xbf# Connexion 🎛️\r\n[copy]\r\ninput = I1 * 2 + 3 # target\r\n# output = O1 # source\r\n[copy]\r\ninput = I2\r\noutput = O2\r\n";
    engine.load(original);
    engine.connectParameters(0, "o", 0, "O3", 1, "i", 1, "I4 * 0.5 + 2V", "mix");
    CHECK(engine.source().find("# output = _MIX # source") != std::string::npos);
    CHECK(engine.source().find("input = I4 * _MIX + 2V\r\n") != std::string::npos);
    CHECK(parameter(engine, 0, "output")->disabled);
    const auto changed = engine.source();
    engine.undo(); CHECK(engine.source() == original && !engine.canUndo());
    engine.redo(); CHECK(engine.source() == changed);
    const auto before = engine.state();
    for (int column : {-1, 3}) rejected([&] { engine.connectParameters(0, "output", 0, "_MIX", 1, "input", column, "I2", "_OTHER"); });
    rejected([&] { engine.connectParameters(0, "output", 1, "_MIX", 1, "input", 0, "I2", "_OTHER"); });
    rejected([&] { engine.connectParameters(0, "input", 0, "I1", 1, "input", 0, "I2", "_OTHER"); });
    rejected([&] { engine.connectParameters(0, "output", 0, "_MIX", 1, "input", 0, "I2 * 2 * 3", "_OTHER"); });
    rejected([&] { engine.connectParameters(0, "output", 0, "_MIX", 1, "unknown", 0, "I2", "_OTHER"); });
    rejected([&] { engine.connectParameters(0, "output", 0, "_MIX", 1, "input", 0, "I2", "_"); });
    CHECK(engine.state() == before && engine.source() == changed);
    // The same circuit still contains two distinct ranges and one Undo step.
    engine.load("[copy]\ninput = I1\noutput = O1\n");
    engine.connectParameters(0, "input", 2, "I1 * 2 + 3", 0, "output", 0, "O1", "_LOCAL");
    CHECK(engine.source() == "[copy]\ninput = I1 * 2 + _LOCAL\noutput = _LOCAL\n");
    engine.undo(); CHECK(engine.source() == "[copy]\ninput = I1\noutput = O1\n");
    const auto emptyBefore = engine.state();
    rejected([&] { engine.connectParameterExpressions(0, "output", 0, "O1 + O2", "", "", 0, "input", 0, "", "2", "3", "_BAD"); });
    rejected([&] { engine.connectParameterExpressions(0, "output", 0, "O1", "", "", 0, "input", 0, "I1 * 2 * 3", "", "", "_BAD"); });
    rejected([&] { engine.connectParameterExpressions(0, "output", 0, "O1", "", "", 0, "input", 0, "I1\n[copy]", "", "", "_BAD"); });
    CHECK(engine.state() == emptyBefore);
    engine.connectParameterExpressions(0, "input", 0, "", "2", "3", 0, "output", 0, "O1", "", "", "_EMPTY_BASE");
    CHECK(engine.source() == "[copy]\ninput = _EMPTY_BASE * 2 + 3\noutput = _EMPTY_BASE\n");
    engine.undo(); CHECK(engine.source() == "[copy]\ninput = I1\noutput = O1\n");
    engine.connectParameterExpressions(0, "output", 0, "O1", "", "", 0, "input", 1, "", "2", "3", "_EMPTY_BASE");
    CHECK(engine.source() == "[copy]\ninput = _EMPTY_BASE + 3\noutput = _EMPTY_BASE\n");
    engine.undo(); CHECK(engine.source() == "[copy]\ninput = I1\noutput = O1\n");
    // Whole-document capacity is checked before either endpoint is published.
    std::string maximum = "[copy]\ninput = I1\noutput = O1\n#";
    maximum.append(PatchEngine::kMaxSourceBytes - maximum.size(), 'x');
    engine.load(maximum);
    rejected([&] { engine.connectParameters(0, "output", 0, "O1", 0, "input", 0, "I1", "_TOO_LONG"); });
    CHECK(engine.source() == maximum && !engine.canUndo());
}

void cableInventoryAndGlobalEdits() {
    PatchEngine engine;
    const std::string original = "# _old comment\r\n[copy]\r\ninput = _old * _OLD + _old # _old retained\r\noutput = _old\r\n# input = _Old\r\n[future]\r\nopaque = (_old) + _old_extra + \"_old # text\"\r\nraw _old line\r\n#[copy]\r\ninput = _OLD\r\noutput = _old\r\n";
    engine.load(original);
    const auto graph = engine.cables();
    CHECK(graph.find("\"name\":\"_OLD\",\"inputs\":3,\"outputs\":1,\"disabled\":3,\"unknown\":1") != std::string::npos);
    CHECK(graph.find("\"column\":0") != std::string::npos && graph.find("\"column\":1") != std::string::npos && graph.find("\"column\":2") != std::string::npos);
    CHECK(graph.find("\"column\":-1") != std::string::npos);
    CHECK(graph.find("\"name\":\"_OLD_EXTRA\"") != std::string::npos);
    engine.renameCable("old", "New");
    CHECK(engine.source().find("input = _NEW * _NEW + _NEW # _old retained") != std::string::npos);
    CHECK(engine.source().find("opaque = (_NEW) + _old_extra + \"_old # text\"") != std::string::npos);
    CHECK(engine.source().find("raw _old line\r\n") != std::string::npos);
    CHECK(engine.source().find("# _old comment\r\n") == 0);
    CHECK(engine.source().find("#[copy]\r\ninput = _NEW\r\noutput = _NEW") != std::string::npos);
    const auto renamed = engine.source();
    engine.undo(); CHECK(engine.source() == original);
    engine.redo(); CHECK(engine.source() == renamed);
    const auto before = engine.state();
    engine.renameCable("new", "NEW"); CHECK(engine.state() == before);
    rejected([&] { engine.renameCable("missing", "next"); });
    rejected([&] { engine.renameCable("new", "old_extra"); });
    rejected([&] { engine.deleteCable("new"); }); // Unknown/complex occurrence: all-or-nothing.
    CHECK(engine.source() == renamed && engine.state() == before);
    engine.renameCable("new", "old_extra", true);
    CHECK(engine.source().find("input = _OLD_EXTRA * _OLD_EXTRA + _OLD_EXTRA") != std::string::npos);
    CHECK(engine.cables().find("\"name\":\"_NEW\"") == std::string::npos);
    engine.undo(); CHECK(engine.source() == renamed);
    engine.load("[copy]\ninput = _A * 2 + _A # comment _A\n# output = _A\n");
    engine.deleteCable("a");
    CHECK(engine.source() == "[copy]\ninput = 2 # comment _A\n# output = \n");
    engine.undo(); CHECK(engine.source() == "[copy]\ninput = _A * 2 + _A # comment _A\n# output = _A\n");
    engine.load("[copy]\ninput = _LEFT - _RIGHT\noutput = _RIGHT\n");
    CHECK(engine.cables().find("\"name\":\"input\",\"line\":2,\"direction\":\"input\",\"column\":2") != std::string::npos);
    CHECK(engine.cables().find("\"name\":\"input\",\"line\":2,\"direction\":\"input\",\"column\":1") != std::string::npos);
    std::string maximum = "[copy]\ninput = _A\noutput = _A\n#";
    maximum.append(PatchEngine::kMaxSourceBytes - maximum.size(), 'x');
    engine.load(maximum);
    rejected([&] { engine.renameCable("a", "longer"); });
    CHECK(engine.source() == maximum && !engine.canUndo());
    const std::string longCable = "_" + std::string(1000, 'a');
    engine.load("[copy]\ninput = " + longCable + "\n");
    CHECK(engine.cables().find("\"analysisComplete\":true") != std::string::npos);
    // The graph is bounded independently of the full native source model.
    std::string many;
    for (int i = 0; i < 3000; ++i) many += "[copy]\ninput = _A * _B + _C\n";
    engine.load(many);
    CHECK(engine.analysisComplete());
    const auto boundedGraph = engine.cables();
    CHECK(boundedGraph.find("\"analysisComplete\":false") != std::string::npos);
    CHECK(boundedGraph.size() < 2 * 1024 * 1024);
    engine.renameCable("a", "renamed");
    CHECK(engine.source().find("_A *") == std::string::npos);
    CHECK(engine.source().find("_RENAMED * _B + _C") != std::string::npos);
    engine.load("[copy]\ninput = " + std::string(8193, '1') + "\noutput = _A\n");
    const auto partial = engine.source();
    CHECK(engine.cables().find("\"analysisComplete\":false") != std::string::npos);
    rejected([&] { engine.renameCable("a", "b"); });
    rejected([&] { engine.deleteCable("a"); });
    rejected([&] { engine.connectParameters(0, "output", 0, "_A", 0, "input", 0, "I1", "_A"); });
    CHECK(engine.source() == partial);
}

void cableConnectivityDiagnosticsAreConservative() {
    PatchEngine engine;
    engine.load("[copy]\ninput = _MISSING\noutput = O1\n");
    CHECK(diagnostic(engine, "error", "Aucune sortie"));
    engine.load("[copy]\ninput = _CABLE\noutput = _CABLE\n");
    CHECK(!diagnostic(engine, "error"));
    engine.load("[copy]\ninput = _CABLE\noutput = _CABLE\n[copy]\noutput = _cable\n");
    CHECK(diagnostic(engine, "error", "Plusieurs sorties"));
    // Connection remains allowed; diagnostics expose the resulting conflict.
    engine.connectParameters(1, "output", 0, "_CABLE", 0, "input", 0, "_CABLE", "_CABLE");
    CHECK(diagnostic(engine, "error", "Plusieurs sorties"));
    engine.load("[copy]\noutput = _UNUSED\n");
    CHECK(diagnostic(engine, "warning", "Aucune entrée"));
    CHECK(!diagnostic(engine, "error"));
    engine.load("#[copy]\ninput = _MISSING\noutput = _CABLE\n[copy]\n# input = _MISSING\noutput = _CABLE\n");
    CHECK(!diagnostic(engine, "error"));
    CHECK(!diagnostic(engine, "warning", "Plusieurs sorties"));
    engine.load("[copy]\ninput = _UNKNOWN\n[future]\nopaque = _UNKNOWN\n");
    CHECK(diagnostic(engine, "warning", "Validation partielle du câble"));
    CHECK(!diagnostic(engine, "error", "Aucune sortie"));
    engine.load("[copy]\ninput = \"_PHANTOM\" # _COMMENT\noutput = O1\n");
    CHECK(engine.cables() == "{\"analysisComplete\":true,\"cables\":[]}");
    CHECK(!diagnostic(engine, "error"));
}

void roundTripAndTargetedEdit() {
    PatchEngine engine;
    const std::string original = "\xef\xbb\xbf# Séquence 🎛️\r\n# description\r\n\r\n[p2b8]\r\n\r\n[COPY] # titre\r\n\tI  =  I1 * 0.5 + 2V  # ne pas changer\r\n  output=O1\r\n\r\n# --- section ---\r\n#[lfo]\r\n# hz = 4\r\n[future99]\r\nopaque = abc\r\nunknown syntax preserved";
    engine.load(original);
    CHECK(engine.source() == original);
    CHECK(engine.blocks().size() == 4);
    CHECK(engine.blocks()[0].controller);
    CHECK(engine.blocks()[1].name == "copy");
    CHECK(engine.blocks()[1].parameters[0].name == "input");
    CHECK(engine.blocks()[1].parameters[0].line == 7);
    CHECK(engine.blocks()[2].disabled);
    CHECK(engine.blocks()[2].parameters[0].disabled);
    CHECK(diagnostic(engine, "warning", "future99"));
    CHECK(diagnostic(engine, "error"));
    engine.setParameter(1, "input", "I2 / 2 + 3V");
    auto expected = original;
    const auto position = expected.find("I1 * 0.5 + 2V");
    expected.replace(position, std::string("I1 * 0.5 + 2V").size(), "I2 / 2 + 3V");
    CHECK(engine.source() == expected);
    CHECK(engine.canUndo());
    engine.undo();
    CHECK(engine.source() == original);
    CHECK(engine.canRedo());
    engine.redo();
    CHECK(engine.source() == expected);
}

void quotedHashesAndUtf8() {
    PatchEngine engine;
    const std::string source = "# Éditeur 🥁\n[copy]\ninput = \"A # hash\" # explanation\noutput = O1\n";
    engine.load(source);
    CHECK(engine.blocks()[0].parameters[0].value == "\"A # hash\"");
    CHECK(!diagnostic(engine, "error"));
    engine.setParameter(0, "input", "\"Another # hash\"");
    CHECK(engine.source().find("\"Another # hash\" # explanation") != std::string::npos);
    CHECK(engine.source().find("Éditeur 🥁") != std::string::npos);
    CHECK(engine.state().find("\\\"Another # hash\\\"") != std::string::npos);
    engine.load("[copy]\ninput = \"unfinished # hash\noutput = O1\n");
    CHECK(diagnostic(engine, "error", "guillemets"));
    const std::string malformed = std::string("[future]\npayload = ") + char(0xff) + '\n';
    engine.load(malformed);
    CHECK(engine.source() == malformed);
    CHECK(engine.state().find("\\ufffd") != std::string::npos);
}

void additionsRemovalAndBom() {
    PatchEngine engine;
    engine.load("# title\r\n[copy] # circuit\r\n# comment kept\r\ninput = I1\r\n# next section\r\n[lfo]\r\nhz = 2");
    engine.setParameter(0, "output", "O2");
    CHECK(engine.source().find("input = I1\r\n    output = O2\r\n# next section") != std::string::npos);
    engine.removeCircuit(0);
    CHECK(engine.source() == "# title\r\n# comment kept\r\n# next section\r\n[lfo]\r\nhz = 2");
    engine.addCircuit("copy");
    CHECK(engine.source().find("hz = 2\r\n\r\n[copy]\r\n") != std::string::npos);
    engine.setParameter(1, "input", "I1");
    CHECK(engine.blocks()[1].parameters[0].value == "I1");
    engine.addCircuit("p2b8");
    CHECK(engine.blocks()[0].controller);
    CHECK(engine.blocks()[1].name == "lfo");
    engine.load("\xef\xbb\xbf[copy]\ninput = I1\n# keep\n[lfo]\nhz = 3\n");
    engine.removeCircuit(0);
    CHECK(engine.source() == "\xef\xbb\xbf# keep\n[lfo]\nhz = 3\n");
    engine.load("[copy]");
    engine.setParameter(0, "input", "2");
    CHECK(engine.source() == "[copy]\n    input = 2\n");
    engine.load("[copy]\ninput =   # retained");
    engine.setParameter(0, "input", "I1");
    CHECK(engine.source() == "[copy]\ninput = I1   # retained");
}

void syntaxAndSchemaValidation() {
    PatchEngine engine;
    const std::vector<std::string> valid = {
        "I1", "I1 * 0.5", "I1 * 0.5 + 2V", "I1 * 2 - I2", "I1 + I2", "1 - I1",
        "I1 / 2", "I1 / 2 + I2", "I1 / 2 - 1", "-1.5", "on", "OFF", "50%", "10V", "_CV", "P1.2", "G1.8", "\"Hello # world\""
    };
    for (const auto& expression : valid) {
        engine.load("[copy]\ninput = " + expression + "\noutput = O1\n");
        CHECK(!diagnostic(engine, "error", "Expression DROID"));
        CHECK(!diagnostic(engine, "error", "guillemets"));
    }
    const std::vector<std::string> invalid = {
        "I1 / 0", "I1 / 0.0V", "I1 / I2", "I1 + I2 + I3", "I1 * I2 * I3", "(I1)", "nan", "1e3", "I1 @ 2", "_", "P1", "Z1", "\"unterminated", "\"é\"", "\"a\\b\""
    };
    for (const auto& expression : invalid) {
        engine.load("[copy]\ninput = " + expression + "\n");
        CHECK(diagnostic(engine, "error"));
    }
    engine.load("[copy]\noutput = 2\n");
    CHECK(diagnostic(engine, "error"));
    engine.load("[copy]\ninput = \noutput =\n");
    CHECK(!diagnostic(engine, "error"));
    CHECK(diagnostic(engine, "warning", "non renseignée"));
    engine.load("[copy]\nunknown = I1\n");
    CHECK(diagnostic(engine, "warning", "unknown"));
    CHECK(!diagnostic(engine, "error"));
    engine.load("input = I1\n[copy] extra\ninput = I2\n[copy]\ninput = I3\n");
    CHECK(engine.blocks().size() == 1);
    CHECK(engine.blocks()[0].parameters.size() == 1);
    CHECK(engine.blocks()[0].parameters[0].value == "I3");
    CHECK(diagnostic(engine, "error"));
}

void atomicRejectionsAndUnknownEditing() {
    PatchEngine engine;
    const std::string source = "[copy]\ninput = I1\noutput = O1\n";
    engine.load(source);
    rejected([&] { engine.addCircuit("copy]\n[lfo"); });
    rejected([&] { engine.addCircuit("unknown999"); });
    rejected([&] { engine.setParameter(-1, "input", "3"); });
    rejected([&] { engine.setParameter(5, "input", "3"); });
    rejected([&] { engine.setParameter(0, "input\noutput", "3"); });
    rejected([&] { engine.setParameter(0, "input", "3\n[lfo]"); });
    rejected([&] { engine.setParameter(0, "input", "3\routput=O2"); });
    rejected([&] { engine.setParameter(0, "input", std::string("3\0x", 3)); });
    rejected([&] { engine.setParameter(0, "input", "3 # comment"); });
    rejected([&] { engine.setParameter(0, "inexistent", "3"); });
    rejected([&] { engine.removeCircuit(2); });
    CHECK(engine.source() == source);
    CHECK(!engine.canUndo());
    CHECK(!engine.canRedo());
    engine.setParameter(0, "input", "I1");
    CHECK(!engine.canUndo());
    engine.load("[future]\nopaque = newSyntax\n");
    engine.setParameter(0, "opaque", "newerSyntax");
    CHECK(engine.source() == "[future]\nopaque = newerSyntax\n");
    CHECK(diagnostic(engine, "warning"));
}

void duplicateAndDisabledAssignments() {
    PatchEngine engine;
    engine.load("#[copy]\n# input = 1\ninput = 2\ninput = 3 # effective\n# input = 4\n");
    CHECK(engine.blocks()[0].disabled);
    CHECK(!diagnostic(engine, "warning", "plusieurs"));
    engine.setParameter(0, "i", "5");
    CHECK(engine.source() == "#[copy]\n# input = 1\ninput = 2\ninput = 5 # effective\n# input = 4\n");
    engine.load("[copy]\n# i = 1\ninput = 2\ni = 3 # effective\n# input = 4\n");
    CHECK(diagnostic(engine, "warning", "plusieurs"));
    engine.setParameter(0, "input", "5");
    CHECK(engine.source() == "[copy]\n# i = 1\ninput = 2\ni = 5 # effective\n# input = 4\n");
    engine.load("[copy]\n# input = 1\n");
    engine.setParameter(0, "input", "2");
    CHECK(engine.source() == "[copy]\n# input = 2\n");

    const std::string inactive = "[copy]\ninput = 1\n#[copy] # inactive\n# input = 2 # preserve\n";
    engine.load(inactive);
    engine.setParameter(1, "output", "O2");
    CHECK(engine.source() == inactive + "#   output = O2\n");
    CHECK(engine.blocks()[1].parameters.back().disabled);
    engine.undo();
    CHECK(engine.source() == inactive);
    engine.setParameter(1, "i", "3");
    CHECK(engine.source() == "[copy]\ninput = 1\n#[copy] # inactive\n# input = 3 # preserve\n");
    CHECK(engine.blocks()[1].parameters[0].disabled);

    for (const auto& source : std::vector<std::string>{
        "[copy]\n# input = (I1)\n",
        "#[copy]\ninput = (I1)\n",
        "[copy]\n# input = \"unfinished # hash\n",
        "#[copy]\ninput = \"unfinished # hash\n"
    }) {
        engine.load(source);
        CHECK(!diagnostic(engine, "error"));
        CHECK(diagnostic(engine, "warning", "Valeur désactivée"));
        CHECK(engine.source() == source);
    }
    engine.load("#[copy]\ninput = 1\ninput = 2\n");
    CHECK(!diagnostic(engine, "warning", "plusieurs"));
    engine.load("#[copy] unexpected\ninput = 1\n");
    CHECK(diagnostic(engine, "error", "En-tête"));
}

void documentSizeLimitIsAtomic() {
    PatchEngine engine;
    std::string exact = "[copy]\ninput = 1\n# ";
    exact.append(PatchEngine::kMaxSourceBytes - exact.size(), 'x');
    engine.load(exact);
    CHECK(engine.source().size() == PatchEngine::kMaxSourceBytes);
    engine.setParameter(0, "input", "2");
    engine.undo();
    const auto before = engine.source();
    CHECK(!engine.canUndo());
    CHECK(engine.canRedo());
    for (const auto& operation : std::vector<std::function<void()>>{
        [&] { engine.load(exact + "x"); },
        [&] { engine.replaceSource(exact + "x"); },
        [&] { engine.addCircuit("copy"); },
        [&] { engine.setParameter(0, "input", "10"); },
        [&] { engine.setParameter(0, "output", "O1"); }
    }) {
        rejected(operation);
        CHECK(engine.source() == before);
        CHECK(!engine.canUndo());
        CHECK(engine.canRedo());
    }
    engine.redo();
    CHECK(engine.blocks()[0].parameters[0].value == "2");
    engine.undo();
    CHECK(engine.source() == exact);
}

void boundedAnalysisPreservesTheWholeDocument() {
    PatchEngine engine;
    const std::string ordinary = "[copy]\ninput = I1\n";
    const auto verifyPartial = [&](const std::string& source, const std::string& reason) {
        engine.load(ordinary);
        engine.replaceSource(source);
        CHECK(engine.source() == source);
        CHECK(!engine.analysisComplete());
        CHECK(diagnostic(engine, "error", reason));
        CHECK(engine.state().find("\"analysisComplete\":false") != std::string::npos);
        const auto partialState = engine.state();
        rejected([&] { engine.addCircuit("copy"); });
        rejected([&] { engine.setParameter(0, "input", "I2"); });
        rejected([&] { engine.removeCircuit(0); });
        CHECK(engine.source() == source);
        CHECK(engine.state() == partialState);
        engine.undo();
        CHECK(engine.source() == ordinary);
        CHECK(engine.analysisComplete());
        engine.redo();
        CHECK(engine.source() == source);
        CHECK(!engine.analysisComplete());
    };

    std::string manyBlocks;
    for (std::size_t i = 0; i <= PatchEngine::kMaxAnalyzedBlocks; ++i) manyBlocks += "[copy]\n";
    verifyPartial(manyBlocks, "blocs");
    CHECK(engine.blocks().size() == PatchEngine::kMaxAnalyzedBlocks);

    std::string manyAssignments = "[copy]\n";
    for (std::size_t i = 0; i <= PatchEngine::kMaxAnalyzedParameters; ++i) manyAssignments += "# input = 1\n";
    verifyPartial(manyAssignments, "affectations");
    CHECK(engine.blocks()[0].parameters.size() == PatchEngine::kMaxAnalyzedParameters);

    std::string manyErrors;
    for (std::size_t i = 0; i <= PatchEngine::kMaxDiagnostics; ++i) manyErrors += "?\n";
    verifyPartial(manyErrors, "diagnostics");
    CHECK(engine.diagnostics().size() == PatchEngine::kMaxDiagnostics);

    verifyPartial("[copy]\ninput = \"" + std::string(PatchEngine::kMaxAnalyzedFieldBytes, 'a') + "\"\n", "champ");
    CHECK(engine.blocks()[0].parameters.empty());
    verifyPartial("# LABELS: firmware=" + std::string(PatchEngine::kMaxAnalyzedFieldBytes + 1, 'a') + "\n", "champ");
    CHECK(engine.blocks().empty());
    verifyPartial('[' + std::string(PatchEngine::kMaxAnalyzedFieldBytes + 1, 'a') + "]\n", "champ");
    verifyPartial("[copy]\n" + std::string(PatchEngine::kMaxAnalyzedFieldBytes + 1, 'a') + " = 1\n", "champ");

    // The cumulative field budget still catches many individually small fields.
    std::string excessiveFields = "[copy]\n";
    const auto longValue = std::string(8000, 'a');
    for (int i = 0; i < 132; ++i) excessiveFields += "# input = \"" + longValue + "\"\n";
    verifyPartial(excessiveFields, "volume des champs");

    std::string harmlessComments = "# ";
    harmlessComments.append(PatchEngine::kMaxSourceBytes - harmlessComments.size(), 'x');
    engine.load(harmlessComments);
    CHECK(engine.analysisComplete());
    CHECK(engine.source() == harmlessComments);
    CHECK(engine.blocks().empty());

    // Unknown names are repeated in warnings. Bound that amplification without
    // changing their exact source or canonical model fields.
    const auto hugeName = std::string(8000, 'a');
    std::string repeatedWarnings = '[' + hugeName + "]\n";
    for (int i = 0; i < 100; ++i) repeatedWarnings += "x = _CV\n";
    engine.load(repeatedWarnings);
    CHECK(engine.analysisComplete());
    CHECK(engine.source() == repeatedWarnings);
    CHECK(engine.state().size() < 256 * 1024);
    for (const auto& warning : engine.diagnostics()) CHECK(warning.message.size() <= PatchEngine::kMaxDiagnosticMessageBytes);
}

void randomizedLosslessHistoryAndCoordinates() {
    // Seeded edits exercise ranges after variable-width UTF-8 text, duplicate
    // aliases, mixed newlines, disabled blocks and retained malformed syntax.
    const std::string original = "\xef\xbb\xbf# Séquence 🎛️\r\n[copy]\r\ninput = I1\n# i = 2 # retained\r\noutput = O1\r\n#[lfo]\nhz = 3\n[future]\nopaque = newSyntax\nunknown syntax\n";
    PatchEngine engine(512);
    engine.load(original);
    std::mt19937 random(0x464f5247);
    std::vector<std::string> snapshots;
    for (int iteration = 0; iteration < 300; ++iteration) {
        const auto before = engine.source();
        const auto action = random() % 3;
        if (action == 0 || engine.blocks().empty()) engine.addCircuit(random() % 2 ? "copy" : "lfo");
        else {
            const auto index = static_cast<int>(random() % engine.blocks().size());
            if (action == 1) engine.removeCircuit(index);
            else {
                const auto& block = engine.blocks()[index];
                const auto name = block.name == "lfo" ? "hz" : block.name == "future" ? "opaque" : "input";
                engine.setParameter(index, name, std::to_string(random() % 1000));
            }
        }
        if (engine.source() != before) snapshots.push_back(before);
        for (const auto& block : engine.blocks()) {
            CHECK(block.sourceStart <= block.sourceEnd);
            CHECK(block.sourceEnd <= engine.source().size());
            for (const auto& parameter : block.parameters) {
                CHECK(parameter.sourceStart <= parameter.valueStart);
                CHECK(parameter.valueStart <= parameter.valueEnd);
                CHECK(parameter.valueEnd <= parameter.sourceEnd);
                CHECK(parameter.sourceEnd <= engine.source().size());
                CHECK(engine.source().substr(parameter.valueStart, parameter.valueEnd - parameter.valueStart) == parameter.value);
            }
        }
        const auto changed = engine.source();
        if (changed != before) {
            engine.undo();
            CHECK(engine.source() == before);
            engine.redo();
            CHECK(engine.source() == changed);
        }
    }
    for (auto snapshot = snapshots.rbegin(); snapshot != snapshots.rend(); ++snapshot) {
        engine.undo();
        CHECK(engine.source() == *snapshot);
    }
    CHECK(engine.source() == original);
    CHECK(!engine.canUndo());

    // Arbitrary binary input must neither crash nor rewrite the original bytes.
    for (int iteration = 0; iteration < 500; ++iteration) {
        std::string bytes(random() % 4096, '\0');
        for (auto& byte : bytes) byte = static_cast<char>(random() & 0xff);
        engine.load(bytes);
        CHECK(engine.source() == bytes);
        CHECK(!engine.state().empty());
    }
}

void allocationFailuresKeepPublishedStateConsistent() {
    const std::string source0 = "# Allocation failure fixture 🎛️\r\n[copy]\r\ninput = 0 # retained\r\noutput = _CABLE\r\n# input = _MERGE\r\n";
    auto source1 = source0;
    source1.replace(source1.find("input = 0"), 9, "input = 1");
    auto source2 = source0;
    source2.replace(source2.find("input = 0"), 9, "input = 2");
    const std::string replacement = "# Replacement 🥁\n[copy]\ninput = I2\n[lfo]\nhz = 3\n";
    const auto prepare = [&](PatchEngine& engine) {
        engine.load(source0);
        engine.setParameter(0, "input", "1");
        engine.setParameter(0, "input", "2");
        engine.undo();
    };
    const std::vector<std::function<void(PatchEngine&)>> operations = {
        [&](PatchEngine& engine) { engine.load(replacement); },
        [&](PatchEngine& engine) { engine.replaceSource(replacement); },
        [](PatchEngine& engine) { engine.setParameter(0, "input", "3"); },
        [](PatchEngine& engine) { engine.setParameterExpression(0, "input", "I2", "0.5", "3"); },
        [](PatchEngine& engine) { engine.connectParameters(0, "output", 0, "_CABLE", 0, "input", 1, "1", "_CONNECTED"); },
        [](PatchEngine& engine) { engine.connectParameterExpressions(0, "output", 0, "_CABLE", "", "", 0, "input", 0, "", "2", "3", "_CONNECTED"); },
        [](PatchEngine& engine) { engine.renameCable("cable", "next"); },
        [](PatchEngine& engine) { engine.renameCable("cable", "merge", true); },
        [](PatchEngine& engine) { engine.deleteCable("cable"); },
        [](PatchEngine& engine) { engine.setRegisterLabel("I1", "CLK 🥁", "external clock"); },
        [](PatchEngine& engine) { engine.setBlockComment(0, "Circuit commentaire 🥁\nDeuxième ligne"); },
        [](PatchEngine& engine) { engine.addCircuit("lfo"); },
        [](PatchEngine& engine) { engine.removeCircuit(0); },
        [](PatchEngine& engine) { engine.undo(); },
        [](PatchEngine& engine) { engine.redo(); }
    };
    std::size_t injectedFailures = 0;
    for (const auto& operation : operations) {
        PatchEngine expected;
        prepare(expected);
        const auto beforeState = expected.state();
        allocation_fault::count = 0;
        allocation_fault::tracking = true;
        operation(expected);
        allocation_fault::tracking = false;
        const auto allocationCount = allocation_fault::count;
        CHECK(allocationCount > 0);
        const auto failuresBefore = injectedFailures;
        const auto afterSource = expected.source();
        const auto afterState = expected.state();
        CHECK(afterSource != source1);

        for (std::size_t failure = 0; failure <= allocationCount; ++failure) {
            PatchEngine engine;
            prepare(engine);
            allocation_fault::countdown = static_cast<long>(failure);
            try { operation(engine); }
            catch (const std::bad_alloc&) { ++injectedFailures; }
            allocation_fault::countdown = -1;
            // Serialization may fail after the operation has fully succeeded.
            // There must never be a published source with half-parsed metadata.
            if (engine.source() == source1) {
                CHECK(engine.state() == beforeState);
                engine.undo();
                CHECK(engine.source() == source0);
                engine.redo();
                CHECK(engine.source() == source1);
                engine.redo();
                CHECK(engine.source() == source2);
            } else {
                CHECK(engine.source() == afterSource);
                CHECK(engine.state() == afterState);
            }
        }
        CHECK(injectedFailures > failuresBefore);
    }
    CHECK(injectedFailures > 0);
}

void boundedHistoryAndLoadReset() {
    PatchEngine engine(2, 10000);
    engine.load("[copy]\ninput = 0\n");
    engine.setParameter(0, "input", "1");
    engine.setParameter(0, "input", "2");
    engine.setParameter(0, "input", "3");
    engine.undo();
    CHECK(engine.blocks()[0].parameters[0].value == "2");
    engine.undo();
    CHECK(engine.blocks()[0].parameters[0].value == "1");
    CHECK(!engine.canUndo());
    engine.redo();
    CHECK(engine.blocks()[0].parameters[0].value == "2");
    engine.setParameter(0, "input", "4");
    CHECK(!engine.canRedo());
    engine.load("[copy]\n");
    CHECK(!engine.canUndo());
    CHECK(!engine.canRedo());
    PatchEngine tiny(2, 2);
    tiny.load("[copy]\ninput = 0\n");
    tiny.setParameter(0, "input", "1");
    CHECK(!tiny.canUndo());
}

void rawSourceEditsHaveHistory() {
    PatchEngine engine;
    const std::string original = "# Commentaire original 🎛️\r\n[copy]\r\ninput = I1  # preserve\r\n";
    const std::string edited = "# Nouveau texte\n[copy]\ninput = I2\n[future]\nopaque = custom\n";
    engine.load(original);
    engine.replaceSource(original);
    CHECK(!engine.canUndo());
    engine.replaceSource(edited);
    CHECK(engine.source() == edited);
    CHECK(engine.canUndo());
    CHECK(diagnostic(engine, "warning", "future"));
    engine.undo();
    CHECK(engine.source() == original);
    CHECK(engine.canRedo());
    engine.redo();
    CHECK(engine.source() == edited);
}

void limitsFirmwareAndJson() {
    PatchEngine engine;
    engine.load("# LABELS: firmware=future-99; version=1\n[copy]\ninput = I1\n");
    CHECK(engine.state().find("\"firmware\":\"future-99\"") != std::string::npos);
    CHECK(diagnostic(engine, "warning", "Firmware"));
    std::string large = "# ";
    large.append(65000, 'x');
    engine.load(large);
    CHECK(engine.source() == large);
    CHECK(diagnostic(engine, "warning", "64 000"));
    engine.load("[copy]\ninput = _" + std::string(64, 'a') + '\n');
    CHECK(diagnostic(engine, "warning", "63 octets"));
    CHECK(!diagnostic(engine, "error", "Expression DROID"));
    CHECK(PatchEngine::catalogue().front() == '[');
    CHECK(PatchEngine::catalogue().find("\"name\":\"copy\"") != std::string::npos);
    engine.load("");
    CHECK(engine.state().find("\"blocks\":[]") != std::string::npos);
}

void forgeRegisterLabelsAndRack() {
    PatchEngine engine;
    const std::string original = "\xef\xbb\xbf# Mon patch 🎛️\r\n# LABELS: master=18; firmware=blue-7; unknown=keep\r\n"
        "# INPUTS:\r\n#  I1: [CLK] Horloge externe\r\n#  P1.2: bouton libre\r\n"
        "#  G1: [OLD] ancienne forme\r\n#  G1.1: [NEW] dernière définition\r\n"
        "# future metadata remains\r\n\r\n[p10]\r\n[m4]\r\n[copy]\r\ninput = I1\r\noutput = G2.3\r\n";
    engine.load(original);
    CHECK(engine.source() == original);
    CHECK(engine.registerLabels().size() == 4);
    CHECK(engine.registerLabels()[2].reg == "G1.1");
    CHECK(engine.state().find("\"masterType\":18") != std::string::npos);
    CHECK(engine.state().find("\"catalogueFirmware\":\"blue-7\"") != std::string::npos);
    CHECK(engine.state().find("\"name\":\"g8\",\"block\":-1,\"controller\":0,\"g8\":2,\"rgbOffset\":16") != std::string::npos);
    CHECK(engine.state().find("\"name\":\"p10\",\"block\":0,\"controller\":1") != std::string::npos);
    CHECK(engine.state().find("\"name\":\"m4\",\"block\":1,\"controller\":2") != std::string::npos);
    engine.setRegisterLabel("g1", "NEW", "dernière définition");
    CHECK(engine.source() == original && !engine.canUndo());
    engine.setRegisterLabel("i1", "CLOCK 🥁", "Nouvelle horloge");
    auto expected = original;
    expected.replace(expected.find("[CLK] Horloge externe"), std::string("[CLK] Horloge externe").size(), "[CLOCK 🥁] Nouvelle horloge");
    CHECK(engine.source() == expected);
    engine.undo(); CHECK(engine.source() == original);
    engine.redo(); CHECK(engine.source() == expected);
    engine.undo();
    engine.setRegisterLabel("G1.1", "", "");
    CHECK(engine.registerLabels().size() == 2);
    CHECK(engine.source().find("ancienne forme") == std::string::npos);
    CHECK(engine.source().find("dernière définition") == std::string::npos);
    CHECK(engine.source().find("unknown=keep") != std::string::npos);
    engine.undo(); CHECK(engine.source() == original);
    engine.load("# I1: [TITLE] first comment is the title\n[copy]\ninput = I1\n");
    CHECK(engine.registerLabels().empty());
    engine.setRegisterLabel("I1", "CLK", "external");
    CHECK(engine.registerLabels().size() == 1);
    CHECK(engine.source().find("# I1: [TITLE] first comment is the title\n") == 0);
    engine.load("\xef\xbb\xbf"); engine.setRegisterLabel("I1", "CLK", "");
    CHECK(engine.source().find("\xef\xbb\xbf# REGISTER LABELS:\n#  I1: [CLK]\n") == 0);
    CHECK(engine.registerLabels().size() == 1);
    engine.load("[copy]\ninput = I1\n# I2: [CIRCUIT] ordinary circuit comment\n[copy]\ninput = I2\n");
    CHECK(engine.registerLabels().empty());
    CHECK(engine.blocks()[1].comment == "I2: [CIRCUIT] ordinary circuit comment");
    engine.load("#LABELS: notmaster=18;master=18; master=bad\n[midiin]\n");
    CHECK(engine.state().find("\"masterType\":16") != std::string::npos);
    CHECK(engine.state().find("\"name\":\"x7\",\"block\":-1") != std::string::npos);
    engine.load("#LABELS: master=+018\n[midiout]\n");
    CHECK(engine.state().find("\"masterType\":18") != std::string::npos);
    CHECK(engine.state().find("\"name\":\"x7\"") == std::string::npos);
    engine.load("[p10]\n[g8]\n[x7]\n[m4]\n[copy]\ninput = G4.1\noutput = R49\n");
    CHECK(engine.blocks()[0].controller && !engine.blocks()[1].controller && !engine.blocks()[2].controller);
    CHECK(engine.state().find("\"name\":\"m4\",\"block\":3,\"controller\":2") != std::string::npos);
    CHECK(engine.state().find("\"g8\":4,\"rgbOffset\":40") != std::string::npos);
    CHECK(engine.state().find("\"name\":\"x7\",\"block\":-1") != std::string::npos);
    engine.load("[copy]\ninput = G4294967295.1\noutput = O1\n");
    CHECK(engine.state().find("\"rackComplete\":false") != std::string::npos);
    CHECK(engine.state().size() < 10000);
    engine.load("# [midiin]\n# input = G2.1\n[copy]\ninput = \"G4.1 R49\"\noutput = O1\n");
    CHECK(engine.state().find("\"name\":\"x7\"") == std::string::npos);
    CHECK(engine.state().find("\"name\":\"g8\"") == std::string::npos);
}

void circuitCommentsPreserveOwnedText() {
    PatchEngine engine;
    const std::string original = "# Title\r\n# Patch description\r\n\r\n# INPUTS:\r\n# I1: [CLK] global\r\n"
        "# ---\r\n# Section title\r\n# ---\r\n\r\n# First circuit\r\n# Second line\r\n"
        "[copy]  # inline text\r\ninput = I1 # parameter comment\r\n# input = I2 # disabled\r\n\r\n"
        "# Next circuit\r\n[lfo]\r\nhz = 1\r\n";
    engine.load(original);
    CHECK(engine.blocks()[0].comment == "First circuit\nSecond line\ninline text");
    CHECK(engine.blocks()[1].comment == "Next circuit");
    engine.setBlockComment(0, engine.blocks()[0].comment);
    CHECK(engine.source() == original && !engine.canUndo());
    engine.setBlockComment(0, "Nouveau 🥁\r\nDeuxième ligne");
    auto expected = original;
    expected.replace(expected.find("# First circuit\r\n# Second line\r\n"), std::string("# First circuit\r\n# Second line\r\n").size(), "# Nouveau 🥁\r\n# Deuxième ligne\r\n");
    expected.replace(expected.find("[copy]  # inline text"), std::string("[copy]  # inline text").size(), "[copy]");
    CHECK(engine.source() == expected);
    CHECK(engine.blocks()[0].comment == "Nouveau 🥁\nDeuxième ligne");
    CHECK(engine.blocks()[1].comment == "Next circuit");
    CHECK(engine.registerLabels().size() == 1);
    engine.undo(); CHECK(engine.source() == original);
    engine.setBlockComment(0, "");
    CHECK(engine.blocks()[0].comment.empty());
    CHECK(engine.source().find("Patch description") != std::string::npos);
    CHECK(engine.source().find("Section title") != std::string::npos);
    CHECK(engine.source().find("# parameter comment") != std::string::npos);
    CHECK(engine.source().find("# input = I2 # disabled") != std::string::npos);
    engine.undo(); engine.removeCircuit(0);
    CHECK(engine.source().find("First circuit") == std::string::npos);
    CHECK(engine.source().find("Next circuit") != std::string::npos);
    CHECK(engine.blocks()[0].comment == "Next circuit");
    engine.undo(); CHECK(engine.source() == original);
    engine.load("[copy]\ninput = I1\n");
    engine.setBlockComment(0, "First\nsecond");
    CHECK(engine.source() == "\n# First\n# second\n[copy]\ninput = I1\n");
    CHECK(engine.blocks()[0].comment == "First\nsecond");
    engine.load("# Title\n# description\n\n# Notes\n[copy] # original\ninput = I1\n");
    CHECK(engine.blocks()[0].comment == "Notes\noriginal");
    engine.setBlockComment(0, "Updated");
    CHECK(engine.source() == "# Title\n# description\n\n# Updated\n[copy]\ninput = I1\n");
}

void annotationRejectionsAndBudgetsAreAtomic() {
    PatchEngine engine;
    const std::string original = "# LABELS: master=18;unknown=keep\n# I1: [CLK] external\n\n[p10]\n[copy]\ninput = I1\n";
    engine.load(original);
    engine.setParameter(1, "input", "I2"); engine.undo();
    const auto before = engine.state();
    for (const auto& reg : {"_WIRE", "I0", "P0.1", "I1\n[copy]", "P4294967296.1"})
        rejected([&] { engine.setRegisterLabel(reg, "LABEL", ""); });
    rejected([&] { engine.setRegisterLabel("I1", "a]b", ""); });
    rejected([&] { engine.setRegisterLabel("I1", std::string(19, 'a'), ""); });
    rejected([&] { engine.setRegisterLabel("I1", "", "[not a short] description"); });
    rejected([&] { engine.setRegisterLabel("I1", "CLOCK", std::string(4097, 'a')); });
    rejected([&] { engine.setRegisterLabel("I1", "CLOCK", "x\ny"); });
    rejected([&] { engine.setRegisterLabel("I1", std::string("\xed\xa0\x80"), ""); });
    rejected([&] { engine.setBlockComment(1, std::string("\xc0\xaf")); });
    rejected([&] { engine.setBlockComment(1, std::string("bad\0text", 8)); });
    rejected([&] { engine.setBlockComment(0, "controller comment"); });
    for (const auto& text : {"[lfo]", "input = I2", "---", "I1: [CLK] global", "LABELS: master=16"})
        rejected([&] { engine.setBlockComment(1, text); });
    rejected([&] { engine.setBlockComment(-1, "text"); });
    rejected([&] { engine.setBlockComment(1, std::string(PatchEngine::kMaxCircuitCommentBytes + 1, 'a')); });
    CHECK(engine.source() == original && engine.state() == before);
    engine.redo(); CHECK(parameter(engine, 1, "input")->value == "I2");
    engine.load("[copy]\ninput = I1\n");
    const std::string maximum(PatchEngine::kMaxCircuitCommentBytes, 'a');
    engine.setBlockComment(0, maximum);
    CHECK(engine.analysisComplete() && engine.blocks()[0].comment == maximum);
    engine.undo();
    std::string lines = "a";
    for (int i = 1; i < 256; ++i) lines += "\na";
    engine.setBlockComment(0, lines);
    CHECK(engine.blocks()[0].comment == lines);
    auto source = engine.source();
    rejected([&] { engine.setBlockComment(0, lines + "\na"); });
    CHECK(engine.source() == source);
    engine.load("# INPUTS:\n# I1: [" + std::string("\xff") + "] malformed\n[copy]\ninput = I1\n");
    CHECK(engine.registerLabels().size() == 1);
    CHECK(engine.source().find('\xff') != std::string::npos);
    CHECK(engine.state().find("\\ufffd") != std::string::npos);
    source = "# INPUTS:\n";
    for (std::size_t i = 0; i <= PatchEngine::kMaxRegisterLabels; ++i) source += "# I1: [CLK] external\n";
    engine.load(source);
    CHECK(!engine.analysisComplete() && engine.source() == source);
    rejected([&] { engine.setRegisterLabel("I1", "NEW", ""); });
    CHECK(engine.source() == source);
    engine.load("# " + std::string(PatchEngine::kMaxSourceBytes - 2, 'a'));
    CHECK(engine.analysisComplete());
    source = engine.source();
    rejected([&] { engine.setRegisterLabel("I1", "NEW", ""); });
    CHECK(engine.source() == source && !engine.canUndo());
}

void controllerRemovalPreservesRegisterIdentity() {
    PatchEngine engine;
    const std::string original = "\xef\xbb\xbf# Title\r\n# LABELS: master=16;unknown=keep\r\n"
        "# P1.1: [DELETE] removed module\r\n# p2.3: [KEEP] surviving module\r\n# G2.1: [GATE] global expander\r\n"
        "# Z2.1: unknown metadata\r\n\r\n[p10]\r\n[m4]\r\n[copy] # comment P2.3\r\n"
        "input = p002.0003 * B2.4 + P20.1 # P2.3 comment\r\noutput = L2.4\r\n"
        "# input = P2.3\r\n[display]\r\ntext = \"P2.3\"\r\n# unchanged _P2_3 and P2.3\r\n";
    engine.load(original);
    engine.removeCircuit(0);
    auto expected = original;
    expected.erase(expected.find("# P1.1: [DELETE] removed module\r\n"), std::string("# P1.1: [DELETE] removed module\r\n").size());
    expected.erase(expected.find("[p10]\r\n"), std::string("[p10]\r\n").size());
    expected.replace(expected.find("# p2.3:"), std::string("# p2.3:").size(), "# p1.3:");
    expected.replace(expected.find("p002.0003 * B2.4 + P20.1"), std::string("p002.0003 * B2.4 + P20.1").size(), "p001.0003 * B1.4 + P19.1");
    expected.replace(expected.find("output = L2.4"), std::string("output = L2.4").size(), "output = L1.4");
    expected.replace(expected.find("# input = P2.3"), std::string("# input = P2.3").size(), "# input = P1.3");
    CHECK(engine.source() == expected);
    CHECK(engine.state().find("\"register\":\"P1.3\"") != std::string::npos);
    CHECK(engine.source().find("# Z2.1: unknown metadata") != std::string::npos);
    CHECK(engine.source().find("# G2.1: [GATE]") != std::string::npos);
    CHECK(engine.source().find("text = \"P2.3\"") != std::string::npos);
    engine.undo(); CHECK(engine.source() == original);
    engine.redo(); CHECK(engine.source() == expected);
    for (const std::string reference : {"input = P1.1", "# input = B1.4", "input = P1.1 * 2 + 3", "opaque = E1.1"}) {
        const auto used = "[p10]\n[m4]\n[copy]\n" + reference + "\noutput = O1\n";
        engine.load(used);
        engine.setParameter(2, "output", "O2"); engine.undo();
        const auto before = engine.state();
        rejected([&] { engine.removeCircuit(0); });
        CHECK(engine.source() == used && engine.state() == before);
        engine.redo(); CHECK(parameter(engine, 2, "output")->value == "O2");
    }
    engine.load("[p10]\n[copy]\ninput = I1\nraw P1.1 unknown syntax\n");
    const auto malformed = engine.source();
    rejected([&] { engine.removeCircuit(0); }); CHECK(engine.source() == malformed);
    engine.load("[p10]\n[copy]\ninput = \"P1.1\n");
    rejected([&] { engine.removeCircuit(0); });
    engine.load("# INPUTS:\n# I1: [OLD] earlier\n# I1:\n[copy]\ninput = I1\n");
    engine.setRegisterLabel("I1", "", "");
    CHECK(engine.registerLabels().empty() && engine.source().find("earlier") == std::string::npos);
}

void controllerRemovalAllocationFailuresAreAtomic() {
    const std::string original = "# LABELS: master=18\n# P1.1: [OLD] removed\n# P2.2: [KEEP] remaining\n"
        "[p10]\n[m4]\n[copy]\ninput = P2.1\noutput = O1\n";
    auto prepare = [&](PatchEngine& engine) {
        engine.load(original); engine.setParameter(2, "input", "P2.2"); engine.undo();
    };
    PatchEngine expected;
    prepare(expected);
    const auto before = expected.state();
    allocation_fault::count = 0; allocation_fault::tracking = true;
    expected.removeCircuit(0);
    allocation_fault::tracking = false;
    const auto count = allocation_fault::count;
    const auto after = expected.state(), source = expected.source();
    CHECK(count > 0 && source != original);
    for (std::size_t failure = 0; failure <= count; ++failure) {
        PatchEngine engine; prepare(engine);
        allocation_fault::countdown = static_cast<long>(failure);
        try { engine.removeCircuit(0); } catch (const std::bad_alloc&) {}
        allocation_fault::countdown = -1;
        if (engine.source() == original) {
            CHECK(engine.state() == before);
            engine.redo(); CHECK(parameter(engine, 2, "input")->value == "P2.2");
        } else CHECK(engine.source() == source && engine.state() == after);
    }
}
} // namespace

int main(int argc, char** argv) {
    try {
        if (argc == 3 && std::string(argv[1]) == "--verify") {
            std::ifstream input(argv[2], std::ios::binary);
            if (!input) throw std::runtime_error("Cannot open patch file.");
            const std::string source((std::istreambuf_iterator<char>(input)), {});
            PatchEngine engine;
            engine.load(source);
            CHECK(engine.source() == source);
            for (const auto& d : engine.diagnostics()) std::cout << d.line << " " << d.severity << " " << d.message << '\n';
            std::cout << engine.blocks().size() << " blocks, " << engine.diagnostics().size() << " diagnostics; exact byte round-trip verified\n";
            return diagnostic(engine, "error") ? EXIT_FAILURE : EXIT_SUCCESS;
        }
        roundTripAndTargetedEdit();
        forgeCircuitCreationDefaults();
        affineCellsPreserveImportedExpressions();
        connectedParametersCommitTogether();
        cableInventoryAndGlobalEdits();
        cableConnectivityDiagnosticsAreConservative();
        quotedHashesAndUtf8();
        additionsRemovalAndBom();
        syntaxAndSchemaValidation();
        atomicRejectionsAndUnknownEditing();
        duplicateAndDisabledAssignments();
        documentSizeLimitIsAtomic();
        boundedAnalysisPreservesTheWholeDocument();
        randomizedLosslessHistoryAndCoordinates();
        allocationFailuresKeepPublishedStateConsistent();
        boundedHistoryAndLoadReset();
        rawSourceEditsHaveHistory();
        limitsFirmwareAndJson();
        forgeRegisterLabelsAndRack();
        circuitCommentsPreserveOwnedText();
        annotationRejectionsAndBudgetsAreAtomic();
        controllerRemovalPreservesRegisterIdentity();
        controllerRemovalAllocationFailuresAreAtomic();
        std::cout << checks << " checks passed\n";
        return EXIT_SUCCESS;
    }
    catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return EXIT_FAILURE;
    }
}
