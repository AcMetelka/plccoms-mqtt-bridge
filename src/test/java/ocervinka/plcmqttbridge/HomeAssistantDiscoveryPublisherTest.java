package ocervinka.plcmqttbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ocervinka.plcmqttbridge.config.VarMappingConfig;
import ocervinka.plcmqttbridge.mqtt.MqttConfig;
import ocervinka.plcmqttbridge.mqtt.MqttGateway;
import ocervinka.plcmqttbridge.mqtt.MqttHaDiscoveryConfig;
import org.junit.Test;

import java.util.Collection;

import static org.junit.Assert.assertEquals;

public class HomeAssistantDiscoveryPublisherTest {
    @Test
    public void buildsExistingTopicAndPayloadShape() throws Exception {
        MqttHaDiscoveryConfig config = new MqttHaDiscoveryConfig("true", "homeassistant", "plc", "Main PLC", "Foxtrot");
        HomeAssistantDiscoveryPublisher publisher = new HomeAssistantDiscoveryPublisher(new NoopMqtt(), config);
        VarMappingConfig mappingConfig = mappingConfig("room\\.Temperature", "home/{0}", "set/{0}", "room temperature");
        VarMapping mapping = new VarMapping(mappingConfig, "room.Temperature", "home/room.temperature",
                "set/room.temperature", false, "room temperature", null, "temperature", "°C", -10.0, 40.0, 0.5);

        HomeAssistantDiscoveryPublisher.DiscoveryMessage message = publisher.build(
                "room.Temperature", mapping, "6.2", "192.0.2.1");
        JsonNode payload = new ObjectMapper().readTree(message.payload);

        assertEquals("homeassistant/number/plc/room_temperature/config", message.topic);
        assertEquals("Room Temperature", payload.get("name").asText());
        assertEquals("plc_room_temperature", payload.get("unique_id").asText());
        assertEquals("home/room.temperature", payload.get("state_topic").asText());
        assertEquals("set/room.temperature", payload.get("command_topic").asText());
        assertEquals("temperature", payload.get("device_class").asText());
        assertEquals("Main PLC", payload.get("device").get("name").asText());
        assertEquals("6.2", payload.get("device").get("sw").asText());
        assertEquals("192.0.2.1", payload.get("device").get("cns").get(0).get(1).asText());
    }

    static VarMappingConfig mappingConfig(String var, String stateTopic, String cmdTopic, String haName) {
        return new VarMappingConfig(var, null, stateTopic, null, null, cmdTopic, null, null,
                haName, null, "temperature", "°C", null, -10.0, 40.0, 0.5, null);
    }

    private static class NoopMqtt implements MqttGateway {
        public void connect(MqttConfig config) { }
        public void close() { }
        public void publish(String topic, String value) { }
        public void subscribe(Collection<String> topics, MessageHandler handler) { }
    }
}
