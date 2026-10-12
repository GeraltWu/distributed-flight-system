/*
 * flight_client.h
 *
 * C client communication layer, corresponding to Java FlightClient.
 * C 版客户端通信层，对应 Java 的 FlightClient：
 * Handles UDP requests, timeout retries, reply validation/decoding, acknowledgements (ACK), and monitoring callbacks.
 * 负责 UDP 请求、超时重传、回复校验与解码、回复确认(ACK)和监控回调。
 */
#ifndef FLIGHT_CLIENT_H
#define FLIGHT_CLIENT_H

#include <stddef.h>
#include <stdint.h>

/* Corresponding Java exception categories. */
/* 对应 Java 的异常类别 */
typedef enum {
    FC_OK = 0,
    /* Corresponds to IOException: timeout or send/receive failure. */
    FC_ERR_IO = 1,       /* 对应 IOException：超时、发送/接收失败 */
    /* Corresponds to ProtocolException: a malformed reply. */
    FC_ERR_PROTOCOL = 2, /* 对应 ProtocolException：回复格式不合法 */
    /* The server returned a business error (status != 0). */
    FC_ERR_SERVER = 3    /* 服务端返回了业务错误（status != 0） */
} FcStatus;

/* Opaque handle containing the socket, clientId, and next requestId. */
/* 不透明句柄，内部持有 socket、clientId 和下一个 requestId */
typedef struct FlightClient FlightClient;

typedef struct {
    int64_t departure_utc_seconds;
    float fare;
    int32_t available_seats;
} FcFlightDetails;

/* Monitoring callback; user is passed through unchanged as the caller's context. */
/* 监控回调；user 原样传回，用来携带调用者的上下文 */
typedef void (*FcRegisteredFn)(int32_t available_seats, uint32_t remaining_millis,
                               void *user);
typedef void (*FcEventFn)(int32_t flight_id, int32_t available_seats,
                          uint32_t event_sequence, int64_t event_utc_millis,
                          void *user);

/* Create a client; on failure return NULL and write the reason to err. host may be an IPv4 address or hostname. */
/* 创建客户端；失败返回 NULL 并把原因写入 err。host 可以是 IPv4 地址或主机名。 */
FlightClient *fc_open(const char *host, uint16_t port, char *err, size_t errcap);
void fc_close(FlightClient *client);

/* Description of the most recent failure, corresponding to getMessage() on a Java exception. */
/* 最近一次失败的说明，对应 Java 异常的 getMessage() */
const char *fc_last_error(const FlightClient *client);

/* *flight_ids is allocated with malloc; the caller must free() it after success. */
/* *flight_ids 由 malloc 分配，成功后由调用者 free()。 */
FcStatus fc_query_route(FlightClient *c, const char *source, const char *destination,
                        int32_t **flight_ids, size_t *count);
FcStatus fc_query_details(FlightClient *c, int32_t flight_id, FcFlightDetails *out);
FcStatus fc_reserve_seats(FlightClient *c, int32_t flight_id, int32_t seat_count,
                          int32_t *available_seats);
FcStatus fc_set_fare(FlightClient *c, int32_t flight_id, float new_fare,
                     float *current_fare);
FcStatus fc_add_seats(FlightClient *c, int32_t flight_id, int32_t seat_count,
                      int32_t *available_seats);

/* Block until the monitoring period ends; deliver events through the callback. */
/* 阻塞直到监控期结束；期间事件通过回调送出。 */
FcStatus fc_monitor_flight(FlightClient *c, int32_t flight_id, int64_t interval_seconds,
                           FcRegisteredFn on_registered, FcEventFn on_event, void *user);

#endif /* FLIGHT_CLIENT_H */
