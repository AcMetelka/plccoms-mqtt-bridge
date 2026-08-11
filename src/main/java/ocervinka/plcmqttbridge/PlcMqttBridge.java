package ocervinka.plcmqttbridge;

import ocervinka.plcmqttbridge.config.Config;
import ocervinka.plcmqttbridge.config.VarMappingConfig;
import ocervinka.plcmqttbridge.mqtt.Mqtt;
import ocervinka.plcmqttbridge.mqtt.MqttGateway;
import ocervinka.plcmqttbridge.plccoms.PlcGateway;
import ocervinka.plcmqttbridge.plccoms.PlccomsClient;
import ocervinka.plcmqttbridge.plccoms.PlccomsDiff;
import ocervinka.plcmqttbridge.plccoms.PlccomsVar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    private final MqttGateway mqttClient;
    private final PlcGateway plccomsClient;
    private final HomeAssistantDiscoveryPublisher discoveryPublisher;

    private final Map<String, VarMapping> varMappingsByTopic = new HashMap<>();
    private final Map<String, VarMapping> varMappingsByVariable = new HashMap<>();

    public static void main(String[] args) throws Exception {
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
            } catch (Exception e) {
                LOGGER.error("Failed to shut down pclcoms-mqtt-bridge", e);
            }
            LOGGER.info("plccoms-mqtt-bridge shut down");
        }, "ShutdownHookThread"));
    }

    public PlcMqttBridge(Config config) {
        this(config, new Mqtt(), new PlccomsClient());
    }

    public PlcMqttBridge(Config config, MqttGateway mqttClient, PlcGateway plccomsClient) {
        this.config = config;
        this.mqttClient = mqttClient;
        this.plccomsClient = plccomsClient;
        this.discoveryPublisher = new HomeAssistantDiscoveryPublisher(mqttClient, config.mqtt.haDiscovery);
        plccomsClient.setListeners(this::onList, this::onDiff);
    }

    public void connect() throws Exception {
        mqttClient.connect(config.mqtt);
        plccomsClient.connect(config.plccoms, config.mqtt.haDiscovery.enabled);
    }

    public void close() throws Exception {
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
                    Double haNumberMin      = config.haNumberMin;
                    Double haNumberMax      = config.haNumberMax;
                    Double haNumberStep     = config.haNumberStep;

                    varMappingsByVariable.put(var.name, new VarMapping(config, var.name, stateTopic, cmdTopic, mqttToPlcOnly, haName, haComponent, haDevClass, haUnit, haNumberMin, haNumberMax, haNumberStep));
                    LOGGER.info("State topic mapped:   {} {} -> {}", var.name, var.type, stateTopic);
                    if (config.cmdTopic != null) {
                        varMappingsByTopic.put(cmdTopic, new VarMapping(config, var.name, stateTopic, cmdTopic, mqttToPlcOnly, haName, haComponent, haDevClass, haUnit, haNumberMin, haNumberMax, haNumberStep));
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
                VarMapping mapping = entry.getValue();
                if (mapping.mqttToPlcOnly)
                    continue;
                try {
                    HomeAssistantDiscoveryPublisher.DiscoveryMessage message = discoveryPublisher.publish(
                            entry.getKey(), mapping, plccomsClient.getPlcVersion(), plccomsClient.getPlcIp());
                    LOGGER.info("Published HA discovery: {}", message.topic);
                } catch (Exception e) {
                    LOGGER.error("Failed to publish Home Assistant discovery for {}", mapping.haName, e);
                }
            }
        }

        // ** Subscribe to MQTT Topics **
        try {
            mqttClient.subscribe(varMappingsByTopic.keySet(), (topic, message) -> {
                VarMapping varMapping = varMappingsByTopic.get(topic);
                String inputValue = new String(message);
                String convertedValue = varMapping.config.enumReverseFunction.apply(inputValue);
                convertedValue = varMapping.config.cmdFunction.apply(convertedValue);
                LOGGER.log(varMapping.config.logLevel, "MQTT->PLC: {},{} -> {},{}",
                        topic, inputValue, varMapping.varName, convertedValue);
                plccomsClient.setVar(varMapping.varName, convertedValue);
            });
        } catch (Exception e) {
            LOGGER.error("Failed to subscribe to topic(s)", e);
        }

        return varMappingsByVariable.entrySet().stream()
                .map(e -> new PlccomsVar(e.getKey(), e.getValue().config.varDelta))
                .collect(Collectors.toList());
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
            convertedValue = varMapping.config.enumFunction.apply(convertedValue);
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
