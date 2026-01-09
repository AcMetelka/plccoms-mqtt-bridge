package ocervinka.plcmqttbridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import ocervinka.plcmqttbridge.config.Config;
import ocervinka.plcmqttbridge.config.VarMappingConfig;
import ocervinka.plcmqttbridge.mqtt.Mqtt;
import ocervinka.plcmqttbridge.plccoms.PlccomsClient;
import ocervinka.plcmqttbridge.plccoms.PlccomsDiff;
import ocervinka.plcmqttbridge.plccoms.PlccomsVar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


public class PlcMqttBridge {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String DEFAULT_CONFIG = "/etc/plccoms-mqtt-bridge/config.yaml";

    private final Config config;
    private final Mqtt mqttClient;
    private final PlccomsClient plccomsClient;

    private final Map<String, VarMapping> varMappingsByTopic = new HashMap<>();
    private final Map<String, VarMapping> varMappingsByVariable = new HashMap<>();

    public static void main(String[] args) throws MqttException, IOException {
        LOGGER.info("Starting plccoms-mqtt-bridge");
        LOGGER.info("Version: {}", System.getenv("VERSION_TAG"));
        String configFile = args.length == 0 ? DEFAULT_CONFIG : args[0];
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        Config config = objectMapper.readValue(new FileReader(configFile), Config.class);
        LOGGER.info("Configuration: {}", configFile);
        PlcMqttBridge plcMqttBridge = new PlcMqttBridge(config);
        plcMqttBridge.connect();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down plccoms-mqtt-bridge...");
            try {
                plcMqttBridge.close();
            } catch (MqttException e) {
                LOGGER.error("Failed to shut down pclcoms-mqtt-bridge", e);
            }
            LOGGER.info("plccoms-mqtt-bridge shut down");
        }, "ShutdownHookThread"));
    }

    public PlcMqttBridge(Config config) {
        this.config = config;
        this.mqttClient = new Mqtt();
        this.plccomsClient = new PlccomsClient(this::onList, this::onDiff);
    }

    private void connect() throws MqttException {
        mqttClient.connect(config.mqtt);
        plccomsClient.connect(config.plccoms, config.mqtt.haDiscovery.enabled);
    }

    private void close() throws MqttException {
        mqttClient.close();
        plccomsClient.close();
    }

    private Collection<PlccomsVar> onList(Collection<PlccomsVar> listedVars) {
        int blacklistedCount = 0;
        Map<String, Integer> blacklistedCountByPattern = new HashMap<>();
        List<String> unmappedVars = new ArrayList<>();

        for_each_label:
        for (PlccomsVar var : listedVars) {
            for (Pattern pattern : config.varBlacklist) {
                Matcher matcher = pattern.matcher(var.name);
                if (matcher.matches()) {
                    blacklistedCount++;
                    blacklistedCountByPattern.compute(pattern.pattern(), (k,v) -> v == null ? 1 : (v + 1));
                    continue for_each_label;
                }
            }

            for (VarMappingConfig config : config.varMappings) {
                Matcher matcher = config.varPattern.matcher(var.name);
                if (matcher.matches()) {
                    String stateTopic       = config.stateTopic.format(getGroups(matcher));
                    String cmdTopic         = config.cmdTopic != null ? config.cmdTopic.format(getGroups(matcher)) : null;
                    boolean mqttToPlcOnly   = config.mqttToPlcOnly;
                    String haName           = config.haName != null ? config.haName.format(getGroups(matcher)) : null;
                    String haComponent      = config.haComponent != null ? config.haComponent.format(getGroups(matcher)) : null;
                    String haDevClass       = config.haDeviceClass != null ? config.haDeviceClass.format(getGroups(matcher)) : null;
                    String haUnit           = config.haUnitOfMeas != null ? config.haUnitOfMeas.format(getGroups(matcher)) : null;

                    varMappingsByVariable.put(var.name, new VarMapping(config, var.name, stateTopic, cmdTopic, mqttToPlcOnly, haName, haComponent, haDevClass, haUnit));
                    LOGGER.info("State topic mapped:   {} {} -> {}", var.name, var.type, stateTopic);
                    if (config.cmdTopic != null) {
                        varMappingsByTopic.put(cmdTopic, new VarMapping(config, var.name, stateTopic, cmdTopic, mqttToPlcOnly, haName, haComponent, haDevClass, haUnit));
                        LOGGER.info("Command topic mapped: {} {} <- {}", var.name, var.type, stateTopic);
                    }
                    continue for_each_label;
                }
            }
            unmappedVars.add(var.name + " " + var.type);
        }

        LOGGER.info("Total number of variables listed by PLCComS: {}", listedVars.size());
        LOGGER.info("Blacklisted variables: {}", blacklistedCount);
        for (Map.Entry<String, Integer> entry : blacklistedCountByPattern.entrySet()) {
            LOGGER.info("  {}: {}", entry.getKey(), entry.getValue());
        }
        LOGGER.info("Variables available for mapping: {}", listedVars.size() - blacklistedCount);
        LOGGER.info("Variables mapped to state topics: {}", varMappingsByVariable.size());
        LOGGER.info("Variables mapped from command topics: {}", varMappingsByTopic.size());
        LOGGER.info("Unmapped variables: {}", unmappedVars.size());
        for (String unmappedVar : unmappedVars) {
            LOGGER.info("  {}", unmappedVar);
        }

        // ** Add HomeAssistant Discovery Topics **
        if (config.mqtt.haDiscovery.enabled) {
            for (Map.Entry<String, VarMapping> entry : varMappingsByVariable.entrySet()) {
                String haPrefix = config.mqtt.haDiscovery.prefix;
                String deviceName = config.mqtt.haDiscovery.deviceName;
                String deviceFriendlyName = config.mqtt.haDiscovery.deviceFriendlyName;
                String deviceModel = config.mqtt.haDiscovery.deviceModel;
                String plcDeviceVersion = plccomsClient.plcVersion;
                String plcDeviceIp = plccomsClient.plcIp;

                VarMapping mapping = entry.getValue();
                if (mapping.mqttToPlcOnly)
                    continue;
                String entityName = getEntityName(mapping, entry.getKey());
                String component = getComponent(mapping, mapping.haComponent);
                String entityId = entry.getKey().replace('.', '_').replaceAll("\\[(\\d+)]", "_$1").toLowerCase();

                String haDiscoveryTopic = haPrefix + "/" + component + "/" + deviceName + "/" + entityId + "/config";

                // Home Assistant discovery payload (JSON format)
                Map<String, Object> haDiscoveryPayload = new HashMap<>();
                haDiscoveryPayload.put("name", entityName);
                haDiscoveryPayload.put("uniq_id", deviceName + "_" + entityId);
                haDiscoveryPayload.put("state_topic", mapping.stateTopic);
                if (mapping.cmdTopic != null) haDiscoveryPayload.put("command_topic", mapping.cmdTopic);
                if (mapping.haDeviceClass != null) haDiscoveryPayload.put("device_class", mapping.haDeviceClass);
                if ("sensor".equals(component)) haDiscoveryPayload.put("state_class", "measurement");
                if (mapping.haUnitOfMeas != null) haDiscoveryPayload.put("unit_of_meas", mapping.haUnitOfMeas);
                // Add device data
                Map<String, Object> device = new HashMap<>();
                device.put("ids", deviceName);
                device.put("name", deviceFriendlyName);
                device.put("mdl", deviceModel);
                device.put("sw", plcDeviceVersion);
                device.put("mf", "Teco a.s.");
                // Add connection list
                List<List<String>> cns = new ArrayList<>();
                cns.add(Arrays.asList("ip", plcDeviceIp));
                device.put("cns", cns);
                haDiscoveryPayload.put("device", device);
                // Convert to JSON
                ObjectMapper objectMapper = new ObjectMapper();
                String haDiscoveryPayloadStr;
                try {
                    haDiscoveryPayloadStr = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(haDiscoveryPayload);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }

                try {
                    mqttClient.publish(haDiscoveryTopic, haDiscoveryPayloadStr);  // Retained message
                    LOGGER.info("Published HA discovery: {}", haDiscoveryTopic);
                } catch (Exception e) {
                    LOGGER.error("Failed to publish Home Assistant discovery for {}", mapping.haName, e);
                }
            }
        }

        // ** Subscribe to MQTT Topics **
        try {
            mqttClient.subscribe(varMappingsByTopic.keySet(), (topic, message) -> {
                VarMapping varMapping = varMappingsByTopic.get(topic);
                String inputValue = new String(message.getPayload());
                String convertedValue = varMapping.config.cmdFunction.apply(inputValue);
                LOGGER.log(varMapping.config.logLevel, "MQTT->PLC: {},{} -> {},{}",
                        topic, inputValue, varMapping.stateTopic, convertedValue);
                plccomsClient.setVar(varMapping.varName, convertedValue);
            });
        } catch (MqttException e) {
            LOGGER.error("Failed to subscribe to topic(s)", e);
        }

        return varMappingsByVariable.entrySet().stream()
                .map(e -> new PlccomsVar(e.getKey(), e.getValue().config.varDelta))
                .collect(Collectors.toList());
    }

    private static String getComponent(VarMapping mapping, String haComponent) {
        boolean hasCmd      = mapping.config.cmdTopic != null;

        String component;
        if (haComponent != null) {
            component = haComponent;
        } else if (mapping.config.isOneToOnState()) {
            component = hasCmd ? "switch" : "binary_sensor";
        } else {
            component = hasCmd ? "number" : "sensor";
        }
        return component;
    }
    private static String getEntityName(VarMapping mapping, String varName) {
        if (mapping.haName != null && !mapping.haName.isBlank()) {
            return Arrays.stream(
                            mapping.haName
                                    .replaceAll("[._]", " ")
                                    .split("\\s+")
                    )
                    .map(word -> {
                        if (!word.isEmpty() && Character.isLowerCase(word.charAt(0))) {
                            return Character.toUpperCase(word.charAt(0)) + word.substring(1);
                        }
                        return word;
                    })
                    .collect(Collectors.joining(" "));
        }
        // Fallback: name from PLC variable name
        return Arrays.stream(
                        varName
                                .replaceAll("\\[(\\d+)]", " $1")
                                .split("\\.")
                )
                .map(word ->
                        word.isEmpty()
                                ? word
                                : Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase()
                )
                .collect(Collectors.joining(" "));
    }

    private void onDiff(PlccomsDiff diff) {
        VarMapping varMapping = varMappingsByVariable.get(diff.name);
        if (varMapping == null) {
            LOGGER.warn("Received unexpected variable from PLC: {}", diff.name);
            return;
        }

        if (varMapping.config.mqttToPlcOnly) {
            LOGGER.debug("PLC->MQTT suppressed (mqtt-to-plc-only): {}", diff.name);
            return;
        }

        String convertedValue;
        try {
            convertedValue = varMapping.config.stateFunction.apply(diff.value); // OneToOn, OnToOne
            convertedValue = varMapping.config.decimalFunction.apply(convertedValue); // Decimals
            LOGGER.log(varMapping.config.logLevel, "PLC->MQTT: {},{} -> {},{}",
                    diff.name, diff.value, varMapping.stateTopic, convertedValue);
        } catch (Exception e) {
            LOGGER.error("PLC->MQTT: {},{} -> {},{}",
                    diff.name, diff.value, varMapping.stateTopic, e.getMessage());
            return;
        }

        try {
            mqttClient.publish(varMapping.stateTopic, convertedValue);
        } catch (Exception e) {
            LOGGER.error("Failed to publish to {}", varMapping.stateTopic, e);
        }
    }

    private static String[] getGroups(Matcher matcher) {
        String[] groups = new String[matcher.groupCount() + 1];
        for (int i = 0; i < groups.length; i++) {
            groups[i] = matcher.group(i).toLowerCase();
        }
        return groups;
    }
}
