/*
 * client_main.c
 *
 * 控制台入口，对应 Java 的 ClientMain。
 * 用法：./flight_client <server-host> <server-port>
 */
#include "flight_client.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#if defined(_WIN32)
#include <windows.h>
#endif

#define LINE_CAP 1024

static int g_eof = 0; /* 标准输入结束时置 1，菜单循环据此退出 */

/* ------------------------------------------------------------------ */
/* 输入辅助                                                            */
/* ------------------------------------------------------------------ */

/* 读一行并去掉结尾换行。成功返回 1；EOF 或行过长返回 0。 */
static int read_line(char *buf, size_t cap) {
    size_t n;
    if (fgets(buf, (int)cap, stdin) == NULL) {
        g_eof = 1;
        return 0;
    }
    n = strlen(buf);
    if (n > 0 && buf[n - 1] == '\n') {
        buf[--n] = '\0';
        if (n > 0 && buf[n - 1] == '\r') {
            buf[--n] = '\0';
        }
        return 1;
    }
    /* 没有换行：要么行太长，要么最后一行没有换行符 */
    {
        int ch, too_long = 0;
        while ((ch = getchar()) != EOF && ch != '\n') {
            too_long = 1;
        }
        if (ch == EOF && !too_long) {
            return 1; /* 末行无换行，正常 */
        }
        if (ch == EOF) {
            g_eof = 1;
        }
        printf("[INPUT] Input line is too long.\n");
        return 0;
    }
}

static char *trim(char *s) {
    char *end;
    while (*s == ' ' || *s == '\t') {
        s++;
    }
    end = s + strlen(s);
    while (end > s && (end[-1] == ' ' || end[-1] == '\t')) {
        *--end = '\0';
    }
    return s;
}

static int prompt_line(const char *prompt, char *buf, size_t cap) {
    printf("%s", prompt);
    fflush(stdout);
    return read_line(buf, cap);
}

/* 严格解析整数：不允许空串、不允许多余字符，并检查范围 */
static int parse_int64(const char *text, int64_t min, int64_t max, int64_t *out) {
    char copy[LINE_CAP];
    char *s, *end;
    long long value;

    snprintf(copy, sizeof copy, "%s", text);
    s = trim(copy);
    if (*s == '\0') {
        return 0;
    }
    errno = 0;
    value = strtoll(s, &end, 10);
    if (errno != 0 || *end != '\0' || value < min || value > max) {
        return 0;
    }
    *out = value;
    return 1;
}

/* 读一个 int32；失败时打印与 Java 相同的提示并返回 0 */
static int read_int(const char *prompt, int32_t *out) {
    char line[LINE_CAP];
    int64_t value;
    if (!prompt_line(prompt, line, sizeof line)) {
        return 0;
    }
    if (!parse_int64(line, INT32_MIN, INT32_MAX, &value)) {
        printf("[INPUT] Please enter a valid numeric value.\n");
        return 0;
    }
    *out = (int32_t)value;
    return 1;
}

static int read_long(const char *prompt, int64_t *out) {
    char line[LINE_CAP];
    if (!prompt_line(prompt, line, sizeof line)) {
        return 0;
    }
    if (!parse_int64(line, INT64_MIN, INT64_MAX, out)) {
        printf("[INPUT] Please enter a valid numeric value.\n");
        return 0;
    }
    return 1;
}

static int read_float(const char *prompt, float *out) {
    char line[LINE_CAP], *s, *end;
    float value;
    if (!prompt_line(prompt, line, sizeof line)) {
        return 0;
    }
    s = trim(line);
    errno = 0;
    value = strtof(s, &end);
    if (*s == '\0' || *end != '\0') {
        printf("[INPUT] Please enter a valid numeric value.\n");
        return 0;
    }
    *out = value;
    return 1;
}

/* ------------------------------------------------------------------ */
/* 时间显示：明确标注时区（新加坡时间，UTC+8，无夏令时）               */
/* ------------------------------------------------------------------ */
static void format_sgt(int64_t epoch_seconds, char *buf, size_t cap) {
    time_t shifted = (time_t)(epoch_seconds + 8 * 3600);
    struct tm *parts = gmtime(&shifted);
    if (parts == NULL || strftime(buf, cap, "%Y-%m-%d %H:%M:%S SGT", parts) == 0) {
        snprintf(buf, cap, "(epoch %lld s, UTC)", (long long)epoch_seconds);
    }
}

static int64_t floor_div(int64_t a, int64_t b) {
    int64_t q = a / b;
    return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q;
}

/* ------------------------------------------------------------------ */
/* 菜单操作                                                            */
/* ------------------------------------------------------------------ */
static void report_failure(FlightClient *client) {
    printf("[ERROR] Operation failed: %s\n", fc_last_error(client));
}

static void query_route(FlightClient *client) {
    char source[LINE_CAP], destination[LINE_CAP];
    int32_t *ids = NULL;
    size_t count = 0, i;

    if (!prompt_line("Source: ", source, sizeof source) ||
        !prompt_line("Destination: ", destination, sizeof destination)) {
        return;
    }
    if (fc_query_route(client, source, destination, &ids, &count) != FC_OK) {
        report_failure(client);
        return;
    }
    printf("[RESULT] Matching flight IDs: [");
    for (i = 0; i < count; i++) {
        printf("%s%ld", i == 0 ? "" : ", ", (long)ids[i]);
    }
    printf("]\n");
    free(ids);
}

static void query_details(FlightClient *client) {
    int32_t flight_id;
    FcFlightDetails details;
    char when[64];

    if (!read_int("Flight ID: ", &flight_id)) {
        return;
    }
    if (fc_query_details(client, flight_id, &details) != FC_OK) {
        report_failure(client);
        return;
    }
    format_sgt(details.departure_utc_seconds, when, sizeof when);
    printf("[RESULT] Flight details:\n");
    printf("  Departure time: %s\n", when);
    printf("  Fare: %.2f\n", (double)details.fare);
    printf("  Available seats: %ld\n", (long)details.available_seats);
}

static void reserve_seats(FlightClient *client) {
    int32_t flight_id, seat_count, available;
    if (!read_int("Flight ID: ", &flight_id) || !read_int("Seats to reserve: ", &seat_count)) {
        return;
    }
    if (fc_reserve_seats(client, flight_id, seat_count, &available) != FC_OK) {
        report_failure(client);
        return;
    }
    printf("[RESULT] Reservation completed. Available seats: %ld\n", (long)available);
}

static void set_fare(FlightClient *client) {
    int32_t flight_id;
    float new_fare, current_fare;
    if (!read_int("Flight ID: ", &flight_id) || !read_float("New fare: ", &new_fare)) {
        return;
    }
    if (fc_set_fare(client, flight_id, new_fare, &current_fare) != FC_OK) {
        report_failure(client);
        return;
    }
    printf("[RESULT] Fare updated: %.2f\n", (double)current_fare);
}

static void add_seats(FlightClient *client) {
    int32_t flight_id, seat_count, available;
    if (!read_int("Flight ID: ", &flight_id) || !read_int("Seats to add: ", &seat_count)) {
        return;
    }
    if (fc_add_seats(client, flight_id, seat_count, &available) != FC_OK) {
        report_failure(client);
        return;
    }
    printf("[RESULT] Seats added. Available seats: %ld\n", (long)available);
}

static void on_registered(int32_t available_seats, uint32_t remaining_millis, void *user) {
    (void)user;
    printf("[RESULT] Monitor registered. Available seats: %ld; remaining: %lu ms\n",
           (long)available_seats, (unsigned long)remaining_millis);
}

static void on_event(int32_t flight_id, int32_t available_seats, uint32_t event_sequence,
                     int64_t event_utc_millis, void *user) {
    char when[64];
    (void)user;
    format_sgt(floor_div(event_utc_millis, 1000), when, sizeof when);
    printf("[EVENT] Flight %ld seats=%ld, sequence=%lu, time=%s\n", (long)flight_id,
           (long)available_seats, (unsigned long)event_sequence, when);
}

static void monitor_flight(FlightClient *client) {
    int32_t flight_id;
    int64_t interval_seconds;

    if (!read_int("Flight ID: ", &flight_id) ||
        !read_long("Monitor interval in seconds (1-600): ", &interval_seconds)) {
        return;
    }
    if (fc_monitor_flight(client, flight_id, interval_seconds, on_registered, on_event, NULL) !=
        FC_OK) {
        report_failure(client);
        return;
    }
    printf("[RESULT] Monitor period ended.\n");
}

static void print_menu(void) {
    printf("\n=== Flight Operations ===\n");
    printf("1. Find flights by route\n");
    printf("2. View flight details\n");
    printf("3. Reserve seats\n");
    printf("4. Monitor seat availability\n");
    printf("5. Set fare\n");
    printf("6. Add seats\n");
    printf("0. Exit\n");
    printf("Select an option: ");
    fflush(stdout);
}

static void print_usage(void) {
    printf("[USAGE] ./flight_client <server-host> <server-port>\n");
}

static void run_menu(FlightClient *client) {
    char line[LINE_CAP];

    while (!g_eof) {
        char *choice;
        print_menu();
        if (!read_line(line, sizeof line)) {
            continue;
        }
        choice = trim(line);
        if (strcmp(choice, "0") == 0) {
            return;
        } else if (strcmp(choice, "1") == 0) {
            query_route(client);
        } else if (strcmp(choice, "2") == 0) {
            query_details(client);
        } else if (strcmp(choice, "3") == 0) {
            reserve_seats(client);
        } else if (strcmp(choice, "4") == 0) {
            monitor_flight(client);
        } else if (strcmp(choice, "5") == 0) {
            set_fare(client);
        } else if (strcmp(choice, "6") == 0) {
            add_seats(client);
        } else {
            printf("[INPUT] Unknown menu option.\n");
        }
    }
}

int main(int argc, char **argv) {
    FlightClient *client;
    char err[256];
    long port;
    char *end;

#if defined(_WIN32)
    /* 让控制台按 UTF-8 收发，地名等字符串才能按 UTF-8 字节发送 */
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
#endif

    if (argc != 3) {
        print_usage();
        return 1;
    }
    port = strtol(argv[2], &end, 10);
    if (*argv[2] == '\0' || *end != '\0' || port < 1 || port > 65535) {
        print_usage();
        return 1;
    }
    client = fc_open(argv[1], (uint16_t)port, err, sizeof err);
    if (client == NULL) {
        printf("[ERROR] Client startup failed: %s\n", err);
        return 1;
    }
    run_menu(client);
    fc_close(client);
    return 0;
}
