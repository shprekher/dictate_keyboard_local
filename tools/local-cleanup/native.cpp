// Copyright 2026. SPDX-License-Identifier: Apache-2.0
#include <jni.h>
#include "llama.h"
#include <sys/stat.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
struct Request {
    std::atomic<bool> cancelled{false};
    Clock::time_point deadline = Clock::time_point::max();
};
std::mutex requests_mutex;
std::unordered_map<jlong, std::shared_ptr<Request>> requests;
std::atomic<jlong> next_id{1};
std::once_flag backend_once;

constexpr int CONTEXT_TOKENS = 4096;
constexpr int MAX_NEW_TOKENS = 1536;

// Only the fixed instruction prefix survives a request. No transcript or generated tokens are cached.
struct Resident {
    std::string path;
    long long size = 0;
    long long mtime_ns = 0;
    int threads = 0;
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::vector<llama_token> prefix_tokens;
    void reset() {
        if (ctx) { llama_free(ctx); ctx = nullptr; }
        if (model) { llama_model_free(model); model = nullptr; }
        path.clear();
        size = 0;
        mtime_ns = 0;
        threads = 0;
        prefix_tokens.clear();
    }
};
std::mutex model_mutex;
Resident resident;
jlong last_metrics[9] = {};

jlong elapsed_ms(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
}

bool aborted(void *data);
void check(const std::shared_ptr<Request> &r);

bool file_stamp(const std::string &path, long long &size, long long &mtime_ns) {
    struct stat st;
    if (stat(path.c_str(), &st) != 0) return false;
    size = static_cast<long long>(st.st_size);
#ifdef __APPLE__
    mtime_ns = static_cast<long long>(st.st_mtimespec.tv_sec) * 1000000000LL + st.st_mtimespec.tv_nsec;
#else
    mtime_ns = static_cast<long long>(st.st_mtim.tv_sec) * 1000000000LL + st.st_mtim.tv_nsec;
#endif
    return true;
}

// Caller must hold model_mutex. Returns true when the weights had to be (re)loaded, i.e. a cold start.
bool ensure_model_locked(const std::string &path, int threads, const std::shared_ptr<Request> &r) {
    long long size = 0;
    long long mtime_ns = 0;
    if (!file_stamp(path, size, mtime_ns)) throw std::runtime_error("Local cleanup model is missing");
    const int clamped = std::clamp(static_cast<int>(threads), 1, 8);
    if (resident.model && resident.ctx && resident.path == path && resident.size == size &&
        resident.mtime_ns == mtime_ns && resident.threads == clamped) {
        return false;
    }
    resident.reset();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    if (r) {
        mp.progress_callback = [](float, void *data) { return !aborted(data); };
        mp.progress_callback_user_data = r.get();
    }
    resident.model = llama_model_load_from_file(path.c_str(), mp);
    if (r) check(r);
    if (!resident.model) {
        resident.reset();
        throw std::runtime_error("Could not load the local cleanup model");
    }
    auto cp = llama_context_default_params();
    cp.n_ctx = CONTEXT_TOKENS;
    cp.n_batch = 512;
    cp.n_ubatch = 128;
    cp.n_threads = cp.n_threads_batch = clamped;
    cp.offload_kqv = false;
    cp.op_offload = false;
    resident.ctx = llama_init_from_model(resident.model, cp);
    if (r) check(r);
    if (!resident.ctx) {
        resident.reset();
        throw std::runtime_error("Could not allocate local cleanup context");
    }
    resident.path = path;
    resident.size = size;
    resident.mtime_ns = mtime_ns;
    resident.threads = clamped;
    return true;
}

bool aborted(void *data) {
    auto *r = static_cast<Request *>(data);
    return r && (r->cancelled.load() || Clock::now() >= r->deadline);
}
// The resident context must not retain a pointer to a completed/cancelled request.
struct DecodeAbortScope {
    llama_context *ctx;
    DecodeAbortScope(llama_context *context, Request *r) : ctx(context) {
        llama_set_abort_callback(ctx, aborted, r);
    }
    ~DecodeAbortScope() { llama_set_abort_callback(ctx, nullptr, nullptr); }
};
void check(const std::shared_ptr<Request> &r) {
    if (!r) return;
    if (r->cancelled.load()) throw std::runtime_error("cancelled");
    if (Clock::now() >= r->deadline) throw std::runtime_error("timeout");
}
void fail(JNIEnv *env, const char *type, const char *message) {
    if (env->ExceptionCheck()) return;
    const auto cls = env->FindClass(type);
    if (cls) env->ThrowNew(cls, message);
}

std::string read_bytes(JNIEnv *env, jbyteArray bytes, int limit) {
    if (!bytes) throw std::runtime_error("Missing cleanup prompt bytes");
    const auto size = env->GetArrayLength(bytes);
    if (size > limit) throw std::runtime_error("Cleanup input is too long");
    std::string text(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte *>(text.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Could not read cleanup prompt bytes");
    return text;
}

std::vector<llama_token> tokenize_locked(const std::string &text) {
    const auto *vocab = llama_model_get_vocab(resident.model);
    int n = llama_tokenize(vocab, text.data(), text.size(), nullptr, 0, true, true);
    if (n >= 0) throw std::runtime_error("Could not tokenize cleanup input");
    std::vector<llama_token> tokens(static_cast<size_t>(-n));
    n = llama_tokenize(vocab, text.data(), text.size(), tokens.data(), tokens.size(), true, true);
    if (n <= 0) throw std::runtime_error("Could not tokenize cleanup input");
    tokens.resize(n);
    return tokens;
}

std::vector<llama_token> fixed_tokens_locked(const std::string &prefix) {
    if (prefix.empty()) return {};
    auto tokens = tokenize_locked(prefix);
    // Leave the boundary token for the request: BPE can merge it with the first input character.
    tokens.pop_back();
    if (tokens.size() + MAX_NEW_TOKENS >= CONTEXT_TOKENS) {
        throw std::runtime_error("Cleanup instruction prefix exceeds the context limit");
    }
    return tokens;
}

void clear_prefix_locked() {
    resident.prefix_tokens.clear();
    llama_memory_clear(llama_get_memory(resident.ctx), false);
}

void purge_request_locked() {
    llama_synchronize(resident.ctx);
    auto memory = llama_get_memory(resident.ctx);
    if (resident.prefix_tokens.empty() ||
        !llama_memory_seq_rm(memory, 0, resident.prefix_tokens.size(), -1)) {
        clear_prefix_locked();
    }
}

struct RequestMemoryScope {
    ~RequestMemoryScope() { purge_request_locked(); }
};

void evaluate_locked(std::vector<llama_token> &tokens, int offset, const std::shared_ptr<Request> &r) {
    for (; offset < static_cast<int>(tokens.size()); offset += 512) {
        check(r);
        auto batch = llama_batch_get_one(tokens.data() + offset, std::min<int>(512, tokens.size() - offset));
        const int rc = llama_decode(resident.ctx, batch);
        llama_synchronize(resident.ctx);
        check(r);
        if (rc != 0) throw std::runtime_error("Local cleanup prompt evaluation failed");
    }
}

// The prefix is published only after its entire evaluation succeeds.
int prepare_prefix_locked(std::vector<llama_token> &tokens, const std::shared_ptr<Request> &r) {
    purge_request_locked();
    const auto memory = llama_get_memory(resident.ctx);
    if (resident.prefix_tokens == tokens &&
        llama_memory_seq_pos_max(memory, 0) + 1 == static_cast<llama_pos>(tokens.size())) {
        return tokens.size();
    }
    clear_prefix_locked();
    try {
        evaluate_locked(tokens, 0, r);
        resident.prefix_tokens = tokens;
    } catch (...) {
        clear_prefix_locked();
        throw;
    }
    return 0;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_patrickgold_florisboard_dictate_cleanup_LocalCleanupNative_createRequest(JNIEnv *env, jobject, jlong timeout_ms) {
    try {
        auto r = std::make_shared<Request>();
        if (timeout_ms > 0) r->deadline = Clock::now() + std::chrono::milliseconds(timeout_ms);
        const jlong id = next_id.fetch_add(1);
        std::lock_guard<std::mutex> lock(requests_mutex);
        requests.emplace(id, r);
        return id;
    } catch (const std::exception &e) {
        fail(env, "java/lang/IllegalStateException", e.what());
        return 0;
    } catch (...) {
        fail(env, "java/lang/IllegalStateException", "Could not create cleanup request");
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_patrickgold_florisboard_dictate_cleanup_LocalCleanupNative_cancelRequest(JNIEnv *, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(requests_mutex);
    auto it = requests.find(id);
    if (it != requests.end()) it->second->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_patrickgold_florisboard_dictate_cleanup_LocalCleanupNative_releaseRequest(JNIEnv *, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(requests_mutex);
    requests.erase(id);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_patrickgold_florisboard_dictate_cleanup_LocalCleanupNative_warm(JNIEnv *env, jobject, jstring path,
                                                                         jbyteArray prefix_bytes, jint threads) {
    try {
        std::call_once(backend_once, [] {
            llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
            llama_backend_init();
        });
        const char *chars = env->GetStringUTFChars(path, nullptr);
        if (!chars) return JNI_FALSE;
        const std::string filename(chars);
        env->ReleaseStringUTFChars(path, chars);
        const std::string prefix = read_bytes(env, prefix_bytes, 16000);
        std::lock_guard<std::mutex> lock(model_mutex);
        std::fill_n(last_metrics, 9, 0);
        auto started = Clock::now();
        const bool cold = ensure_model_locked(filename, threads, nullptr);
        last_metrics[0] = elapsed_ms(started);
        DecodeAbortScope abort_scope(resident.ctx, nullptr);
        RequestMemoryScope memory_scope;
        auto tokens = fixed_tokens_locked(prefix);
        started = Clock::now();
        last_metrics[5] = prepare_prefix_locked(tokens, nullptr);
        last_metrics[1] = elapsed_ms(started);
        last_metrics[3] = tokens.size();
        last_metrics[6] = tokens.size() - last_metrics[5];
        return cold ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception &e) {
        fail(env, "java/lang/IllegalStateException", e.what());
        return JNI_FALSE;
    } catch (...) {
        fail(env, "java/lang/IllegalStateException", "Local cleanup warm-up failed");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_patrickgold_florisboard_dictate_cleanup_LocalCleanupNative_unload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(model_mutex);
    resident.reset();
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_patrickgold_florisboard_dictate_cleanup_LocalCleanupNative_performance(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(model_mutex);
    last_metrics[7] = resident.prefix_tokens.size();
    last_metrics[8] = resident.ctx ? llama_memory_seq_pos_max(llama_get_memory(resident.ctx), 0) + 1 : 0;
    auto result = env->NewLongArray(9);
    if (result) env->SetLongArrayRegion(result, 0, 9, last_metrics);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_patrickgold_florisboard_dictate_cleanup_LocalCleanupNative_complete(
        JNIEnv *env, jobject, jlong id, jstring path, jbyteArray input, jbyteArray prefix_bytes, jint threads) {
    std::shared_ptr<Request> r;
    {
        std::lock_guard<std::mutex> lock(requests_mutex);
        auto it = requests.find(id);
        if (it != requests.end()) r = it->second;
    }
    if (!r) { fail(env, "java/lang/IllegalStateException", "Unknown cleanup request"); return nullptr; }
    try {
        check(r);
        std::call_once(backend_once, [] {
            // Model metadata is not needed in the Android log; never log prompts or transcripts.
            llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
            llama_backend_init();
        });
        const char *chars = env->GetStringUTFChars(path, nullptr);
        if (!chars) return nullptr;
        std::string filename(chars);
        env->ReleaseStringUTFChars(path, chars);
        const std::string prompt = read_bytes(env, input, 64000);
        const std::string prefix = read_bytes(env, prefix_bytes, 16000);

        std::lock_guard<std::mutex> lock(model_mutex);
        std::fill_n(last_metrics, 9, 0);
        auto phase_start = Clock::now();
        ensure_model_locked(filename, threads, r);
        last_metrics[0] = elapsed_ms(phase_start);
        llama_context *ctx = resident.ctx;
        DecodeAbortScope abort_scope(ctx, r.get());
        RequestMemoryScope memory_scope;
        if (prompt.compare(0, prefix.size(), prefix) != 0) {
            throw std::runtime_error("Fixed cleanup prefix does not match the prompt");
        }
        const auto *vocab = llama_model_get_vocab(resident.model);
        auto tokens = tokenize_locked(prompt);
        const int n = tokens.size();
        if (n + MAX_NEW_TOKENS > CONTEXT_TOKENS) throw std::runtime_error("Cleanup input exceeds the local context limit; keep the original transcript");
        auto fixed = fixed_tokens_locked(prefix);
        // Verify the actual full-prompt tokenization. Never reuse input-dependent boundary tokens.
        size_t shared = 0;
        while (shared < fixed.size() && shared + 1 < tokens.size() && fixed[shared] == tokens[shared]) ++shared;
        fixed.resize(shared);

        phase_start = Clock::now();
        last_metrics[5] = prepare_prefix_locked(fixed, r);
        evaluate_locked(tokens, fixed.size(), r);
        last_metrics[1] = elapsed_ms(phase_start);
        last_metrics[3] = n;
        last_metrics[6] = n - last_metrics[5];
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        std::string output;
        bool finished = false;
        phase_start = Clock::now();
        for (int i = 0; i < MAX_NEW_TOKENS; ++i) {
            check(r);
            llama_token token = llama_sampler_sample(sampler.get(), ctx, -1);
            if (llama_vocab_is_eog(vocab, token)) { finished = true; break; }
            last_metrics[4]++;
            char piece[256];
            const int size = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            if (size < 0) throw std::runtime_error("Invalid local cleanup token");
            output.append(piece, size);
            auto batch = llama_batch_get_one(&token, 1);
            const int rc = llama_decode(ctx, batch);
            check(r);
            if (rc != 0) throw std::runtime_error("Local cleanup decoding failed");
        }
        last_metrics[2] = elapsed_ms(phase_start);
        if (!finished) throw std::runtime_error("Local cleanup output limit reached; keep the original transcript");
        auto result = env->NewByteArray(output.size());
        if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::exception &e) {
        const std::string message(e.what());
        const char *type = message == "cancelled" ? "java/util/concurrent/CancellationException" :
                           message == "timeout" ? "java/util/concurrent/TimeoutException" : "java/lang/IllegalStateException";
        fail(env, type, e.what());
        return nullptr;
    } catch (...) {
        fail(env, "java/lang/IllegalStateException", "Local cleanup failed");
        return nullptr;
    }
}
