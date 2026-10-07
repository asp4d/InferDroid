#ifndef INFERDROID_RUNTIME_API_H
#define INFERDROID_RUNTIME_API_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// InferDroid's C boundary, not a LiteRT-LM upstream API. All strings are UTF-8.
// The returned memory belongs to this runtime; release with inferdroid_result_free.
typedef struct {
    int success;
    char* text;
    size_t text_size;
    char* diagnostics;
    size_t diagnostics_size;
} InferDroidResult;

__attribute__((visibility("default")))
InferDroidResult* inferdroid_generate(int borrowed_model_fd, const char* model_path,
                                    const char* native_library_dir,
                                    const char* cache_dir, const char* prompt,
                                    int verbose);

__attribute__((visibility("default")))
void inferdroid_result_free(InferDroidResult* result);

#ifdef __cplusplus
}
#endif
#endif
