package flight.server;

/** 客户端和服务端启动时选择的调用语义。 */
enum InvocationMode {
    AT_LEAST_ONCE("at-least-once"),
    AT_MOST_ONCE("at-most-once");

    private final String argument;

    InvocationMode(String argument) {
        this.argument = argument;
    }

    static InvocationMode parse(String value) {
        for (InvocationMode mode : values()) {
            if (mode.argument.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown invocation mode: " + value);
    }

    @Override
    public String toString() {
        return argument;
    }
}
