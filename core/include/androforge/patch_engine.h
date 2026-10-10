#pragma once

#include <cstddef>
#include <deque>
#include <string>
#include <vector>

namespace androforge {

struct SourceRange {
    std::size_t start = 0;
    std::size_t end = 0;
};

struct RegisterLabel {
    std::string reg;
    std::string shorthand;
    std::string description;
    std::size_t line = 0;
    SourceRange source;
    SourceRange value;
    SourceRange name;
};

struct Parameter {
    std::string name;
    std::string value;
    std::size_t line = 0;
    bool disabled = false;
    // Byte ranges in the unmodified source; internal editing coordinates.
    std::size_t sourceStart = 0;
    std::size_t sourceEnd = 0;
    std::size_t valueStart = 0;
    std::size_t valueEnd = 0;
};

struct Block {
    std::string name;
    std::size_t line = 0;
    bool controller = false;
    bool disabled = false;
    std::vector<Parameter> parameters;
    std::size_t sourceStart = 0;
    std::size_t sourceEnd = 0;
    std::string comment;
    std::vector<SourceRange> commentRanges;
    SourceRange inlineComment;
    bool commentNeedsLeadingNewline = false;
    bool commentPrefixAllowed = true;
    bool commentInDescription = false;
};

struct Diagnostic {
    std::size_t line = 0;
    std::string severity;
    std::string message;
};

struct ExpressionParts {
    bool editable = false;
    std::string base;
    std::string factor;
    std::string offset;
};

// No Qt, Android, or JSON-library dependency. Sources and coordinates are UTF-8
// bytes. Loading never normalizes text; even malformed/unknown lines survive.
class PatchEngine {
public:
    // Shared with the Android file reader: every accepted document must remain
    // readable from its autosave, including after a structured edit.
    static constexpr std::size_t kMaxSourceBytes = 4 * 1024 * 1024;
    static constexpr std::size_t kMaxAnalyzedBlocks = 4096;
    static constexpr std::size_t kMaxAnalyzedParameters = 32768;
    static constexpr std::size_t kMaxDiagnostics = 256;
    static constexpr std::size_t kMaxDiagnosticMessageBytes = 1024;
    static constexpr std::size_t kMaxAnalyzedTextBytes = 1024 * 1024;
    static constexpr std::size_t kMaxAnalyzedFieldBytes = 8192;
    static constexpr std::size_t kMaxRegisterLabels = 4096;
    static constexpr std::size_t kMaxCircuitCommentBytes = 16 * 1024;
    static constexpr std::size_t kMaxCircuitCommentLines = 256;

    explicit PatchEngine(std::size_t historyLimit = 64,
                         std::size_t historyByteLimit = 16 * 1024 * 1024);

    std::string load(std::string source);
    const std::string& source() const noexcept { return source_; }
    std::string state() const;
    static std::string catalogue();
    static ExpressionParts decomposeExpression(const std::string& value, bool output = false);
    static std::string analyzeExpression(const std::string& value, bool output = false);
    static std::string composeExpression(const std::string& base, const std::string& factor,
                                         const std::string& offset, bool output = false);
    std::string cables() const;

    // Mutations return state JSON. Invalid indices/names or line-injection
    // attempts or documents over kMaxSourceBytes throw std::invalid_argument
    // without changing text or history. Malformed bytes within the limit remain
    // losslessly preserved; their state JSON uses replacement characters.
    // Forge's creation modes: all=0, typical=1 (default), essential=2, empty=3.
    std::string addCircuit(const std::string& name, int selection = 1);
    std::string replaceSource(std::string source);
    std::string setParameter(int index, const std::string& name,
                             const std::string& value);
    std::string setParameterExpression(int index, const std::string& name,
                                      const std::string& base, const std::string& factor,
                                      const std::string& offset);
    // Columns are 0=base, 1=factor, 2=offset. Output jacks only have column 0.
    std::string connectParameters(int fromBlock, const std::string& fromName, int fromColumn,
                                  const std::string& fromDraftValue, int toBlock,
                                  const std::string& toName, int toColumn,
                                  const std::string& toDraftValue, const std::string& cableName);
    std::string connectParameterExpressions(int fromBlock, const std::string& fromName, int fromColumn,
                                            const std::string& fromBase, const std::string& fromFactor,
                                            const std::string& fromOffset, int toBlock,
                                            const std::string& toName, int toColumn,
                                            const std::string& toBase, const std::string& toFactor,
                                            const std::string& toOffset, const std::string& cableName);
    std::string renameCable(const std::string& oldName, const std::string& newName, bool allowMerge = false);
    std::string deleteCable(const std::string& name);
    std::string setRegisterLabel(const std::string& reg, const std::string& shorthand,
                                 const std::string& description);
    std::string setBlockComment(int index, const std::string& text);
    std::string removeCircuit(int index);
    std::string undo();
    std::string redo();

    const std::vector<Block>& blocks() const noexcept { return blocks_; }
    const std::vector<Diagnostic>& diagnostics() const noexcept { return diagnostics_; }
    const std::vector<RegisterLabel>& registerLabels() const noexcept { return registerLabels_; }
    bool canUndo() const noexcept { return !undo_.empty(); }
    bool canRedo() const noexcept { return !redo_.empty(); }
    bool analysisComplete() const noexcept { return analysisComplete_; }

private:
    std::string source_;
    std::string firmware_;
    std::string newline_ = "\n";
    std::vector<Block> blocks_;
    std::vector<Diagnostic> diagnostics_;
    std::vector<RegisterLabel> registerLabels_;
    int masterType_ = 16;
    std::size_t labelInsertionPosition_ = 0;
    std::deque<std::string> undo_;
    std::deque<std::string> redo_;
    std::size_t historyLimit_;
    std::size_t historyByteLimit_;
    bool analysisComplete_ = true;
    bool controllerReferencesComplete_ = true;

    void parse();
    void parseAnnotations();
    void publishParsed(PatchEngine& parsed) noexcept;
    void commit(std::string changed);
    void trimHistory() noexcept;
    const Block& checkedBlock(int index) const;
    std::string parameterChange(int index, const std::string& name,
                                const std::string& value, std::string changed) const;
};

} // namespace androforge
