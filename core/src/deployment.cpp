#include "androforge/deployment.h"
#include "androforge/patch_engine.h"
#include "catalogue_data.h"

#include <algorithm>
#include <map>
#include <stdexcept>
#include <string_view>
#include <unordered_set>

namespace androforge {
namespace {

char upper(char c) { return c >= 'a' && c <= 'z' ? char(c - 'a' + 'A') : c; }

const CircuitSchema* schemaFor(const std::string& name) {
    for (const auto& schema : kCircuits) if (name == schema.name) return &schema;
    return nullptr;
}

const ParameterSchema* parameterFor(const CircuitSchema& circuit, const std::string& name) {
    for (std::size_t i = 0; i < circuit.parameterCount; ++i)
        if (name == circuit.parameters[i].name) return &circuit.parameters[i];
    return nullptr;
}

void validateBytes(std::string_view text) {
    if (text.size() > kMaxDeploymentBytes)
        throw std::invalid_argument("Le patch de déploiement dépasse 64 000 octets.");
    std::size_t lineBytes = 0;
    for (unsigned char c : text) {
        if (c == '\n') { lineBytes = 0; continue; }
        if (c < 32 || c > 126)
            throw std::invalid_argument("Le déploiement doit contenir uniquement des caractères ASCII imprimables et LF.");
        if (++lineBytes > kMaxDeploymentLineBytes)
            throw std::invalid_argument("Une ligne du patch de déploiement dépasse 63 octets.");
    }
}

std::string withoutSpaces(std::string_view text) {
    std::string result;
    result.reserve(text.size());
    bool quoted = false;
    for (char c : text) {
        if (c == '"') quoted = !quoted;
        if (quoted || (c != ' ' && c != '\t' && c != '\r')) result += c;
    }
    if (quoted) throw std::invalid_argument("Chaîne non terminée dans le patch de déploiement.");
    return result;
}

std::string trimLeadingZeroes(std::string_view number) {
    while (number.size() > 1 && number.front() == '0') number.remove_prefix(1);
    return std::string(number);
}

std::string canonicalAtom(std::string atom) {
    if (atom.empty() || atom.front() == '"') return atom;
    if (atom.front() == '_') {
        std::transform(atom.begin(), atom.end(), atom.begin(), upper);
        return atom;
    }
    // A fraction factor is already represented as "1 / denominator" by the
    // shared expression codec; preserve its numeric spelling and units.
    if (atom.front() >= '0' && atom.front() <= '9') return atom;
    if (atom.front() == '-') return atom;
    std::string uppercase = atom;
    std::transform(uppercase.begin(), uppercase.end(), uppercase.begin(), upper);
    if (uppercase == "ON") return "on";
    if (uppercase == "OFF") return "off";
    const auto dot = uppercase.find('.');
    if (dot != std::string::npos) {
        return uppercase.substr(0, 1) + trimLeadingZeroes(std::string_view(uppercase).substr(1, dot - 1)) +
               "." + trimLeadingZeroes(std::string_view(uppercase).substr(dot + 1));
    }
    const std::string number = trimLeadingZeroes(std::string_view(uppercase).substr(1));
    // PC AtomRegister canonicalizes G1..G8 to the first G8's dotted bank.
    if (uppercase.front() == 'G' && number.size() == 1 && number[0] >= '1' && number[0] <= '8')
        return "G1." + number;
    return uppercase.substr(0, 1) + number;
}

void appendLine(std::string& result, const std::string& line) {
    validateBytes(line);
    if (line.size() + 1 > kMaxDeploymentBytes - result.size())
        throw std::invalid_argument("Le patch de déploiement dépasse 64 000 octets.");
    result += line;
    result += '\n';
}

struct CableConnections { std::size_t inputs = 0, outputs = 0; };
void countCable(const std::string& atom, bool output, std::map<std::string, CableConnections>& cables) {
    if (atom.empty() || atom.front() != '_') return;
    auto& connections = cables[atom];
    if (output) ++connections.outputs;
    else ++connections.inputs;
}

} // namespace

std::string prepareDeployment(const PatchEngine& engine) {
    if (!engine.analysisComplete())
        throw std::invalid_argument("Analyse incomplète : le déploiement est impossible.");
    for (const auto& diagnostic : engine.diagnostics())
        if (diagnostic.severity == "error")
            throw std::invalid_argument("Corriger les erreurs du patch avant le déploiement (ligne " +
                                        std::to_string(diagnostic.line) + ").");

    std::string result;
    result.reserve(std::min(engine.source().size(), kMaxDeploymentBytes));
    for (const auto& block : engine.blocks()) {
        if (!block.controller || block.disabled) continue;
        appendLine(result, "[" + block.name + "]");
    }
    std::map<std::string, CableConnections> cables;
    for (const auto& block : engine.blocks()) {
        if (block.controller || block.disabled) continue;
        const auto* circuit = schemaFor(block.name);
        if (!circuit) throw std::invalid_argument("Circuit actif inconnu : " + block.name + ".");
        appendLine(result, "[" + block.name + "]");
        std::unordered_set<std::string_view> assigned;
        for (const auto& parameter : block.parameters) {
            if (parameter.disabled) continue;
            const auto* schema = parameterFor(*circuit, parameter.name);
            if (!schema) throw std::invalid_argument("Paramètre actif inconnu : " + block.name + "." + parameter.name + ".");
            if (!assigned.insert(parameter.name).second)
                throw std::invalid_argument("Paramètre actif affecté plusieurs fois : " + parameter.name + ".");
            if (parameter.value.empty())
                throw std::invalid_argument("Valeur non renseignée : " + block.name + "." + parameter.name + ".");
            const bool output = std::string_view(schema->direction) == "output";
            auto expression = PatchEngine::decomposeExpression(parameter.value, output);
            if (!expression.editable)
                throw std::invalid_argument("Expression incompatible avec Forge : " + block.name + "." + parameter.name + ".");
            expression.base = canonicalAtom(std::move(expression.base));
            expression.factor = canonicalAtom(std::move(expression.factor));
            expression.offset = canonicalAtom(std::move(expression.offset));
            const auto value = withoutSpaces(PatchEngine::composeExpression(expression.base, expression.factor, expression.offset, output));
            appendLine(result, parameter.name + "=" + value);
            countCable(expression.base, output, cables);
            countCable(expression.factor, false, cables);
            countCable(expression.offset, false, cables);
        }
    }
    // Recompute from the bounded deployment instead of relying on an inventory
    // that the editor may have capped. Disabled/quoted references never count.
    for (const auto& entry : cables) {
        if (entry.second.outputs != 1 || entry.second.inputs == 0)
            throw std::invalid_argument("Connexion incomplète ou sorties multiples du câble " + entry.first + ".");
    }
    return result;
}

std::vector<std::uint8_t> encodeActivation(const std::string& deployment) {
    validateBytes(deployment);
    std::vector<std::uint8_t> result;
    result.reserve(deployment.size() + kActivationEnvelopeBytes);
    result.insert(result.end(), {0xf0, 0x00, 0x66, 0x66, 'P'});
    result.insert(result.end(), deployment.begin(), deployment.end());
    result.push_back(0xf7);
    return result;
}

} // namespace androforge
