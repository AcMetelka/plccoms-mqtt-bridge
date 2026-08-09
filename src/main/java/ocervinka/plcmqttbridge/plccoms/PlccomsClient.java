package ocervinka.plcmqttbridge.plccoms;

import ocervinka.plcmqttbridge.telnet.TelnetClient;
import ocervinka.plcmqttbridge.telnet.TelnetClientListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlccomsClient {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Pattern LIST_CMD_PATTERN = Pattern.compile("(.+),(.+)");

    private TelnetClient telnetClient;

    private final Function<Collection<PlccomsVar>, Collection<PlccomsVar>> listConsumer;
    private final Consumer<PlccomsDiff> diffConsumer;

    private Collection<PlccomsVar> vars = new ArrayList<>();
    private final Queue<String> connectCommandQueue = new LinkedList<>();

    public String plcVersion, plcIp;

    public PlccomsClient(Function<Collection<PlccomsVar>, Collection<PlccomsVar>> listConsumer, Consumer<PlccomsDiff> diffConsumer) {
        this.listConsumer = listConsumer;
        this.diffConsumer = diffConsumer;
    }

    public final void connect(PlccomsConfig config, boolean haDiscovery) {
        telnetClient = new TelnetClient(config.host, config.port, new TelnetClientListener() {

            @Override
            public void onConnect(TelnetClient tc, String message) {
                LOGGER.info("Connected to PLCComS: " + message);
                initializeConnection(tc::write, haDiscovery);
            }

            private void sendNextCommand(TelnetClient tc) {
                if (!connectCommandQueue.isEmpty()) {
                    String nextCommand = connectCommandQueue.poll();
                    //LOGGER.info("Sending: " + nextCommand);
                    tc.write(nextCommand);
                }
            }

            @Override
            public void onLineRead(TelnetClient tc, String line) {
                String[] splitLine = line.trim().toUpperCase().split(":", -1);
                if ( !("GETINFO".equals(splitLine[0])) && splitLine.length != 2) {
                    LOGGER.warn("Invalid command received (no single colon): {}", line);
                    return;
                }

                String cmd = splitLine[0];
                String args = splitLine[1].trim();

                if ("GETINFO".equals(cmd)) {
                    if (args.contains("VERSION_PLC")) plcVersion = args.substring(args.indexOf(',') + 1).trim();
                    if (args.contains("IPADDR")) plcIp = args.substring(args.indexOf(',') + 1).trim();
                    sendNextCommand(tc);

                } else if ("LIST".equals(cmd)) {
                    processListLine(tc::write, args);

                } else if ("DIFF".equals(cmd) || "GET".equals(cmd)) {
                    try {
                        String[] diffArgs = args.split(",");
                        if (diffArgs.length != 2) {
                            LOGGER.info("DIFF/GET command must have two arguments: {}", args);
                            return;
                        }
                        diffConsumer.accept(new PlccomsDiff(diffArgs[0], diffArgs[1]));
                        LOGGER.trace("Received PLCComS: " +diffArgs[0]+" : "+diffArgs[1]);
                    } catch (Exception e) {
                        LOGGER.warn("Failed to process DIFF/GET command: {}", args, e);
                    }
                } else {
                    LOGGER.info("Unexpected command \"{}\" received: ", line);
                }
            }

            @Override
            public void onError(TelnetClient tc, String message, Exception e) {
                LOGGER.info("PLCComS connection error", e);
            }

            @Override
            public void onDisconnect(TelnetClient tc) {
                LOGGER.info("Disconnected from PLCComS");
            }
        });
    }

    void initializeConnection(Consumer<String> commandConsumer, boolean haDiscovery) {
        connectCommandQueue.clear();
        vars = new ArrayList<>();

        if (haDiscovery) {
            connectCommandQueue.add("GETINFO:version_plc");
            connectCommandQueue.add("GETINFO:ipaddr");
        }
        connectCommandQueue.add("LIST:"); // LIST is executed last

        if (!connectCommandQueue.isEmpty()) {
            commandConsumer.accept(connectCommandQueue.poll());
        }
    }

    void processListLine(Consumer<String> commandConsumer, String args) {
        if (!args.isBlank()) {
            Matcher matcher = LIST_CMD_PATTERN.matcher(args);
            if (matcher.matches()) {
                vars.add(new PlccomsVar(matcher.group(1), matcher.group(2)));
            } else {
                LOGGER.error("LIST args \"{}\" did not match {}", args, LIST_CMD_PATTERN);
            }
            return;
        }

        Collection<PlccomsVar> varsToSubscribe = listConsumer.apply(vars);
        for (PlccomsVar var : varsToSubscribe) {
            if (var.delta == null) {
                commandConsumer.accept("EN:" + var.name);
            } else {
                commandConsumer.accept("EN:" + var.name + " " + var.delta);
            }
            commandConsumer.accept("GET:" + var.name);
        }
    }

    public void setVar(String name, Object value) {
        String cmd = "SET:" + name + "," + value.toString();
        telnetClient.write(cmd);
    }

    public void close() {
        telnetClient.close();
    }

}
