/*
 * flight_client.c
 *
 * 平台说明：POSIX(Linux/macOS) 与 Windows(Winsock) 共用同一份代码，
 * 平台差异只集中在文件开头的少数几个小函数里。
 */
#if defined(_WIN32)
#define _CRT_RAND_S /* 提供 rand_s()，用来生成 clientId，必须在 <stdlib.h> 之前 */
#define WIN32_LEAN_AND_MEAN
#else
#define _POSIX_C_SOURCE 200809L
#endif

#include "flight_client.h"
#include "flight_protocol.h"

#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#include <windows.h>
#include <winsock2.h>
#include <ws2tcpip.h>
typedef SOCKET sock_t;
typedef int socklen_type;
#define SOCK_BAD INVALID_SOCKET
#define sock_close closesocket
#else
#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>
typedef int sock_t;
typedef socklen_t socklen_type;
#define SOCK_BAD (-1)
#define sock_close close
#endif

#define RECEIVE_TIMEOUT_MILLIS 1000 /* 与 Java 客户端一致：每次等待 1 秒 */
#define MAX_SEND_ATTEMPTS 4
#define FC_ERROR_LEN 640

struct FlightClient {
    sock_t sock;
    struct sockaddr_in server;
    uint64_t client_id;
    uint64_t next_request_id;
    char error[FC_ERROR_LEN];
};

/* ------------------------------------------------------------------ */
/* 平台小工具                                                          */
/* ------------------------------------------------------------------ */

/* 单调时钟（毫秒）：对应 System.nanoTime()，不受系统时间调整影响 */
static int64_t now_millis(void) {
#if defined(_WIN32)
    return (int64_t)GetTickCount64();
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
#endif
}

static void socket_error_text(char *buf, size_t cap) {
#if defined(_WIN32)
    snprintf(buf, cap, "Winsock error %d", WSAGetLastError());
#else
    snprintf(buf, cap, "%s", strerror(errno));
#endif
}

static int socket_call_interrupted(void) {
#if defined(_WIN32)
    return WSAGetLastError() == WSAEINTR;
#else
    return errno == EINTR;
#endif
}

/* Windows 上，向已关闭端口发 UDP 后下一次 recvfrom 可能返回 WSAECONNRESET，应忽略 */
static int socket_error_is_ignorable(void) {
#if defined(_WIN32)
    return WSAGetLastError() == WSAECONNRESET;
#else
    return 0;
#endif
}

/* 每次启动生成随机 clientId，对应 ThreadLocalRandom.nextLong() */
static uint64_t random_u64(void) {
#if defined(_WIN32)
    unsigned int a = 0, b = 0;
    rand_s(&a);
    rand_s(&b);
    return ((uint64_t)a << 32) | b;
#else
    uint64_t value = 0;
    FILE *f = fopen("/dev/urandom", "rb");
    if (f != NULL) {
        size_t got = fread(&value, 1, sizeof value, f);
        fclose(f);
        if (got == sizeof value) {
            return value;
        }
    }
    /* 兜底：时间 + 进程号混合（splitmix64） */
    {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        value = (uint64_t)ts.tv_sec * 1000000007ULL ^ (uint64_t)ts.tv_nsec ^
                ((uint64_t)getpid() << 32);
        value += 0x9E3779B97F4A7C15ULL;
        value = (value ^ (value >> 30)) * 0xBF58476D1CE4E5B9ULL;
        value = (value ^ (value >> 27)) * 0x94D049BB133111EBULL;
        return value ^ (value >> 31);
    }
#endif
}

/* ------------------------------------------------------------------ */
/* 错误记录                                                            */
/* ------------------------------------------------------------------ */
static FcStatus fail(FlightClient *c, FcStatus status, const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    vsnprintf(c->error, sizeof c->error, fmt, args);
    va_end(args);
    return status;
}

const char *fc_last_error(const FlightClient *client) {
    return client->error;
}

/* ------------------------------------------------------------------ */
/* 创建与关闭                                                          */
/* ------------------------------------------------------------------ */
FlightClient *fc_open(const char *host, uint16_t port, char *err, size_t errcap) {
    FlightClient *c;
    struct addrinfo hints, *res = NULL;

#if defined(_WIN32)
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        snprintf(err, errcap, "WSAStartup failed");
        return NULL;
    }
#endif

    c = (FlightClient *)calloc(1, sizeof *c);
    if (c == NULL) {
        snprintf(err, errcap, "Out of memory");
        return NULL;
    }
    c->sock = SOCK_BAD;

    /* 协议按 IPv4 报文长度设计，这里只解析 IPv4 地址 */
    memset(&hints, 0, sizeof hints);
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;
    if (getaddrinfo(host, NULL, &hints, &res) != 0 || res == NULL) {
        snprintf(err, errcap, "Cannot resolve host: %s", host);
        free(c);
        return NULL;
    }
    memcpy(&c->server, res->ai_addr, sizeof c->server);
    c->server.sin_port = htons(port);
    freeaddrinfo(res);

    /* 不 bind：首次 sendto 时系统自动分配临时端口，服务端据此记录回调地址 */
    c->sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (c->sock == SOCK_BAD) {
        char reason[64];
        socket_error_text(reason, sizeof reason);
        snprintf(err, errcap, "Cannot create UDP socket: %s", reason);
        free(c);
        return NULL;
    }

    c->client_id = random_u64();
    c->next_request_id = 1;
    c->error[0] = '\0';
    return c;
}

void fc_close(FlightClient *c) {
    if (c == NULL) {
        return;
    }
    if (c->sock != SOCK_BAD) {
        sock_close(c->sock);
    }
    free(c);
#if defined(_WIN32)
    WSACleanup();
#endif
}

/* ------------------------------------------------------------------ */
/* 收发                                                                */
/* ------------------------------------------------------------------ */

/* 发送一个已编码的数据报；失败时记录原因。 */
static FcStatus send_datagram(FlightClient *c, const uint8_t *data, size_t length) {
    long sent = (long)sendto(c->sock, (const char *)data, (int)length, 0,
                             (const struct sockaddr *)&c->server, sizeof c->server);
    if (sent < 0 || (size_t)sent != length) {
        char reason[64];
        socket_error_text(reason, sizeof reason);
        return fail(c, FC_ERR_IO, "Send failed: %s", reason);
    }
    return FC_OK;
}

/*
 * 在 deadline（单调时钟毫秒）之前收下一条合法的服务端消息。
 * 返回 1：收到；0：超时；-1：I/O 错误。
 * 非法/来源不符的数据报被丢弃后继续等，且不会重新开始计时（与 Java 一致）。
 */
static int receive_next_server_message(FlightClient *c, int64_t deadline, FpMessage *out) {
    for (;;) {
        int64_t remaining = deadline - now_millis();
        fd_set readable;
        struct timeval tv;
        int ready;
        uint8_t buffer[FP_MAX_DATAGRAM_LENGTH + 1]; /* 多留 1 字节，用来发现超长报文 */
        struct sockaddr_in from;
        socklen_type from_length = sizeof from;
        long received;
        char reason[FP_ERR_LEN];

        if (remaining <= 0) {
            return 0;
        }
        FD_ZERO(&readable);
        FD_SET(c->sock, &readable);
        tv.tv_sec = (long)(remaining / 1000);
        tv.tv_usec = (long)((remaining % 1000) * 1000);
        ready = select((int)c->sock + 1, &readable, NULL, NULL, &tv);
        if (ready < 0) {
            if (socket_call_interrupted()) {
                continue;
            }
            socket_error_text(reason, sizeof reason);
            fail(c, FC_ERR_IO, "Receive failed: %s", reason);
            return -1;
        }
        if (ready == 0) {
            return 0;
        }

        received = (long)recvfrom(c->sock, (char *)buffer, (int)sizeof buffer, 0,
                                  (struct sockaddr *)&from, &from_length);
        if (received < 0) {
            if (socket_error_is_ignorable() || socket_call_interrupted()) {
                continue;
            }
            socket_error_text(reason, sizeof reason);
            fail(c, FC_ERR_IO, "Receive failed: %s", reason);
            return -1;
        }

        /* 只接受配置的服务端地址和端口，避免其他 UDP 数据干扰 */
        if (from.sin_addr.s_addr != c->server.sin_addr.s_addr ||
            from.sin_port != c->server.sin_port) {
            continue;
        }
        if (fp_decode(buffer, (size_t)received, out, reason, sizeof reason) != 0) {
            printf("[DROP] Invalid server message: %s\n", reason);
            continue;
        }
        return 1;
    }
}

static int is_matching_reply(const FpMessage *m, const FpMessage *request) {
    return m->message_type == FP_MSG_REPLY && m->operation == request->operation &&
           m->client_id == request->client_id && m->request_id == request->request_id;
}

static int is_matching_monitor_event(const FpMessage *m, const FpMessage *request) {
    return m->message_type == FP_MSG_EVENT && m->operation == FP_OP_MONITOR &&
           m->client_id == request->client_id && m->request_id == request->request_id;
}

/* 对应 sendWithRetry：每次重发使用完全相同的字节（同一 clientId/requestId/body） */
static FcStatus send_with_retry(FlightClient *c, const FpMessage *request, FpMessage *reply) {
    uint8_t encoded[FP_MAX_DATAGRAM_LENGTH];
    size_t length = fp_encode(request, encoded);
    int attempt;

    for (attempt = 1; attempt <= MAX_SEND_ATTEMPTS; attempt++) {
        int64_t deadline;
        FcStatus st = send_datagram(c, encoded, length);
        if (st != FC_OK) {
            return st;
        }
        printf("[REQUEST] Sent attempt %d/%d; waiting for reply...\n", attempt,
               MAX_SEND_ATTEMPTS);
        fflush(stdout);

        deadline = now_millis() + RECEIVE_TIMEOUT_MILLIS;
        for (;;) {
            int rc = receive_next_server_message(c, deadline, reply);
            if (rc < 0) {
                return FC_ERR_IO;
            }
            if (rc == 0) {
                break; /* 本轮超时，重发 */
            }
            if (is_matching_reply(reply, request)) {
                return FC_OK;
            }
        }
    }
    return fail(c, FC_ERR_IO, "Timed out after %d attempts", MAX_SEND_ATTEMPTS);
}

static FcStatus invoke(FlightClient *c, int operation, const FpWriter *body,
                       FpMessage *reply) {
    FpMessage request;
    char reason[FP_ERR_LEN];

    if (body->failed) {
        return fail(c, FC_ERR_PROTOCOL, "%s", body->error);
    }
    if (fp_make_request(&request, operation, c->client_id, c->next_request_id++, body->buf,
                        body->pos, reason, sizeof reason) != 0) {
        return fail(c, FC_ERR_PROTOCOL, "%s", reason);
    }
    return send_with_retry(c, &request, reply);
}

/* ------------------------------------------------------------------ */
/* 回复确认与解析                                                      */
/* ------------------------------------------------------------------ */

/* 确认只帮助服务端提前清理缓存；发送失败时服务端仍有 60 秒 TTL 兜底 */
static void send_acknowledgement(FlightClient *c, const FpMessage *reply) {
    FpMessage ack;
    uint8_t encoded[FP_MAX_DATAGRAM_LENGTH];
    char reason[FP_ERR_LEN];
    size_t length;

    if (fp_make_ack(&ack, reply, reason, sizeof reason) != 0) {
        printf("[ERROR] Failed to build acknowledgement: %s\n", reason);
        return;
    }
    length = fp_encode(&ack, encoded);
    if (send_datagram(c, encoded, length) == FC_OK) {
        printf("[ACK] Reply acknowledgement sent.\n");
    } else {
        printf("[ERROR] Failed to send reply acknowledgement: %s\n", c->error);
    }
}

/* 遇到 reader 出错就返回协议错误 */
#define RETURN_IF_READER_FAILED(c, r)                                                      \
    do {                                                                                   \
        if ((r).failed) {                                                                  \
            return fail((c), FC_ERR_PROTOCOL, "%s", (r).error);                            \
        }                                                                                  \
    } while (0)

/*
 * 对应 successReader：读 status；成功则把 reader 停在 status 之后；
 * 业务错误则解析错误说明、发 ACK，并返回 FC_ERR_SERVER。
 */
static FcStatus success_reader(FlightClient *c, const FpMessage *reply, FpReader *reader) {
    int status;
    char description[FP_MAX_ERROR_TEXT_LENGTH + 1];

    fp_reader_init(reader, reply->body, reply->body_length);
    status = fp_read_u8(reader);
    RETURN_IF_READER_FAILED(c, *reader);
    if (status == FP_STATUS_OK) {
        return FC_OK;
    }

    /* 错误回复只能包含 status、u16 长度和最多 512 字节的说明 */
    if (status > FP_STATUS_STALE_REQUEST ||
        reply->body_length > 1 + 2 + FP_MAX_ERROR_TEXT_LENGTH) {
        return fail(c, FC_ERR_PROTOCOL, "Invalid error reply");
    }
    fp_read_string(reader, description, sizeof description);
    fp_require_fully_read(reader);
    RETURN_IF_READER_FAILED(c, *reader);
    send_acknowledgement(c, reply);
    return fail(c, FC_ERR_SERVER, "Server returned an error (status=%d): %s", status,
                description);
}

static FcStatus complete_reply(FlightClient *c, const FpMessage *reply, FpReader *reader) {
    fp_require_fully_read(reader);
    RETURN_IF_READER_FAILED(c, *reader);
    send_acknowledgement(c, reply);
    return FC_OK;
}

static FcStatus read_seat_result(FlightClient *c, const FpMessage *reply,
                                 int32_t *available_seats) {
    FpReader r;
    int32_t seats;
    FcStatus st = success_reader(c, reply, &r);
    if (st != FC_OK) {
        return st;
    }
    seats = fp_read_i32(&r);
    RETURN_IF_READER_FAILED(c, r);
    if (seats < 0) {
        return fail(c, FC_ERR_PROTOCOL, "Invalid seat availability reply");
    }
    st = complete_reply(c, reply, &r);
    if (st != FC_OK) {
        return st;
    }
    *available_seats = seats;
    return FC_OK;
}

/* ------------------------------------------------------------------ */
/* 业务操作                                                            */
/* ------------------------------------------------------------------ */

FcStatus fc_query_route(FlightClient *c, const char *source, const char *destination,
                        int32_t **flight_ids, size_t *count) {
    int32_t *ids = NULL;
    size_t n = 0, capacity = 0;
    int64_t offset = 0;
    FcStatus st;

    for (;;) {
        FpWriter w;
        FpMessage reply;
        FpReader r;
        int has_more, page_count, i;

        fp_writer_init(&w);
        fp_write_string(&w, source);
        fp_write_string(&w, destination);
        fp_write_u32(&w, offset);

        /* 每一页都是一个新的请求编号（invoke 内部递增） */
        st = invoke(c, FP_OP_ROUTE, &w, &reply);
        if (st != FC_OK) {
            goto cleanup;
        }
        st = success_reader(c, &reply, &r);
        if (st != FC_OK) {
            goto cleanup;
        }

        has_more = fp_read_u8(&r);
        page_count = fp_read_u16(&r);
        if (r.failed) {
            st = fail(c, FC_ERR_PROTOCOL, "%s", r.error);
            goto cleanup;
        }
        if ((has_more != 0 && has_more != 1) || page_count > FP_MAX_ROUTE_RESULTS_PER_PAGE ||
            page_count == 0) {
            st = fail(c, FC_ERR_PROTOCOL, "Invalid route page");
            goto cleanup;
        }

        for (i = 0; i < page_count; i++) {
            int32_t flight_id = fp_read_i32(&r);
            if (r.failed) {
                st = fail(c, FC_ERR_PROTOCOL, "%s", r.error);
                goto cleanup;
            }
            if (flight_id <= 0 || (n > 0 && flight_id <= ids[n - 1])) {
                st = fail(c, FC_ERR_PROTOCOL, "Route results are not in ascending order");
                goto cleanup;
            }
            if (n == capacity) {
                size_t new_capacity = capacity == 0 ? 128 : capacity * 2;
                int32_t *grown = (int32_t *)realloc(ids, new_capacity * sizeof *ids);
                if (grown == NULL) {
                    st = fail(c, FC_ERR_IO, "Out of memory");
                    goto cleanup;
                }
                ids = grown;
                capacity = new_capacity;
            }
            ids[n++] = flight_id;
        }
        st = complete_reply(c, &reply, &r);
        if (st != FC_OK) {
            goto cleanup;
        }

        if (has_more == 0) {
            *flight_ids = ids;
            *count = n;
            return FC_OK;
        }
        offset += page_count;
    }

cleanup:
    free(ids);
    return st;
}

FcStatus fc_query_details(FlightClient *c, int32_t flight_id, FcFlightDetails *out) {
    FpWriter w;
    FpMessage reply;
    FpReader r;
    int64_t departure;
    float fare;
    int32_t seats;
    FcStatus st;

    fp_writer_init(&w);
    fp_write_i32(&w, flight_id);
    st = invoke(c, FP_OP_DETAILS, &w, &reply);
    if (st != FC_OK) {
        return st;
    }
    st = success_reader(c, &reply, &r);
    if (st != FC_OK) {
        return st;
    }
    departure = fp_read_i64(&r);
    fare = fp_read_float32(&r);
    seats = fp_read_i32(&r);
    RETURN_IF_READER_FAILED(c, r);
    if (!isfinite(fare) || fare < 0 || seats < 0) {
        return fail(c, FC_ERR_PROTOCOL, "Invalid flight details reply");
    }
    st = complete_reply(c, &reply, &r);
    if (st != FC_OK) {
        return st;
    }
    out->departure_utc_seconds = departure;
    out->fare = fare;
    out->available_seats = seats;
    return FC_OK;
}

FcStatus fc_reserve_seats(FlightClient *c, int32_t flight_id, int32_t seat_count,
                          int32_t *available_seats) {
    FpWriter w;
    FpMessage reply;
    FcStatus st;

    fp_writer_init(&w);
    fp_write_i32(&w, flight_id);
    fp_write_i32(&w, seat_count);
    st = invoke(c, FP_OP_RESERVE, &w, &reply);
    if (st != FC_OK) {
        return st;
    }
    return read_seat_result(c, &reply, available_seats);
}

FcStatus fc_add_seats(FlightClient *c, int32_t flight_id, int32_t seat_count,
                      int32_t *available_seats) {
    FpWriter w;
    FpMessage reply;
    FcStatus st;

    fp_writer_init(&w);
    fp_write_i32(&w, flight_id);
    fp_write_i32(&w, seat_count);
    st = invoke(c, FP_OP_ADD_SEATS, &w, &reply);
    if (st != FC_OK) {
        return st;
    }
    return read_seat_result(c, &reply, available_seats);
}

FcStatus fc_set_fare(FlightClient *c, int32_t flight_id, float new_fare, float *current_fare) {
    FpWriter w;
    FpMessage reply;
    FpReader r;
    float fare;
    FcStatus st;

    fp_writer_init(&w);
    fp_write_i32(&w, flight_id);
    fp_write_float32(&w, new_fare);
    st = invoke(c, FP_OP_SET_FARE, &w, &reply);
    if (st != FC_OK) {
        return st;
    }
    st = success_reader(c, &reply, &r);
    if (st != FC_OK) {
        return st;
    }
    fare = fp_read_float32(&r);
    RETURN_IF_READER_FAILED(c, r);
    if (!isfinite(fare) || fare < 0) {
        return fail(c, FC_ERR_PROTOCOL, "Invalid fare reply");
    }
    st = complete_reply(c, &reply, &r);
    if (st != FC_OK) {
        return st;
    }
    *current_fare = fare;
    return FC_OK;
}

/* ------------------------------------------------------------------ */
/* 监控                                                                */
/* ------------------------------------------------------------------ */

/*
 * 解析并投递一个座位事件，返回更新后的"已接收最大序号"。
 * 非法事件或重复/旧事件只打印日志并忽略。
 */
static uint32_t deliver_monitor_event(const FpMessage *message, int32_t expected_flight_id,
                                      uint32_t highest_sequence, FcEventFn on_event,
                                      void *user) {
    FpReader r;
    int32_t flight_id, seats;
    uint32_t sequence;
    int64_t utc_millis;

    fp_reader_init(&r, message->body, message->body_length);
    flight_id = fp_read_i32(&r);
    seats = fp_read_i32(&r);
    sequence = fp_read_u32(&r);
    utc_millis = fp_read_i64(&r);
    fp_require_fully_read(&r);
    if (!r.failed && (flight_id != expected_flight_id || seats < 0 || sequence == 0)) {
        snprintf(r.error, sizeof r.error, "Invalid MONITOR event fields");
        r.failed = 1;
    }
    if (r.failed) {
        printf("[DROP] Invalid MONITOR event: %s\n", r.error);
        return highest_sequence;
    }
    if (sequence <= highest_sequence) {
        printf("[DROP] Duplicate or old MONITOR event sequence=%lu\n", (unsigned long)sequence);
        return highest_sequence;
    }
    on_event(flight_id, seats, sequence, utc_millis, user);
    return sequence;
}

FcStatus fc_monitor_flight(FlightClient *c, int32_t flight_id, int64_t interval_seconds,
                           FcRegisteredFn on_registered, FcEventFn on_event, void *user) {
    FpWriter w;
    FpMessage request, reply;
    FpReader r;
    uint8_t encoded[FP_MAX_DATAGRAM_LENGTH];
    size_t length;
    char reason[FP_ERR_LEN];
    int have_reply = 0, attempt;
    uint32_t highest_sequence = 0;
    int32_t seats;
    uint32_t remaining_millis;
    int64_t monitor_deadline;
    FcStatus st;

    fp_writer_init(&w);
    fp_write_i32(&w, flight_id);
    fp_write_u32(&w, interval_seconds);
    if (w.failed) {
        return fail(c, FC_ERR_PROTOCOL, "%s", w.error);
    }
    if (fp_make_request(&request, FP_OP_MONITOR, c->client_id, c->next_request_id++, w.buf,
                        w.pos, reason, sizeof reason) != 0) {
        return fail(c, FC_ERR_PROTOCOL, "%s", reason);
    }
    length = fp_encode(&request, encoded);

    /*
     * 第一次发送前就进入"监控接收状态"：注册回复和座位事件走同一个 socket。
     * 注册回复丢失时，事件仍可能先到，所以等待回复期间也要处理事件。
     */
    for (attempt = 1; attempt <= MAX_SEND_ATTEMPTS && !have_reply; attempt++) {
        int64_t deadline;
        st = send_datagram(c, encoded, length);
        if (st != FC_OK) {
            return st;
        }
        printf("[REQUEST] Sent MONITOR attempt %d/%d; waiting for reply and events...\n", attempt,
               MAX_SEND_ATTEMPTS);
        fflush(stdout);

        deadline = now_millis() + RECEIVE_TIMEOUT_MILLIS;
        for (;;) {
            FpMessage incoming;
            int rc = receive_next_server_message(c, deadline, &incoming);
            if (rc < 0) {
                return FC_ERR_IO;
            }
            if (rc == 0) {
                break;
            }
            if (is_matching_reply(&incoming, &request)) {
                reply = incoming;
                have_reply = 1;
                break;
            }
            if (is_matching_monitor_event(&incoming, &request)) {
                highest_sequence = deliver_monitor_event(&incoming, flight_id, highest_sequence,
                                                         on_event, user);
            }
        }
    }
    if (!have_reply) {
        return fail(c, FC_ERR_IO, "Monitor registration status is unknown after %d attempts",
                    MAX_SEND_ATTEMPTS);
    }

    st = success_reader(c, &reply, &r);
    if (st != FC_OK) {
        return st;
    }
    seats = fp_read_i32(&r);
    remaining_millis = fp_read_u32(&r);
    RETURN_IF_READER_FAILED(c, r);
    if (seats < 0 || remaining_millis > 600000u) {
        return fail(c, FC_ERR_PROTOCOL, "Invalid MONITOR reply");
    }
    st = complete_reply(c, &reply, &r);
    if (st != FC_OK) {
        return st;
    }
    on_registered(seats, remaining_millis, user);
    fflush(stdout);

    if (remaining_millis == 0) {
        return FC_OK;
    }
    /* 以本地单调时钟计算结束时刻，继续收事件直到期满 */
    monitor_deadline = now_millis() + (int64_t)remaining_millis;
    for (;;) {
        FpMessage incoming;
        int rc = receive_next_server_message(c, monitor_deadline, &incoming);
        if (rc < 0) {
            return FC_ERR_IO;
        }
        if (rc == 0) {
            return FC_OK; /* 监控期结束 */
        }
        if (is_matching_monitor_event(&incoming, &request)) {
            highest_sequence =
                deliver_monitor_event(&incoming, flight_id, highest_sequence, on_event, user);
            fflush(stdout);
        }
    }
}
