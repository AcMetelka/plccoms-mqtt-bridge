package ocervinka.plcmqttbridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ocervinka.plcmqttbridge.mqtt.MqttGateway;
import ocervinka.plcmqttbridge.mqtt.MqttHaDiscoveryConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class HomeAssistantDiscoveryPublisher {
    private final MqttGateway mqtt;
    private final MqttHaDiscoveryConfig config;
    private final ObjectMapper objectMapper;

    public HomeAssistantDiscoveryPublisher(MqttGateway mqtt, MqttHaDiscoveryConfig config) {
        this(mqtt, config, new ObjectMapper());
    }

    HomeAssistantDiscoveryPublisher(MqttGateway mqtt, MqttHaDiscoveryConfig config, ObjectMapper objectMapper) {
        this.mqtt = mqtt;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    public DiscoveryMessage build(String variableName, VarMapping mapping, String plcVersion, String plcIp) {
        String component = getComponent(mapping);
        String entityId = variableName.replace('.', '_').replaceAll("\\[(\\d+)]", "_$1").toLowerCase();
        String topic = config.prefix + "/" + component + "/" + config.deviceName + "/" + entityId + "/config";

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", getEntityName(mapping, variableName));
        payload.put("unique_id", config.deviceName + "_" + entityId);
        payload.put("state_topic", mapping.stateTopic);
        if (mapping.cmdTopic != null) payload.put("command_topic", mapping.cmdTopic);
        if (mapping.haDeviceClass != null) payload.put("device_class", mapping.haDeviceClass);
        if ("sensor".equals(component)) {
            payload.put("state_class", "measurement");
            if (mapping.haUnitOfMeas != null) payload.put("unit_of_measurement", mapping.haUnitOfMeas);
        } else if ("number".equals(component)) {
            if (mapping.haUnitOfMeas != null) payload.put("unit_of_measurement", mapping.haUnitOfMeas);
            if (mapping.haNumberMin != null) payload.put("min", mapping.haNumberMin);
            if (mapping.haNumberMax != null) payload.put("max", mapping.haNumberMax);
            if (mapping.haNumberStep != null) payload.put("step", mapping.haNumberStep);
        }
        if ("select".equals(component) && mapping.config.haOptions != null) {
            payload.put("options", new ArrayList<>(mapping.config.haOptions.values()));
        }

        Map<String, Object> device = new HashMap<>();
        device.put("ids", config.deviceName);
        device.put("name", config.deviceFriendlyName);
        device.put("mdl", config.deviceModel);
        device.put("sw", plcVersion);
        device.put("mf", "Teco a.s.");
        List<List<String>> connections = new ArrayList<>();
        connections.add(Arrays.asList("ip", plcIp));
        device.put("cns", connections);
        payload.put("device", device);

        try {
            return new DiscoveryMessage(topic, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public DiscoveryMessage publish(String variableName, VarMapping mapping, String plcVersion, String plcIp) throws Exception {
        DiscoveryMessage message = build(variableName, mapping, plcVersion, plcIp);
        mqtt.publish(message.topic, message.payload);
        return message;
    }

    static String getComponent(VarMapping mapping) {
        if (mapping.haComponent != null) return mapping.haComponent;
        boolean hasCommand = mapping.config.cmdTopic != null;
        if (mapping.config.isOneToOnState()) return hasCommand ? "switch" : "binary_sensor";
        if (mapping.config.isEnum()) return hasCommand ? "select" : "sensor";
        return hasCommand ? "number" : "sensor";
    }

    static String getEntityName(VarMapping mapping, String variableName) {
        if (mapping.haName != null && !mapping.haName.isBlank()) {
            return Arrays.stream(mapping.haName.replaceAll("[._]", " ").split("\\s+"))
                    .map(word -> !word.isEmpty() && Character.isLowerCase(word.charAt(0))
                            ? Character.toUpperCase(word.charAt(0)) + word.substring(1) : word)
                    .collect(Collectors.joining(" "));
        }
        return Arrays.stream(variableName.replaceAll("\\[(\\d+)]", " $1").split("\\."))
                .map(word -> word.isEmpty() ? word
                        : Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }

    public static class DiscoveryMessage {
        public final String topic;
        public final String payload;

        DiscoveryMessage(String topic, String payload) {
            this.topic = topic;
            this.payload = payload;
        }
    }
}
