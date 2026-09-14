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
#définir TAG "RNLLAMA_ANDROID_JNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

statique en ligne int min(int a, int b) {
    retourner (a < b) ? a : b;
}

externe "C" {

// Méthode auxiliaire pour créer une HashMap Java
statique en ligne jobject createHashMap(JNIEnv *env) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID init = env->GetMethodID(hashMapClass, "<init>", "()V");
    jobject hashMap = env->NewObject(hashMapClass, init);
    renvoyer hashMap ;
}

// Méthode auxiliaire pour insérer une chaîne de caractères dans une HashMap Java
static inline void putStringHashMap(JNIEnv *env, jobject hashMap, const char *key, const char *value) {
    si (valeur == nullptr) retourner;
    
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    jstring jKey = env->NewStringUTF(clé);
    jstring jValue = env->NewStringUTF(valeur);
    
    si (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGW("putStringHashMap : UTF-8 invalide pour la clé %s", clé);
        jValue = env->NewStringUTF("");
    }

    env->CallObjectMethod(hashMap, putMethod, jKey, jValue);
    
    env->SupprimerLocalRef(jKey);
    si (jValue) env->DeleteLocalRef(jValue);
}

// Méthode auxiliaire pour insérer un entier dans une HashMap Java
static inline void putIntHashMap(JNIEnv *env, jobject hashMap, const char *key, int value) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    jstring jKey = env->NewStringUTF(clé);

    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID integerConstructor = env->GetMethodID(integerClass, "<init>", "(I)V");
    jobject jValue = env->NewObject(integerClass, integerConstructor, value);

    env->CallObjectMethod(hashMap, putMethod, jKey, jValue);
    
    env->SupprimerLocalRef(jKey);
    env->SupprimerLocalRef(integerClass);
    env->SupprimerLocalRef(jValue);
}

// Méthode auxiliaire pour insérer un nombre décimal dans une HashMap Java
void putDoubleHashMap(JNIEnv *env, jobject hashMap, const char *key, double value) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    jstring jKey = env->NewStringUTF(clé);

    jclass doubleClass = env->FindClass("java/lang/Double");
    jmethodID doubleConstructor = env->GetMethodID(doubleClass, "<init>", "(D)V");
    jobject jValue = env->NewObject(doubleClass, doubleConstructor, valeur);

    env->CallObjectMethod(hashMap, putMethod, jKey, jValue);
    
    env->SupprimerLocalRef(jKey);
    env->SupprimerLocalRef(doubleClass);
    env->SupprimerLocalRef(jValue);
}

// Méthode auxiliaire pour insérer un booléen dans une HashMap Java
static inline void putBooleanHashMap(JNIEnv *env, jobject hashMap, const char *key, bool value) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    jstring jKey = env->NewStringUTF(clé);

    jclass booleanClass = env->FindClass("java/lang/Boolean");
    jmethodID booleanConstructor = env->GetMethodID(booleanClass, "<init>", "(Z)V");
    jobject jValue = env->NewObject(booleanClass, booleanConstructor, value);

    env->CallObjectMethod(hashMap, putMethod, jKey, jValue);
    
    env->SupprimerLocalRef(jKey);
    env->SupprimerLocalRef(booleanClass);
    env->SupprimerLocalRef(jValue);
}

// Méthode auxiliaire pour créer une ArrayList Java
statique en ligne jobject createArrayList(JNIEnv *env) {
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID init = env->GetMethodID(arrayListClass, "<init>", "()V");
    jobject arrayList = env->NewObject(arrayListClass, init);
    retourner arrayList;
}

// Méthode auxiliaire pour ajouter un entier à une ArrayList Java
static inline void addIntArrayList(JNIEnv *env, jobject arrayList, int value) {
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID addMethod = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");

    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID integerConstructor = env->GetMethodID(integerClass, "<init>", "(I)V");
    jobject jValue = env->NewObject(integerClass, integerConstructor, value);

    env->CallBooleanMethod(arrayList, addMethod, jValue);
    
    env->SupprimerLocalRef(integerClass);
    env->SupprimerLocalRef(jValue);
}

// Méthode auxiliaire pour ajouter un nombre à virgule flottante double précision à une ArrayList Java
static inline void addDoubleArrayList(JNIEnv *env, jobject arrayList, double value) {
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID addMethod = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");

    jclass doubleClass = env->FindClass("java/lang/Double");
    jmethodID doubleConstructor = env->GetMethodID(doubleClass, "<init>", "(D)V");
    jobject jValue = env->NewObject(doubleClass, doubleConstructor, valeur);

    env->CallBooleanMethod(arrayList, addMethod, jValue);
    
    env->SupprimerLocalRef(doubleClass);
    env->SupprimerLocalRef(jValue);
}

// Méthode auxiliaire pour ajouter une chaîne de caractères à une ArrayList Java
static inline void addStringArrayList(JNIEnv *env, jobject arrayList, const char *value) {
    si (valeur == nullptr) retourner;
    
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID addMethod = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");

    jstring jValue = env->NewStringUTF(valeur);
    si (env->ExceptionCheck()) {
        env->ExceptionClear();
        jValue = env->NewStringUTF("");
    }

    env->CallBooleanMethod(arrayList, addMethod, jValue);
    
    si (jValue) env->DeleteLocalRef(jValue);
}

// Méthode auxiliaire pour ajouter une HashMap à une ArrayList Java
static inline void addHashMapArrayList(JNIEnv *env, jobject arrayList, jobject value) {
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID addMethod = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");

    env->CallBooleanMethod(arrayList, addMethod, value);
}

// Méthode auxiliaire pour insérer une ArrayList Java dans une HashMap Java
static inline void putArrayListHashMap(JNIEnv *env, jobject hashMap, const char *key, jobject value) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    jstring jKey = env->NewStringUTF(clé);

    env->CallObjectMethod(hashMap, putMethod, jKey, value);
    
    env->SupprimerLocalRef(jKey);
}

// Méthode auxiliaire pour insérer un HashMap Java dans un autre HashMap Java
static inline void putHashMapHashMap(JNIEnv *env, jobject hashMap, const char *key, jobject value) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    jstring jKey = env->NewStringUTF(clé);

    env->CallObjectMethod(hashMap, putMethod, jKey, value);
    
    env->SupprimerLocalRef(jKey);
}

std::unordered_map<long, rnllama::llama_rn_context *> context_map;

JNIEXPORT jlong ​​JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_initContextWithFd(
        JNIEnv *environnement,
        jobject ceci,
        jint modèle_fd,
        Intégration de jboolean,
        jint n_ctx,
        jint n_batch,
        jint n_threads,
        jint n_gpu_layers,
        jboolean utiliser_mlock,
        jboolean use_mmap,
        jboolean vocabulaire uniquement,
        jstring lora_str,
        jfloat lora_scaled,
        jfloat corde_freq_base,
        jfloat échelle_fréquence_corde,
        jint mmproj_fd,
        jintArray image_fds
) {
    NON UTILISÉ(ceci);

    paramètres_communs par défaut ;

    defaultParams.vocab_only = vocab_only;
    si (vocab_only) defaultParams.warmup = false;

    si (model_fd < 0) {
        LOGW("Modèle invalide_fd < 0");
        renvoyer 0 ;
    }

    int dupfd = dup(model_fd);
    si (dupfd == -1) {
        LOGW("dup(model_fd=%d) a échoué errno=%d (%s)",
             modèle_fd, errno, strerror(errno));
        renvoyer 0 ;
    }
    fermer(model_fd);

    char fdString[32];
    snprintf(fdString, 32, "%d", dupfd);
    defaultParams.model.path = fdString;

    defaultParams.embedding = embedding;
    defaultParams.n_ctx = n_ctx;
    defaultParams.n_batch = n_batch;

    int max_threads = std::thread::hardware_concurrency();
    int auto_threads = max_threads == 4 ? 2 : std::min(4, max_threads);
    defaultParams.cpuparams.n_threads =
            n_threads > 0 ? n_threads : auto_threads ;

    defaultParams.n_gpu_layers = n_gpu_layers;
    defaultParams.use_mlock = use_mlock;
    defaultParams.use_mmap = use_mmap;

    const char *lora_chars = env->GetStringUTFChars(lora_str, nullptr);
    si (lora_chars && lora_chars[0] != '\0') {
        defaultParams.lora_adapters.push_back({lora_chars, lora_scaled, "", "", nullptr});
    }

    si (mmproj_fd >= 0) {
        int dup_mmproj_fd = dup(mmproj_fd);
        si (dup_mmproj_fd != -1) {
            char mmproj_path[32];
            snprintf(mmproj_path, 32, "%d", dup_mmproj_fd);
            defaultParams.mmproj.path = mmproj_path;
            LOGI("mmproj défini sur FD : %s", defaultParams.mmproj.path.c_str());
        }
        fermer(mmproj_fd);
    }

    defaultParams.rope_freq_base = rope_freq_base;
    defaultParams.rope_freq_scale = rope_freq_scale;

    auto lama = nouveau rnllama::llama_rn_context();
    bool ok = llama->loadModel(defaultParams);

    si (ok) {
        context_map[(long) llama->ctx] = llama;
        si (!defaultParams.mmproj.path.empty()) {
            LOGI("Initialisation multimodale avec mmproj : %s", defaultParams.mmproj.path.c_str());
            bool mm_ok = llama->initMultimodal(defaultParams.mmproj.path, n_gpu_layers > 0);
            LOGI("Résultat de l'initialisation multimodale : %s", mm_ok ? "succès" : "échec");
            LOGI("Vérification de l'activation du contexte multimodal : %s", llama->isMultimodalEnabled() ? "oui" : "non");
        }
    } autre {
        supprimer le lama ;
    }

    env->ReleaseStringUTFChars(lora_str, lora_chars);
    retourner ok ? réinterpréter_cast<jlong>(llama->ctx) : 0 ;
}

JNIEXPORT jobject JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_loadModelDetails(
        JNIEnv *environnement,
        jobject ceci,
        jlong ​​context_ptr
) {
    NON UTILISÉ(ceci);
    auto it = context_map.find((long) context_ptr);
    si (it == context_map.end()) retourner nullptr ;
    auto lama = il->seconde;

    int count = llama_model_meta_count(llama->model);
    auto meta = créerHashMap(env);
    pour (int i = 0; i < count; i++) {
        char clé[256];
        llama_model_meta_key_by_index(llama->model, i, key, sizeof(key));
        char val[2048];
        llama_model_meta_val_str_by_index(llama->model, i, val, sizeof(val));

        putStringHashMap(env, meta, key, val);
    }

    résultat automatique = créerHashMap(env);

    char desc[1024];
    lama_model_desc(llama->model, desc, sizeof(desc));
    putStringHashMap(env, résultat, "desc", desc);
    putDoubleHashMap(env, result, "size", llama_model_size(llama->model));
    putDoubleHashMap(env, result, "nParams", (double)llama_model_n_params(llama->model));
    putBooleanHashMap(env, result, "isChatTemplateSupported", llama->validateModelChatTemplate(true, nullptr));
    putHashMapHashMap(env, résultat, "métadonnées", méta);

    retourner reinterpret_cast<jobject>(result);
}

JNIEXPORT jobject JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_getModelArchitecture(
        JNIEnv *environnement,
        jobject ceci,
        jlong ​​context_ptr
) {
    NON UTILISÉ(ceci);
    auto it = context_map.find((long) context_ptr);
    si (it == context_map.end()) retourner nullptr ;
    auto lama = il->seconde;

    résultat automatique = créerHashMap(env);
    
    // Architecture générale
    putStringHashMap(env, result, "famille", llama_model_family(llama->model));
    putStringHashMap(env, result, "arch", llama_model_arch(llama->model));
    
    // Dimensions principales
    putDoubleHashMap(env, result, "nEmbd", (double)llama_n_embd(llama->model));
    putDoubleHashMap(env, result, "nHeads", (double)llama_n_head(llama->model));
    putDoubleHashMap(env, result, "nHeadsKv", (double)llama_n_head_kv(llama->model));
    putDoubleHashMap(env, résultat, "nCtxTrain", (double)llama_n_ctx_train(llama->model));
    putDoubleHashMap(env, résultat, "nLayer", (double)llama_n_layer(llama->model));
    
    // Hyperparamètres
    putDoubleHashMap(env, result, "fRopeFreqBase", llama_rope_freq_scale_train(llama->model));
    putDoubleHashMap(env, result, "fRopeFreqScale", llama_rope_freq_scale_train(llama->model));
    
    // Capacités
    putBooleanHashMap(env, result, "supportsLogits", llama_supports_logits(llama->model));
    putBooleanHashMap(env, result, "supportsEmbedding", llama_supports_embeddings(llama->model));
    
    retourner reinterpret_cast<jobject>(result);
}

JNIEXPORT jstring JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_getFormattedChat(
        JNIEnv *environnement,
        jobject ceci,
        jlong ​​context_ptr,
        messages jobjectArray,
        jstring chat_template
) {
    NON UTILISÉ(ceci);
    auto it = context_map.find((long) context_ptr);
    si (it == context_map.end()) retourner nullptr ;
    retourner env->NewStringUTF("");
}

JNIEXPORT jobject JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_loadSession(
        JNIEnv *environnement,
        jobject ceci,
        jlong ​​context_ptr,
        chemin jstring
) {
    NON UTILISÉ(ceci);
    auto it = context_map.find((long) context_ptr);
    si (it == context_map.end()) retourner nullptr ;
    auto lama = il->seconde;
    const char *path_chars = env->GetStringUTFChars(path, nullptr);

    résultat automatique = créerHashMap(env);
    taille_t n_token_count_out = 0;
    si (!llama_state_load_file(llama->ctx, path_chars, nullptr, 0, &n_token_count_out)) {
        env->ReleaseStringUTFChars(chemin, chemin_chars);
        putStringHashMap(env, result, "error", "Échec du chargement de la session");
        retourner reinterpret_cast<jobject>(result);
    }
    env->ReleaseStringUTFChars(chemin, chemin_chars);

    putIntHashMap(env, result, "tokens_loaded", (int)n_token_count_out);
    putStringHashMap(env, résultat, "prompt", "");
    retourner reinterpret_cast<jobject>(result);
}

JNIEXPORT jint JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_saveSession(
        JNIEnv *environnement,
        jobject ceci,
        jlong ​​context_ptr,
        chemin jstring,
        taille jint
) {
    NON UTILISÉ(ceci);
    auto it = context_map.find((long) context_ptr);
    si (it == context_map.end()) retourner -1 ;
    auto lama = il->seconde;

    const char *path_chars = env->GetStringUTFChars(path, nullptr);

    si (!llama_state_save_file(llama->ctx, path_chars, nullptr, 0)) {
        env->ReleaseStringUTFChars(chemin, chemin_chars);
        renvoyer -1 ;
    }

    env->ReleaseStringUTFChars(chemin, chemin_chars);
    renvoyer 0 ;
}

JNIEXPORT jobject JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_doCompletion(
        JNIEnv *environnement,
        jobject ceci,
        jlong ​​context_ptr,
        invite jstring,
        grammaire jstring,
        température jfloat,
        jint n_threads,
        jint n_prédiction,
        jint n_probs,
        pénalité jint_dernier_n,
        jfloat pénalité_répétition,
        jfloat pénalité_freq,
        pénalité jfloat présente,
        mirostat jfloat,
        jfloat mirostat_tau,
        jfloat mirostat_eta,
        jboolean pénaliser_nl,
        jint top_k,
        jfloat top_p,
        jfloat min_p,
        jfloat xtc_t,
        jfloat xtc_p,
        jfloat tfs_z,
        jfloat typique_p,
        graine de jint,
        jobjectArray s'arrête,
        jboolean ignore_eos,
        jobjectArray logit_bias,
        jintArray image_fds,
        jobject partialCompletionCallback
) {
    NON UTILISÉ(ceci);
    auto it = context_map.find((long) context_ptr);
    si (it == context_map.end()) retourner nullptr ;
    auto lama = il->seconde;

    si (llama->completion == nullptr) retourner nullptr;

    lama->achèvement->rembobiner();

    const char* prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    llama->params.prompt = prompt_chars;

    llama->params.sampling.seed = (seed == -1) ? time(NULL) : seed;
    llama->params.sampling.temp = température;
    llama->params.sampling.top_k = top_k;
    llama->params.sampling.top_p = top_p;
    llama->params.sampling.min_p = min_p;
    llama->params.n_predict = n_predict;

    lama->achèvement->initSampling();
    
    std::vector<std::string> images;
    si (image_fds != nullptr) {
        jsize len = env->GetArrayLength(image_fds);
        jint *fds = env->GetIntArrayElements(image_fds, nullptr);
        pour (jsize i = 0; i < len; i++) {
            int dup_img_fd = dup(fds[i]);
            si (dup_img_fd != -1) {
                char img_path[32];
                snprintf(img_path, 32, "%d", dup_img_fd);
                images.push_back(chemin_image);
            }
            fermer(fds[i]);
        }
        env->ReleaseIntArrayElements(image_fds, fds, 0);
    }
    
    LOGI("doCompletion: prompt='%s', images=%zu, multimodal_enabled=%s", prompt_chars, images.size(), llama->isMultimodalEnabled() ? "oui" : "non");
    
    essayer {
        lama->completion->loadPrompt(images);
        lama->achèvement->débutAchèvement();

        jclass cb_class = env->GetObjectClass(partialCompletionCallback);
        jmethodID onPartialCompletion = env->GetMethodID(cb_class, "onPartialCompletion", "(Ljava/util/Map;)V");

        taille_t nombre_envoyé = 0 ;
        tant que (llama->completion->has_next_token && !llama->completion->is_interrupted) {
            auto token_output = llama->completion->doCompletion();
            si (token_output.tok == -1) interrompre ;

            si (llama->completion->incomplet) continuer ;

            taille_t pos = std::min(sent_count, llama->completion->generated_text.size());
            std::string à_envoyer = llama->completion->generated_text.substr(pos);
            nombre_envoyés += à_envoyer.taille();

            si (!to_send.empty()) {
                auto tokenResult = createHashMap(env);
                putStringHashMap(env, tokenResult, "token", to_send.c_str());
                env->CallVoidMethod(partialCompletionCallback, onPartialCompletion, tokenResult);
                env->SupprimerLocalRef(tokenResult);
            }
        }
        
        lama->achèvement->finAchèvement();
    } catch (const std::exception& e) {
        LOGW("doCompletion : Exception interceptée : %s", e.what());
    } attraper (...) {
        LOGW("doCompletion : Exception inconnue détectée");
    }

    env->ReleaseStringUTFChars(prompt, prompt_chars);
    renvoie createHashMap(env);
}

JNIEXPORT void JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_stopCompletion(
        JNIEnv *env, jobject thiz, jlong ​​context_ptr) {
    NON UTILISÉ(env);
    NON UTILISÉ(ceci);
    auto it = context_map.find((long) context_ptr);
    si (it == context_map.end()) retourner;
    auto lama = il->seconde;
    si (llama->completion) llama->completion->is_interrupted = true;
}

JNIEXPORT jbooléen JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_isPredicting(
        JNIEnv *env, jobject thiz, jlong ​​context_ptr) {
    NON UTILISÉ(env);
    NON UTILISÉ(ceci);
    auto it = context_map.find((long) context_ptr);
    si (it == context_map.end()) retourner faux ;
    auto lama = il->seconde;
    si (llama->completion) retourner llama->completion->is_predicting ;
    renvoyer faux ;
}

JNIEXPORT jobject JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_tokenize(
        JNIEnv *env, jobject thiz, jlong ​​context_ptr, jstring text) {
    NON UTILISÉ(ceci);
    auto it = context_map.find((long) context_ptr);
    si (it == context_map.end()) retourner nullptr ;
    auto lama = il->seconde;

    const char *text_chars = env->GetStringUTFChars(text, nullptr);
    résultat automatique = llama->tokenize(text_chars, {});
    env->ReleaseStringUTFChars(texte, text_chars);

    jobject liste = créerArrayList(env);
    pour (const auto &tok : result.tokens) {
        ajouterIntArrayList(env, liste, tok);
    }
    renvoyer la liste ;
}

JNIEXPORT jstring JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_detokenize(
        JNIEnv *env, jobject thiz, jlong ​​context_ptr, jintArray tokens) {
    NON UTILISÉ(ceci);
    auto it = context_map.find((long) context_ptr);
    si (it == context_map.end()) retourner nullptr ;
    auto lama = il->seconde;

    jsize tokens_len = env->GetArrayLength(tokens);
    jint *tokens_ptr = env->GetIntArrayElements(tokens, 0);
    jetons std::vector<llama_token> ;
    pour (int i = 0; i < tokens_len; i++) {
        toks.push_back(tokens_ptr[i]);
    }
    env->ReleaseIntArrayElements(tokens, tokens_ptr, 0);

    auto text = rnllama::tokens_to_str(llama->ctx, toks.cbegin(), toks.cend());
    
    jstring jText = env->NewStringUTF(text.c_str());
    si (env->ExceptionCheck()) {
        env->ExceptionClear();
        jText = env->NewStringUTF("");
    }
    renvoyer jText ;
}

JNIEXPORT jbooléen JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_isEmbeddingEnabled(
        JNIEnv *env, jobject thiz, jlong ​​context_ptr) {
    NON UTILISÉ(env);
    NON UTILISÉ(ceci);
    auto it = context_map.find((long) context_ptr);
    si (it == context_map.end()) retourner faux ;
    auto lama = il->seconde;
    retourner llama->params.embedding;
}

JNIEXPORT jobject JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_embedding(
        JNIEnv *env, jobject thiz, jlong ​​context_ptr, jstring text) {
    NON UTILISÉ(ceci);
    auto it = context_map.find((long) context_ptr);
    si (it == context_map.end()) retourner nullptr ;
    auto lama = il->seconde;

    const char *text_chars = env->GetStringUTFChars(text, nullptr);
    llama->params.prompt = text_chars;

    si (llama->completion) {
        résultat automatique = llama->completion->embedding(llama->params);
        env->ReleaseStringUTFChars(texte, text_chars);
        jobject liste = créerArrayList(env);
        pour (const auto &val : résultat) {
            ajouterDoubleArrayList(env, liste, (double)val);
        }
        renvoyer la liste ;
    }
    env->ReleaseStringUTFChars(texte, text_chars);
    renvoie createArrayList(env);
}

JNIEXPORT jstring JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_bench(
        JNIEnv *environnement,
        jobject ceci,
        jlong ​​context_ptr,
        jint pp,
        jint tg,
        jint pl,
        jint nr
) {
    NON UTILISÉ(ceci);
    auto it = context_map.find((long) context_ptr);
    si (it == context_map.end()) retourner nullptr ;
    auto lama = il->seconde;
    si (llama->completion) {
        std::string résultat = llama->completion->bench(pp, tg, pl, nr);
        retourner env->NewStringUTF(result.c_str());
    }
    retourner env->NewStringUTF("[]");
}

JNIEXPORT void JNICALL
Java_org_nehuatl_llamacpp_LlamaContext_freeContext(
        JNIEnv *env, jobject thiz, jlong ​​context_ptr) {
    NON UTILISÉ(env);
    NON UTILISÉ(ceci);
    auto it = context_map.find((long) context_ptr);
    si (it == context_map.end()) retourner;
    auto lama = il->seconde;
    context_map.erase((long) llama->ctx);
    supprimer le lama ;
}

} // extern "C"