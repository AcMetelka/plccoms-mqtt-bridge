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
import java.util.Locale;

public class PlccomsClient implements PlcGateway {

    private static final Logger LOGGER = LogManager.getLogger();
    private TelnetClient telnetClient;

    private Function<Collection<PlccomsVar>, Collection<PlccomsVar>> listConsumer;
    private Consumer<PlccomsDiff> diffConsumer;

    private Collection<PlccomsVar> vars = new ArrayList<>();
    private final Queue<String> connectCommandQueue = new LinkedList<>();
    private boolean publicFileReloadInProgress;

    public String plcVersion, plcIp;

    public PlccomsClient(Function<Collection<PlccomsVar>, Collection<PlccomsVar>> listConsumer, Consumer<PlccomsDiff> diffConsumer) {
        setListeners(listConsumer, diffConsumer);
    }

    public PlccomsClient() { }

    @Override
    public void setListeners(Function<Collection<PlccomsVar>, Collection<PlccomsVar>> listListener,
                             Consumer<PlccomsDiff> diffListener) {
        this.listConsumer = listListener;
        this.diffConsumer = diffListener;
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
                processLine(tc::write, line, () -> sendNextCommand(tc));
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

    void processLine(Consumer<String> commandConsumer, String line, Runnable nextCommand) {
        String trimmedLine = line.trim();
        int colon = trimmedLine.indexOf(':');
        if (colon < 0) {
            String upperLine = trimmedLine.toUpperCase(Locale.ROOT);
            if (upperLine.startsWith("ERROR") || upperLine.startsWith("WARNING")) {
                LOGGER.warn("PLCComS reported: {}", line);
            } else {
                LOGGER.warn("Invalid command received (no colon): {}", line);
            }
            return;
        }

        String cmd = trimmedLine.substring(0, colon).trim().toUpperCase(Locale.ROOT);
        String args = trimmedLine.substring(colon + 1).trim();
        if ("GETINFO".equals(cmd)) {
            String[] infoArgs = args.split(",", 2);
            if (infoArgs.length == 2) {
                String key = infoArgs[0].trim().toUpperCase(Locale.ROOT);
                if ("VERSION_PLC".equals(key)) plcVersion = infoArgs[1].trim();
                if ("IPADDR".equals(key)) plcIp = infoArgs[1].trim();
            }
            nextCommand.run();
        } else if ("LIST".equals(cmd)) {
            processListLine(commandConsumer, args);
        } else if ("DIFF".equals(cmd) || "GET".equals(cmd)) {
            try {
                String[] diffArgs = args.split(",", 2);
                if (diffArgs.length != 2) {
                    LOGGER.info("DIFF/GET command must have two arguments: {}", args);
                    return;
                }
                diffConsumer.accept(new PlccomsDiff(diffArgs[0].trim(), diffArgs[1].trim()));
                LOGGER.trace("Received PLCComS: {} : {}", diffArgs[0], diffArgs[1]);
            } catch (Exception e) {
                LOGGER.warn("Failed to process DIFF/GET command: {}", args, e);
            }
        } else if ("ERROR".equals(cmd) || "WARNING".equals(cmd)) {
            LOGGER.warn("PLCComS {}: {}", cmd, args);
            if ("WARNING".equals(cmd)
                    && args.toUpperCase(Locale.ROOT).startsWith("250 CHANGED PUBLIC FILE:")) {
                reloadPublicFile(commandConsumer);
            }
        } else {
            LOGGER.info("Unexpected command received: {}", line);
        }
    }

    void initializeConnection(Consumer<String> commandConsumer, boolean haDiscovery) {
        connectCommandQueue.clear();
        vars = new ArrayList<>();
        publicFileReloadInProgress = false;

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
            String[] listArgs = args.split(",", 2);
            if (listArgs.length == 2 && !listArgs[0].isBlank() && !listArgs[1].isBlank()) {
                vars.add(new PlccomsVar(listArgs[0].trim(), listArgs[1].trim()));
            } else {
                LOGGER.error("Invalid LIST args: {}", args);
            }
            return;
        }

        publicFileReloadInProgress = false;
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

    private void reloadPublicFile(Consumer<String> commandConsumer) {
        if (publicFileReloadInProgress) {
            LOGGER.info("PLCComS public file reload is already in progress");
            return;
        }

        LOGGER.info("PLCComS public file changed; refreshing LIST and variable subscriptions");
        publicFileReloadInProgress = true;
        connectCommandQueue.clear();
        vars = new ArrayList<>();
        commandConsumer.accept("LIST:");
    }

    public void setVar(String name, Object value) {
        String cmd = "SET:" + name + "," + value.toString();
        telnetClient.write(cmd);
    }

    public void close() {
        telnetClient.close();
    }

    public String getPlcVersion() { return plcVersion; }

    public String getPlcIp() { return plcIp; }

}
