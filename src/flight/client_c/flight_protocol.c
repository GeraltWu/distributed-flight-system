/*
 * flight_protocol.c
 *
 * 手写的网络字节序编码/解码，只按偏移读写字节数组，不使用任何序列化库。
 */
#include "flight_protocol.h"

#include <stdarg.h>
#include <stdio.h>
#include <string.h>

/* 保证 float 是 4 字节，才能直接搬运 IEEE 754 位模式（对应 Float.floatToRawIntBits）。 */
typedef char fp_assert_float_is_32_bits[(sizeof(float) == 4) ? 1 : -1];

/* ------------------------------------------------------------------ */
/* 错误文字                                                            */
/* ------------------------------------------------------------------ */
static void set_error(char *err, size_t cap, const char *fmt, ...) {
    va_list args;
    if (err == NULL || cap == 0) {
        return;
    }
    va_start(args, fmt);
    vsnprintf(err, cap, fmt, args);
    va_end(args);
}

/* ------------------------------------------------------------------ */
/* 大端字节序读写（用移位实现，不依赖 htonl/ntohl）                   */
/* ------------------------------------------------------------------ */
static uint16_t get_u16(const uint8_t *p) {
    return (uint16_t)(((unsigned)p[0] << 8) | p[1]);
}

static uint32_t get_u32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) |
           (uint32_t)p[3];
}

static uint64_t get_u64(const uint8_t *p) {
    return ((uint64_t)get_u32(p) << 32) | get_u32(p + 4);
}

static void put_u16(uint8_t *p, uint16_t v) {
    p[0] = (uint8_t)(v >> 8);
    p[1] = (uint8_t)v;
}

static void put_u32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)v;
}

static void put_u64(uint8_t *p, uint64_t v) {
    put_u32(p, (uint32_t)(v >> 32));
    put_u32(p + 4, (uint32_t)v);
}

/* ------------------------------------------------------------------ */
/* 严格的 UTF-8 校验（拒绝过长编码、代理区、超过 U+10FFFF 的码点）    */
/* 对应 Java 里 CodingErrorAction.REPORT 的行为                        */
/* ------------------------------------------------------------------ */
static int utf8_is_valid(const uint8_t *s, size_t n) {
    size_t i = 0;
    while (i < n) {
        uint8_t c = s[i];
        size_t extra;
        uint32_t cp;
        size_t k;

        if (c < 0x80) {
            i++;
            continue;
        }
        if (c >= 0xC2 && c <= 0xDF) {
            extra = 1;
            cp = c & 0x1Fu;
        } else if ((c & 0xF0) == 0xE0) {
            extra = 2;
            cp = c & 0x0Fu;
        } else if (c >= 0xF0 && c <= 0xF4) {
            extra = 3;
            cp = c & 0x07u;
        } else {
            return 0;
        }
        if (n - i <= extra) {
            return 0; /* 末尾被截断 */
        }
        for (k = 1; k <= extra; k++) {
            if ((s[i + k] & 0xC0) != 0x80) {
                return 0;
            }
            cp = (cp << 6) | (s[i + k] & 0x3Fu);
        }
        if (extra == 2 && (cp < 0x800 || (cp >= 0xD800 && cp <= 0xDFFF))) {
            return 0;
        }
        if (extra == 3 && (cp < 0x10000 || cp > 0x10FFFF)) {
            return 0;
        }
        i += extra + 1;
    }
    return 1;
}

/* ------------------------------------------------------------------ */
/* Message                                                             */
/* ------------------------------------------------------------------ */
static int known_message_type(int t) {
    return t == FP_MSG_REQUEST || t == FP_MSG_REPLY || t == FP_MSG_EVENT ||
           t == FP_MSG_ACK;
}

/* 对应 Java Message 构造函数里的检查。 */
static int validate_fields(int message_type, int operation, uint64_t request_id,
                           size_t body_length, char *err, size_t errcap) {
    if (!known_message_type(message_type)) {
        set_error(err, errcap, "Unknown message type: %d", message_type);
        return -1;
    }
    if (operation < 0 || operation > 0xff) {
        set_error(err, errcap, "Operation must fit in u8: %d", operation);
        return -1;
    }
    if (message_type == FP_MSG_EVENT && operation != FP_OP_MONITOR) {
        set_error(err, errcap, "Event messages must use the MONITOR operation");
        return -1;
    }
    if (request_id == 0) {
        set_error(err, errcap, "requestId must be a non-zero u64 value");
        return -1;
    }
    if (body_length > FP_MAX_BODY_LENGTH) {
        set_error(err, errcap, "Body exceeds %d bytes: %zu", FP_MAX_BODY_LENGTH,
                  body_length);
        return -1;
    }
    return 0;
}

int fp_make_request(FpMessage *out, int operation, uint64_t client_id,
                    uint64_t request_id, const uint8_t *body, size_t body_length,
                    char *err, size_t errcap) {
    if (validate_fields(FP_MSG_REQUEST, operation, request_id, body_length, err,
                        errcap) != 0) {
        return -1;
    }
    out->message_type = FP_MSG_REQUEST;
    out->operation = (uint8_t)operation;
    out->client_id = client_id;
    out->request_id = request_id;
    out->body_length = (uint16_t)body_length;
    if (body_length > 0) {
        memcpy(out->body, body, body_length);
    }
    return 0;
}

int fp_make_ack(FpMessage *out, const FpMessage *reply, char *err, size_t errcap) {
    if (reply->message_type != FP_MSG_REPLY) {
        set_error(err, errcap, "An acknowledgement requires a reply");
        return -1;
    }
    out->message_type = FP_MSG_ACK;
    out->operation = reply->operation;
    out->client_id = reply->client_id;
    out->request_id = reply->request_id;
    out->body_length = 0;
    return 0;
}

size_t fp_encode(const FpMessage *m, uint8_t *out) {
    out[FP_MAGIC_OFFSET] = FP_MAGIC_FIRST;
    out[FP_MAGIC_OFFSET + 1] = FP_MAGIC_SECOND;
    out[FP_VERSION_OFFSET] = FP_VERSION;
    out[FP_MESSAGE_TYPE_OFFSET] = m->message_type;
    out[FP_OPERATION_OFFSET] = m->operation;
    put_u64(out + FP_CLIENT_ID_OFFSET, m->client_id);
    put_u64(out + FP_REQUEST_ID_OFFSET, m->request_id);
    put_u16(out + FP_BODY_LENGTH_OFFSET, m->body_length);
    if (m->body_length > 0) {
        memcpy(out + FP_BODY_OFFSET, m->body, m->body_length);
    }
    return (size_t)FP_HEADER_LENGTH + m->body_length;
}

int fp_decode(const uint8_t *d, size_t length, FpMessage *out, char *err,
              size_t errcap) {
    int message_type, operation;
    uint64_t client_id, request_id;
    size_t body_length;

    /* 先检查外层长度，之后读固定头字段才不会越界。 */
    if (length < FP_HEADER_LENGTH) {
        set_error(err, errcap, "Datagram is shorter than the %d-byte header",
                  FP_HEADER_LENGTH);
        return -1;
    }
    if (length > FP_MAX_DATAGRAM_LENGTH) {
        set_error(err, errcap, "Datagram exceeds %d bytes: %zu",
                  FP_MAX_DATAGRAM_LENGTH, length);
        return -1;
    }
    if (d[FP_MAGIC_OFFSET] != FP_MAGIC_FIRST ||
        d[FP_MAGIC_OFFSET + 1] != FP_MAGIC_SECOND) {
        set_error(err, errcap, "Invalid protocol magic");
        return -1;
    }
    if (d[FP_VERSION_OFFSET] != FP_VERSION) {
        set_error(err, errcap, "Unsupported protocol version: %d",
                  d[FP_VERSION_OFFSET]);
        return -1;
    }
    message_type = d[FP_MESSAGE_TYPE_OFFSET];
    if (!known_message_type(message_type)) {
        set_error(err, errcap, "Unknown message type: %d", message_type);
        return -1;
    }
    operation = d[FP_OPERATION_OFFSET];
    if (message_type == FP_MSG_EVENT && operation != FP_OP_MONITOR) {
        set_error(err, errcap, "Event messages must use the MONITOR operation");
        return -1;
    }
    client_id = get_u64(d + FP_CLIENT_ID_OFFSET);
    request_id = get_u64(d + FP_REQUEST_ID_OFFSET);
    if (request_id == 0) {
        set_error(err, errcap, "requestId must be non-zero");
        return -1;
    }
    body_length = get_u16(d + FP_BODY_LENGTH_OFFSET);
    if (body_length > FP_MAX_BODY_LENGTH) {
        set_error(err, errcap, "Body is too large: %zu", body_length);
        return -1;
    }
    /* 必须恰好相等：同时拒绝被截断的消息体和消息体后多余的字节。 */
    if (length != (size_t)FP_HEADER_LENGTH + body_length) {
        set_error(err, errcap,
                  "Datagram length does not match bodyLength: length=%zu, bodyLength=%zu",
                  length, body_length);
        return -1;
    }
    if (message_type == FP_MSG_ACK && body_length != 0) {
        set_error(err, errcap, "Acknowledgement body must be empty");
        return -1;
    }

    out->message_type = (uint8_t)message_type;
    out->operation = (uint8_t)operation;
    out->client_id = client_id;
    out->request_id = request_id;
    out->body_length = (uint16_t)body_length;
    if (body_length > 0) {
        memcpy(out->body, d + FP_BODY_OFFSET, body_length);
    }
    return 0;
}

const char *fp_operation_name(int operation) {
    switch (operation) {
    case FP_OP_ROUTE:
        return "ROUTE";
    case FP_OP_DETAILS:
        return "DETAILS";
    case FP_OP_RESERVE:
        return "RESERVE";
    case FP_OP_MONITOR:
        return "MONITOR";
    case FP_OP_SET_FARE:
        return "SET_FARE";
    case FP_OP_ADD_SEATS:
        return "ADD_SEATS";
    default:
        return "UNKNOWN";
    }
}

/* ------------------------------------------------------------------ */
/* FpWriter                                                            */
/* ------------------------------------------------------------------ */
void fp_writer_init(FpWriter *w) {
    w->pos = 0;
    w->failed = 0;
    w->error[0] = '\0';
}

static void writer_fail(FpWriter *w, const char *fmt, ...) {
    va_list args;
    if (w->failed) {
        return; /* 只保留第一个错误 */
    }
    w->failed = 1;
    va_start(args, fmt);
    vsnprintf(w->error, sizeof w->error, fmt, args);
    va_end(args);
}

static int writer_reserve(FpWriter *w, size_t count) {
    if (w->failed) {
        return 0;
    }
    if (count > FP_MAX_BODY_LENGTH - w->pos) {
        writer_fail(w, "Message body exceeds %d bytes", FP_MAX_BODY_LENGTH);
        return 0;
    }
    return 1;
}

static int writer_check_range(FpWriter *w, int64_t value, int64_t max, const char *type) {
    if (w->failed) {
        return 0;
    }
    if (value < 0 || value > max) {
        writer_fail(w, "%s value out of range: %lld", type, (long long)value);
        return 0;
    }
    return 1;
}

void fp_write_u8(FpWriter *w, int64_t value) {
    if (!writer_check_range(w, value, 0xff, "u8") || !writer_reserve(w, 1)) {
        return;
    }
    w->buf[w->pos++] = (uint8_t)value;
}

void fp_write_u16(FpWriter *w, int64_t value) {
    if (!writer_check_range(w, value, 0xffff, "u16") || !writer_reserve(w, 2)) {
        return;
    }
    put_u16(w->buf + w->pos, (uint16_t)value);
    w->pos += 2;
}

void fp_write_u32(FpWriter *w, int64_t value) {
    if (!writer_check_range(w, value, 0xffffffffLL, "u32") || !writer_reserve(w, 4)) {
        return;
    }
    put_u32(w->buf + w->pos, (uint32_t)value);
    w->pos += 4;
}

void fp_write_i32(FpWriter *w, int32_t value) {
    if (!writer_reserve(w, 4)) {
        return;
    }
    put_u32(w->buf + w->pos, (uint32_t)value);
    w->pos += 4;
}

void fp_write_i64(FpWriter *w, int64_t value) {
    if (!writer_reserve(w, 8)) {
        return;
    }
    put_u64(w->buf + w->pos, (uint64_t)value);
    w->pos += 8;
}

void fp_write_float32(FpWriter *w, float value) {
    uint32_t bits;
    memcpy(&bits, &value, sizeof bits); /* 不做数值转换，直接搬运 IEEE 754 位模式 */
    fp_write_i32(w, (int32_t)bits);
}

void fp_write_string(FpWriter *w, const char *utf8) {
    size_t length = strlen(utf8); /* 长度写的是 UTF-8 字节数，不是字符数 */
    if (w->failed) {
        return;
    }
    if (!utf8_is_valid((const uint8_t *)utf8, length)) {
        writer_fail(w, "String is not valid UTF-8");
        return;
    }
    if (!writer_reserve(w, 2 + length)) {
        return;
    }
    put_u16(w->buf + w->pos, (uint16_t)length);
    w->pos += 2;
    memcpy(w->buf + w->pos, utf8, length);
    w->pos += length;
}

/* ------------------------------------------------------------------ */
/* FpReader                                                            */
/* ------------------------------------------------------------------ */
void fp_reader_init(FpReader *r, const uint8_t *body, size_t length) {
    r->buf = body;
    r->len = length;
    r->pos = 0;
    r->failed = 0;
    r->error[0] = '\0';
}

static void reader_fail(FpReader *r, const char *fmt, ...) {
    va_list args;
    if (r->failed) {
        return;
    }
    r->failed = 1;
    va_start(args, fmt);
    vsnprintf(r->error, sizeof r->error, fmt, args);
    va_end(args);
}

size_t fp_remaining(const FpReader *r) {
    return r->len - r->pos;
}

static int reader_ensure(FpReader *r, size_t count) {
    if (r->failed) {
        return 0;
    }
    if (count > fp_remaining(r)) {
        reader_fail(r,
                    "Message body ended early at offset %zu; needed %zu bytes but only %zu remain",
                    r->pos, count, fp_remaining(r));
        return 0;
    }
    return 1;
}

uint8_t fp_read_u8(FpReader *r) {
    if (!reader_ensure(r, 1)) {
        return 0;
    }
    return r->buf[r->pos++];
}

uint16_t fp_read_u16(FpReader *r) {
    uint16_t v;
    if (!reader_ensure(r, 2)) {
        return 0;
    }
    v = get_u16(r->buf + r->pos);
    r->pos += 2;
    return v;
}

uint32_t fp_read_u32(FpReader *r) {
    uint32_t v;
    if (!reader_ensure(r, 4)) {
        return 0;
    }
    v = get_u32(r->buf + r->pos);
    r->pos += 4;
    return v;
}

int32_t fp_read_i32(FpReader *r) {
    return (int32_t)fp_read_u32(r);
}

int64_t fp_read_i64(FpReader *r) {
    uint64_t v;
    if (!reader_ensure(r, 8)) {
        return 0;
    }
    v = get_u64(r->buf + r->pos);
    r->pos += 8;
    return (int64_t)v;
}

float fp_read_float32(FpReader *r) {
    uint32_t bits = fp_read_u32(r);
    float value;
    memcpy(&value, &bits, sizeof value);
    return value;
}

void fp_read_string(FpReader *r, char *out, size_t cap) {
    size_t byte_length = fp_read_u16(r);
    if (cap > 0) {
        out[0] = '\0';
    }
    if (!reader_ensure(r, byte_length)) {
        return;
    }
    if (!utf8_is_valid(r->buf + r->pos, byte_length)) {
        reader_fail(r, "String contains invalid UTF-8");
        return;
    }
    if (byte_length + 1 > cap) {
        reader_fail(r, "String of %zu bytes does not fit the local buffer", byte_length);
        return;
    }
    memcpy(out, r->buf + r->pos, byte_length);
    out[byte_length] = '\0';
    r->pos += byte_length;
}

void fp_require_fully_read(FpReader *r) {
    /* 每种 operation 字段顺序固定，多出的尾部字节同样属于格式错误。 */
    if (!r->failed && r->pos != r->len) {
        reader_fail(r, "Message body contains %zu unexpected trailing bytes",
                    fp_remaining(r));
    }
}
