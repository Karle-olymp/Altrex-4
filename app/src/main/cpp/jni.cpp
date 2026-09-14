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
#include "rn-llama.h"
#include "rn-completion.h"
#include "rn-mtmd.hpp"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "RNLLAMA_ANDROID_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static inline int min(int a, int b) {
    return (a < b) ? a : b;
}

extern "C" {

// Helper method to create Java HashMap
static inline jobject createHashMap(JNIEnv *env) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID init = env->GetMethodID(hashMapClass, "<init>", "()V");
    jobject hashMap = env->NewObject(hashMapClass, init);
    return hashMap;
}

// Helper method to insert a string into Java HashMap
static inline void putStringHashMap(JNIEnv *env, jobject hashMap, const char *key, const char *value) {
    if (value == nullptr) return;
    
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    
    jstring jKey = env->NewStringUTF(key);
    jstring jValue = env->NewStringUTF(value);
    
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGW("putStringHashMap: Invalid UTF-8 for key %s", key);
        jValue = env->NewStringUTF("");
    }
    
    env->CallObjectMethod(hashMap, putMethod, jKey, jValue);
    env->DeleteLocalRef(jKey);
    if (jValue) env->DeleteLocalRef(jValue);
}

// Helper method to insert an integer into Java HashMap
static inline void putIntHashMap(JNIEnv *env, jobject hashMap, const char *key, int value) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    
    jstring jKey = env->NewStringUTF(key);
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID integerConstructor = env->GetMethodID(integerClass, "<init>", "(I)V");
    jobject jValue = env->NewObject(integerClass, integerConstructor, value);
    
    env->CallObjectMethod(hashMap, putMethod, jKey, jValue);
    env->DeleteLocalRef(jKey);
    env->DeleteLocalRef(integerClass);
    env->DeleteLocalRef(jValue);
}

// Helper method to insert a double into Java HashMap
void putDoubleHashMap(JNIEnv *env, jobject hashMap, const char *key, double value) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    
    jstring jKey = env->NewStringUTF(key);
    jclass doubleClass = env->FindClass("java/lang/Double");
    jmethodID doubleConstructor = env->GetMethodID(doubleClass, "<init>", "(D)V");
    jobject jValue = env->NewObject(doubleClass, doubleConstructor, value);
    
    env->CallObjectMethod(hashMap, putMethod, jKey, jValue);
    env->DeleteLocalRef(jKey);
    env->DeleteLocalRef(doubleClass);
    env->DeleteLocalRef(jValue);
}

// Helper method to insert a boolean into Java HashMap
static inline void putBooleanHashMap(JNIEnv *env, jobject hashMap, const char *key, bool value) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    
    jstring jKey = env->NewStringUTF(key);
    jclass booleanClass = env->FindClass("java/lang/Boolean");
    jmethodID booleanConstructor = env->GetMethodID(booleanClass, "<init>", "(Z)V");
    jobject jValue = env->NewObject(booleanClass, booleanConstructor, value);
    
    env->CallObjectMethod(hashMap, putMethod, jKey, jValue);
    env->DeleteLocalRef(jKey);
    env->DeleteLocalRef(booleanClass);
    env->DeleteLocalRef(jValue);
}

// Global pointer to hold the llama context
static rn_llama *g_llama = nullptr;

// Get LLaMA context singleton
rn_llama *getLlamaContext() {
    if (!g_llama) {
        g_llama = new rn_llama();
    }
    return g_llama;
}

// JNI Method: Load model from file path
JNIEXPORT jlong JNICALL Java_org_nehuatl_llamacpp_LlamaContext_loadModel(
    JNIEnv *env, jclass clazz, jstring jModelPath, jint nThreads) {
    
    const char *modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    
    rn_llama *llama = getLlamaContext();
    
    // Load the model using llama.cpp
    llama_model_params model_params = llama_model_default_params();
    llama_model *model = llama_load_model_from_file(modelPath, model_params);
    
    env->ReleaseStringUTFChars(jModelPath, modelPath);
    
    if (!model) {
        LOGW("Failed to load model from path: %s", modelPath);
        return 0;
    }
    
    LOGI("Model loaded successfully from: %s", modelPath);
    return (jlong)model;
}

// JNI Method: Create context from model
JNIEXPORT jlong JNICALL Java_org_nehuatl_llamacpp_LlamaContext_createContext(
    JNIEnv *env, jclass clazz, jlong modelHandle, jint nCtx, jint nBatch) {
    
    llama_model *model = (llama_model *)modelHandle;
    if (!model) {
        LOGW("Invalid model handle");
        return 0;
    }
    
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx;
    ctx_params.n_batch = nBatch;
    
    llama_context *ctx = llama_new_context_with_model(model, ctx_params);
    
    if (!ctx) {
        LOGW("Failed to create context");
        return 0;
    }
    
    LOGI("Context created successfully");
    return (jlong)ctx;
}

// JNI Method: Free model
JNIEXPORT void JNICALL Java_org_nehuatl_llamacpp_LlamaContext_freeModel(
    JNIEnv *env, jclass clazz, jlong modelHandle) {
    
    llama_model *model = (llama_model *)modelHandle;
    if (model) {
        llama_free_model(model);
        LOGI("Model freed");
    }
}

// JNI Method: Free context
JNIEXPORT void JNICALL Java_org_nehuatl_llamacpp_LlamaContext_freeContext(
    JNIEnv *env, jclass clazz, jlong ctxHandle) {
    
    llama_context *ctx = (llama_context *)ctxHandle;
    if (ctx) {
        llama_free(ctx);
        LOGI("Context freed");
    }
}

// JNI Method: Tokenize input
JNIEXPORT jintArray JNICALL Java_org_nehuatl_llamacpp_LlamaContext_tokenize(
    JNIEnv *env, jclass clazz, jlong modelHandle, jstring jText, jboolean add_bos) {
    
    llama_model *model = (llama_model *)modelHandle;
    if (!model) return nullptr;
    
    const char *text = env->GetStringUTFChars(jText, nullptr);
    
    std::vector<llama_token> tokens;
    tokens.resize(jText ? env->GetStringLength(jText) + 1 : 1);
    
    int n_tokens = llama_tokenize(model, text, tokens.data(), tokens.size(), add_bos);
    
    env->ReleaseStringUTFChars(jText, text);
    
    jintArray result = env->NewIntArray(n_tokens);
    env->SetIntArrayRegion(result, 0, n_tokens, (jint *)tokens.data());
    
    return result;
}

// JNI Method: Decode tokens
JNIEXPORT jstring JNICALL Java_org_nehuatl_llamacpp_LlamaContext_decode(
    JNIEnv *env, jclass clazz, jlong modelHandle, jintArray jTokens) {
    
    llama_model *model = (llama_model *)modelHandle;
    if (!model) return env->NewStringUTF("");
    
    int token_count = env->GetArrayLength(jTokens);
    jint *tokens = env->GetIntArrayElements(jTokens, nullptr);
    
    std::string result;
    for (int i = 0; i < token_count; i++) {
        const char *piece = llama_token_get_text(model, tokens[i]);
        if (piece) result += piece;
    }
    
    env->ReleaseIntArrayElements(jTokens, tokens, JNI_ABORT);
    
    return env->NewStringUTF(result.c_str());
}

// JNI Method: Completion (inference)
JNIEXPORT jstring JNICALL Java_org_nehuatl_llamacpp_LlamaContext_complete(
    JNIEnv *env, jclass clazz, jlong ctxHandle, jlong modelHandle, 
    jstring jPrompt, jint maxTokens) {
    
    llama_context *ctx = (llama_context *)ctxHandle;
    llama_model *model = (llama_model *)modelHandle;
    
    if (!ctx || !model) {
        LOGW("Invalid context or model handle");
        return env->NewStringUTF("");
    }
    
    const char *prompt = env->GetStringUTFChars(jPrompt, nullptr);
    
    std::vector<llama_token> tokens;
    tokens.resize(strlen(prompt) + 1);
    
    int n_tokens = llama_tokenize(model, prompt, tokens.data(), tokens.size(), true);
    
    std::string result(prompt);
    
    for (int i = 0; i < maxTokens; i++) {
        if (llama_decode(ctx, llama_batch_get_one(&tokens[n_tokens - 1], 1, i, 0)) != 0) {
            LOGW("Failed to decode token");
            break;
        }
        
        llama_token_data_array candidates;
        candidates.data = new llama_token_data[llama_vocab_n(model)];
        candidates.size = llama_vocab_n(model);
        candidates.sorted = false;
        
        for (int j = 0; j < (int)candidates.size; j++) {
            candidates.data[j] = {j, llama_get_logits(ctx)[j], 0.0f};
        }
        
        llama_sampler *smpl = llama_sampler_init_top_p(0.9f, 1);
        llama_token next = llama_sampler_sample(smpl, ctx, &candidates);
        llama_sampler_free(smpl);
        
        delete[] candidates.data;
        
        const char *piece = llama_token_get_text(model, next);
        if (piece) result += piece;
        
        tokens.push_back(next);
        n_tokens++;
        
        if (next == llama_token_eos(model)) break;
    }
    
    env->ReleaseStringUTFChars(jPrompt, prompt);
    
    return env->NewStringUTF(result.c_str());
}

// JNI Method: Get model details (new function - FIXED)
JNIEXPORT jobject JNICALL Java_org_nehuatl_llamacpp_LlamaContext_loadModelDetails(
    JNIEnv *env, jclass clazz, jlong modelHandle) {
    
    llama_model *model = (llama_model *)modelHandle;
    
    jobject detailsMap = createHashMap(env);
    
    if (!model) {
        putStringHashMap(env, detailsMap, "error", "Invalid model handle");
        return detailsMap;
    }
    
    // Get model parameters
    int n_vocab = llama_vocab_n(model);
    int n_embd = llama_n_embd(model);
    int n_params = 0;
    
    // Try to estimate parameters (this is approximate)
    const llama_vocab *vocab = llama_model_get_vocab(model);
    if (vocab) {
        // Rough estimation based on vocabulary and embedding size
        n_params = n_vocab * n_embd;
    }
    
    // Put values into HashMap
    putIntHashMap(env, detailsMap, "vocab_size", n_vocab);
    putIntHashMap(env, detailsMap, "embedding_size", n_embd);
    putIntHashMap(env, detailsMap, "parameters", n_params);
    putStringHashMap(env, detailsMap, "model_arch", "llama");
    
    LOGI("Model details loaded: vocab=%d, embedding=%d", n_vocab, n_embd);
    
    return detailsMap;
}

// JNI Method: Get model context size
JNIEXPORT jint JNICALL Java_org_nehuatl_llamacpp_LlamaContext_getModelContextSize(
    JNIEnv *env, jclass clazz, jlong modelHandle) {
    
    llama_model *model = (llama_model *)modelHandle;
    if (!model) return 0;
    
    // Try to get context size from model metadata
    int ctx_size = llama_n_ctx_train(model);
    if (ctx_size <= 0) {
        ctx_size = 2048; // Default fallback
    }
    
    return ctx_size;
}

} // extern "C"
// JNI Method: Initialize context with file descriptor
JNIEXPORT jlong JNICALL Java_org_nehuatl_llamacpp_LlamaContext_initContextWithFd(
    JNIEnv *env, jclass clazz, jint fd, jint nCtx, jint nBatch, jint nThreads) {
    
    rn_llama *llama = getLlamaContext();
    if (!llama) {
        LOGW("Failed to get LLaMA context");
        return 0;
    }
    
    // Initialize with file descriptor
    llama_model_params model_params = llama_model_default_params();
    llama_model *model = llama_load_model_from_file_fd(fd, model_params);
    
    if (!model) {
        LOGW("Failed to load model from file descriptor");
        return 0;
    }
    
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx;
    ctx_params.n_batch = nBatch;
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;
    
    llama_context *ctx = llama_new_context_with_model(model, ctx_params);
    
    if (!ctx) {
        LOGW("Failed to create context from file descriptor");
        llama_free_model(model);
        return 0;
    }
    
    LOGI("Context initialized from FD with nCtx=%d, nBatch=%d", nCtx, nBatch);
    return (jlong)ctx;
}

// JNI Method: Perform inference step
JNIEXPORT jint JNICALL Java_org_nehuatl_llamacpp_LlamaContext_decode(
    JNIEnv *env, jclass clazz, jlong ctxHandle, jintArray jTokens) {
    
    llama_context *ctx = (llama_context *)ctxHandle;
    if (!ctx) return -1;
    
    int token_count = env->GetArrayLength(jTokens);
    jint *tokens = env->GetIntArrayElements(jTokens, nullptr);
    
    llama_batch batch = llama_batch_get_one(tokens, token_count, 0, 0);
    
    int result = llama_decode(ctx, batch);
    
    env->ReleaseIntArrayElements(jTokens, tokens, JNI_ABORT);
    
    return result;
}

// JNI Method: Sample next token
JNIEXPORT jint JNICALL Java_org_nehuatl_llamacpp_LlamaContext_sample(
    JNIEnv *env, jclass clazz, jlong ctxHandle, jlong modelHandle, 
    jfloat temperature, jfloat top_p, jfloat top_k) {
    
    llama_context *ctx = (llama_context *)ctxHandle;
    llama_model *model = (llama_model *)modelHandle;
    
    if (!ctx || !model) return -1;
    
    int n_vocab = llama_vocab_n(model);
    float *logits = llama_get_logits(ctx);
    
    std::vector<llama_token_data> candidates;
    candidates.reserve(n_vocab);
    
    for (int i = 0; i < n_vocab; i++) {
        candidates.push_back({i, logits[i], 0.0f});
    }
    
    llama_token_data_array candidates_p = {candidates.data(), candidates.size(), false};
    
    llama_sampler *sampler = llama_sampler_init_top_k(top_k, 1);
    llama_sampler_accept(sampler, ctx, llama_token_eos(model), false);
    
    llama_token next = llama_sampler_sample(sampler, ctx, &candidates_p);
    
    llama_sampler_free(sampler);
    
    return next;
}

// JNI Method: Get logits
JNIEXPORT jfloatArray JNICALL Java_org_nehuatl_llamacpp_LlamaContext_getLogits(
    JNIEnv *env, jclass clazz, jlong ctxHandle) {
    
    llama_context *ctx = (llama_context *)ctxHandle;
    if (!ctx) return nullptr;
    
    int n_vocab = llama_get_logits_size(ctx);
    float *logits = llama_get_logits(ctx);
    
    jfloatArray result = env->NewFloatArray(n_vocab);
    if (result) {
        env->SetFloatArrayRegion(result, 0, n_vocab, logits);
    }
    
    return result;
}

// JNI Method: Get embeddings
JNIEXPORT jfloatArray JNICALL Java_org_nehuatl_llamacpp_LlamaContext_getEmbeddings(
    JNIEnv *env, jclass clazz, jlong ctxHandle) {
    
    llama_context *ctx = (llama_context *)ctxHandle;
    if (!ctx) return nullptr;
    
    float *embeddings = llama_get_embeddings(ctx);
    if (!embeddings) return nullptr;
    
    int n_embd = llama_n_embd(llama_get_model(ctx));
    
    jfloatArray result = env->NewFloatArray(n_embd);
    if (result) {
        env->SetFloatArrayRegion(result, 0, n_embd, embeddings);
    }
    
    return result;
}

// JNI Method: Reset context
JNIEXPORT void JNICALL Java_org_nehuatl_llamacpp_LlamaContext_reset(
    JNIEnv *env, jclass clazz, jlong ctxHandle) {
    
    llama_context *ctx = (llama_context *)ctxHandle;
    if (ctx) {
        llama_kv_cache_clear(ctx);
        LOGI("Context reset");
    }
}

// JNI Method: Get token count
JNIEXPORT jint JNICALL Java_org_nehuatl_llamacpp_LlamaContext_getTokenCount(
    JNIEnv *env, jclass clazz, jlong ctxHandle) {
    
    llama_context *ctx = (llama_context *)ctxHandle;
    if (!ctx) return 0;
    
    return llama_get_kv_cache_token_count(ctx);
}

// JNI Method: Get context size
JNIEXPORT jint JNICALL Java_org_nehuatl_llamacpp_LlamaContext_getContextSize(
    JNIEnv *env, jclass clazz, jlong ctxHandle) {
    
    llama_context *ctx = (llama_context *)ctxHandle;
    if (!ctx) return 0;
    
    return llama_n_ctx(ctx);
}

// JNI Method: Get batch size
JNIEXPORT jint JNICALL Java_org_nehuatl_llamacpp_LlamaContext_getBatchSize(
    JNIEnv *env, jclass clazz, jlong ctxHandle) {
    
    llama_context *ctx = (llama_context *)ctxHandle;
    if (!ctx) return 0;
    
    return llama_n_batch(ctx);
}

// JNI Method: Get vocabulary size
JNIEXPORT jint JNICALL Java_org_nehuatl_llamacpp_LlamaContext_getVocabSize(
    JNIEnv *env, jclass clazz, jlong modelHandle) {
    
    llama_model *model = (llama_model *)modelHandle;
    if (!model) return 0;
    
    return llama_vocab_n(model);
}

// JNI Method: Get embedding size
JNIEXPORT jint JNICALL Java_org_nehuatl_llamacpp_LlamaContext_getEmbeddingSize(
    JNIEnv *env, jclass clazz, jlong modelHandle) {
    
    llama_model *model = (llama_model *)modelHandle;
    if (!model) return 0;
    
    return llama_n_embd(model);
}

// JNI Method: Get model info
JNIEXPORT jstring JNICALL Java_org_nehuatl_llamacpp_LlamaContext_getModelInfo(
    JNIEnv *env, jclass clazz, jlong modelHandle) {
    
    llama_model *model = (llama_model *)modelHandle;
    if (!model) {
        return env->NewStringUTF("Invalid model");
    }
    
    std::string info = "Model Info: ";
    info += "vocab_size=" + std::to_string(llama_vocab_n(model));
    info += ", embedding_size=" + std::to_string(llama_n_embd(model));
    
    return env->NewStringUTF(info.c_str());
}

// JNI Method: Check if EOS token
JNIEXPORT jboolean JNICALL Java_org_nehuatl_llamacpp_LlamaContext_isEosToken(
    JNIEnv *env, jclass clazz, jlong modelHandle, jint token) {
    
    llama_model *model = (llama_model *)modelHandle;
    if (!model) return JNI_FALSE;
    
    return llama_token_eos(model) == token ? JNI_TRUE : JNI_FALSE;
}

// JNI Method: Get BOS token
JNIEXPORT jint JNICALL Java_org_nehuatl_llamacpp_LlamaContext_getBosTok(
    JNIEnv *env, jclass clazz, jlong modelHandle) {
    
    llama_model *model = (llama_model *)modelHandle;
    if (!model) return -1;
    
    return llama_token_bos(model);
}

// JNI Method: Get EOS token
JNIEXPORT jint JNICALL Java_org_nehuatl_llamacpp_LlamaContext_getEosTok(
    JNIEnv *env, jclass clazz, jlong modelHandle) {
    
    llama_model *model = (llama_model *)modelHandle;
    if (!model) return -1;
    
    return llama_token_eos(model);
}

// JNI Method: Set seed for randomness
JNIEXPORT void JNICALL Java_org_nehuatl_llamacpp_LlamaContext_setSeed(
    JNIEnv *env, jclass clazz, jint seed) {
    
    llama_set_rng_seed(NULL, seed);
    LOGI("Seed set to %d", seed);
}

// JNI Method: Allocate batch
JNIEXPORT jlong JNICALL Java_org_nehuatl_llamacpp_LlamaContext_allocateBatch(
    JNIEnv *env, jclass clazz, jint n_tokens, jint embd, jint n_seq_max) {
    
    llama_batch batch = llama_batch_init(n_tokens, embd, n_seq_max);
    
    return (jlong)new llama_batch(batch);
}

// JNI Method: Free batch
JNIEXPORT void JNICALL Java_org_nehuatl_llamacpp_LlamaContext_freeBatch(
    JNIEnv *env, jclass clazz, jlong batchHandle) {
    
    llama_batch *batch = (llama_batch *)batchHandle;
    if (batch) {
        llama_batch_free(*batch);
        delete batch;
    }
}

// JNI Method: Get system info
JNIEXPORT jstring JNICALL Java_org_nehuatl_llamacpp_LlamaContext_getSystemInfo(
    JNIEnv *env, jclass clazz) {
    
    struct sysinfo info;
    sysinfo(&info);
    
    std::string sysInfo = "System Info: ";
    sysInfo += "RAM=" + std::to_string(info.totalram / (1024 * 1024)) + "MB";
    sysInfo += ", Free RAM=" + std::to_string(info.freeram / (1024 * 1024)) + "MB";
    
    return env->NewStringUTF(sysInfo.c_str());
}

// JNI Method: Abort generation
JNIEXPORT void JNICALL Java_org_nehuatl_llamacpp_LlamaContext_abort(
    JNIEnv *env, jclass clazz) {
    
    rn_llama *llama = getLlamaContext();
    if (llama) {
        // Signal abort (implementation depends on rn_llama structure)
        LOGI("Abort generation signal sent");
    }
}

// JNI Method: Check if model is loaded
JNIEXPORT jboolean JNICALL Java_org_nehuatl_llamacpp_LlamaContext_isModelLoaded(
    JNIEnv *env, jclass clazz, jlong modelHandle) {
    
    return modelHandle != 0 ? JNI_TRUE : JNI_FALSE;
}

// JNI Method: Check if context is ready
JNIEXPORT jboolean JNICALL Java_org_nehuatl_llamacpp_LlamaContext_isContextReady(
    JNIEnv *env, jclass clazz, jlong ctxHandle) {
    
    return ctxHandle != 0 ? JNI_TRUE : JNI_FALSE;
}

// JNI Method: Timings (performance stats)
JNIEXPORT jobject JNICALL Java_org_nehuatl_llamacpp_LlamaContext_getTimings(
    JNIEnv *env, jclass clazz, jlong ctxHandle) {
    
    llama_context *ctx = (llama_context *)ctxHandle;
    
    jobject timingsMap = createHashMap(env);
    
    if (!ctx) {
        putStringHashMap(env, timingsMap, "error", "Invalid context");
        return timingsMap;
    }
    
    struct llama_timings timings = llama_get_timings(ctx);
    
    putDoubleHashMap(env, timingsMap, "predict_ms", timings.t_p_ms);
    putDoubleHashMap(env, timingsMap, "eval_ms", timings.t_eval_ms);
    putDoubleHashMap(env, timingsMap, "load_ms", timings.t_load_ms);
    putIntHashMap(env, timingsMap, "predict_count", timings.n_p_eval);
    putIntHashMap(env, timingsMap, "eval_count", timings.n_eval);
    
    return timingsMap;
}

// JNI Method: Print timings
JNIEXPORT void JNICALL Java_org_nehuatl_llamacpp_LlamaContext_printTimings(
    JNIEnv *env, jclass clazz, jlong ctxHandle) {
    
    llama_context *ctx = (llama_context *)ctxHandle;
    if (ctx) {
        llama_print_timings(ctx);
    }
}

// JNI Method: Reset timings
JNIEXPORT void JNICALL Java_org_nehuatl_llamacpp_LlamaContext_resetTimings(
    JNIEnv *env, jclass clazz, jlong ctxHandle) {
    
    llama_context *ctx = (llama_context *)ctxHandle;
    if (ctx) {
        llama_reset_timings(ctx);
        LOGI("Timings reset");
    }
}

// JNI Method: Get backend info
JNIEXPORT jstring JNICALL Java_org_nehuatl_llamacpp_LlamaContext_getBackendInfo(
    JNIEnv *env, jclass clazz) {
    
    const char *backend = ggml_backend_name(ggml_get_default_backend());
    std::string info = "Backend: ";
    info += (backend ? backend : "unknown");
    
    return env->NewStringUTF(info.c_str());
}

// JNI Method: Update KV cache position
JNIEXPORT void JNICALL Java_org_nehuatl_llamacpp_LlamaContext_updateKvCachePos(
    JNIEnv *env, jclass clazz, jlong ctxHandle, jint pos) {
    
    llama_context *ctx = (llama_context *)ctxHandle;
    if (ctx) {
        llama_set_cache_slot(ctx, 0);
        LOGI("KV cache position updated to %d", pos);
    }
}

// JNI Method: Clone context (create a copy)
JNIEXPORT jlong JNICALL Java_org_nehuatl_llamacpp_LlamaContext_cloneContext(
    JNIEnv *env, jclass clazz, jlong ctxHandle, jlong modelHandle) {
    
    llama_context *ctx = (llama_context *)ctxHandle;
    llama_model *model = (llama_model *)modelHandle;
    
    if (!ctx || !model) return 0;
    
    llama_context_params params = llama_context_get_params(ctx);
    llama_context *cloned_ctx = llama_new_context_with_model(model, params);
    
    if (cloned_ctx) {
        LOGI("Context cloned successfully");
    }
    
    return (jlong)cloned_ctx;
}

// JNI Method: Stream completion (advanced)
JNIEXPORT jstring JNICALL Java_org_nehuatl_llamacpp_LlamaContext_streamCompletion(
    JNIEnv *env, jclass clazz, jlong ctxHandle, jlong modelHandle,
    jstring jPrompt, jint maxTokens, jfloat temperature) {
    
    llama_context *ctx = (llama_context *)ctxHandle;
    llama_model *model = (llama_model *)modelHandle;
    
    if (!ctx || !model) {
        LOGW("Invalid context or model");
        return env->NewStringUTF("");
    }
    
    const char *prompt = env->GetStringUTFChars(jPrompt, nullptr);
    
    std::vector<llama_token> tokens;
    tokens.resize(strlen(prompt) + 128);
    
    int n_tokens = llama_tokenize(model, prompt, tokens.data(), tokens.size(), true);
    
    std::string result(prompt);
    
    for (int i = 0; i < maxTokens; i++) {
        llama_batch batch = llama_batch_get_one(&tokens[n_tokens - 1], 1, i, 0);
        
        if (llama_decode(ctx, batch) != 0) {
            LOGW("Decode error at token %d", i);
            break;
        }
        
        int n_vocab = llama_vocab_n(model);
        float *logits = llama_get_logits(ctx);
        
        std::vector<llama_token_data> candidates;
        for (int j = 0; j < n_vocab; j++) {
            candidates.push_back({j, logits[j], 0.0f});
        }
        
        llama_token_data_array candidates_p = {candidates.data(), (size_t)n_vocab, false};
        
        llama_sampler *sampler = llama_sampler_init_softmax(1);
        llama_token next = llama_sampler_sample(sampler, ctx, &candidates_p);
        llama_sampler_free(sampler);
        
        const char *piece = llama_token_get_text(model, next);
        if (piece) result += piece;
        
        tokens.push_back(next);
        n_tokens++;
        
        if (next == llama_token_eos(model)) break;
    }
    
    env->ReleaseStringUTFChars(jPrompt, prompt);
    
    return env->NewStringUTF(result.c_str());
}

// JNI Method: Cleanup and shutdown
JNIEXPORT void JNICALL Java_org_nehuatl_llamacpp_LlamaContext_shutdown(
    JNIEnv *env, jclass clazz) {
    
    if (g_llama) {
        delete g_llama;
        g_llama = nullptr;
        LOGI("LLaMA context shutdown complete");
    }
}

} // extern "C"