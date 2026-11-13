package ocervinka.plcmqttbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import ocervinka.plcmqttbridge.config.Config;
import ocervinka.plcmqttbridge.config.VarMappingConfig;
import ocervinka.plcmqttbridge.mqtt.MqttConfig;
import ocervinka.plcmqttbridge.mqtt.MqttGateway;
import ocervinka.plcmqttbridge.mqtt.MqttHaDiscoveryConfig;
import ocervinka.plcmqttbridge.plccoms.PlcGateway;
import ocervinka.plcmqttbridge.plccoms.PlccomsConfig;
import ocervinka.plcmqttbridge.plccoms.PlccomsDiff;
import ocervinka.plcmqttbridge.plccoms.PlccomsVar;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlcMqttBridgeTest {
    @Test
    public void coordinatesMappingsThroughFakeGateways() throws Exception {
        FakeMqtt mqtt = new FakeMqtt();
        FakePlc plc = new FakePlc();
        PlcMqttBridge bridge = new PlcMqttBridge(config(), mqtt, plc);

        bridge.connect();
        Collection<PlccomsVar> subscriptions = plc.listListener.apply(List.of(new PlccomsVar("Room.Text", "STRING[80]*")));
        mqtt.handler.onMessage("cmd/room.text", "lowerCase,value:one".getBytes(StandardCharsets.UTF_8));
        plc.diffListener.accept(new PlccomsDiff("Room.Text", "plcLower,value:two"));

        assertTrue(mqtt.connected);
        assertTrue(plc.connected);
        assertEquals("Room.Text", subscriptions.iterator().next().name);
        assertEquals(List.of("Room.Text=lowerCase,value:one"), plc.writes);
        assertTrue(mqtt.published.stream().anyMatch(value -> value.startsWith("homeassistant/number/plc/room_text/config=")));
        assertTrue(mqtt.published.contains("state/room.text=plcLower,value:two"));
    }

    @Test
    public void publishesOnlyNewCommandsWithoutRetainAcrossInitializationAndMqttRecovery() throws Exception {
        FakeMqtt mqtt = new FakeMqtt();
        FakePlc plc = new FakePlc();
        Config defaults = config();
        VarMappingConfig command = new ObjectMapper(new YAMLFactory()).readValue(
                "var: FVEPWRSOURCE.(.*)SWITCH\n"
                        + "state-topic: plc-fve/src-switch/{1}switch/set\nmqtt-retain: false\n",
                VarMappingConfig.class);
        new PlcMqttBridge(new Config(defaults.plccoms, defaults.mqtt, null,
                new VarMappingConfig[]{command, defaults.varMappings[0]}), mqtt, plc).connect();
        String ds = "FVEPWRSOURCE.DSINVSWITCH";
        String aqua = "FVEPWRSOURCE.AQUASWITCH";
        List<PlccomsVar> vars = List.of(new PlccomsVar(ds, "STRING"), new PlccomsVar(aqua, "STRING"),
                new PlccomsVar("Room.Text", "STRING"));

        for (int cycle = 0; cycle < 3; cycle++) {
            plc.listListener.apply(vars);
            mqtt.published.clear();
            mqtt.nonRetained.clear();
            plc.diffListener.accept(new PlccomsDiff(ds, "SRCINV")); // DIFF before GET
            plc.diffListener.accept(new PlccomsDiff(ds, "SRCINV", true));
            plc.diffListener.accept(new PlccomsDiff(ds, "SRCINV")); // DIFF after GET
            plc.diffListener.accept(new PlccomsDiff(aqua, "SRCAQUA", true));
            plc.diffListener.accept(new PlccomsDiff("Room.Text", "initial state", true));
            plc.diffListener.accept(new PlccomsDiff("Room.Text", "new state"));
            assertEquals(List.of("state/room.text=initial state", "state/room.text=new state"), mqtt.published);
            assertTrue(mqtt.nonRetained.isEmpty());

            plc.diffListener.accept(new PlccomsDiff(ds, "SRCGRID"));
            plc.diffListener.accept(new PlccomsDiff(ds, "SRCGRID"));
            plc.diffListener.accept(new PlccomsDiff(aqua, "SRCINV"));
            assertEquals(List.of("plc-fve/src-switch/dsinvswitch/set=SRCGRID",
                    "plc-fve/src-switch/aquaswitch/set=SRCINV"), mqtt.nonRetained);
        }

        mqtt.connected = false;
        plc.diffListener.accept(new PlccomsDiff(ds, "SRCINV"));
        mqtt.connected = true;
        plc.diffListener.accept(new PlccomsDiff(ds, "SRCINV"));
        assertEquals(2, mqtt.nonRetained.size());
        plc.diffListener.accept(new PlccomsDiff(ds, "SRCGRID"));
        assertEquals("plc-fve/src-switch/dsinvswitch/set=SRCGRID", mqtt.nonRetained.get(2));
    }

    @Test
    public void mqttRetainDefaultsToTrue() throws Exception {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        String mapping = "var: Room.Text\nstate-topic: state/room.text\n";
        assertTrue(yaml.readValue(mapping, VarMappingConfig.class).mqttRetain);
        assertTrue(yaml.readValue(mapping + "mqtt-retain: true\n",
                VarMappingConfig.class).mqttRetain);
    }

    private static Config config() {
        PlccomsConfig plc = new PlccomsConfig();
        plc.host = "localhost";
        plc.port = 5010;
        MqttHaDiscoveryConfig discovery = new MqttHaDiscoveryConfig("true", "homeassistant", "plc", "PLC", "Foxtrot");
        MqttConfig mqtt = new MqttConfig(null, null, null, null, null, "test", discovery);
        VarMappingConfig mapping = HomeAssistantDiscoveryPublisherTest.mappingConfig(
                "Room\\.Text", "state/{0}", "cmd/{0}", "room text");
        return new Config(plc, mqtt, null, new VarMappingConfig[]{mapping});
    }

    private static class FakeMqtt implements MqttGateway {
        boolean connected;
        MessageHandler handler;
        final List<String> published = new ArrayList<>();
        final List<String> nonRetained = new ArrayList<>();

        public void connect(MqttConfig config) { connected = true; }
        public void close() { }
        public void publish(String topic, String value, boolean retained) {
            if (!connected) throw new IllegalStateException("MQTT disconnected");
            (retained ? published : nonRetained).add(topic + "=" + value);
        }
        public void subscribe(Collection<String> topics, MessageHandler handler) { this.handler = handler; }
    }

    private static class FakePlc implements PlcGateway {
        boolean connected;
        Function<Collection<PlccomsVar>, Collection<PlccomsVar>> listListener;
        Consumer<PlccomsDiff> diffListener;
        final List<String> writes = new ArrayList<>();

        public void setListeners(Function<Collection<PlccomsVar>, Collection<PlccomsVar>> listListener,
                                 Consumer<PlccomsDiff> diffListener) {
            this.listListener = listListener;
            this.diffListener = diffListener;
        }
        public void connect(PlccomsConfig config, boolean haDiscovery) { connected = true; }
        public void close() { }
        public void setVar(String name, Object value) { writes.add(name + "=" + value); }
        public String getPlcVersion() { return "6.2"; }
        public String getPlcIp() { return "192.0.2.1"; }
    }
}
