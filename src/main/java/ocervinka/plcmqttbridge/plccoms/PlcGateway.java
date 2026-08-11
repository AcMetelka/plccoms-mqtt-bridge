package ocervinka.plcmqttbridge.plccoms;

import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Function;

public interface PlcGateway {
    void setListeners(Function<Collection<PlccomsVar>, Collection<PlccomsVar>> listListener,
                      Consumer<PlccomsDiff> diffListener);
    void connect(PlccomsConfig config, boolean haDiscovery);
    void close();
    void setVar(String name, Object value);
    String getPlcVersion();
    String getPlcIp();
}
