#include "androforge/patch_engine.h"
#include "catalogue_data.h"

#include <algorithm>
#include <array>
#include <cstdint>
#include <iterator>
#include <limits>
#include <map>
#include <stdexcept>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <utility>

namespace androforge {
namespace {

bool letter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
}
bool digit(char c) { return c >= '0' && c <= '9'; }
bool space(char c) { return c == ' ' || c == '\t' || c == '\r'; }
char lower(char c) { return c >= 'A' && c <= 'Z' ? char(c + 'a' - 'A') : c; }
std::string lowercase(std::string_view value) {
    std::string result(value);
    std::transform(result.begin(), result.end(), result.begin(), lower);
    return result;
}
std::string_view trim(std::string_view value) {
    while (!value.empty() && space(value.front())) value.remove_prefix(1);
    while (!value.empty() && space(value.back())) value.remove_suffix(1);
    return value;
}
bool identifier(std::string_view value, bool circuit = false) {
    if (value.empty() || !letter(value.front()) || (circuit && value.size() < 2)) return false;
    return std::all_of(value.begin(), value.end(), [](char c) { return letter(c) || digit(c); });
}
bool inlineSafe(std::string_view value) {
    return value.find_first_of("\r\n") == std::string_view::npos &&
           value.find('\0') == std::string_view::npos;
}

const CircuitSchema* circuitSchema(std::string_view name) {
    static const auto index = [] {
        std::unordered_map<std::string_view, const CircuitSchema*> result;
        for (const auto& circuit : kCircuits) result.emplace(circuit.name, &circuit);
        for (const auto& circuit : kCircuits) {
            if (circuit.shortName && *circuit.shortName) result.emplace(circuit.shortName, &circuit);
        }
        return result;
    }();
    const auto found = index.find(name);
    return found == index.end() ? nullptr : found->second;
}
bool controllerExists(std::string_view name) {
    // G8 and X7 have faceplate assets but are inferred expanders, not entries
    // in Forge's numbered controller chain (ModuleBuilder::allControllers).
    if (name == "g8" || name == "x7") return false;
    return std::any_of(std::begin(kControllers), std::end(kControllers),
                       [&](const char* controller) { return controller && name == controller; });
}
const ParameterSchema* parameterSchema(const CircuitSchema* circuit, std::string_view name) {
    if (!circuit) return nullptr;
    using ParameterIndex = std::unordered_map<std::string_view, const ParameterSchema*>;
    static const auto indexes = [] {
        std::unordered_map<const CircuitSchema*, ParameterIndex> result;
        for (const auto& schema : kCircuits) {
            auto& index = result[&schema];
            index.reserve(schema.parameterCount * 2);
            for (std::size_t i = 0; i < schema.parameterCount; ++i) index.emplace(schema.parameters[i].name, &schema.parameters[i]);
            for (std::size_t i = 0; i < schema.parameterCount; ++i) {
                const auto& param = schema.parameters[i];
                if (param.shortName && *param.shortName) index.emplace(param.shortName, &param);
            }
        }
        return result;
    }();
    // Singular names win over a first element's omitted index (Forge grammar).
    const auto& index = indexes.at(circuit);
    const auto exact = index.find(name);
    if (exact != index.end()) return exact->second;
    const std::string first = std::string(name) + "1";
    const auto indexed = index.find(first);
    return indexed == index.end() ? nullptr : indexed->second;
}

// Like Forge's findCommentMark: hashes inside a quoted display string are data.
std::size_t commentMark(std::string_view value, bool* unterminated = nullptr) {
    bool inQuotes = false;
    for (std::size_t i = 0; i < value.size(); ++i) {
        if (value[i] == '"') inQuotes = !inQuotes;
        else if (value[i] == '#' && !inQuotes) {
            if (unterminated) *unterminated = false;
            return i;
        }
    }
    if (unterminated) *unterminated = inQuotes;
    return std::string_view::npos;
}

struct Expression {
    std::string_view text;
    std::size_t position = 0;
    void skip() { while (position < text.size() && space(text[position])) ++position; }
    bool number(std::string_view token) const {
        if (!token.empty() && token.front() == '-') token.remove_prefix(1);
        if (token.empty() || !digit(token.front())) return false;
        std::size_t i = 0;
        while (i < token.size() && digit(token[i])) ++i;
        if (i < token.size() && token[i] == '.') {
            ++i;
            const auto firstFraction = i;
            while (i < token.size() && digit(token[i])) ++i;
            if (i == firstFraction) return false;
        }
        if (i < token.size() && (token[i] == 'V' || token[i] == '%')) ++i;
        return i == token.size();
    }
    static bool reg(std::string_view token) {
        if (token.size() < 2 || !letter(token[0])) return false;
        const char type = lower(token[0]);
        token.remove_prefix(1);
        const auto dot = token.find('.');
        if (dot == std::string_view::npos) {
            if (std::string_view("inogrx").find(type) == std::string_view::npos) return false;
            return std::all_of(token.begin(), token.end(), digit);
        }
        if (std::string_view("gblpesr").find(type) == std::string_view::npos || dot == 0 || dot + 1 == token.size()) return false;
        return std::all_of(token.begin(), token.begin() + dot, digit) &&
               std::all_of(token.begin() + dot + 1, token.end(), digit);
    }
    static bool cable(std::string_view token) {
        return token.size() > 1 && token[0] == '_' &&
               std::all_of(token.begin() + 1, token.end(), [](char c) { return letter(c) || digit(c) || c == '_'; });
    }
    bool atom(bool numericOnly = false, bool* zero = nullptr) {
        skip();
        if (position == text.size()) return false;
        const auto start = position;
        if (text[position] == '"') {
            ++position;
            while (position < text.size() && text[position] != '"') {
                const auto c = static_cast<unsigned char>(text[position++]);
                // DROID's display text grammar is printable ASCII.
                if (c < 32 || c > 126 || c == '\\') return false;
            }
            if (position == text.size() || numericOnly) return false;
            ++position;
            return true;
        }
        if (text[position] == '-') ++position;
        while (position < text.size()) {
            const char c = text[position];
            if (space(c) || c == '*' || c == '/' || c == '+' || c == '-') break;
            ++position;
        }
        const auto token = text.substr(start, position - start);
        const bool isNumber = number(token);
        if (zero) {
            *zero = isNumber && std::none_of(token.begin(), token.end(), [](char c) { return c >= '1' && c <= '9'; });
        }
        if (numericOnly) return isNumber;
        const auto word = lowercase(token);
        return isNumber || word == "on" || word == "off" || cable(token) || reg(token);
    }
    bool valid(bool output) {
        if (text.empty()) return false;
        if (output) return cable(text) || reg(text);
        if (!atom()) return false;
        skip();
        if (position == text.size()) return true;
        bool divided = false;
        if (text[position] == '*' || text[position] == '/') {
            divided = text[position++] == '/';
            bool zero = false;
            if (!atom(divided, &zero) || (divided && zero)) return false;
            skip();
        }
        if (position == text.size()) return true;
        const char op = text[position++];
        if (op != '+' && op != '-') return false;
        if (!atom(divided && op == '-')) return false;
        skip();
        return position == text.size();
    }
};

bool singleAtom(std::string_view value) {
    Expression expression{value};
    return expression.atom() && (expression.skip(), expression.position == value.size());
}

struct AtomRange {
    std::size_t start = std::string_view::npos;
    std::size_t length = 0;
};
struct DecodedExpression {
    ExpressionParts parts;
    std::array<AtomRange, 3> ranges{};
};

DecodedExpression decodeExpression(std::string_view raw, bool output) {
    DecodedExpression decoded;
    const auto value = trim(raw);
    const auto prefix = static_cast<std::size_t>(value.data() - raw.data());
    if (raw.size() > PatchEngine::kMaxAnalyzedFieldBytes || !inlineSafe(raw)) return decoded;
    if (value.empty()) { decoded.parts.editable = true; return decoded; }
    if (output) {
        if (!Expression{value}.valid(true)) return decoded;
        decoded.parts = {true, std::string(value), {}, {}};
        decoded.ranges[0] = {prefix, value.size()};
        return decoded;
    }
    Expression parser{value};
    auto atom = [&](bool numericOnly, AtomRange& range) {
        parser.skip();
        const auto start = parser.position;
        if (!parser.atom(numericOnly)) return false;
        range = {prefix + start, parser.position - start};
        return true;
    };
    AtomRange first, second, third;
    if (!atom(false, first)) return decoded;
    decoded.parts.base = std::string(raw.substr(first.start, first.length));
    decoded.ranges[0] = first;
    parser.skip();
    if (parser.position == value.size()) { decoded.parts.editable = true; return decoded; }
    const char operation = value[parser.position++];
    bool divided = false;
    if (operation == '*' || operation == '/') {
        divided = operation == '/';
        if (!atom(divided, second)) return DecodedExpression{};
        const auto factor = raw.substr(second.start, second.length);
        if (divided) {
            // Forge's fraction editor accepts an ordinary numeric denominator,
            // not units or another signal. Preserve unsupported forms as raw.
            if (factor.find_first_of("V%") != std::string_view::npos ||
                std::none_of(factor.begin(), factor.end(), [](char c) { return c >= '1' && c <= '9'; }))
                return DecodedExpression{};
            decoded.parts.factor = "1 / " + std::string(factor);
        } else decoded.parts.factor = std::string(factor);
        decoded.ranges[1] = second;
        parser.skip();
        if (parser.position == value.size()) { decoded.parts.editable = true; return decoded; }
        const char add = value[parser.position++];
        if ((add != '+' && add != '-') || !atom(false, third)) return DecodedExpression{};
        auto offset = raw.substr(third.start, third.length);
        if (add == '-') {
            if (!Expression{offset}.number(offset)) return DecodedExpression{};
            decoded.parts.offset = offset.front() == '-' ? std::string(offset.substr(1)) : "-" + std::string(offset);
        } else decoded.parts.offset = std::string(offset);
        decoded.ranges[2] = third;
    } else if (operation == '+' || operation == '-') {
        if (!atom(false, second)) return DecodedExpression{};
        if (operation == '+') {
            decoded.parts.offset = std::string(raw.substr(second.start, second.length));
            decoded.ranges[2] = second;
        } else {
            // Forge maps A - B to -1 * B + A, keeping all three cells atoms.
            decoded.parts.offset = std::move(decoded.parts.base);
            decoded.parts.base = "-1";
            decoded.parts.factor = std::string(raw.substr(second.start, second.length));
            decoded.ranges[0] = {};
            decoded.ranges[1] = second;
            decoded.ranges[2] = first;
        }
    } else return DecodedExpression{};
    parser.skip();
    if (parser.position != value.size()) return DecodedExpression{};
    decoded.parts.editable = true;
    return decoded;
}

std::string_view directionOf(const Block& block, const Parameter& parameter) {
    const auto* schema = parameterSchema(circuitSchema(block.name), parameter.name);
    return !block.controller && schema && schema->direction ? std::string_view(schema->direction) : "unknown";
}

std::string cableName(std::string_view value) {
    value = trim(value);
    std::string result(value);
    if (result.empty() || result.front() != '_') result.insert(result.begin(), '_');
    if (result.size() > 256 || !Expression::cable(result)) throw std::invalid_argument("Nom de câble invalide (lettres, chiffres et _ ; 256 octets maximum).");
    for (char& c : result) if (c >= 'a' && c <= 'z') c = char(c + 'A' - 'a');
    return result;
}

struct CableToken { std::size_t start, length; };
std::vector<CableToken> cableTokens(std::string_view value) {
    std::vector<CableToken> tokens;
    bool quotedText = false;
    auto word = [](char c) { return letter(c) || digit(c) || c == '_'; };
    for (std::size_t index = 0; index < value.size();) {
        if (value[index] == '"') { quotedText = !quotedText; ++index; }
        else if (!quotedText && value[index] == '_' && (index == 0 || !word(value[index - 1]))) {
            const auto start = index++;
            while (index < value.size() && word(value[index])) ++index;
            if (index - start > 1) tokens.push_back({start, index - start});
        } else ++index;
    }
    return tokens;
}

struct CableOccurrence {
    std::size_t block;
    const Parameter* parameter;
    std::string_view direction;
    int column;
    bool disabled;
};
struct CableConnections {
    std::size_t inputs = 0, outputs = 0, disabled = 0, unknown = 0;
    std::vector<CableOccurrence> occurrences;
};
struct CableInventory {
    std::map<std::string, CableConnections> cables;
    bool complete;
};

CableInventory cableInventory(const std::vector<Block>& blocks, bool complete) {
    CableInventory result{{}, complete};
    std::size_t occurrenceCount = 0, copiedTextBytes = 0;
    for (std::size_t index = 0; result.complete && index < blocks.size(); ++index) {
        const auto& block = blocks[index];
        for (const auto& parameter : block.parameters) {
            const auto direction = directionOf(block, parameter);
            const auto decoded = direction == "unknown" ? DecodedExpression{} :
                decodeExpression(parameter.value, direction == "output");
            for (const auto token : cableTokens(parameter.value)) {
                std::string name = parameter.value.substr(token.start, token.length);
                for (char& c : name) if (c >= 'a' && c <= 'z') c = char(c + 'A' - 'a');
                if (occurrenceCount == 8192 || parameter.name.size() + token.length >
                    PatchEngine::kMaxAnalyzedTextBytes - copiedTextBytes || (result.cables.size() == 4096 &&
                    result.cables.find(name) == result.cables.end())) {
                    result.complete = false; break;
                }
                auto& cable = result.cables[name];
                const bool inactive = block.disabled || parameter.disabled;
                int column = -1;
                if (decoded.parts.editable) for (int candidate = 0; candidate < 3; ++candidate)
                    if (decoded.ranges[candidate].start == token.start && decoded.ranges[candidate].length == token.length)
                        column = candidate;
                if (inactive) ++cable.disabled;
                else if (direction == "input") ++cable.inputs;
                else if (direction == "output") ++cable.outputs;
                else ++cable.unknown;
                cable.occurrences.push_back({index, &parameter, direction, column, inactive});
                copiedTextBytes += parameter.name.size() + token.length;
                ++occurrenceCount;
            }
            if (!result.complete) break;
        }
    }
    return result;
}

void expressionJson(std::string& result, const ExpressionParts& parts);

void quoted(std::string& result, std::string_view value) {
    constexpr char hex[] = "0123456789abcdef";
    result.push_back('"');
    for (std::size_t i = 0; i < value.size(); ++i) {
        const auto c = static_cast<unsigned char>(value[i]);
        if (c == '"' || c == '\\') { result.push_back('\\'); result.push_back(char(c)); }
        else if (c < 32) { result += "\\u00"; result.push_back(hex[c >> 4]); result.push_back(hex[c & 15]); }
        else if (c < 128) result.push_back(char(c));
        else {
            // Keep valid UTF-8; malformed source bytes remain in source(), while
            // state stays well-formed JSON using the replacement character.
            std::size_t n = c >= 0xc2 && c <= 0xdf ? 2 : c >= 0xe0 && c <= 0xef ? 3 : c >= 0xf0 && c <= 0xf4 ? 4 : 0;
            std::uint32_t cp = n ? c & (0x7f >> n) : 0;
            bool valid = n && i + n <= value.size();
            for (std::size_t j = 1; valid && j < n; ++j) {
                const auto next = static_cast<unsigned char>(value[i + j]);
                valid = (next & 0xc0) == 0x80;
                cp = (cp << 6) | (next & 0x3f);
            }
            valid = valid && cp >= (n == 2 ? 0x80u : n == 3 ? 0x800u : 0x10000u) &&
                    cp <= 0x10ffff && !(cp >= 0xd800 && cp <= 0xdfff);
            if (valid) { result.append(value.substr(i, n)); i += n - 1; }
            else result += "\\ufffd";
        }
    }
    result.push_back('"');
}

void expressionJson(std::string& result, const ExpressionParts& parts) {
    result += parts.editable ? "{\"editable\":true,\"base\":" : "{\"editable\":false,\"base\":";
    quoted(result, parts.base);
    result += ",\"factor\":"; quoted(result, parts.factor);
    result += ",\"offset\":"; quoted(result, parts.offset);
    result += '}';
}

bool positiveNumber(std::string_view text, unsigned& value) {
    if (text.empty()) return false;
    value = 0;
    for (char c : text) {
        if (!digit(c) || value > (std::numeric_limits<unsigned>::max() - unsigned(c - '0')) / 10) return false;
        value = value * 10 + unsigned(c - '0');
    }
    return value != 0;
}

struct RegisterName {
    std::string canonical;
    char type = 0;
    unsigned controller = 0, g8 = 0, number = 0;
};

bool registerName(std::string_view text, RegisterName& result, bool knownTypes = false) {
    if (text.size() < 2 || !letter(text.front())) return false;
    result = {};
    result.type = static_cast<char>(lower(text.front()) - 'a' + 'A');
    text.remove_prefix(1);
    const auto dot = text.find('.');
    unsigned first = 0;
    if (dot == std::string_view::npos) {
        if (!positiveNumber(text, result.number) ||
            (knownTypes && std::string_view("INOGRX").find(result.type) == std::string_view::npos)) return false;
    } else {
        if (!positiveNumber(text.substr(0, dot), first) || !positiveNumber(text.substr(dot + 1), result.number) ||
            (knownTypes && std::string_view("GBLPESR").find(result.type) == std::string_view::npos)) return false;
        if (result.type == 'G' && result.number <= 8) result.g8 = first;
        else result.controller = first;
    }
    if (result.type == 'G' && !result.controller && !result.g8 && result.number <= 8) result.g8 = 1;
    result.canonical = result.type;
    if (result.controller || result.g8) result.canonical += std::to_string(result.controller ? result.controller : result.g8) + ".";
    result.canonical += std::to_string(result.number);
    return true;
}

bool registerLabelParts(std::string_view body, RegisterName& reg, std::string_view& shorthand,
                        std::string_view& description, std::size_t& valuePosition) {
    const auto colon = body.find(':');
    if (colon == std::string_view::npos) return false;
    const auto name = body.substr(0, colon);
    if (name.size() < 2 || !letter(name.front()) || name[1] == '0') return false;
    const auto dot = name.find('.');
    if (dot != std::string_view::npos && (dot + 1 == name.size() || name[dot + 1] == '0')) return false;
    if (!registerName(name, reg)) return false;
    const auto value = trim(body.substr(colon + 1));
    valuePosition = value.empty() ? colon + 1 : static_cast<std::size_t>(value.data() - body.data());
    shorthand = {};
    description = value;
    if (!value.empty() && value.front() == '[') {
        const auto close = value.find(']');
        if (close != std::string_view::npos && close > 1) {
            shorthand = value.substr(1, close - 1);
            description = trim(value.substr(close + 1));
        }
    }
    return true;
}

struct RegisterToken {
    SourceRange range;
    RegisterName reg;
};
std::vector<RegisterToken> registerTokens(std::string_view value) {
    std::vector<RegisterToken> result;
    bool quotedText = false;
    auto word = [](char c) { return letter(c) || digit(c) || c == '_' || c == '.'; };
    for (std::size_t i = 0; i < value.size();) {
        if (value[i] == '"') { quotedText = !quotedText; ++i; }
        else if (!quotedText && letter(value[i]) && (i == 0 || !word(value[i - 1]))) {
            const auto start = i++;
            while (i < value.size() && word(value[i])) ++i;
            RegisterName reg;
            if (registerName(value.substr(start, i - start), reg, true)) result.push_back({{start, i}, std::move(reg)});
        } else ++i;
    }
    if (quotedText) throw std::invalid_argument("Références de contrôleur ambiguës : corriger la chaîne non terminée dans le texte source.");
    return result;
}

std::string shiftedControllerDigits(std::string_view token, unsigned controller) {
    const auto dot = token.find('.');
    auto digits = std::to_string(controller);
    if (dot > 1 && token[1] == '0' && digits.size() < dot - 1) digits.insert(0, dot - 1 - digits.size(), '0');
    return digits;
}

bool metaComment(std::string_view body) {
    const auto colon = body.find(':');
    return colon != std::string_view::npos && colon != 0 && body.front() >= 'A' && body.front() <= 'Z' &&
        std::all_of(body.begin(), body.begin() + colon, [](char c) { return (c >= 'A' && c <= 'Z') || digit(c) || c == ' '; });
}
bool sectionSeparator(std::string_view body) {
    return body.size() >= 3 && std::all_of(body.begin(), body.end(), [](char c) { return c == '-'; });
}

void readMasterLabel(std::string_view body, int& masterType) {
    if (body.substr(0, 7) != "LABELS:") return;
    auto fields = body.substr(7);
    while (!fields.empty()) {
        const auto semicolon = fields.find(';');
        const auto field = trim(fields.substr(0, semicolon));
        const auto equal = field.find('=');
        if (equal != std::string_view::npos && trim(field.substr(0, equal)) == "master") {
            auto value = trim(field.substr(equal + 1));
            if (!value.empty() && value.front() == '+') value.remove_prefix(1);
            unsigned number = 0;
            masterType = positiveNumber(value, number) && number == 18 ? 18 : 16;
        }
        if (semicolon == std::string_view::npos) break;
        fields.remove_prefix(semicolon + 1);
    }
}

// Count UTF-16 units as Qt/Java do, while rejecting malformed UTF-8 and source
// control characters in new annotations. load() remains lossless and tolerant.
std::size_t annotationUnits(std::string_view value, bool multiline) {
    std::size_t units = 0;
    for (std::size_t i = 0; i < value.size();) {
        const auto first = static_cast<unsigned char>(value[i]);
        const std::size_t count = first < 0x80 ? 1 : first >= 0xc2 && first <= 0xdf ? 2 :
            first >= 0xe0 && first <= 0xef ? 3 : first >= 0xf0 && first <= 0xf4 ? 4 : 0;
        std::uint32_t cp = count == 1 ? first : count ? first & (0x7f >> count) : 0;
        bool valid = count && i + count <= value.size();
        for (std::size_t j = 1; valid && j < count; ++j) {
            const auto next = static_cast<unsigned char>(value[i + j]);
            valid = (next & 0xc0) == 0x80;
            cp = (cp << 6) | (next & 0x3f);
        }
        valid = valid && (count == 1 || cp >= (count == 2 ? 0x80u : count == 3 ? 0x800u : 0x10000u)) &&
            cp <= 0x10ffff && !(cp >= 0xd800 && cp <= 0xdfff);
        if (!valid || (cp < 32 && cp != '\t' && !(multiline && (cp == '\r' || cp == '\n'))) || cp == 127)
            throw std::invalid_argument("Texte d'annotation invalide : Unicode ou caractère de contrôle.");
        units += cp >= 0x10000 ? 2 : 1;
        i += count;
    }
    return units;
}

} // namespace

PatchEngine::PatchEngine(std::size_t historyLimit, std::size_t historyByteLimit)
    : firmware_(kFirmware), historyLimit_(historyLimit), historyByteLimit_(historyByteLimit) {}

std::string PatchEngine::load(std::string source) {
    if (source.size() > kMaxSourceBytes) throw std::invalid_argument("Le patch dépasse la limite de 4 Mio de l'éditeur.");
    PatchEngine parsed;
    parsed.source_ = std::move(source);
    parsed.parse();
    // Parsing allocates. Publish only after it has completed so a failure keeps
    // the previous document, coordinates, diagnostics and history consistent.
    publishParsed(parsed);
    undo_.clear();
    redo_.clear();
    return state();
}

void PatchEngine::publishParsed(PatchEngine& parsed) noexcept {
    source_.swap(parsed.source_);
    firmware_.swap(parsed.firmware_);
    newline_.swap(parsed.newline_);
    blocks_.swap(parsed.blocks_);
    diagnostics_.swap(parsed.diagnostics_);
    registerLabels_.swap(parsed.registerLabels_);
    std::swap(masterType_, parsed.masterType_);
    std::swap(labelInsertionPosition_, parsed.labelInsertionPosition_);
    std::swap(analysisComplete_, parsed.analysisComplete_);
    std::swap(controllerReferencesComplete_, parsed.controllerReferencesComplete_);
}

void PatchEngine::parse() {
    blocks_.clear();
    diagnostics_.clear();
    registerLabels_.clear();
    masterType_ = 16;
    labelInsertionPosition_ = source_.compare(0, 3, "\xef\xbb\xbf") == 0 ? 3 : 0;
    firmware_ = kFirmware;
    newline_ = "\n";
    analysisComplete_ = true;
    controllerReferencesComplete_ = true;
    const auto firstNewline = source_.find('\n');
    if (firstNewline != std::string::npos && firstNewline > 0 && source_[firstNewline - 1] == '\r') newline_ = "\r\n";
    auto diagnostic = [&](std::size_t line, const char* severity, std::string message) {
        if (!analysisComplete_) return;
        if (diagnostics_.size() + 1 >= kMaxDiagnostics) {
            analysisComplete_ = false;
            diagnostics_.push_back({line, "error", "Analyse interrompue : trop de diagnostics pour l'éditeur. Le texte intégral est conservé ; utiliser l'édition du texte source."});
            return;
        }
        // One enormous unknown block name can otherwise be repeated in every
        // parameter warning, multiplying a small input into huge diagnostic JSON.
        if (message.size() > kMaxDiagnosticMessageBytes) {
            constexpr std::string_view suffix = "… [message abrégé]";
            auto end = kMaxDiagnosticMessageBytes - suffix.size();
            while (end && (static_cast<unsigned char>(message[end]) & 0xc0) == 0x80) --end;
            message.resize(end);
            message += suffix;
        }
        diagnostics_.push_back({line, severity, std::move(message)});
    };
    auto stopAnalysis = [&](std::size_t line, const char* reason) {
        diagnostic(line, "error", std::string("Analyse interrompue : ") + reason + ". Le texte intégral est conservé ; utiliser l'édition du texte source.");
        analysisComplete_ = false;
    };
    if (source_.size() > 64000) diagnostic(1, "warning", "Le patch dépasse 64 000 octets : vérifier les limites du firmware avant transfert.");

    std::size_t start = 0;
    std::size_t lineNumber = 0;
    int active = -1;
    std::size_t parameterCount = 0;
    std::size_t analyzedTextBytes = 0;
    std::unordered_set<std::string> assignedNames;
    while (start < source_.size() && analysisComplete_) {
        ++lineNumber;
        const auto newline = source_.find('\n', start);
        const auto end = newline == std::string::npos ? source_.size() : newline + 1;
        auto contentEnd = newline == std::string::npos ? source_.size() : newline;
        if (contentEnd > start && source_[contentEnd - 1] == '\r') --contentEnd;
        std::string_view line(source_.data() + start, contentEnd - start);
        if (start == 0 && line.substr(0, 3) == "\xef\xbb\xbf") line.remove_prefix(3);
        const auto trimmed = trim(line);
        if (trimmed.empty()) { start = end; continue; }

        bool disabled = trimmed.front() == '#';
        auto body = disabled ? trim(trimmed.substr(1)) : trimmed;
        if (disabled && lowercase(body.substr(0, 7)) == "labels:") {
            auto labels = body.substr(7);
            while (!labels.empty()) {
                const auto separator = labels.find(';');
                const auto label = trim(labels.substr(0, separator));
                const auto equal = label.find('=');
                const auto key = equal == std::string_view::npos ? std::string_view{} : trim(label.substr(0, equal));
                if (key.size() == 8 && lowercase(key) == "firmware") {
                    const auto declaredFirmware = trim(label.substr(equal + 1));
                    if (declaredFirmware.size() > kMaxAnalyzedFieldBytes) {
                        stopAnalysis(lineNumber, "un champ analysé dépasse 8 Kio");
                        break;
                    }
                    if (declaredFirmware.size() > kMaxAnalyzedTextBytes - analyzedTextBytes) {
                        stopAnalysis(lineNumber, "volume des champs analysés trop élevé");
                        break;
                    }
                    analyzedTextBytes += declaredFirmware.size();
                    firmware_ = std::string(declaredFirmware);
                    if (firmware_ != kFirmware) diagnostic(lineNumber, "warning", "Firmware déclaré " + firmware_ + " ; catalogue chargé " + kFirmware + ".");
                }
                if (separator == std::string_view::npos) break;
                labels.remove_prefix(separator + 1);
            }
        }
        if (!analysisComplete_) break;
        if (!body.empty() && body.front() == '[') {
            const auto close = body.find(']');
            const auto tail = close == std::string_view::npos ? std::string_view{} : trim(body.substr(close + 1));
            const auto name = close == std::string_view::npos ? std::string_view{} : body.substr(1, close - 1);
            if (close == std::string_view::npos || !identifier(name) || (!tail.empty() && tail.front() != '#')) {
                controllerReferencesComplete_ = false;
                diagnostic(lineNumber, "error", "En-tête de bloc invalide.");
                active = -1;
                start = end;
                continue;
            }
            if (blocks_.size() >= kMaxAnalyzedBlocks) {
                stopAnalysis(lineNumber, "trop de blocs pour l'éditeur");
                break;
            }
            if (name.size() > kMaxAnalyzedFieldBytes) {
                stopAnalysis(lineNumber, "un champ analysé dépasse 8 Kio");
                break;
            }
            if (name.size() > kMaxAnalyzedTextBytes - analyzedTextBytes) {
                stopAnalysis(lineNumber, "volume des champs analysés trop élevé");
                break;
            }
            auto canonicalName = lowercase(name);
            const bool controller = controllerExists(canonicalName);
            const auto* schema = circuitSchema(canonicalName);
            if (schema) canonicalName = schema->name;
            if (canonicalName.size() > kMaxAnalyzedTextBytes - analyzedTextBytes) {
                stopAnalysis(lineNumber, "volume des champs analysés trop élevé");
                break;
            }
            analyzedTextBytes += canonicalName.size();
            if (!controller && !identifier(canonicalName, true)) diagnostic(lineNumber, "error", "Nom de circuit invalide : " + canonicalName + ".");
            else if (!controller && !schema) diagnostic(lineNumber, "warning", "Circuit inconnu du catalogue : " + canonicalName + ". Source conservée.");
            if (controller && disabled) diagnostic(lineNumber, "warning", "La désactivation d'un contrôleur n'est pas prise en charge par Forge.");
            const auto headerStart = start == 0 && source_.compare(0, 3, "\xef\xbb\xbf") == 0 ? 3 : start;
            Block parsedBlock;
            parsedBlock.name = std::move(canonicalName);
            parsedBlock.line = lineNumber;
            parsedBlock.controller = controller;
            parsedBlock.disabled = disabled;
            parsedBlock.sourceStart = headerStart;
            parsedBlock.sourceEnd = end;
            blocks_.push_back(std::move(parsedBlock));
            active = static_cast<int>(blocks_.size() - 1);
            assignedNames.clear();
            start = end;
            continue;
        }

        const auto equal = body.find('=');
        if (disabled && (active < 0 || equal == std::string_view::npos || !identifier(trim(body.substr(0, equal))))) {
            start = end;
            continue; // A normal comment, including metadata/section headers.
        }
        if (equal == std::string_view::npos || !identifier(trim(body.substr(0, equal)))) {
            controllerReferencesComplete_ = false;
            diagnostic(lineNumber, "error", "Ligne invalide : attendu [circuit] ou paramètre = valeur.");
            start = end;
            continue;
        }
        if (active < 0) {
            controllerReferencesComplete_ = false;
            diagnostic(lineNumber, "error", "Affectation avant la déclaration d'un circuit.");
            start = end;
            continue;
        }
        if (parameterCount >= kMaxAnalyzedParameters) {
            stopAnalysis(lineNumber, "trop d'affectations pour l'éditeur");
            break;
        }
        auto& block = blocks_[static_cast<std::size_t>(active)];
        const auto rawName = trim(body.substr(0, equal));
        if (rawName.size() > kMaxAnalyzedFieldBytes) {
            stopAnalysis(lineNumber, "un champ analysé dépasse 8 Kio");
            break;
        }
        if (rawName.size() > kMaxAnalyzedTextBytes - analyzedTextBytes) {
            stopAnalysis(lineNumber, "volume des champs analysés trop élevé");
            break;
        }
        auto name = lowercase(rawName);
        const auto* schema = parameterSchema(circuitSchema(block.name), name);
        if (schema) name = schema->name;
        bool unterminated = false;
        const auto comment = commentMark(body, &unterminated);
        const auto noncomment = body.substr(0, comment);
        auto rawValue = noncomment.substr(equal + 1);
        const auto value = trim(rawValue);
        if (value.size() > kMaxAnalyzedFieldBytes) {
            stopAnalysis(lineNumber, "un champ analysé dépasse 8 Kio");
            break;
        }
        if (name.size() + value.size() > kMaxAnalyzedTextBytes - analyzedTextBytes) {
            stopAnalysis(lineNumber, "volume des champs analysés trop élevé");
            break;
        }
        analyzedTextBytes += name.size() + value.size();
        ++parameterCount;
        const auto bodyOffset = static_cast<std::size_t>(body.data() - source_.data());
        // For an empty value insert before retained whitespace/comment, preserving
        // the exact existing suffix, e.g. "pitch =   # explanation".
        const auto valueStart = value.empty() ? bodyOffset + equal + 1 : static_cast<std::size_t>(value.data() - source_.data());
        block.parameters.push_back({name, std::string(value), lineNumber, disabled, start, end,
                                    valueStart, valueStart + value.size()});
        const bool inactive = disabled || block.disabled;
        const auto valueSeverity = inactive ? "warning" : "error";
        const std::string valuePrefix = inactive ? "Valeur désactivée : " : "";
        if (block.controller) diagnostic(lineNumber, valueSeverity, valuePrefix + "Un contrôleur n'accepte pas d'affectations de paramètres.");
        else if (!schema) diagnostic(lineNumber, "warning", "Paramètre inconnu pour " + block.name + " : " + name + ". Source conservée.");
        else {
            const auto direction = lowercase(schema->direction ? schema->direction : "");
            const bool output = direction == "output" || direction == "outputs";
            if (value.empty()) diagnostic(lineNumber, "warning", valuePrefix + "Valeur non renseignée pour " + name + ".");
            else if (!Expression{value}.valid(output)) diagnostic(lineNumber, valueSeverity, valuePrefix + "Expression DROID invalide pour " + name + ".");
        }
        if (unterminated) diagnostic(lineNumber, valueSeverity, valuePrefix + "Chaîne entre guillemets non terminée.");
        if (!disabled && !block.disabled && noncomment.size() > 63) diagnostic(lineNumber, "warning", "Cette affectation dépasse 63 octets : vérifier la limite du firmware.");
        if (!inactive) {
            if (!assignedNames.insert(name).second) diagnostic(lineNumber, "warning", "Paramètre affecté plusieurs fois : " + name + ".");
        }
        start = end;
    }
    if (analysisComplete_) parseAnnotations();
    if (analysisComplete_) {
        const auto inventory = cableInventory(blocks_, true);
        if (!inventory.complete) {
            diagnostic(1, "warning", "Validation des câbles partielle : inventaire trop volumineux. Le texte intégral est conservé.");
        } else for (const auto& entry : inventory.cables) {
            const auto& cable = entry.second;
            const auto active = std::find_if(cable.occurrences.begin(), cable.occurrences.end(),
                [](const auto& occurrence) { return !occurrence.disabled; });
            if (active == cable.occurrences.end()) continue;
            if (cable.unknown) {
                diagnostic(active->parameter->line, "warning", "Validation partielle du câble " + entry.first +
                    " : une affectation inconnue empêche de vérifier les connexions.");
            } else if (cable.outputs == 0 && cable.inputs != 0) {
                diagnostic(active->parameter->line, "error", "Aucune sortie n'est connectée au câble " + entry.first + ".");
            } else if (cable.outputs > 1) {
                diagnostic(active->parameter->line, "error", "Plusieurs sorties sont connectées au câble " + entry.first + ".");
            } else if (cable.outputs != 0 && cable.inputs == 0) {
                diagnostic(active->parameter->line, "warning", "Aucune entrée n'est connectée au câble " + entry.first + ".");
            }
            if (!analysisComplete_) break;
        }
    }
}

void PatchEngine::parseAnnotations() {
    // Same comment states as Forge's PatchParser. Only the description/prologue
    // recognizes register labels; comments after a circuit belong to the next
    // circuit. Title, section headings and inline parameter comments are distinct.
    enum class CommentState { Title, Description, Section, Circuit };
    CommentState mode = CommentState::Title;
    std::map<std::size_t, std::size_t> headers;
    std::size_t copied = firmware_.size();
    for (std::size_t i = 0; i < blocks_.size(); ++i) {
        headers.emplace(blocks_[i].sourceStart, i);
        copied += blocks_[i].name.size();
        for (const auto& p : blocks_[i].parameters) copied += p.name.size() + p.value.size();
    }
    std::string pending;
    std::vector<SourceRange> ranges;
    bool overflow = false;
    std::size_t pendingLines = 0;
    bool haveCircuit = false;
    auto clear = [&] { pending.clear(); ranges.clear(); overflow = false; pendingLines = 0; };
    auto stop = [&](std::size_t line, const char* reason) {
        analysisComplete_ = false;
        if (diagnostics_.size() < kMaxDiagnostics)
            diagnostics_.push_back({line, "error", std::string("Analyse interrompue : ") + reason +
                ". Le texte intégral est conservé ; utiliser l'édition du texte source."});
    };
    auto append = [&](std::string_view text, SourceRange range) {
        ++pendingLines;
        if (pendingLines > kMaxCircuitCommentLines || text.size() + (pending.empty() ? 0 : 1) >
            kMaxCircuitCommentBytes - pending.size()) { overflow = true; return; }
        if (!pending.empty()) pending += '\n';
        pending += text;
        ranges.push_back(range);
    };
    std::size_t lineNumber = 0;
    for (std::size_t start = 0; start < source_.size() && analysisComplete_;) {
        ++lineNumber;
        const auto newline = source_.find('\n', start);
        const auto end = newline == std::string::npos ? source_.size() : newline + 1;
        auto contentEnd = newline == std::string::npos ? source_.size() : newline;
        if (contentEnd > start && source_[contentEnd - 1] == '\r') --contentEnd;
        const auto lineStart = start == 0 ? labelInsertionPosition_ : start;
        auto line = trim(std::string_view(source_).substr(lineStart, contentEnd - lineStart));
        const auto header = headers.find(lineStart);
        if (header != headers.end()) {
            auto& block = blocks_[header->second];
            if (block.controller) clear();
            else {
                block.commentNeedsLeadingNewline = mode == CommentState::Title;
                block.commentPrefixAllowed = mode != CommentState::Section;
                block.commentInDescription = mode == CommentState::Description || mode == CommentState::Title;
                auto body = line;
                if (!body.empty() && body.front() == '#') body = trim(body.substr(1));
                const auto close = body.find(']');
                auto tail = close == std::string_view::npos ? std::string_view{} : trim(body.substr(close + 1));
                if (!tail.empty() && tail.front() == '#') {
                    const auto mark = static_cast<std::size_t>(tail.data() - source_.data());
                    auto removeStart = mark;
                    const auto closePosition = static_cast<std::size_t>(body.data() - source_.data()) + close + 1;
                    while (removeStart > closePosition && space(source_[removeStart - 1])) --removeStart;
                    block.inlineComment = {removeStart, contentEnd};
                    append(trim(tail.substr(1)), {});
                    // The inline range is removed independently from the prefix.
                    if (!ranges.empty() && ranges.back().end == 0) ranges.pop_back();
                }
                while (!pending.empty() && pending.back() == '\n') pending.pop_back();
                if (overflow || pending.size() > kMaxAnalyzedTextBytes - std::min(copied, kMaxAnalyzedTextBytes)) {
                    stop(lineNumber, "commentaires de circuits trop volumineux"); break;
                }
                copied += pending.size();
                block.comment = std::move(pending);
                block.commentRanges = std::move(ranges);
                clear();
                mode = CommentState::Circuit;
                haveCircuit = true;
            }
        } else if (line.empty()) {
            if (mode == CommentState::Title) mode = CommentState::Description;
            else if (mode == CommentState::Description) clear();
            else if (!pending.empty()) append({}, {start, end});
        } else if (line.front() == '#') {
            const auto body = trim(line.substr(1));
            const auto equal = body.find('=');
            if (!body.empty() && (body.front() == '[' ||
                (haveCircuit && equal != std::string_view::npos && identifier(trim(body.substr(0, equal)))))) {
                // Owned disabled syntax lines, not explanatory comments.
            } else if (sectionSeparator(body)) {
                mode = mode == CommentState::Section ? CommentState::Circuit : CommentState::Section;
            } else if (mode == CommentState::Title) {
                // Preserve the first comment's PC interpretation even when it
                // resembles a port label: in this state I1: is generic metadata
                // and must not become an effective label after insertion.
                labelInsertionPosition_ = end;
                if (metaComment(body)) readMasterLabel(body, masterType_);
                mode = CommentState::Description;
            } else if (mode == CommentState::Description) {
                RegisterName reg;
                std::string_view shorthand, description;
                std::size_t valuePosition = 0;
                if (registerLabelParts(body, reg, shorthand, description, valuePosition)) {
                    if (registerLabels_.size() >= kMaxRegisterLabels || shorthand.size() > kMaxAnalyzedFieldBytes ||
                        description.size() > kMaxAnalyzedFieldBytes || reg.canonical.size() + shorthand.size() + description.size() >
                        kMaxAnalyzedTextBytes - std::min(copied, kMaxAnalyzedTextBytes)) {
                        stop(lineNumber, "labels de registres trop volumineux"); break;
                    }
                    copied += reg.canonical.size() + shorthand.size() + description.size();
                    const auto bodyPosition = static_cast<std::size_t>(body.data() - source_.data());
                    const auto value = trim(body.substr(body.find(':') + 1));
                    registerLabels_.push_back({reg.canonical, std::string(shorthand), std::string(description), lineNumber,
                        {start == 0 ? lineStart : start, end}, {bodyPosition + valuePosition, bodyPosition + valuePosition + value.size()},
                        {bodyPosition, bodyPosition + body.find(':')}});
                } else if (metaComment(body)) {
                    readMasterLabel(body, masterType_);
                } else append(body, {start, end});
            } else if (mode == CommentState::Circuit) append(body, {start, end});
        }
        start = end;
    }
}

std::string PatchEngine::state() const {
    std::string result;
    result.reserve(source_.size() / 2 + 256);
    result += "{\"firmware\":";
    quoted(result, firmware_);
    result += ",\"catalogueFirmware\":"; quoted(result, kFirmware);
    result += ",\"masterType\":" + std::to_string(masterType_);
    result += ",\"registerLabels\":[";
    std::map<std::string_view, const RegisterLabel*> effectiveLabels;
    for (const auto& label : registerLabels_) effectiveLabels[label.reg] = &label;
    bool firstLabel = true;
    for (const auto& item : effectiveLabels) {
        const auto& label = *item.second;
        if (!firstLabel) result += ',';
        firstLabel = false;
        result += "{\"register\":"; quoted(result, label.reg);
        result += ",\"short\":"; quoted(result, label.shorthand);
        result += ",\"description\":"; quoted(result, label.description);
        result += ",\"line\":" + std::to_string(label.line) + '}';
    }
    result += "],\"rackModules\":[";
    auto module = [&](std::string_view name, int block, unsigned controller, unsigned g8, unsigned rgbOffset, bool first = false) {
        if (!first) result += ',';
        result += "{\"name\":"; quoted(result, name);
        result += ",\"block\":" + std::to_string(block) + ",\"controller\":" + std::to_string(controller) +
            ",\"g8\":" + std::to_string(g8) + ",\"rgbOffset\":" + std::to_string(rgbOffset) + '}';
    };
    module(masterType_ == 18 ? "master18" : "master", -1, 0, 0, 0, true);
    unsigned highestGate = 0;
    bool x7 = false;
    for (const auto& block : blocks_) {
        if (block.disabled || block.controller) continue;
        const auto* schema = circuitSchema(block.name);
        if (masterType_ == 16 && schema && schema->needsMIDI) x7 = true;
        for (const auto& p : block.parameters) {
            if (p.disabled || !Expression{p.value}.valid(directionOf(block, p) == "output")) continue;
            bool inQuotes = false;
            for (std::size_t i = 0; i < p.value.size();) {
                if (p.value[i] == '"') { inQuotes = !inQuotes; ++i; continue; }
                if (inQuotes || !letter(p.value[i]) || (i && (letter(p.value[i - 1]) || digit(p.value[i - 1]) || p.value[i - 1] == '_'))) { ++i; continue; }
                const auto start = i++;
                while (i < p.value.size() && (letter(p.value[i]) || digit(p.value[i]) || p.value[i] == '.')) ++i;
                RegisterName reg;
                if (!registerName(std::string_view(p.value).substr(start, i - start), reg, true)) continue;
                if (reg.type == 'G' && !reg.controller && reg.number <= 8) highestGate = std::max(highestGate, reg.g8);
                if (!reg.controller && ((reg.type == 'G' && reg.number >= 9 && reg.number <= 12) ||
                    (reg.type == 'R' && reg.number >= 49 && reg.number <= 56))) x7 = true;
            }
        }
    }
    const unsigned gateOffset = masterType_ == 18 ? 1 : 0;
    const auto expanders = highestGate > gateOffset ? highestGate - gateOffset : 0;
    for (unsigned i = 1; i <= std::min(expanders, 4u); ++i) module("g8", -1, 0, i + gateOffset, 8 + i * 8);
    if (x7) module("x7", -1, 0, 0, 48);
    unsigned controllerNumber = 0;
    for (std::size_t i = 0; i < blocks_.size(); ++i) {
        const auto& block = blocks_[i];
        if (block.controller && !block.disabled) module(block.name, static_cast<int>(i), ++controllerNumber, 0, 0);
    }
    result += "],\"rackComplete\":";
    result += analysisComplete_ && expanders <= 4 ? "true" : "false";
    result += ",\"blocks\":[";
    for (std::size_t i = 0; i < blocks_.size(); ++i) {
        const auto& block = blocks_[i];
        if (i) result += ',';
        result += "{\"index\":" + std::to_string(i) + ",\"name\":";
        quoted(result, block.name);
        result += ",\"line\":" + std::to_string(block.line) + ",\"controller\":" + (block.controller ? "true" : "false") +
                  ",\"disabled\":" + (block.disabled ? "true" : "false") + ",\"comment\":";
        quoted(result, block.comment);
        result += ",\"commentEditable\":";
        result += !block.controller && block.commentPrefixAllowed && analysisComplete_ ? "true" : "false";
        result += ",\"parameters\":[";
        for (std::size_t j = 0; j < block.parameters.size(); ++j) {
            const auto& param = block.parameters[j];
            if (j) result += ',';
            result += "{\"name\":";
            quoted(result, param.name);
            result += ",\"value\":";
            quoted(result, param.value);
            result += ",\"line\":" + std::to_string(param.line) + ",\"disabled\":" + (param.disabled ? "true" : "false");
            const auto direction = directionOf(block, param);
            result += ",\"direction\":"; quoted(result, direction);
            result += ",\"expression\":";
            expressionJson(result, direction == "unknown" ? ExpressionParts{} :
                           decodeExpression(param.value, direction == "output").parts);
            result += '}';
        }
        result += "]}";
    }
    result += "],\"diagnostics\":[";
    for (std::size_t i = 0; i < diagnostics_.size(); ++i) {
        const auto& d = diagnostics_[i];
        if (i) result += ',';
        result += "{\"line\":" + std::to_string(d.line) + ",\"severity\":";
        quoted(result, d.severity);
        result += ",\"message\":";
        quoted(result, d.message);
        result += '}';
    }
    result += "],\"canUndo\":";
    result += canUndo() ? "true" : "false";
    result += ",\"canRedo\":";
    result += canRedo() ? "true" : "false";
    result += ",\"analysisComplete\":";
    result += analysisComplete_ ? "true" : "false";
    result += '}';
    return result;
}

std::string PatchEngine::catalogue() { return kCatalogueJson; }

ExpressionParts PatchEngine::decomposeExpression(const std::string& value, bool output) {
    return decodeExpression(value, output).parts;
}

std::string PatchEngine::analyzeExpression(const std::string& value, bool output) {
    std::string result;
    expressionJson(result, decomposeExpression(value, output));
    return result;
}

std::string PatchEngine::composeExpression(const std::string& baseText, const std::string& factorText,
                                          const std::string& offsetText, bool output) {
    const auto base = trim(baseText), factor = trim(factorText), offset = trim(offsetText);
    for (const auto value : {base, factor, offset})
        if (value.size() > kMaxAnalyzedFieldBytes || !inlineSafe(value))
            throw std::invalid_argument("Une cellule doit tenir sur une ligne et au plus 8 Kio.");
    if (output) {
        if (!factor.empty() || !offset.empty() || (!base.empty() && !Expression{base}.valid(true)))
            throw std::invalid_argument("Une sortie accepte seulement un câble ou un registre dans la cellule valeur.");
        return std::string(base);
    }
    if ((!base.empty() && !singleAtom(base)) || (!offset.empty() && !singleAtom(offset)))
        throw std::invalid_argument("Valeur ou ajout invalide : saisir un seul nombre, câble, registre ou texte.");
    std::string_view denominator;
    if (!factor.empty() && !singleAtom(factor)) {
        const auto slash = factor.find('/');
        if (slash == std::string_view::npos || trim(factor.substr(0, slash)) != "1")
            throw std::invalid_argument("Facteur invalide : saisir un atome ou une fraction 1 / nombre.");
        denominator = trim(factor.substr(slash + 1));
        if (!Expression{denominator}.number(denominator) || denominator.find_first_of("V%") != std::string_view::npos ||
            std::none_of(denominator.begin(), denominator.end(), [](char c) { return c >= '1' && c <= '9'; }))
            throw std::invalid_argument("Le diviseur de la fraction doit être un nombre non nul sans unité.");
    }
    std::string result;
    if (!base.empty()) result = base;
    if (!factor.empty()) {
        if (!denominator.empty()) {
            if (result.empty()) result = "1";
            result += " / "; result += denominator;
        } else {
            if (!result.empty()) result += " * ";
            result += factor;
        }
    }
    if (!offset.empty()) {
        if (result.empty()) result = offset;
        else if (offset.front() == '-' && Expression{offset}.number(offset)) {
            result += " - "; result += offset.substr(1);
        } else { result += " + "; result += offset; }
    }
    if (result.size() > kMaxAnalyzedFieldBytes) throw std::invalid_argument("L'expression composée dépasse 8 Kio.");
    if (!result.empty() && !Expression{result}.valid(false)) throw std::invalid_argument("Expression composée invalide.");
    return result;
}

std::string PatchEngine::replaceSource(std::string source) {
    commit(std::move(source));
    return state();
}

void PatchEngine::trimHistory() noexcept {
    while (undo_.size() > historyLimit_) undo_.pop_front();
    while (redo_.size() > historyLimit_) redo_.pop_front();
    auto bytes = [](const std::deque<std::string>& list) {
        std::size_t sum = 0;
        for (const auto& item : list) sum += item.size();
        return sum;
    };
    auto used = bytes(undo_) + bytes(redo_);
    while (used > historyByteLimit_ && (!undo_.empty() || !redo_.empty())) {
        auto& list = undo_.empty() ? redo_ : undo_;
        used -= list.front().size();
        list.pop_front();
    }
}

void PatchEngine::commit(std::string changed) {
    if (changed.size() > kMaxSourceBytes) throw std::invalid_argument("Le patch dépasse la limite de 4 Mio de l'éditeur.");
    if (changed == source_) return;
    PatchEngine parsed;
    parsed.source_ = std::move(changed);
    parsed.parse();
    // Reserve the history node before publishing. Moving the old source into it
    // is allocation-free and does not copy the rest of the bounded history.
    undo_.emplace_back();
    undo_.back().swap(source_);
    redo_.clear();
    publishParsed(parsed);
    trimHistory();
}

const Block& PatchEngine::checkedBlock(int index) const {
    if (!analysisComplete_) throw std::invalid_argument("Analyse incomplète : modifier le texte source avant une édition structurée.");
    if (index < 0 || static_cast<std::size_t>(index) >= blocks_.size()) throw std::invalid_argument("Bloc introuvable.");
    return blocks_[static_cast<std::size_t>(index)];
}

std::string PatchEngine::addCircuit(const std::string& name, int selection) {
    if (!analysisComplete_) throw std::invalid_argument("Analyse incomplète : modifier le texte source avant une édition structurée.");
    if (!identifier(name, true)) throw std::invalid_argument("Nom de circuit invalide.");
    if (selection < 0 || selection > 3) throw std::invalid_argument("Choix des paramètres de création invalide.");
    auto canonical = lowercase(name);
    const auto* schema = circuitSchema(canonical);
    const bool controller = controllerExists(canonical);
    if (!schema && !controller) throw std::invalid_argument("Circuit absent du catalogue du firmware.");
    if (schema) canonical = schema->name;
    auto changed = source_;
    std::size_t position = changed.size();
    if (controller) {
        const auto firstCircuit = std::find_if(blocks_.begin(), blocks_.end(), [](const Block& block) { return !block.controller; });
        if (firstCircuit != blocks_.end()) position = firstCircuit->sourceStart;
    }
    std::string insertion;
    if (position > 0 && changed[position - 1] != '\n') insertion += newline_;
    if (position == changed.size() && !changed.empty() && (changed.size() < newline_.size() * 2 ||
        changed.substr(changed.size() - newline_.size() * 2) != newline_ + newline_)) insertion += newline_;
    insertion += '[' + canonical + ']' + newline_;
    if (schema && selection < 3) for (std::size_t index = 0; index < schema->parameterCount; ++index) {
        const auto& parameter = schema->parameters[index];
        if (parameter.initialSelectionMax < selection) continue;
        insertion += "    "; insertion += parameter.name; insertion += " =";
        if (parameter.initialValue && *parameter.initialValue) {
            insertion += ' '; insertion += parameter.initialValue;
        }
        insertion += newline_;
    }
    changed.insert(position, insertion);
    commit(std::move(changed));
    return state();
}

std::string PatchEngine::setParameter(int index, const std::string& name, const std::string& value) {
    commit(parameterChange(index, name, value, source_));
    return state();
}

std::string PatchEngine::parameterChange(int index, const std::string& name, const std::string& value,
                                        std::string changed) const {
    const auto& block = checkedBlock(index);
    if (block.controller) throw std::invalid_argument("Un contrôleur n'a pas de paramètres de circuit.");
    if (!identifier(name)) throw std::invalid_argument("Nom de paramètre invalide.");
    if (!inlineSafe(value) || commentMark(value) != std::string_view::npos) throw std::invalid_argument("La valeur doit tenir sur une ligne ; placer les # de texte entre guillemets.");
    auto canonical = lowercase(name);
    const auto* schema = parameterSchema(circuitSchema(block.name), canonical);
    if (schema) canonical = schema->name;
    const Parameter* existing = nullptr;
    for (const auto& param : block.parameters) {
        if (param.name == canonical && (!existing || !param.disabled || existing->disabled)) existing = &param;
    }
    if (!existing && !schema) throw std::invalid_argument("Paramètre absent du catalogue du circuit.");
    if (existing) {
        std::string replacement = value;
        if (existing->valueStart == existing->valueEnd && !value.empty()) replacement.insert(replacement.begin(), ' ');
        changed.replace(existing->valueStart, existing->valueEnd - existing->valueStart, replacement);
    }
    else {
        const auto position = block.parameters.empty() ? block.sourceEnd : block.parameters.back().sourceEnd;
        std::string insertion;
        if (position > 0 && changed[position - 1] != '\n') insertion += newline_;
        insertion += (block.disabled ? "#   " : "    ") + canonical + " = " + value + newline_;
        changed.insert(position, insertion);
    }
    return changed;
}

std::string PatchEngine::setParameterExpression(int index, const std::string& name, const std::string& base,
                                               const std::string& factor, const std::string& offset) {
    const auto& block = checkedBlock(index);
    const auto* schema = parameterSchema(circuitSchema(block.name), lowercase(name));
    if (block.controller || !schema) throw std::invalid_argument("Ce paramètre ne possède pas de cellules documentées.");
    const bool output = std::string_view(schema->direction) == "output";
    const auto composed = composeExpression(base, factor, offset, output);
    // An unchanged cell edit must not normalize the original whitespace or
    // rewrite subtraction/division syntax that already denotes these cells.
    const Parameter* existing = nullptr;
    for (const auto& parameter : block.parameters) if (parameter.name == schema->name &&
        (!existing || !parameter.disabled || existing->disabled)) existing = &parameter;
    if (existing) {
        const auto original = decomposeExpression(existing->value, output);
        if (original.editable && original.base == trim(base) && original.factor == trim(factor) && original.offset == trim(offset))
            return state();
    }
    return setParameter(index, name, composed);
}

std::string PatchEngine::connectParameters(int fromBlock, const std::string& fromName, int fromColumn,
                                          const std::string& fromDraftValue, int toBlock,
                                          const std::string& toName, int toColumn,
                                          const std::string& toDraftValue, const std::string& name) {
    const auto& first = checkedBlock(fromBlock);
    const auto& second = checkedBlock(toBlock);
    const auto* firstSchema = parameterSchema(circuitSchema(first.name), lowercase(fromName));
    const auto* secondSchema = parameterSchema(circuitSchema(second.name), lowercase(toName));
    const auto from = decomposeExpression(fromDraftValue, firstSchema && std::string_view(firstSchema->direction) == "output");
    const auto to = decomposeExpression(toDraftValue, secondSchema && std::string_view(secondSchema->direction) == "output");
    if (!from.editable || !to.editable)
        throw std::invalid_argument("Cette expression ne permet pas une connexion automatique ; utiliser le texte source.");
    return connectParameterExpressions(fromBlock, fromName, fromColumn, from.base, from.factor, from.offset,
        toBlock, toName, toColumn, to.base, to.factor, to.offset, name);
}

std::string PatchEngine::connectParameterExpressions(int fromBlock, const std::string& fromName, int fromColumn,
                                                    const std::string& fromBase, const std::string& fromFactor,
                                                    const std::string& fromOffset, int toBlock,
                                                    const std::string& toName, int toColumn,
                                                    const std::string& toBase, const std::string& toFactor,
                                                    const std::string& toOffset, const std::string& name) {
    const auto& first = checkedBlock(fromBlock);
    const auto& second = checkedBlock(toBlock);
    const auto* firstSchema = parameterSchema(circuitSchema(first.name), lowercase(fromName));
    const auto* secondSchema = parameterSchema(circuitSchema(second.name), lowercase(toName));
    if (first.controller || second.controller || !firstSchema || !secondSchema ||
        std::string_view(firstSchema->direction) == secondSchema->direction)
        throw std::invalid_argument("Une connexion doit relier une entrée et une sortie documentées.");
    const auto cable = cableName(name);
    auto connectionValue = [&](const ParameterSchema* schema, int column, const std::string& base,
                               const std::string& factor, const std::string& offset) {
        const bool output = std::string_view(schema->direction) == "output";
        if (column < 0 || column > 2 || (output && column != 0))
            throw std::invalid_argument("Cette cellule ne permet pas une connexion automatique ; utiliser le texte source.");
        // The explicit triplet preserves empty-cell roles, but never silently
        // discards an invalid/complex imported value in the replaced cell.
        (void)composeExpression(base, factor, offset, output);
        ExpressionParts parts{true, base, factor, offset};
        auto* cell = column == 0 ? &parts.base : column == 1 ? &parts.factor : &parts.offset;
        *cell = cable;
        return composeExpression(parts.base, parts.factor, parts.offset, output);
    };
    const auto firstValue = connectionValue(firstSchema, fromColumn, fromBase, fromFactor, fromOffset);
    const auto secondValue = connectionValue(secondSchema, toColumn, toBase, toFactor, toOffset);
    auto effective = [](const Block& block, const char* parameterName) {
        const Parameter* result = nullptr;
        for (const auto& parameter : block.parameters) if (parameter.name == parameterName &&
            (!result || !parameter.disabled || result->disabled)) result = &parameter;
        return result;
    };
    const auto* firstParameter = effective(first, firstSchema->name);
    const auto* secondParameter = effective(second, secondSchema->name);
    if (!firstParameter || !secondParameter) throw std::invalid_argument("Appliquer le paramètre avant de le connecter.");
    auto changed = source_;
    if (firstParameter->valueStart > secondParameter->valueStart) {
        changed = parameterChange(fromBlock, fromName, firstValue, std::move(changed));
        changed = parameterChange(toBlock, toName, secondValue, std::move(changed));
    } else {
        changed = parameterChange(toBlock, toName, secondValue, std::move(changed));
        changed = parameterChange(fromBlock, fromName, firstValue, std::move(changed));
    }
    commit(std::move(changed));
    return state();
}

std::string PatchEngine::cables() const {
    const auto inventory = cableInventory(blocks_, analysisComplete_);
    std::string result = inventory.complete ? "{\"analysisComplete\":true,\"cables\":[" : "{\"analysisComplete\":false,\"cables\":[";
    bool first = true;
    for (const auto& entry : inventory.cables) {
        if (!first) result += ',';
        first = false;
        result += "{\"name\":"; quoted(result, entry.first);
        const auto& cable = entry.second;
        result += ",\"inputs\":" + std::to_string(cable.inputs) + ",\"outputs\":" + std::to_string(cable.outputs) +
                  ",\"disabled\":" + std::to_string(cable.disabled) + ",\"unknown\":" + std::to_string(cable.unknown) + ",\"occurrences\":[";
        bool firstOccurrence = true;
        for (const auto& occurrence : cable.occurrences) {
            if (!firstOccurrence) result += ',';
            firstOccurrence = false;
            result += "{\"block\":" + std::to_string(occurrence.block) + ",\"name\":";
            quoted(result, occurrence.parameter->name);
            result += ",\"line\":" + std::to_string(occurrence.parameter->line) + ",\"direction\":";
            quoted(result, occurrence.direction);
            result += ",\"column\":" + std::to_string(occurrence.column) + ",\"disabled\":" + (occurrence.disabled ? "true" : "false") + '}';
        }
        result += "]}";
    }
    result += "]}";
    return result;
}

std::string PatchEngine::renameCable(const std::string& oldName, const std::string& newName, bool allowMerge) {
    if (!analysisComplete_) throw std::invalid_argument("Analyse incomplète : utiliser le texte source pour les câbles.");
    const auto oldCable = cableName(oldName), newCable = cableName(newName);
    if (oldCable == newCable) return state();
    struct Change { std::size_t start, length; };
    std::vector<Change> changes;
    // Identify complete lexical tokens, never prefixes, comments or text inside
    // quotes. Unknown expressions and disabled assignments keep every other byte.
    for (const auto& block : blocks_) for (const auto& parameter : block.parameters)
        for (const auto token : cableTokens(parameter.value)) {
            const auto value = parameter.value.substr(token.start, token.length);
            if (!allowMerge && lowercase(value) == lowercase(newCable))
                throw std::invalid_argument("Le nom du câble existe déjà ; confirmer explicitement la fusion.");
            if (lowercase(value) == lowercase(oldCable))
                changes.push_back({parameter.valueStart + token.start, token.length});
        }
    if (changes.empty()) throw std::invalid_argument("Câble introuvable.");
    if (newCable.size() > oldCable.size() && changes.size() >
        (kMaxSourceBytes - source_.size()) / (newCable.size() - oldCable.size()))
        throw std::invalid_argument("Le renommage dépasserait la limite de 4 Mio.");
    auto changed = source_;
    for (auto change = changes.rbegin(); change != changes.rend(); ++change)
        changed.replace(change->start, change->length, newCable);
    commit(std::move(changed));
    return state();
}

std::string PatchEngine::deleteCable(const std::string& name) {
    if (!analysisComplete_) throw std::invalid_argument("Analyse incomplète : utiliser le texte source pour les câbles.");
    const auto wanted = cableName(name);
    struct Change { std::size_t start, length; std::string replacement; };
    std::vector<Change> changes;
    for (const auto& block : blocks_) for (const auto& parameter : block.parameters) {
        bool present = false;
        for (const auto token : cableTokens(parameter.value))
            if (lowercase(parameter.value.substr(token.start, token.length)) == lowercase(wanted)) present = true;
        if (!present) continue;
        const auto direction = directionOf(block, parameter);
        auto parts = decomposeExpression(parameter.value, direction == "output");
        if (direction == "unknown" || !parts.editable)
            throw std::invalid_argument("Ce câble intervient dans une expression complexe ou inconnue ; utiliser le texte source.");
        for (auto* cell : {&parts.base, &parts.factor, &parts.offset})
            if (lowercase(*cell) == lowercase(wanted)) cell->clear();
        changes.push_back({parameter.valueStart, parameter.valueEnd - parameter.valueStart,
                           composeExpression(parts.base, parts.factor, parts.offset, direction == "output")});
    }
    if (changes.empty()) throw std::invalid_argument("Câble introuvable.");
    auto changed = source_;
    for (auto change = changes.rbegin(); change != changes.rend(); ++change)
        changed.replace(change->start, change->length, change->replacement);
    commit(std::move(changed));
    return state();
}

std::string PatchEngine::setRegisterLabel(const std::string& registerText, const std::string& shortText,
                                           const std::string& descriptionText) {
    if (!analysisComplete_) throw std::invalid_argument("Analyse incomplète : modifier le texte source avant une édition structurée.");
    RegisterName reg;
    if (registerText.size() > 64 || !registerName(registerText, reg, true)) throw std::invalid_argument("Registre de label invalide.");
    if (shortText.size() > kMaxAnalyzedFieldBytes || descriptionText.size() > 4096)
        throw std::invalid_argument("Label trop volumineux : description limitée à 4 Kio.");
    if (annotationUnits(shortText, false) > 18 || shortText.find(']') != std::string::npos)
        throw std::invalid_argument("Le raccourci est limité à 18 caractères UTF-16 et ne peut pas contenir ].");
    annotationUnits(descriptionText, false);
    const auto shorthand = trim(shortText), description = trim(descriptionText);
    if (shorthand.empty() && !description.empty() && description.front() == '[') {
        const auto close = description.find(']');
        if (close != std::string_view::npos && close > 1)
            throw std::invalid_argument("Une description commençant par [texte] serait relue comme raccourci ; renseigner le raccourci explicitement.");
    }
    const RegisterLabel* effective = nullptr;
    for (const auto& label : registerLabels_) if (label.reg == reg.canonical) effective = &label;
    if (effective && (!shorthand.empty() || !description.empty()) &&
        effective->shorthand == shorthand && effective->description == description) return state();
    if (!effective && (!shorthand.empty() || !description.empty()) && registerLabels_.size() >= kMaxRegisterLabels)
        throw std::invalid_argument("Nombre maximal de labels atteint : retirer un label avant d'en ajouter un.");
    auto changed = source_;
    if (shorthand.empty() && description.empty()) {
        for (auto label = registerLabels_.rbegin(); label != registerLabels_.rend(); ++label)
            if (label->reg == reg.canonical) changed.erase(label->source.start, label->source.end - label->source.start);
    } else {
        std::string value;
        if (!shorthand.empty()) value = "[" + std::string(shorthand) + "]";
        if (!description.empty()) { if (!value.empty()) value += ' '; value += description; }
        if (effective) changed.replace(effective->value.start, effective->value.end - effective->value.start, value);
        else {
            std::string added;
            if (labelInsertionPosition_ > 0 && changed[labelInsertionPosition_ - 1] != '\n' &&
                labelInsertionPosition_ != (changed.compare(0, 3, "\xef\xbb\xbf") == 0 ? 3u : 0u)) added += newline_;
            // A meta header avoids interpreting the first port label as the
            // patch title. Existing title/description and all unknown metadata
            // remain in their original positions and bytes.
            added += "# REGISTER LABELS:" + newline_ + "#  " + reg.canonical + ": " + value + newline_ + newline_;
            changed.insert(labelInsertionPosition_, added);
        }
    }
    commit(std::move(changed));
    return state();
}

std::string PatchEngine::setBlockComment(int index, const std::string& text) {
    const auto& block = checkedBlock(index);
    if (block.controller) throw std::invalid_argument("Les commentaires structurés appartiennent aux circuits ; utiliser les labels pour les contrôleurs.");
    if (!block.commentPrefixAllowed) throw std::invalid_argument("En-tête de section incomplet : corriger le texte source avant d'éditer ce commentaire.");
    if (text.size() > kMaxCircuitCommentBytes) throw std::invalid_argument("Le commentaire dépasse 16 Kio.");
    annotationUnits(text, true);
    std::string normalized;
    normalized.reserve(text.size());
    for (std::size_t i = 0; i < text.size(); ++i) {
        if (text[i] == '\r') { normalized += '\n'; if (i + 1 < text.size() && text[i + 1] == '\n') ++i; }
        else normalized += text[i];
    }
    const auto first = normalized.find_first_not_of(" \t\r\n"), last = normalized.find_last_not_of(" \t\r\n");
    normalized = first == std::string::npos ? "" : normalized.substr(first, last - first + 1);
    if (normalized == block.comment) return state();
    std::string added;
    std::size_t lines = 0;
    for (std::size_t start = 0; start < normalized.size();) {
        const auto newline = normalized.find('\n', start);
        const auto line = std::string_view(normalized).substr(start, newline == std::string::npos ? normalized.size() - start : newline - start);
        const auto body = trim(line);
        const auto equal = body.find('=');
        RegisterName reg;
        std::string_view shorthand, description;
        std::size_t valuePosition = 0;
        if ((!body.empty() && body.front() == '[') || sectionSeparator(body) ||
            (equal != std::string_view::npos && identifier(trim(body.substr(0, equal)))) ||
            (block.commentInDescription && (metaComment(body) || registerLabelParts(body, reg, shorthand, description, valuePosition))))
            throw std::invalid_argument("Une ligne de commentaire ressemble à un bloc, une affectation, une section ou un label ; utiliser le texte source pour cette syntaxe.");
        if (++lines > kMaxCircuitCommentLines) throw std::invalid_argument("Le commentaire dépasse 256 lignes.");
        added += "# "; added += line; added += newline_;
        if (newline == std::string::npos) break;
        start = newline + 1;
    }
    if (!added.empty() && block.commentNeedsLeadingNewline) added.insert(0, newline_);
    auto changed = source_;
    if (block.inlineComment.end > block.inlineComment.start)
        changed.erase(block.inlineComment.start, block.inlineComment.end - block.inlineComment.start);
    std::size_t insertion = block.sourceStart;
    for (auto range = block.commentRanges.rbegin(); range != block.commentRanges.rend(); ++range) {
        changed.erase(range->start, range->end - range->start);
        insertion -= range->end - range->start;
    }
    changed.insert(insertion, added);
    commit(std::move(changed));
    return state();
}

std::string PatchEngine::removeCircuit(int index) {
    const auto& block = checkedBlock(index);
    struct Change { std::size_t start, end; std::string replacement; };
    std::vector<Change> changes;
    if (block.controller && !block.disabled) {
        if (!controllerReferencesComplete_)
            throw std::invalid_argument("Références de contrôleur ambiguës : corriger les lignes non analysées dans le texte source.");
        unsigned number = 0;
        for (int i = 0; i <= index; ++i) if (blocks_[i].controller && !blocks_[i].disabled) ++number;
        std::size_t occurrences = 0;
        for (std::size_t i = 0; i < blocks_.size(); ++i) for (const auto& parameter : blocks_[i].parameters) {
            for (const auto& token : registerTokens(parameter.value)) {
                if (++occurrences > 8192) throw std::invalid_argument("Inventaire des registres trop volumineux : utiliser le texte source pour supprimer ce contrôleur.");
                if (token.reg.controller == number)
                    throw std::invalid_argument("Ce contrôleur est référencé par un paramètre actif ou désactivé. Retirer ses références avant de le supprimer.");
                if (token.reg.controller > number && i != static_cast<std::size_t>(index)) {
                    const auto raw = std::string_view(parameter.value).substr(token.range.start, token.range.end - token.range.start);
                    changes.push_back({parameter.valueStart + token.range.start + 1,
                        parameter.valueStart + token.range.start + raw.find('.'), shiftedControllerDigits(raw, token.reg.controller - 1)});
                }
            }
        }
        for (const auto& label : registerLabels_) {
            RegisterName reg;
            if (!registerName(label.reg, reg, true)) continue; // Unknown metadata survives unchanged.
            if (reg.controller == number) changes.push_back({label.source.start, label.source.end, {}});
            else if (reg.controller > number) {
                const auto raw = std::string_view(source_).substr(label.name.start, label.name.end - label.name.start);
                changes.push_back({label.name.start + 1, label.name.start + raw.find('.'), shiftedControllerDigits(raw, reg.controller - 1)});
            }
        }
    }
    // Remove only owned syntax lines. Section headings and neighbouring comments
    // stay intact; explicit PC circuit-comment ranges have a known owner.
    for (const auto& parameter : block.parameters) changes.push_back({parameter.sourceStart, parameter.sourceEnd, {}});
    changes.push_back({block.sourceStart, block.sourceEnd, {}});
    for (const auto& range : block.commentRanges) changes.push_back({range.start, range.end, {}});
    std::sort(changes.begin(), changes.end(), [](const auto& a, const auto& b) { return a.start > b.start; });
    auto previous = source_.size();
    for (const auto& change : changes) {
        if (change.end > previous || change.start > change.end)
            throw std::invalid_argument("Lignes ambiguës : utiliser le texte source pour supprimer ce bloc.");
        previous = change.start;
    }
    auto changed = source_;
    for (const auto& change : changes) changed.replace(change.start, change.end - change.start, change.replacement);
    commit(std::move(changed));
    return state();
}

std::string PatchEngine::undo() {
    if (!undo_.empty()) {
        PatchEngine parsed;
        parsed.source_ = undo_.back();
        parsed.parse();
        redo_.emplace_back();
        redo_.back().swap(source_);
        undo_.pop_back();
        publishParsed(parsed);
        trimHistory();
    }
    return state();
}

std::string PatchEngine::redo() {
    if (!redo_.empty()) {
        PatchEngine parsed;
        parsed.source_ = redo_.back();
        parsed.parse();
        undo_.emplace_back();
        undo_.back().swap(source_);
        redo_.pop_back();
        publishParsed(parsed);
        trimHistory();
    }
    return state();
}

} // namespace androforge
