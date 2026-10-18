#include <jni.h>
#include <unistd.h>
#include <fcntl.h>

#include <android/log.h>
#include <algorithm>
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <sys/sysinfo.h>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>
#include <mutex> // [ALTREX] Ajout de l'inclusion pour le verrou de sécurité
#include "llama.h"
#include "rn-llama.h"
#include "rn-completion.h"
#include "rn-mtmd.hpp"
#include "ggml.h"
#include "gguf.h"
#include "speculative.h"
#include <deque>

#define UNUSED(x) (void)(x)
#define TAG "RNLLAMA_ANDROID_JNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)

static inline int min(int a, int b) {
    return (a < b) ? a : b;
}

// [ALTREX] llama.cpp / ggml logs go to logcat, and the last warnings/errors are kept so the app
// can tell the user why a model failed to load ("Model initialization failed" said nothing).
static std::mutex g_log_mutex;
static std::deque<std::string> g_last_errors;
// [ALTREX] Full llama.cpp log (INFO and above) of the recent loads, exported from the app to
// diagnose a model that gives incoherent text on a device.
static std::string g_full_log;
// [ALTREX] Diagnostic load options set from Kotlin just before a load (KV cache type, flash attention).
static std::mutex g_load_opts_mutex;
static ggml_type g_next_kv_type = GGML_TYPE_F16;
static llama_flash_attn_type g_next_flash_attn = LLAMA_FLASH_ATTN_TYPE_AUTO;
static const size_t ALTREX_FULL_LOG_MAX = 160 * 1024;

static void altrex_full_log_append(const char *text) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_full_log += text;
    if (g_full_log.size() > ALTREX_FULL_LOG_MAX) {
        size_t cut = g_full_log.size() - ALTREX_FULL_LOG_MAX;
        size_t nl = g_full_log.find('\n', cut);
        g_full_log.erase(0, nl == std::string::npos ? cut : nl + 1);
    }
}

static void altrex_log_callback(enum ggml_log_level level, const char *text, void *user_data) {
    (void) user_data;
    if (text == nullptr) return;
    int prio = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) prio = ANDROID_LOG_INFO;
    __android_log_print(prio, "LLAMA_CPP", "%s", text);
    // A CONT chunk continues the previous message: keep it only if that one was kept.
    static enum ggml_log_level last_level = GGML_LOG_LEVEL_INFO;
    if (level != GGML_LOG_LEVEL_CONT) last_level = level;
    if (last_level != GGML_LOG_LEVEL_DEBUG) altrex_full_log_append(text);
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {
        std::string line(text);
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
        if (line.empty()) return;
        std::lock_guard<std::mutex> lock(g_log_mutex);
        g_last_errors.push_back(line);
        while (g_last_errors.size() > 12) g_last_errors.pop_front();
    }
}

static void altrex_install_log() {
    static std::once_flag once;
    std::call_once(once, []() {
        llama_log_set(altrex_log_callback, nullptr);
        ggml_log_set(altrex_log_callback, nullptr);
    });
}

extern "C" {

// Helper method to create a Java HashMap
static inline jobject createHashMap(JNIEnv *env) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID init = env->GetMethodID(hashMapClass, "<init>", "()V");
    jobject hashMap = env->NewObject(hashMapClass, init);
    env->DeleteLocalRef(hashMapClass);
    return hashMap;
}

// [ALTREX] Standard UTF-8 -> Java String. NewStringUTF expects *modified* UTF-8 and rejects
// 4-byte characters (emoji, some CJK) under CheckJNI, which aborts debug builds; decode the raw
// bytes on the Java side instead.
static jstring utf8ToJString(JNIEnv *env, const std::string &text) {
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    jbyteArray bytes = env->NewByteArray((jsize) text.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) text.size(), reinterpret_cast<const jbyte *>(text.data()));
    jstring charset = env->NewStringUTF("UTF-8");
    jstring result = (jstring) env->NewObject(stringClass, ctor, bytes, charset);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(stringClass);
    return result;
}

// [ALTREX] Java String -> standard UTF-8 (GetStringUTFChars returns modified UTF-8, which turns
// emoji into surrogate pairs the tokenizer cannot read).
static std::string jstringToUtf8(JNIEnv *env, jstring text) {
    if (text == nullptr) return "";
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID getBytes = env->GetMethodID(stringClass, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    jbyteArray bytes = (jbyteArray) env->CallObjectMethod(text, getBytes, charset);
    std::string out;
    if (bytes != nullptr) {
        jsize len = env->GetArrayLength(bytes);
        out.resize((size_t) len);
        if (len > 0) env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte *>(&out[0]));
        env->DeleteLocalRef(bytes);
    }
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(stringClass);
    return out;
}

// Helper method to put a string into a Java HashMap
static inline void putStringHashMap(JNIEnv *env, jobject hashMap, const char *key, const char *value) {
    if (value == nullptr) return;
    
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    jstring jKey = env->NewStringUTF(key);
    jstring jValue = utf8ToJString(env, value);

    env->CallObjectMethod(hashMap, putMethod, jKey, jValue);
    
    env->DeleteLocalRef(jKey);
    env->DeleteLocalRef(jValue);
    env->DeleteLocalRef(hashMapClass);
}

// Helper method to put an integer into a Java HashMap
static inline void putIntHashMap(JNIEnv *env, jobject hashMap, const char *key, int value) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID intConstructor = env->GetMethodID(integerClass, "<init>", "(I)V");
    jobject jValue = env->NewObject(integerClass, intConstructor, value);

    jstring jKey = env->NewStringUTF(key);

    env->CallObjectMethod(hashMap, putMethod, jKey, jValue);
    
    env->DeleteLocalRef(jKey);
    env->DeleteLocalRef(integerClass);
    env->DeleteLocalRef(jValue);
}

// Helper method to put a double into a Java HashMap
static inline void putDoubleHashMap(JNIEnv *env, jobject hashMap, const char *key, double value) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    jclass doubleClass = env->FindClass("java/lang/Double");
    jmethodID doubleConstructor = env->GetMethodID(doubleClass, "<init>", "(D)V");
    jobject jValue = env->NewObject(doubleClass, doubleConstructor, value);

    jstring jKey = env->NewStringUTF(key);

    env->CallObjectMethod(hashMap, putMethod, jKey, jValue);
    
    env->DeleteLocalRef(jKey);
    env->DeleteLocalRef(doubleClass);
    env->DeleteLocalRef(jValue);
}

// Helper method to put a boolean into a Java HashMap
static inline void putBooleanHashMap(JNIEnv *env, jobject hashMap, const char *key, bool value) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    jclass booleanClass = env->FindClass("java/lang/Boolean");
    jmethodID boolConstructor = env->GetMethodID(booleanClass, "<init>", "(Z)V");
    jobject jValue = env->NewObject(booleanClass, boolConstructor, value);

    jstring jKey = env->NewStringUTF(key);

    env->CallObjectMethod(hashMap, putMethod, jKey, jValue);
    
    env->DeleteLocalRef(jKey);
    env->DeleteLocalRef(booleanClass);
    env->DeleteLocalRef(jValue);
}

// Helper method to put a Java ArrayList into a Java HashMap
static inline void putArrayListHashMap(JNIEnv *env, jobject hashMap, const char *key, jobject value) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    jstring jKey = env->NewStringUTF(key);

    env->CallObjectMethod(hashMap, putMethod, jKey, value);
    
    env->DeleteLocalRef(jKey);
}

// Helper method to put a Java HashMap into a Java HashMap
static inline void putHashMapHashMap(JNIEnv *env, jobject hashMap, const char *key, jobject value) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    jstring jKey = env->NewStringUTF(key);

    env->CallObjectMethod(hashMap, putMethod, jKey, value);
    
    env->DeleteLocalRef(jKey);
}

// Helper method to create a Java ArrayList
static inline jobject createArrayList(JNIEnv *env) {
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID init = env->GetMethodID(arrayListClass, "<init>", "()V");
    jobject arrayList = env->NewObject(arrayListClass, init);
    return arrayList;
}

// Helper method to add an integer to a Java ArrayList
static inline void addIntArrayList(JNIEnv *env, jobject arrayList, int value) {
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID addMethod = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");

    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID intConstructor = env->GetMethodID(integerClass, "<init>", "(I)V");
    jobject jValue = env->NewObject(integerClass, intConstructor, value);

    env->CallBooleanMethod(arrayList, addMethod, jValue);
    
    env->DeleteLocalRef(integerClass);
    env->DeleteLocalRef(jValue);
}

// Helper method to add a double to a Java ArrayList
static inline void addDoubleArrayList(JNIEnv *env, jobject arrayList, double value) {
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID addMethod = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");

    jclass doubleClass = env->FindClass("java/lang/Double");
    jmethodID doubleConstructor = env->GetMethodID(doubleClass, "<init>", "(D)V");
    jobject jValue = env->NewObject(doubleClass, doubleConstructor, value);

    env->CallBooleanMethod(arrayList, addMethod, jValue);
    
    env->DeleteLocalRef(doubleClass);
    env->DeleteLocalRef(jValue);
}

// Helper method to add a string to a Java ArrayList
static inline void addStringArrayList(JNIEnv *env, jobject arrayList, const char *value) {
    if (value == nullptr) return;
    
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID addMethod = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");

    jstring jValue = utf8ToJString(env, value);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        jValue = env->NewStringUTF("");
    }

    env->CallBooleanMethod(arrayList, addMethod, jValue);
    
    if (jValue) env->DeleteLocalRef(jValue);
    env->DeleteLocalRef(arrayListClass);
}

// Helper method to add a HashMap to a Java ArrayList
static inline void addHashMapArrayList(JNIEnv *env, jobject arrayList, jobject value) {
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID addMethod = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");

    env->CallBooleanMethod(arrayList, addMethod, value);
}

// [ALTREX] Thread-safety: Mutex et Map globale protégée
std::unordered_map<long, rnllama::llama_rn_context *> context_map;
std::mutex context_mutex;

// [ALTREX] Repère les cœurs rapides (fréquence max au-dessus du minimum de la puce).
// Sur un Snapdragon 8 Gen 1 : le X2 et les trois A710, sans les quatre A510 lents.
// Renvoie leur nombre, 0 si la puce est homogène ou si la fréquence est illisible.
static int altrex_pick_fast_cores(bool *mask, int n_cpus) {
    n_cpus = std::min(n_cpus, GGML_MAX_N_THREADS);
    std::vector<long> freqs(n_cpus > 0 ? n_cpus : 0, 0);
    long f_min = LONG_MAX;
    long f_max = 0;
    for (int i = 0; i < n_cpus; i++) {
        char path[96];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE *f = fopen(path, "r");
        if (f == nullptr) continue;
        long v = 0;
        if (fscanf(f, "%ld", &v) == 1 && v > 0) {
            freqs[i] = v;
            f_min = std::min(f_min, v);
            f_max = std::max(f_max, v);
        }
        fclose(f);
    }
    if (f_max == 0 || f_min == f_max) return 0;
    int count = 0;
    for (int i = 0; i < n_cpus; i++) {
        if (freqs[i] > f_min) {
            mask[i] = true;
            count++;
        }
    }
    return count;
}

// [ALTREX] True when the GGUF declares MTP / NextN layers (<arch>.nextn_predict_layers > 0).
static bool altrex_has_mtp_layers(const std::string &path) {
    gguf_init_params gp = { /*no_alloc =*/ true, /*ctx =*/ nullptr };
    gguf_context *g = gguf_init_from_file(path.c_str(), gp);
    if (g == nullptr) return false;
    static const std::string suffix = ".nextn_predict_layers";
    bool found = false;
    const int64_t n_kv = gguf_get_n_kv(g);
    for (int64_t i = 0; i < n_kv; i++) {
        const std::string key = gguf_get_key(g, i);
        if (key.size() > suffix.size() && key.compare(key.size() - suffix.size(), suffix.size(), suffix) == 0) {
            found = gguf_get_kv_type(g, i) != GGUF_TYPE_UINT32 || gguf_get_val_u32(g, i) > 0;
            break;
        }
    }
    gguf_free(g);
    return found;
}

JNIEXPORT jlong JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_initContextWithFd(
        JNIEnv *env,
        jobject thiz,
        jint model_fd,
        jboolean embedding,
        jint n_ctx,
        jint n_batch,
        jint n_threads,
        jint n_gpu_layers,
        jboolean use_mlock,
        jboolean use_mmap,
        jboolean vocab_only,
        jstring lora_str,
        jfloat lora_scaled,
        jfloat rope_freq_base,
        jfloat rope_freq_scale,
        jint mmproj_fd,
        jintArray image_fds,
        jboolean use_mtp,
        jint mtp_n_max
) {
    UNUSED(thiz);
    UNUSED(image_fds);

    common_params defaultParams;

    defaultParams.vocab_only = vocab_only;
    if (vocab_only) defaultParams.warmup = false;

    if (model_fd < 0) {
        LOGW("Invalid model_fd < 0");
        return 0;
    }

    int dupfd = dup(model_fd);
    if (dupfd == -1) {
        LOGW("dup(model_fd=%d) failed errno=%d (%s)",
             model_fd, errno, strerror(errno));
        return 0;
    }
    close(model_fd);

    // [ALTREX] Upstream llama.cpp opens files by path: /proc/self/fd/N points to our descriptor.
    char fdString[48];
    snprintf(fdString, sizeof(fdString), "/proc/self/fd/%d", dupfd);
    defaultParams.model.path = fdString;
    altrex_install_log();
    // CPU features of the native build in use (NEON, FP16, DOTPROD, MATMUL_INT8...).
    altrex_full_log_append("\n=== Altrex: loading model ===\nsystem_info: ");
    altrex_full_log_append(llama_print_system_info());
    altrex_full_log_append("\n");
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        g_last_errors.clear();
    }

    defaultParams.embedding = embedding;
    defaultParams.n_ctx = n_ctx;
    defaultParams.n_batch = n_batch;

    int max_threads = std::thread::hardware_concurrency();
    int auto_threads = max_threads == 4 ? 2 : std::min(4, max_threads);
    // [ALTREX] En mode auto, les threads de calcul restent sur les cœurs rapides : un thread posé
    // sur un cœur lent fait attendre tous les autres à chaque couche.
    bool use_fast_cores = false;
    if (n_threads <= 0) {
        int fast = altrex_pick_fast_cores(defaultParams.cpuparams.cpumask, max_threads);
        if (fast >= 2) {
            auto_threads = std::min(fast, 4);
            defaultParams.cpuparams.mask_valid = true;
            use_fast_cores = true;
        }
        LOGI("Fast cores: %d of %d, threads: %d", fast, max_threads, auto_threads);
    }
    defaultParams.cpuparams.n_threads =
            n_threads > 0 ? n_threads : auto_threads;
    defaultParams.n_gpu_layers = n_gpu_layers;
    // b11385 merged use_mmap/use_mlock into a single load mode.
    if (use_mmap) {
        defaultParams.load_mode = use_mlock ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MMAP;
    } else {
        defaultParams.load_mode = use_mlock ? LLAMA_LOAD_MODE_MLOCK : LLAMA_LOAD_MODE_NONE;
    }

    const char *lora_chars = env->GetStringUTFChars(lora_str, nullptr);
    if (lora_chars && lora_chars[0] != '\0') {
        defaultParams.lora_adapters.push_back({lora_chars, lora_scaled, "", "", nullptr});
    }

    int dup_mmproj_fd = -1;
    if (mmproj_fd >= 0) {
        dup_mmproj_fd = dup(mmproj_fd);
        if (dup_mmproj_fd != -1) {
            char mmproj_path[48];
            snprintf(mmproj_path, sizeof(mmproj_path), "/proc/self/fd/%d", dup_mmproj_fd);
            defaultParams.mmproj.path = mmproj_path;
            LOGI("mmproj set to FD: %s", defaultParams.mmproj.path.c_str());
        }
        close(mmproj_fd);
    }

    {
        std::lock_guard<std::mutex> lock(g_load_opts_mutex);
        defaultParams.cache_type_k = g_next_kv_type;
        defaultParams.cache_type_v = g_next_kv_type;
        defaultParams.flash_attn_type = g_next_flash_attn;
    }
    {
        char opts[160];
        snprintf(opts, sizeof(opts), "Altrex load options: cache_type_k/v=%s, flash_attn=%s\n",
                 ggml_type_name(defaultParams.cache_type_k), llama_flash_attn_type_name(defaultParams.flash_attn_type));
        altrex_full_log_append(opts);
        LOGI("%s", opts);
    }

    defaultParams.rope_freq_base = rope_freq_base;
    defaultParams.rope_freq_scale = rope_freq_scale;

    // [ALTREX] MTP (multi-token prediction) speculative decoding, e.g. Qwen3.5 GGUFs exported with
    // their NextN layer. Off unless asked: it loads the extra layer and needs recurrent-state
    // snapshots for hybrid models.
    // A GGUF without MTP layer loads fine with MTP requested, then fails at the first generation:
    // check the file first and load it normally.
    if (use_mtp && !altrex_has_mtp_layers(defaultParams.model.path)) {
        LOGW("MTP requested but the model has no MTP (nextn) layer: loading without MTP");
        use_mtp = false;
    }
    if (use_mtp) {
        defaultParams.speculative.types = { COMMON_SPECULATIVE_TYPE_DRAFT_MTP };
        defaultParams.speculative.draft.n_max = mtp_n_max > 0 ? mtp_n_max : 3;
        LOGI("MTP speculative decoding requested (n_max=%d)", defaultParams.speculative.draft.n_max);
    }

    auto llama = new rnllama::llama_rn_context();
    bool ok = false;
    // [ALTREX] A C++ exception escaping JNI aborts the app: report a failed load instead.
    try {
        ok = llama->loadModel(defaultParams);
    } catch (const std::exception &e) {
        LOGW("loadModel failed: %s", e.what());
        ok = false;
    } catch (...) {
        LOGW("loadModel failed with an unknown exception");
        ok = false;
    }
    // The loader opened its own copy of the fd and the weights are mapped: ours can go (otherwise
    // every load leaks a descriptor that keeps the multi-GB file alive after it is deleted).
    close(dupfd);

    if (ok) {
        // [ALTREX] Verrouillage propre lors de l'enregistrement du pointeur
        {
            std::lock_guard<std::mutex> lock(context_mutex);
            context_map[(long) llama->ctx] = llama;
        }

        // [ALTREX] Depuis b11385, common_init_from_params() crée lui-même le pool de threads à
        // partir de cpuparams (masque des cœurs rapides inclus) : rien à rattacher ici.
        if (use_fast_cores) LOGI("Fast-core threadpool pinned via cpumask");
        
        if (!defaultParams.mmproj.path.empty()) {
            LOGI("Initializing multimodal with mmproj: %s", defaultParams.mmproj.path.c_str());
            bool mm_ok = false;
            try {
                mm_ok = llama->initMultimodal(defaultParams.mmproj.path, n_gpu_layers > 0);
            } catch (const std::exception &e) {
                LOGW("initMultimodal failed: %s", e.what());
            }
            LOGI("Multimodal initialization result: %s", mm_ok ? "success" : "failed");
            LOGI("Context multimodal enabled check: %s", llama->isMultimodalEnabled() ? "yes" : "no");
        }
    } else {
        delete llama;
    }
    if (dup_mmproj_fd != -1) close(dup_mmproj_fd);

    env->ReleaseStringUTFChars(lora_str, lora_chars);
    return ok ? reinterpret_cast<jlong>(llama->ctx) : 0;
}

JNIEXPORT jobject JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_loadModelDetails(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr
) {
    UNUSED(thiz);
    rnllama::llama_rn_context* llama = nullptr;
    {
        std::lock_guard<std::mutex> lock(context_mutex);
        auto it = context_map.find((long) context_ptr);
        if (it != context_map.end()) llama = it->second;
    }
    if (!llama) return nullptr;

    int count = llama_model_meta_count(llama->model);
    auto meta = createHashMap(env);
    for (int i = 0; i < count; i++) {
        char key[256];
        llama_model_meta_key_by_index(llama->model, i, key, sizeof(key));
        char val[2048];
        llama_model_meta_val_str_by_index(llama->model, i, val, sizeof(val));

        putStringHashMap(env, meta, key, val);
    }

    auto result = createHashMap(env);

    char desc[1024];
    llama_model_desc(llama->model, desc, sizeof(desc));
    putStringHashMap(env, result, "desc", desc);
    putDoubleHashMap(env, result, "size", llama_model_size(llama->model));
    putDoubleHashMap(env, result, "nParams", (double)llama_model_n_params(llama->model));
    putBooleanHashMap(env, result, "isChatTemplateSupported", llama->validateModelChatTemplate(true, nullptr));
    putHashMapHashMap(env, result, "metadata", meta);

    return reinterpret_cast<jobject>(result);
}

JNIEXPORT jstring JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_getFormattedChat(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr,
        jobjectArray messages,
        jstring chat_template
) {
    UNUSED(thiz);
    UNUSED(messages);
    UNUSED(chat_template);
    rnllama::llama_rn_context* llama = nullptr;
    {
        std::lock_guard<std::mutex> lock(context_mutex);
        auto it = context_map.find((long) context_ptr);
        if (it != context_map.end()) llama = it->second;
    }
    if (!llama) return nullptr;
    return env->NewStringUTF("");
}

// [ALTREX] Formats a conversation with the model's own chat template (Jinja, as in llama-server),
// with thinking turned off. messages_json is an OpenAI-style array of {role, content}.
// Returns a JSON object {"prompt": "...", "stops": [...], "jinja": bool}, or "" when the model has
// no usable template (the caller then falls back to ChatML).
JNIEXPORT jstring JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_formatChat(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr,
        jstring messages_json,
        jboolean enable_thinking
) {
    UNUSED(thiz);
    rnllama::llama_rn_context* llama = nullptr;
    {
        std::lock_guard<std::mutex> lock(context_mutex);
        auto it = context_map.find((long) context_ptr);
        if (it != context_map.end()) llama = it->second;
    }
    if (!llama) return utf8ToJString(env, "");
    const std::string messages = jstringToUtf8(env, messages_json);
    std::string out;
    try {
        std::map<std::string, std::string> kwargs;
        kwargs["enable_thinking"] = enable_thinking ? "true" : "false";
        common_chat_params formatted = llama->getFormattedChatWithJinja(
            messages, "", "", "", false, "", enable_thinking, "none", true, "", kwargs, false);
        json res;
        res["prompt"] = formatted.prompt;
        res["stops"] = formatted.additional_stops;
        res["jinja"] = true;
        out = res.dump();
    } catch (const std::exception &e) {
        LOGW("formatChat: jinja template failed (%s), trying the legacy template", e.what());
        try {
            json res;
            res["prompt"] = llama->getFormattedChat(messages, "");
            res["stops"] = json::array();
            res["jinja"] = false;
            out = res.dump();
        } catch (const std::exception &e2) {
            LOGW("formatChat: legacy template failed too (%s)", e2.what());
            out = "";
        }
    }
    return utf8ToJString(env, out);
}

JNIEXPORT jobject JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_loadSession(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr,
        jstring path
) {
    UNUSED(thiz);
    rnllama::llama_rn_context* llama = nullptr;
    {
        std::lock_guard<std::mutex> lock(context_mutex);
        auto it = context_map.find((long) context_ptr);
        if (it != context_map.end()) llama = it->second;
    }
    if (!llama) return nullptr;
    const char *path_chars = env->GetStringUTFChars(path, nullptr);

    auto result = createHashMap(env);
    size_t n_token_count_out = 0;
    if (!llama_state_load_file(llama->ctx, path_chars, nullptr, 0, &n_token_count_out)) {
        env->ReleaseStringUTFChars(path, path_chars);
        putStringHashMap(env, result, "error", "Failed to load session");
        return reinterpret_cast<jobject>(result);
    }
    env->ReleaseStringUTFChars(path, path_chars);

    putIntHashMap(env, result, "tokens_loaded", (int)n_token_count_out);
    putStringHashMap(env, result, "prompt", "");
    return reinterpret_cast<jobject>(result);
}

JNIEXPORT jint JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_saveSession(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr,
        jstring path,
        jint size
) {
    UNUSED(thiz);
    UNUSED(size);
    rnllama::llama_rn_context* llama = nullptr;
    {
        std::lock_guard<std::mutex> lock(context_mutex);
        auto it = context_map.find((long) context_ptr);
        if (it != context_map.end()) llama = it->second;
    }
    if (!llama) return -1;

    const char *path_chars = env->GetStringUTFChars(path, nullptr);

    if (!llama_state_save_file(llama->ctx, path_chars, nullptr, 0)) {
        env->ReleaseStringUTFChars(path, path_chars);
        return -1;
    }

    env->ReleaseStringUTFChars(path, path_chars);
    return 0;
}

JNIEXPORT jobject JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_doCompletion(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr,
        jstring prompt,
        jstring grammar,
        jfloat temperature,
        jint n_threads,
        jint n_predict,
        jint n_probs,
        jint penalty_last_n,
        jfloat penalty_repeat,
        jfloat penalty_freq,
        jfloat penalty_present,
        jfloat mirostat,
        jfloat mirostat_tau,
        jfloat mirostat_eta,
        jboolean penalize_nl,
        jint top_k,
        jfloat top_p,
        jfloat min_p,
        jfloat xtc_t,
        jfloat xtc_p,
        jfloat tfs_z,
        jfloat typical_p,
        jint seed,
        jobjectArray stop,
        jboolean ignore_eos,
        jobjectArray logit_bias,
        jintArray image_fds,
        jobject partialCompletionCallback
) {
    UNUSED(thiz);
    UNUSED(grammar);
    UNUSED(n_probs);
    UNUSED(mirostat);
    UNUSED(mirostat_tau);
    UNUSED(mirostat_eta);
    UNUSED(penalize_nl);
    UNUSED(xtc_t);
    UNUSED(xtc_p);
    UNUSED(tfs_z);
    UNUSED(typical_p);
    UNUSED(ignore_eos);
    UNUSED(logit_bias);
    
    rnllama::llama_rn_context* llama = nullptr;
    {
        std::lock_guard<std::mutex> lock(context_mutex);
        auto it = context_map.find((long) context_ptr);
        if (it != context_map.end()) llama = it->second;
        // [ALTREX] Busy from here (not only from beginCompletion): freeContext waits on this flag,
        // and setting it under the map lock closes the lookup/free race.
        if (llama != nullptr && llama->completion != nullptr) llama->completion->is_predicting = true;
    }
    if (!llama || llama->completion == nullptr) return nullptr;

    llama->completion->rewind();

    llama->params.prompt = jstringToUtf8(env, prompt);

    // [ALTREX] Stop strings (end-of-turn markers of the chat template and the caller's own).
    std::vector<std::string> stop_words;
    if (stop != nullptr) {
        jsize n_stop = env->GetArrayLength(stop);
        for (jsize i = 0; i < n_stop; i++) {
            jstring word = (jstring) env->GetObjectArrayElement(stop, i);
            std::string w = jstringToUtf8(env, word);
            if (!w.empty()) stop_words.push_back(w);
            if (word != nullptr) env->DeleteLocalRef(word);
        }
    }

    // [ALTREX] 0 = garder le réglage du chargement (cœurs rapides) au lieu de tous les cœurs.
    if (n_threads > 0) llama->params.cpuparams.n_threads = n_threads;
    llama->params.sampling.seed = (seed == -1) ? time(NULL) : seed;
    llama->params.sampling.temp = temperature;
    llama->params.sampling.top_k = top_k;
    llama->params.sampling.top_p = top_p;
    llama->params.sampling.min_p = min_p;
    llama->params.n_predict = n_predict;
    llama->params.sampling.penalty_last_n = penalty_last_n;
    llama->params.sampling.penalty_repeat = penalty_repeat;
    llama->params.sampling.penalty_freq = penalty_freq;
    llama->params.sampling.penalty_present = penalty_present;

    llama->completion->initSampling();
    
    std::vector<std::string> images;
    if (image_fds != nullptr) {
        jsize len = env->GetArrayLength(image_fds);
        jint *fds = env->GetIntArrayElements(image_fds, nullptr);
        for (jsize i = 0; i < len; i++) {
            int dup_img_fd = dup(fds[i]);
            if (dup_img_fd != -1) {
                char img_path[48];
                snprintf(img_path, sizeof(img_path), "/proc/self/fd/%d", dup_img_fd);
                images.push_back(img_path);
            }
            close(fds[i]);
        }
        env->ReleaseIntArrayElements(image_fds, fds, 0);
    }
    
    LOGI("doCompletion: prompt=%zu bytes, stops=%zu, images=%zu, multimodal_enabled=%s",
         llama->params.prompt.size(), stop_words.size(), images.size(), llama->isMultimodalEnabled() ? "yes" : "no");
    
    // [ALTREX] Why the turn ended, copied before endCompletion() releases the context to freeContext.
    struct {
        bool context_full = false, stopped_eos = false, stopped_limit = false, interrupted = false;
        int draft_tokens = 0, draft_accepted = 0;
        void take(rnllama::llama_rn_context_completion *c) {
            context_full = c->context_full;
            stopped_eos = c->stopped_eos;
            stopped_limit = c->stopped_limit;
            interrupted = c->is_interrupted;
            draft_tokens = (int) c->num_draft_tokens;
            draft_accepted = (int) c->num_draft_tokens_accepted;
        }
    } snap;

    try {
        llama->completion->loadPrompt(images);
        llama->completion->beginCompletion();

        jclass cb_class = env->GetObjectClass(partialCompletionCallback);
        jmethodID onPartialCompletion = env->GetMethodID(cb_class, "onPartialCompletion", "(Ljava/util/Map;)V");
        size_t sent_count = 0;
        bool stopped_by_word = false;
        while (llama->completion->has_next_token && !llama->completion->is_interrupted) {
            auto token_output = llama->completion->doCompletion();
            if (token_output.tok == -1) break;

            if (llama->completion->incomplete) continue;

            std::string &text = llama->completion->generated_text;

            // [ALTREX] Stop string: cut the text there and end the turn.
            size_t send_end = text.size();
            for (const auto &w : stop_words) {
                size_t from = sent_count > w.size() ? sent_count - w.size() : 0;
                size_t found = text.find(w, from);
                if (found != std::string::npos && found < send_end) {
                    send_end = found;
                    stopped_by_word = true;
                }
            }
            if (stopped_by_word) {
                text.resize(send_end);
                llama->completion->stopped_word = true;
                llama->completion->has_next_token = false;
            } else {
                // Hold back a tail that could be the start of a stop string.
                for (const auto &w : stop_words) {
                    for (size_t len = std::min(w.size() - 1, text.size()); len > 0; len--) {
                        if (text.compare(text.size() - len, len, w, 0, len) == 0) {
                            send_end = std::min(send_end, text.size() - len);
                            break;
                        }
                    }
                }
            }

            if (send_end > sent_count) {
                std::string to_send = text.substr(sent_count, send_end - sent_count);
                sent_count = send_end;
                // One local frame per token: the helpers create several local references and a
                // long answer would otherwise overflow the local reference table.
                if (env->PushLocalFrame(16) == 0) {
                    auto tokenResult = createHashMap(env);
                    putStringHashMap(env, tokenResult, "token", to_send.c_str());
                    env->CallVoidMethod(partialCompletionCallback, onPartialCompletion, tokenResult);
                    env->PopLocalFrame(nullptr);
                }
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    break;
                }
            }
            if (stopped_by_word) break;
        }

        // Flush text held back for a stop string that never came.
        if (!stopped_by_word && llama->completion->generated_text.size() > sent_count) {
            std::string rest = llama->completion->generated_text.substr(sent_count);
            if (env->PushLocalFrame(16) == 0) {
                auto tokenResult = createHashMap(env);
                putStringHashMap(env, tokenResult, "token", rest.c_str());
                env->CallVoidMethod(partialCompletionCallback, onPartialCompletion, tokenResult);
                env->PopLocalFrame(nullptr);
            }
            if (env->ExceptionCheck()) env->ExceptionClear();
        }

        // [ALTREX] The last sampled token is in embd but was never decoded when the turn ends on a
        // stop string, the length limit or Stop: drop it so the next turn's prefix reuse only
        // counts positions that really are in the KV cache.
        if ((int) llama->completion->embd.size() > (int) llama->completion->n_past) {
            llama->completion->embd.resize(llama->completion->n_past);
        }

        snap.take(llama->completion);
        llama->completion->endCompletion();
    } catch (const std::exception& e) {
        LOGW("doCompletion: Caught exception: %s", e.what());
        // [ALTREX] Otherwise the context stays "busy" and every later request fails.
        llama->completion->embd.resize(std::min(llama->completion->embd.size(), (size_t) std::max(0, (int) llama->completion->n_past)));
        snap.take(llama->completion);
        llama->completion->endCompletion();
    } catch (...) {
        LOGW("doCompletion: Caught unknown exception");
        llama->completion->embd.resize(std::min(llama->completion->embd.size(), (size_t) std::max(0, (int) llama->completion->n_past)));
        snap.take(llama->completion);
        llama->completion->endCompletion();
    }

    // [ALTREX] Tell Kotlin why the turn ended (an empty answer because the prompt did not fit the
    // context used to look like a normal, empty reply).
    // Only the snapshot is read here: once endCompletion() ran, freeContext may delete the context.
    auto result = createHashMap(env);
    putBooleanHashMap(env, result, "context_full", snap.context_full);
    putBooleanHashMap(env, result, "stopped_eos", snap.stopped_eos);
    putBooleanHashMap(env, result, "stopped_limit", snap.stopped_limit);
    putBooleanHashMap(env, result, "interrupted", snap.interrupted);
    putIntHashMap(env, result, "draft_tokens", snap.draft_tokens);
    putIntHashMap(env, result, "draft_accepted", snap.draft_accepted);
    return result;
}

JNIEXPORT void JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_stopCompletion(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    rnllama::llama_rn_context* llama = nullptr;
    {
        std::lock_guard<std::mutex> lock(context_mutex);
        auto it = context_map.find((long) context_ptr);
        if (it != context_map.end()) llama = it->second;
    }
    if (llama && llama->completion) llama->completion->is_interrupted = true;
}

JNIEXPORT jboolean JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_isPredicting(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    rnllama::llama_rn_context* llama = nullptr;
    {
        std::lock_guard<std::mutex> lock(context_mutex);
        auto it = context_map.find((long) context_ptr);
        if (it != context_map.end()) llama = it->second;
    }
    if (llama && llama->completion) return llama->completion->is_predicting;
    return false;
}

JNIEXPORT jobject JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_tokenize(
        JNIEnv *env, jobject thiz, jlong context_ptr, jstring text) {
    UNUSED(thiz);
    rnllama::llama_rn_context* llama = nullptr;
    {
        std::lock_guard<std::mutex> lock(context_mutex);
        auto it = context_map.find((long) context_ptr);
        if (it != context_map.end()) llama = it->second;
    }
    if (!llama) return nullptr;

    auto result = llama->tokenize(jstringToUtf8(env, text), {});

    jobject list = createArrayList(env);
    for (const auto &tok : result.tokens) {
        addIntArrayList(env, list, tok);
    }
    return list;
}

JNIEXPORT jstring JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_detokenize(
        JNIEnv *env, jobject thiz, jlong context_ptr, jintArray tokens) {
    UNUSED(thiz);
    rnllama::llama_rn_context* llama = nullptr;
    {
        std::lock_guard<std::mutex> lock(context_mutex);
        auto it = context_map.find((long) context_ptr);
        if (it != context_map.end()) llama = it->second;
    }
    if (!llama) return nullptr;

    jsize tokens_len = env->GetArrayLength(tokens);
    jint *tokens_ptr = env->GetIntArrayElements(tokens, 0);
    std::vector<llama_token> toks;
    for (int i = 0; i < tokens_len; i++) {
        toks.push_back(tokens_ptr[i]);
    }
    env->ReleaseIntArrayElements(tokens, tokens_ptr, 0);

    auto text = rnllama::tokens_to_str(llama->ctx, toks.cbegin(), toks.cend());
    
    jstring jText = utf8ToJString(env, text);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        jText = env->NewStringUTF("");
    }
    return jText;
}

JNIEXPORT jboolean JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_isEmbeddingEnabled(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    rnllama::llama_rn_context* llama = nullptr;
    {
        std::lock_guard<std::mutex> lock(context_mutex);
        auto it = context_map.find((long) context_ptr);
        if (it != context_map.end()) llama = it->second;
    }
    if (!llama) return false;
    return llama->params.embedding;
}

/**
 * [ALTREX] Text embedding for the local memory's search by meaning. Works directly on the llama.cpp
 * context (tokenize, decode, pool, L2-normalize), like llama.cpp's own llama-embedding example, on a
 * context created with embedding = true. Returns null on failure.
 */
JNIEXPORT jfloatArray JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_embedText(
        JNIEnv *env, jobject thiz, jlong context_ptr, jstring text) {
    UNUSED(thiz);
    rnllama::llama_rn_context* llama = nullptr;
    {
        std::lock_guard<std::mutex> lock(context_mutex);
        auto it = context_map.find((long) context_ptr);
        if (it != context_map.end()) llama = it->second;
    }
    if (!llama || !llama->ctx || !llama->model || !llama->params.embedding) return nullptr;
    llama_context *ctx = llama->ctx;
    const llama_model *model = llama->model;

    std::vector<llama_token> tokens = common_tokenize(ctx, jstringToUtf8(env, text), true, false);
    // A non-causal model reads the whole input in one batch.
    const int limit = (int) std::min(llama_n_ctx(ctx), llama_n_ubatch(ctx));
    if (tokens.empty()) return nullptr;
    if ((int) tokens.size() > limit) {
        const llama_token last = tokens.back(); // keep the closing [SEP]/EOS token
        tokens.resize(limit);
        tokens[limit - 1] = last;
    }
    const int n = (int) tokens.size();

    llama_memory_clear(llama_get_memory(ctx), true);
    llama_batch batch = llama_batch_init(n, 0, 1);
    for (int i = 0; i < n; i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = 1;
    }
    batch.n_tokens = n;

    int rc;
    if (llama_model_has_encoder(model) && !llama_model_has_decoder(model)) {
        rc = llama_encode(ctx, batch);
    } else {
        rc = llama_decode(ctx, batch);
    }
    const int n_embd = llama_model_n_embd(model);
    std::vector<float> pooled(n_embd, 0.0f);
    bool ok = rc == 0;
    if (ok) {
        if (llama_pooling_type(ctx) == LLAMA_POOLING_TYPE_NONE) {
            // No pooling in the model: average the token embeddings.
            for (int i = 0; i < n; i++) {
                const float *e = llama_get_embeddings_ith(ctx, i);
                if (!e) { ok = false; break; }
                for (int j = 0; j < n_embd; j++) pooled[j] += e[j] / n;
            }
        } else {
            const float *e = llama_get_embeddings_seq(ctx, 0);
            if (e) std::copy(e, e + n_embd, pooled.begin()); else ok = false;
        }
    }
    llama_batch_free(batch);
    if (!ok) {
        LOGW("embedText failed (rc=%d)", rc);
        return nullptr;
    }
    std::vector<float> out(n_embd);
    common_embd_normalize(pooled.data(), out.data(), n_embd, 2);
    jfloatArray result = env->NewFloatArray(n_embd);
    env->SetFloatArrayRegion(result, 0, n_embd, out.data());
    return result;
}

JNIEXPORT jobject JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_embedding(
        JNIEnv *env, jobject thiz, jlong context_ptr, jstring text) {
    UNUSED(thiz);
    rnllama::llama_rn_context* llama = nullptr;
    {
        std::lock_guard<std::mutex> lock(context_mutex);
        auto it = context_map.find((long) context_ptr);
        if (it != context_map.end()) llama = it->second;
    }
    if (!llama) return nullptr;

    llama->params.prompt = jstringToUtf8(env, text);

    if (llama->completion) {
        auto result = llama->completion->embedding(llama->params);
        jobject list = createArrayList(env);
        for (const auto &val : result) {
            addDoubleArrayList(env, list, (double)val);
        }
        return list;
    }
    return createArrayList(env);
}

JNIEXPORT jstring JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_bench(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr,
        jint pp,
        jint tg,
        jint pl,
        jint nr
) {
    UNUSED(thiz);
    rnllama::llama_rn_context* llama = nullptr;
    {
        std::lock_guard<std::mutex> lock(context_mutex);
        auto it = context_map.find((long) context_ptr);
        if (it != context_map.end()) llama = it->second;
    }
    if (!llama) return nullptr;
    if (llama->completion) {
        std::string result = llama->completion->bench(pp, tg, pl, nr).dump();
        return env->NewStringUTF(result.c_str());
    }
    return env->NewStringUTF("[]");
}

JNIEXPORT void JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    rnllama::llama_rn_context* llama = nullptr;
    {
        std::lock_guard<std::mutex> lock(context_mutex);
        auto it = context_map.find((long) context_ptr);
        if (it != context_map.end()) {
            llama = it->second;
            context_map.erase(it); // Retire de la map de manière sécurisée
        }
    }
    // La suppression réelle (delete) se fait hors du verrou pour ne pas bloquer les autres threads
    if (llama) {
        // [ALTREX] Never delete under a running generation (use-after-free): stop it and wait.
        if (llama->completion) {
            llama->completion->is_interrupted = true;
            for (int i = 0; i < 1500 && llama->completion->is_predicting; i++) {
                usleep(10 * 1000);
            }
            if (llama->completion->is_predicting) {
                LOGW("freeContext: generation still running after 15 s, leaking the context");
                return;
            }
        }
        delete llama;
    }
}

// [ALTREX] Last llama.cpp warnings / errors (most recent last), for the load error message.
JNIEXPORT jstring JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_getLastNativeError(JNIEnv *env, jclass clazz) {
    UNUSED(clazz);
    std::string out;
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        for (const auto &line : g_last_errors) {
            if (!out.empty()) out += "\n";
            out += line;
        }
    }
    return utf8ToJString(env, out);
}

// [ALTREX] Diagnostic options for the next load: KV cache type ("f16", "bf16", "f32") and flash
// attention (-1 auto, 0 off, 1 on).
JNIEXPORT void JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_setLoadOptions(JNIEnv *env, jclass clazz, jstring kv_type, jint flash_attn) {
    UNUSED(clazz);
    const std::string kv = jstringToUtf8(env, kv_type);
    ggml_type type = GGML_TYPE_F16;
    if (kv == "bf16") type = GGML_TYPE_BF16;
    else if (kv == "f32") type = GGML_TYPE_F32;
    llama_flash_attn_type fa = LLAMA_FLASH_ATTN_TYPE_AUTO;
    if (flash_attn == 0) fa = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    else if (flash_attn == 1) fa = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    std::lock_guard<std::mutex> lock(g_load_opts_mutex);
    g_next_kv_type = type;
    g_next_flash_attn = fa;
}

// [ALTREX] Full llama.cpp log of the recent loads and generations (INFO and above).
JNIEXPORT jstring JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_getNativeLog(JNIEnv *env, jclass clazz) {
    UNUSED(clazz);
    std::string out;
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        out = g_full_log;
    }
    return utf8ToJString(env, out);
}

// ============ GET MODEL CONTEXT SIZE ============
JNIEXPORT jint JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_getModelContextSize(
    JNIEnv *env, jclass clazz) {
    UNUSED(env);
    UNUSED(clazz);
    
    // Retourne une valeur par défaut raisonnable
    return 2048;
}

} // extern "C"
