// Copyright 2026 Zero-Assist Community
//
// Licensed under the MIT License. See LICENSE in the project root.
//
// Vendored from https://huggingface.co/Cactus-Compute/needle2 (android-arm64/needle.h).
// Upstream license: Apache-2.0 (Cactus Compute). Do not modify; re-vendor on engine updates.

#ifndef NEEDLE_H
#define NEEDLE_H

#ifndef NEEDLE_API
#define NEEDLE_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

NEEDLE_API int needle_init(
    const char* system_prompt,
    const char* tools_json,
    const char* tool_index_path
);

NEEDLE_API int needle_complete(
    const char* input,
    int max_new_tokens,
    char* out,
    int out_capacity
);

NEEDLE_API void needle_reset(void);

NEEDLE_API int needle_load(
    const unsigned char* cact,
    unsigned long long n
);

#ifdef __cplusplus
}
#endif
#endif
