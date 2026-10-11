#include <jni.h>

#include "androforge/patch_engine.h"
#include "androforge/deployment.h"

#include <cstdint>
#include <exception>
#include <limits>
#include <mutex>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
androforge::PatchEngine engine;
std::mutex engineMutex;

void appendUtf8(std::string& result, std::uint32_t cp) {
    if (cp < 0x80) result.push_back(static_cast<char>(cp));
    else if (cp < 0x800) {
        result.push_back(static_cast<char>(0xc0 | (cp >> 6)));
        result.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    }
    else if (cp < 0x10000) {
        result.push_back(static_cast<char>(0xe0 | (cp >> 12)));
        result.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
        result.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    }
    else {
        result.push_back(static_cast<char>(0xf0 | (cp >> 18)));
        result.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3f)));
        result.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
        result.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    }
}

std::string fromJava(JNIEnv* env, jstring value) {
    if (!value) throw std::invalid_argument("Missing text.");
    const auto length = env->GetStringLength(value);
    if (static_cast<std::size_t>(length) > androforge::PatchEngine::kMaxSourceBytes)
        throw std::invalid_argument("Text too large: the limit is 4 MiB.");
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (!chars) throw std::bad_alloc();
    // Java's modified UTF-8 represents supplementary characters as two encoded
    // surrogates. Read UTF-16 explicitly so emoji/comments export as real UTF-8.
    struct Release {
        JNIEnv* env;
        jstring value;
        const jchar* chars;
        ~Release() { env->ReleaseStringChars(value, chars); }
    } release{env, value, chars};
    std::string result;
    result.reserve(static_cast<std::size_t>(length));
    for (jsize i = 0; i < length; ++i) {
        std::uint32_t cp = chars[i];
        if (cp >= 0xd800 && cp <= 0xdbff) {
            if (i + 1 < length && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
                cp = 0x10000 + ((cp - 0xd800) << 10) + (chars[++i] - 0xdc00);
            }
            else throw std::invalid_argument("Invalid Unicode text: incomplete UTF-16 surrogate pair.");
        }
        else if (cp >= 0xdc00 && cp <= 0xdfff)
            throw std::invalid_argument("Invalid Unicode text: incomplete UTF-16 surrogate pair.");
        const std::size_t bytes = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
        if (result.size() > androforge::PatchEngine::kMaxSourceBytes - bytes)
            throw std::invalid_argument("Text too large: the limit is 4 MiB.");
        appendUtf8(result, cp);
    }
    return result;
}

jstring toJava(JNIEnv* env, const std::string& value) {
    std::vector<jchar> chars;
    chars.reserve(value.size());
    for (std::size_t i = 0; i < value.size(); ++i) {
        const auto first = static_cast<unsigned char>(value[i]);
        std::uint32_t cp = first;
        if (first >= 0x80) {
            const std::size_t count = first >= 0xc2 && first <= 0xdf ? 2 :
                                      first >= 0xe0 && first <= 0xef ? 3 :
                                      first >= 0xf0 && first <= 0xf4 ? 4 : 0;
            cp = count ? first & (0x7f >> count) : 0;
            bool valid = count && i + count <= value.size();
            for (std::size_t j = 1; valid && j < count; ++j) {
                const auto next = static_cast<unsigned char>(value[i + j]);
                valid = (next & 0xc0) == 0x80;
                cp = (cp << 6) | (next & 0x3f);
            }
            valid = valid && cp >= (count == 2 ? 0x80u : count == 3 ? 0x800u : 0x10000u) &&
                    cp <= 0x10ffff && !(cp >= 0xd800 && cp <= 0xdfff);
            if (valid) i += count - 1;
            else cp = 0xfffd;
        }
        if (cp < 0x10000) chars.push_back(static_cast<jchar>(cp));
        else {
            cp -= 0x10000;
            chars.push_back(static_cast<jchar>(0xd800 + (cp >> 10)));
            chars.push_back(static_cast<jchar>(0xdc00 + (cp & 0x3ff)));
        }
    }
    if (chars.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) throw std::length_error("Text too large.");
    const jchar empty = 0;
    return env->NewString(chars.empty() ? &empty : chars.data(), static_cast<jsize>(chars.size()));
}

void javaError(JNIEnv* env, const char* className, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass(className);
    if (!type) return;
    // ThrowNew expects modified UTF-8. Messages from the parser can contain
    // user text (including supplementary Unicode), so construct a real Java
    // String just as for successful JNI results.
    try {
        jmethodID constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;)V");
        if (constructor) {
            jstring text = toJava(env, message);
            if (text) {
                auto exception = static_cast<jthrowable>(env->NewObject(type, constructor, text));
                if (exception) {
                    env->Throw(exception);
                    env->DeleteLocalRef(exception);
                }
                env->DeleteLocalRef(text);
            }
        }
    }
    catch (...) {
        if (!env->ExceptionCheck()) env->ThrowNew(type, "Native operation failed: insufficient memory.");
    }
    env->DeleteLocalRef(type);
}

template <typename Work>
jstring guarded(JNIEnv* env, Work&& work) {
    try {
        const std::lock_guard<std::mutex> lock(engineMutex);
        return toJava(env, work());
    }
    catch (const std::invalid_argument& error) { javaError(env, "java/lang/IllegalArgumentException", error.what()); }
    catch (const std::bad_alloc&) { javaError(env, "java/lang/OutOfMemoryError", "Insufficient memory for the patch."); }
    catch (const std::exception& error) { javaError(env, "java/lang/IllegalStateException", error.what()); }
    catch (...) { javaError(env, "java/lang/IllegalStateException", "Unexpected patch engine error."); }
    return nullptr;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_load(JNIEnv* env, jclass, jstring source) {
    return guarded(env, [&] { return engine.load(fromJava(env, source)); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_replaceSource(JNIEnv* env, jclass, jstring source) {
    return guarded(env, [&] { return engine.replaceSource(fromJava(env, source)); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_source(JNIEnv* env, jclass) {
    return guarded(env, [&] { return engine.source(); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_state(JNIEnv* env, jclass) {
    return guarded(env, [&] { return engine.state(); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_catalogue(JNIEnv* env, jclass) {
    return guarded(env, [&] { return androforge::PatchEngine::catalogue(); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_prepareDeployment(JNIEnv* env, jclass) {
    return guarded(env, [&] { return androforge::prepareDeployment(engine); });
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_fr_androforge_NativeBridge_activationMessage(JNIEnv* env, jclass, jstring deployment) {
    try {
        const auto bytes = androforge::encodeActivation(fromJava(env, deployment));
        if (bytes.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max()))
            throw std::length_error("MIDI message too large.");
        auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (result && !bytes.empty()) env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
                reinterpret_cast<const jbyte*>(bytes.data()));
        return result;
    }
    catch (const std::invalid_argument& error) { javaError(env, "java/lang/IllegalArgumentException", error.what()); }
    catch (const std::bad_alloc&) { javaError(env, "java/lang/OutOfMemoryError", "Insufficient memory for the MIDI transfer."); }
    catch (const std::exception& error) { javaError(env, "java/lang/IllegalStateException", error.what()); }
    catch (...) { javaError(env, "java/lang/IllegalStateException", "Unexpected MIDI preparation error."); }
    return nullptr;
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_addCircuit(JNIEnv* env, jclass, jstring name) {
    return guarded(env, [&] { return engine.addCircuit(fromJava(env, name)); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_addCircuitWithSelection(JNIEnv* env, jclass, jstring name, jint selection) {
    return guarded(env, [&] { return engine.addCircuit(fromJava(env, name), selection); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_setParameter(JNIEnv* env, jclass, jint index, jstring name, jstring value) {
    return guarded(env, [&] { return engine.setParameter(index, fromJava(env, name), fromJava(env, value)); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_analyzeExpression(JNIEnv* env, jclass, jstring value, jboolean output) {
    return guarded(env, [&] { return androforge::PatchEngine::analyzeExpression(fromJava(env, value), output == JNI_TRUE); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_composeExpression(JNIEnv* env, jclass, jstring base, jstring factor, jstring offset, jboolean output) {
    return guarded(env, [&] { return androforge::PatchEngine::composeExpression(fromJava(env, base), fromJava(env, factor), fromJava(env, offset), output == JNI_TRUE); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_setParameterExpression(JNIEnv* env, jclass, jint block, jstring name, jstring base, jstring factor, jstring offset) {
    return guarded(env, [&] { return engine.setParameterExpression(block, fromJava(env, name), fromJava(env, base), fromJava(env, factor), fromJava(env, offset)); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_connectParameters(JNIEnv* env, jclass, jint fromBlock, jstring fromName, jint fromColumn,
                                                jstring fromValue, jint toBlock, jstring toName, jint toColumn,
                                                jstring toValue, jstring cableName) {
    return guarded(env, [&] { return engine.connectParameters(fromBlock, fromJava(env, fromName), fromColumn,
        fromJava(env, fromValue), toBlock, fromJava(env, toName), toColumn, fromJava(env, toValue), fromJava(env, cableName)); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_connectParameterExpressions(JNIEnv* env, jclass, jint fromBlock, jstring fromName, jint fromColumn,
                                                           jstring fromBase, jstring fromFactor, jstring fromOffset,
                                                           jint toBlock, jstring toName, jint toColumn,
                                                           jstring toBase, jstring toFactor, jstring toOffset, jstring cableName) {
    return guarded(env, [&] { return engine.connectParameterExpressions(fromBlock, fromJava(env, fromName), fromColumn,
        fromJava(env, fromBase), fromJava(env, fromFactor), fromJava(env, fromOffset),
        toBlock, fromJava(env, toName), toColumn, fromJava(env, toBase), fromJava(env, toFactor), fromJava(env, toOffset), fromJava(env, cableName)); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_cables(JNIEnv* env, jclass) {
    return guarded(env, [&] { return engine.cables(); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_renameCable(JNIEnv* env, jclass, jstring oldName, jstring newName) {
    return guarded(env, [&] { return engine.renameCable(fromJava(env, oldName), fromJava(env, newName)); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_renameCableWithMerge(JNIEnv* env, jclass, jstring oldName, jstring newName, jboolean allowMerge) {
    return guarded(env, [&] { return engine.renameCable(fromJava(env, oldName), fromJava(env, newName), allowMerge == JNI_TRUE); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_deleteCable(JNIEnv* env, jclass, jstring name) {
    return guarded(env, [&] { return engine.deleteCable(fromJava(env, name)); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_setRegisterLabel(JNIEnv* env, jclass, jstring reg, jstring shorthand, jstring description) {
    return guarded(env, [&] { return engine.setRegisterLabel(fromJava(env, reg), fromJava(env, shorthand), fromJava(env, description)); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_setBlockComment(JNIEnv* env, jclass, jint block, jstring text) {
    return guarded(env, [&] { return engine.setBlockComment(block, fromJava(env, text)); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_removeCircuit(JNIEnv* env, jclass, jint index) {
    return guarded(env, [&] { return engine.removeCircuit(index); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_undo(JNIEnv* env, jclass) {
    return guarded(env, [&] { return engine.undo(); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_fr_androforge_NativeBridge_redo(JNIEnv* env, jclass) {
    return guarded(env, [&] { return engine.redo(); });
}
