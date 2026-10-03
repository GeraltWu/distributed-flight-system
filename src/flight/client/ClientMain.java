package flight.client;

import flight.protocol.ProtocolException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;

/** 客户端控制台入口。 */
public final class ClientMain {
    private static final DateTimeFormatter UTC_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
                    .withZone(ZoneOffset.UTC);

    private ClientMain() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            printUsage();
            return;
        }

        InetAddress serverAddress;
        int serverPort;
        try {
            serverAddress = InetAddress.getByName(args[0]);
            serverPort = Integer.parseInt(args[1]);
            if (serverPort < 1 || serverPort > 65_535) {
                throw new NumberFormatException();
            }
        } catch (UnknownHostException | NumberFormatException exception) {
            printUsage();
            return;
        }

        try (FlightClient client = new FlightClient(serverAddress, serverPort)) {
            runMenu(client);
        } catch (IOException exception) {
            System.out.println("客户端启动失败：" + exception.getMessage());
        }
    }

    private static void runMenu(FlightClient client) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("请输入航班号查询详情，输入 0 退出：");
            String input = scanner.nextLine().trim();

            int flightId;
            try {
                flightId = Integer.parseInt(input);
            } catch (NumberFormatException exception) {
                System.out.println("请输入整数航班号。");
                continue;
            }

            if (flightId == 0) {
                return;
            }

            try {
                FlightClient.FlightDetails details = client.queryDetails(flightId);
                System.out.println("起飞时间："
                        + UTC_FORMAT.format(Instant.ofEpochSecond(details.departureUtcSeconds())));
                System.out.printf("票价：%.2f%n", details.fare());
                System.out.println("剩余座位：" + details.availableSeats());
            } catch (IOException | ProtocolException exception) {
                System.out.println("查询失败：" + exception.getMessage());
            }
        }
    }

    private static void printUsage() {
        System.out.println(
                "用法：java -cp out flight.client.ClientMain <server-host> <server-port>");
    }
}
