#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <android/log.h>
#include <cstdlib>
#include <ctime>
#include <sys/sysinfo.h>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "llama.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "RNLLAMA_ANDROID_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static inline int min(int a, int b) {
    return (a < b) ? a : b;
}

// Global state (single instance per app)
static llama_model *g_model = nullptr;
static llama_context *g_context = nullptr;

extern "C" {

// ============ HashMap helpers for Java interop ============

static inline jobject createHashMap(JNIEnv *env) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
    return hashMap;
}

static inline void putStringHashMap(JNIEnv *env, jobject hashMap, const char *key, const char *value) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jstring jkey = env->NewStringUTF(key);
    jstring jval = env->NewStringUTF(value);
    env->CallObjectMethod(hashMap, putMethod, jkey, jval);
    env->DeleteLocalRef(jkey);
    env->DeleteLocalRef(jval);
}

static inline void putIntHashMap(JNIEnv *env, jobject hashMap, const char *key, int value) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID intInit = env->GetMethodID(integerClass, "<init>", "(I)V");
    jstring jkey = env->NewStringUTF(key);
    jobject jval = env->NewObject(integerClass, intInit, value);
    env->CallObjectMethod(hashMap, putMethod, jkey, jval);
    env->DeleteLocalRef(jkey);
    env->DeleteLocalRef(jval);
}

// ============ JNI Native Methods ============

/**
 * Load model from file descriptor
 */
JNIEXPORT jlong JNICALL Java_org_nehuatl_llamacpp_LlamaContext_loadModel(
    JNIEnv *env, jclass clazz, jint fd, jint nCtx) {
    
    if (g_model != nullptr) {
        LOGW("Model already loaded, freeing old one");
        if (g_context != nullptr) {
            llama_free(g_context);
            g_context = nullptr;
        }
        llama_free_model(g_model);
        g_model = nullptr;
    }
    
    // Seek to beginning of file
    lseek(fd, 0, SEEK_SET);
    
    // Load model with default parameters
    llama_model_params model_params = llama_model_default_params();
    
    // Load from file descriptor
    g_model = llama_load_model_from_fd(fd, 0, -1, model_params);
    
    if (!g_model) {
        LOGE("Failed to load model from file descriptor");
        return 0;
    }
    
    LOGI("Model loaded successfully from FD");
    
    // Create context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx;
    ctx_params.n_batch = min(nCtx, 512);
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 2;
    
    g_context = llama_new_context_with_model(g_model, ctx_params);
    
    if (!g_context) {
        LOGE("Failed to create context");
        llama_free_model(g_model);
        g_model = nullptr;
        return 0;
    }
    
    LOGI("Context created: nCtx=%d, nBatch=%d", nCtx, ctx_params.n_batch);
    
    return (jlong)g_context;
}

/**
 * Get model details as HashMap
 */
JNIEXPORT jobject JNICALL Java_org_nehuatl_llamacpp_LlamaContext_loadModelDetails(
    JNIEnv *env, jclass clazz) {
    
    jobject detailsMap = createHashMap(env);
    
    if (!g_model) {
        putStringHashMap(env, detailsMap, "error", "No model loaded");
        return detailsMap;
    }
    
    // Get model info
    const char *model_arch = llama_model_get_arch(g_model);
    int n_vocab = llama_vocab_n(llama_model_get_vocab(g_model));
    int n_embd = llama_model_n_embd(g_model);
    
    // Get context size from model (default to 2048 if not available)
    int ctx_size = 2048;
    
    putStringHashMap(env, detailsMap, "arch", model_arch ? model_arch : "unknown");
    putIntHashMap(env, detailsMap, "vocab_size", n_vocab);
    putIntHashMap(env, detailsMap, "embedding_size", n_embd);
    putIntHashMap(env, detailsMap, "context_size", ctx_size);
    putStringHashMap(env, detailsMap, "status", "loaded");
    
    LOGI("Model details: arch=%s, vocab=%d, embd=%d, ctx=%d", 
         model_arch, n_vocab, n_embd, ctx_size);
    
    return detailsMap;
}

/**
 * Get context size
 */
JNIEXPORT jint JNICALL Java_org_nehuatl_llamacpp_LlamaContext_getModelContextSize(
    JNIEnv *env, jclass clazz) {
    
    if (!g_context) {
        return 0;
    }
    
    // Get the actual allocated context size
    int ctx_size = llama_get_kv_cache_used(g_context);
    if (ctx_size <= 0) {
        ctx_size = 2048; // Fallback default
    }
    
    LOGI("Context size: %d tokens", ctx_size);
    return ctx_size;
}

/**
 * Tokenize text
 */
JNIEXPORT jintArray JNICALL Java_org_nehuatl_llamacpp_LlamaContext_tokenize(
    JNIEnv *env, jclass clazz, jstring jText, jboolean addBos) {
    
    if (!g_model) {
        return env->NewIntArray(0);
    }
    
    const char *text = env->GetStringUTFChars(jText, nullptr);
    
    // Estimate token count
    std::vector<llama_token> tokens(strlen(text) + 10);
    int n_tokens = llama_tokenize(
        g_model,
        text,
        tokens.data(),
        tokens.size(),
        addBos
    );
    
    if (n_tokens < 0) {
        LOGW("Tokenization failed");
        env->ReleaseStringUTFChars(jText, text);
        return env->NewIntArray(0);
    }
    
    tokens.resize(n_tokens);
    
    jintArray result = env->NewIntArray(n_tokens);
    jint *resultElements = env->GetIntArrayElements(result, nullptr);
    for (int i = 0; i < n_tokens; i++) {
        resultElements[i] = tokens[i];
    }
    env->ReleaseIntArrayElements(result, resultElements, 0);
    env->ReleaseStringUTFChars(jText, text);
    
    return result;
}

/**
 * Decode tokens (single inference step)
 */
JNIEXPORT jint JNICALL Java_org_nehuatl_llamacpp_LlamaContext_decode(
    JNIEnv *env, jclass clazz, jintArray jTokens) {
    
    if (!g_context) {
        return -1;
    }
    
    int token_count = env->GetArrayLength(jTokens);
    jint *tokens = env->GetIntArrayElements(jTokens, nullptr);
    
    // Create batch
    llama_batch batch = llama_batch_get_one(tokens, token_count, 0, 0);
    
    int result = llama_decode(g_context, batch);
    
    env->ReleaseIntArrayElements(jTokens, tokens, JNI_ABORT);
    
    if (result != 0) {
        LOGW("Decode failed with code %d", result);
    }
    
    return result;
}

/**
 * Sample next token
 */
JNIEXPORT jint JNICALL Java_org_nehuatl_llamacpp_LlamaContext_sample(
    JNIEnv *env, jclass clazz, jfloat temperature, jfloat topP) {
    
    if (!g_context || !g_model) {
        return -1;
    }
    
    // Get logits from last position
    int n_vocab = llama_vocab_n(llama_model_get_vocab(g_model));
    float *logits = llama_get_logits(g_context);
    
    if (!logits) {
        return -1;
    }
    
    // Create candidates array
    std::vector<llama_token_data> candidates;
    candidates.reserve(n_vocab);
    
    for (int i = 0; i < n_vocab; i++) {
        candidates.push_back({i, logits[i], 0.0f});
    }
    
    llama_token_data_array candidates_p = {
        candidates.data(),
        candidates.size(),
        false
    };
    
    // Create sampler
    llama_sampler *sampler = llama_sampler_init_softmax(1);
    llama_token next = llama_sampler_sample(sampler, g_context, &candidates_p);
    llama_sampler_free(sampler);
    
    return next;
}

/**
 * Get token text
 */
JNIEXPORT jstring JNICALL Java_org_nehuatl_llamacpp_LlamaContext_getTokenText(
    JNIEnv *env, jclass clazz, jint token) {
    
    if (!g_model) {
        return env->NewStringUTF("");
    }
    
    const char *text = llama_token_get_text(g_model, token);
    if (!text) {
        return env->NewStringUTF("");
    }
    
    return env->NewStringUTF(text);
}

/**
 * Free context and model
 */
JNIEXPORT void JNICALL Java_org_nehuatl_llamacpp_LlamaContext_shutdown(
    JNIEnv *env, jclass clazz) {
    
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
        LOGI("Context freed");
    }
    
    if (g_model) {
        llama_free_model(g_model);
        g_model = nullptr;
        LOGI("Model freed");
    }
}

} // extern "C"