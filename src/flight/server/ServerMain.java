package flight.server;

import java.io.IOException;
import java.nio.file.Paths;

/** 服务端启动入口。 */
public final class ServerMain {
    private ServerMain() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            printUsage();
            return;
        }

        int port;
        try {
            port = Integer.parseInt(args[0]);
            if (port < 1 || port > 65_535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            printUsage();
            return;
        }

        try {
            FlightService service = FlightService.load(Paths.get("data", "flights.tsv"));
            FlightServer server = new FlightServer(port, service);
            server.run();
        } catch (IOException exception) {
            System.out.println("服务端启动、读取数据或接收失败：" + exception.getMessage());
        }
    }

    private static void printUsage() {
        System.out.println("用法：java -cp out flight.server.ServerMain <port>");
    }
}
