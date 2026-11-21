#include <jni.h>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include "cactus.h"

namespace {
std::mutex engine_mutex;
bool model_loaded = false;

jstring result(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yatralens_MirageNative_describeRgb(JNIEnv *env, jobject, jbyteArray model,
                                            jbyteArray rgb, jint width, jint height) {
    if (width < 1 || height < 1 || width > 1024 || height > 1024 ||
        env->GetArrayLength(rgb) != width * height * 3) {
        return result(env, "{\"status\":\"INVALID_IMAGE\"}");
    }
    std::lock_guard<std::mutex> lock(engine_mutex);
    if (!model_loaded) {
        std::vector<unsigned char> weights(env->GetArrayLength(model));
        env->GetByteArrayRegion(model, 0, weights.size(),
                                reinterpret_cast<jbyte *>(weights.data()));
        int loaded = cactus_load(weights.data(), weights.size());
        if (loaded < 0) {
            return result(env, "{\"status\":\"MODEL_LOAD_FAILED\"}");
        }
        model_loaded = true;
    }
    std::vector<unsigned char> pixels(env->GetArrayLength(rgb));
    env->GetByteArrayRegion(rgb, 0, pixels.size(), reinterpret_cast<jbyte *>(pixels.data()));
    std::vector<char> output(262144);
    const char *options = "{\"sections\":[\"scene\",\"objects\",\"text\",\"relations\"],"
                          "\"max_objects\":20,\"max_text_lines\":20,\"max_tokens\":256}";
    const int n = cactus_describe(pixels.data(), width, height, options, "en", nullptr,
                                  output.data(), output.size());
    if (n < 0) {
        return result(env, "{\"status\":\"INFERENCE_FAILED\"}");
    }
    // The runtime's positive return is not a reliable byte count for this
    // vision result. Its output buffer is NUL terminated; read that instead.
    return result(env, std::string(output.data(), strnlen(output.data(), output.size())));
}
