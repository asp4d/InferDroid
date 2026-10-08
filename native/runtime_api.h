#ifndef INFERDROID_RUNTIME_API_H
#define INFERDROID_RUNTIME_API_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// InferDroid's C boundary, not a LiteRT-LM upstream API. All strings are UTF-8.
// The returned memory belongs to this runtime; release with inferdroid_result_free.
typedef struct {
    int success;
    int cancelled;
    uint64_t engine_handle;  // Nonzero only for a successful load.
    char* text;
    size_t text_size;
    char* diagnostics;
    size_t diagnostics_size;
} InferDroidResult;

__attribute__((visibility("default")))
InferDroidResult* inferdroid_engine_load(int borrowed_model_fd, const char* model_path,
                                       const char* native_library_dir,
                                       const char* cache_dir, int verbose);

__attribute__((visibility("default")))
InferDroidResult* inferdroid_engine_generate(uint64_t engine_handle,
                                           uint64_t request_id,
                                           const char* prompt, int verbose);

// Cancellation can run concurrently with generation. Request IDs prevent a
// delayed cancellation from affecting a subsequent request on the same engine.
__attribute__((visibility("default")))
void inferdroid_engine_cancel(uint64_t engine_handle, uint64_t request_id);

__attribute__((visibility("default")))
void inferdroid_engine_unload(uint64_t engine_handle);

__attribute__((visibility("default")))
void inferdroid_result_free(InferDroidResult* result);

#ifdef __cplusplus
}
#endif
#endif
