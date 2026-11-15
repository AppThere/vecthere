/* C ABI of loki-ffi (ffi/src/lib.rs), the same six functions in every Craft front end of this kind.
 * Used by Kotlin/Native cinterop (iOS) and documents the symbols JNA binds on JVM/Android. Keep in step with lib.rs. */
#ifndef LOKI_H
#define LOKI_H
#include <stddef.h>
#include <stdint.h>

typedef struct LokiSession LokiSession;

LokiSession *loki_session_new(void);
void loki_session_free(LokiSession *s);

/* Returns {"ok":true,"result":…} or {"ok":false,"error":"…"}; free with loki_string_free. */
char *loki_call(LokiSession *s, const char *method, const char *params_json);
void loki_string_free(char *p);

/* Straight-alpha RGBA8, width*height*4 bytes; free with loki_buffer_free. NULL on failure. */
uint8_t *loki_render_page(LokiSession *s, uint32_t page, float scale, uint32_t *width, uint32_t *height, uint32_t *pages);
void loki_buffer_free(uint8_t *p, size_t len);
#endif
