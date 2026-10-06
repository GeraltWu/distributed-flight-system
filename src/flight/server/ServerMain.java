package flight.server;

import java.io.IOException;
import java.nio.file.Paths;

/** 服务端启动入口。 */
public final class ServerMain {
    private ServerMain() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            printUsage();
            return;
        }

        int port;
        InvocationMode mode;
        try {
            port = Integer.parseInt(args[0]);
            mode = InvocationMode.parse(args[1]);
            if (port < 1 || port > 65_535) {
                throw new NumberFormatException();
            }
        } catch (IllegalArgumentException exception) {
            printUsage();
            return;
        }

        try {
            FlightService service = FlightService.load(Paths.get("data", "flights.tsv"));
            FlightServer server = new FlightServer(port, service, mode);
            server.run();
        } catch (IOException exception) {
            System.out.println("Server startup, data loading, or receive failed: "
                    + exception.getMessage());
        }
    }

    private static void printUsage() {
        System.out.println(
                "Usage: java -cp out flight.server.ServerMain "
                        + "<port> <at-least-once|at-most-once>");
    }
}
