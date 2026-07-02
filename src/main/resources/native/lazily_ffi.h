/*
 * lazily-kt — C-ABI FFI boundary (host).
 *
 * The lazily-spec C-ABI FFI boundary (`lazily-spec/protocol.md` § FFI Boundary,
 * `lazily-spec/schemas/ffi.json`). lazily-kt's platform CAN host a native
 * in-process boundary, so it declares the `ffi = host` capability and exports
 * these symbols (via a Graal native-image build of the artifact or the JNI shim).
 *
 * Contract:
 *   - The caller owns input bytes; lazily-kt owns output buffers until the
 *     paired lazily_ffi_free() is called with them.
 *   - Errors return a LazilyFfiStatus; panics are caught before crossing the
 *     C ABI (LazilyFfiStatus_Panic).
 *   - lazily_ffi_decode() decodes each accepted frame as an IpcMessage and
 *     re-encodes canonical JSON bytes.
 *   - The message-kind discriminant includes CrdtSync = 3.
 */
#ifndef LAZILY_FFI_H
#define LAZILY_FFI_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    uint8_t *ptr;
    size_t   len;
} LazilyFfiBytes;

typedef enum {
    LazilyFfiStatus_Ok             = 0,
    LazilyFfiStatus_Empty          = 1,
    LazilyFfiStatus_NullPointer    = 2,
    LazilyFfiStatus_InvalidMessage = 3,
    LazilyFfiStatus_EncodeFailed   = 4,
    LazilyFfiStatus_Panic          = 5,
} LazilyFfiStatus;

typedef enum {
    LazilyFfiMessageKind_Unknown  = 0,
    LazilyFfiMessageKind_Snapshot = 1,
    LazilyFfiMessageKind_Delta    = 2,
    LazilyFfiMessageKind_CrdtSync = 3,
} LazilyFfiMessageKind;

/* Encode a canonical-JSON IpcMessage frame. On success *out holds a buffer the
 * caller MUST free with lazily_ffi_free(). */
LazilyFfiStatus lazily_ffi_encode(const char *json_in,
                                  size_t json_in_len,
                                  LazilyFfiBytes *out);

/* Decode a kind-claimed frame as IpcMessage and re-encode canonical JSON bytes.
 * On success *out holds a buffer the caller MUST free with lazily_ffi_free(). */
LazilyFfiStatus lazily_ffi_decode(LazilyFfiMessageKind kind,
                                  const uint8_t *bytes_in,
                                  size_t bytes_in_len,
                                  LazilyFfiBytes *out);

/* Free a buffer returned by lazily_ffi_encode / lazily_ffi_decode. */
void lazily_ffi_free(LazilyFfiBytes *buf);

#ifdef __cplusplus
}
#endif

#endif /* LAZILY_FFI_H */
