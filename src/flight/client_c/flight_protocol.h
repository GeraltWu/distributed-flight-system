/*
 * flight_protocol.h
 *
 * C 版协议层，对应 Java 的 Protocol / Message / MessageCodec / ProtocolException。
 * 字段、偏移和取值与 protocol.md 以及 Java 客户端保持一致。
 *
 * 错误处理约定：Java 里抛 ProtocolException 的地方，这里返回非 0 / 置 failed 标志，
 * 并把说明文字写入调用者提供的缓冲区。
 */
#ifndef FLIGHT_PROTOCOL_H
#define FLIGHT_PROTOCOL_H

#include <stddef.h>
#include <stdint.h>

/* ---------- Protocol.java 中的常量 ---------- */
#define FP_MAGIC_FIRST 0x46  /* 'F' */
#define FP_MAGIC_SECOND 0x49 /* 'I' */
#define FP_VERSION 1

#define FP_HEADER_LENGTH 23
#define FP_MAX_DATAGRAM_LENGTH 548
#define FP_MAX_BODY_LENGTH (FP_MAX_DATAGRAM_LENGTH - FP_HEADER_LENGTH) /* 525 */
#define FP_MAX_ERROR_TEXT_LENGTH 512
#define FP_MAX_ROUTE_RESULTS_PER_PAGE 100

#define FP_MAGIC_OFFSET 0
#define FP_VERSION_OFFSET 2
#define FP_MESSAGE_TYPE_OFFSET 3
#define FP_OPERATION_OFFSET 4
#define FP_CLIENT_ID_OFFSET 5
#define FP_REQUEST_ID_OFFSET 13
#define FP_BODY_LENGTH_OFFSET 21
#define FP_BODY_OFFSET FP_HEADER_LENGTH

/* 错误说明缓冲区的建议大小 */
#define FP_ERR_LEN 128

enum {
    FP_MSG_REQUEST = 1,
    FP_MSG_REPLY = 2,
    FP_MSG_EVENT = 3,
    FP_MSG_ACK = 4 /* Java 实现里存在，protocol.md 尚未记载 */
};

enum {
    FP_OP_ROUTE = 1,
    FP_OP_DETAILS = 2,
    FP_OP_RESERVE = 3,
    FP_OP_MONITOR = 4,
    FP_OP_SET_FARE = 5,
    FP_OP_ADD_SEATS = 6
};

enum {
    FP_STATUS_OK = 0,
    FP_STATUS_BAD_ARGUMENT = 1,
    FP_STATUS_NOT_FOUND = 2,
    FP_STATUS_NO_SEATS = 3,
    FP_STATUS_BAD_PACKET = 4,
    FP_STATUS_UNSUPPORTED = 5,
    FP_STATUS_STALE_REQUEST = 6
};

/* ---------- Message：一条完整的协议消息（按值传递/拷贝） ---------- */
typedef struct {
    uint8_t message_type;
    uint8_t operation;
    uint64_t client_id; /* u64，C 有原生无符号 64 位，不需要 Java 的位模式技巧 */
    uint64_t request_id;
    uint16_t body_length;
    uint8_t body[FP_MAX_BODY_LENGTH];
} FpMessage;

/* 以下函数成功返回 0；失败返回 -1，并把原因写入 err（长度 errcap）。 */
int fp_make_request(FpMessage *out, int operation, uint64_t client_id,
                    uint64_t request_id, const uint8_t *body, size_t body_length,
                    char *err, size_t errcap);
int fp_make_ack(FpMessage *out, const FpMessage *reply, char *err, size_t errcap);

/* 编码到 out（容量至少 FP_MAX_DATAGRAM_LENGTH），返回数据报长度。 */
size_t fp_encode(const FpMessage *message, uint8_t *out);

/* 解码恰好一个完整数据报。 */
int fp_decode(const uint8_t *data, size_t length, FpMessage *out, char *err,
              size_t errcap);

const char *fp_operation_name(int operation);

/* ---------- BodyWriter：错误是"粘性"的，写完后检查 failed 即可 ---------- */
typedef struct {
    uint8_t buf[FP_MAX_BODY_LENGTH];
    size_t pos;
    int failed;
    char error[FP_ERR_LEN];
} FpWriter;

void fp_writer_init(FpWriter *w);
void fp_write_u8(FpWriter *w, int64_t value);
void fp_write_u16(FpWriter *w, int64_t value);
void fp_write_u32(FpWriter *w, int64_t value);
void fp_write_i32(FpWriter *w, int32_t value);
void fp_write_i64(FpWriter *w, int64_t value);
void fp_write_float32(FpWriter *w, float value);
void fp_write_string(FpWriter *w, const char *utf8); /* 要求是合法 UTF-8 */

/* ---------- BodyReader：读越界或字符串非法时置 failed，并返回 0 ---------- */
typedef struct {
    const uint8_t *buf;
    size_t len;
    size_t pos;
    int failed;
    char error[FP_ERR_LEN];
} FpReader;

void fp_reader_init(FpReader *r, const uint8_t *body, size_t length);
uint8_t fp_read_u8(FpReader *r);
uint16_t fp_read_u16(FpReader *r);
uint32_t fp_read_u32(FpReader *r);
int32_t fp_read_i32(FpReader *r);
int64_t fp_read_i64(FpReader *r);
float fp_read_float32(FpReader *r);
/* 读 u16 长度 + UTF-8 字节，校验合法性后复制到 out 并补 '\0'；cap 含结尾 '\0'。 */
void fp_read_string(FpReader *r, char *out, size_t cap);
size_t fp_remaining(const FpReader *r);
void fp_require_fully_read(FpReader *r);

#endif /* FLIGHT_PROTOCOL_H */
