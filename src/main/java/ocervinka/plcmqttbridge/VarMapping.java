package ocervinka.plcmqttbridge;

import ocervinka.plcmqttbridge.config.VarMappingConfig;

public class VarMapping {

    public final VarMappingConfig config;

    // PLC variable name
    public final String varName;

    // MQTT topics
    public final String stateTopic;
    public final String cmdTopic; // may be null
    public final boolean mqttToPlcOnly;

    // HA metadata (already resolved / formatted)
    public final String haName;        // may be null
    public final String haComponent;   // may be null
    public final String haDeviceClass; // may be null
    public final String haUnitOfMeas;  // may be null

    public VarMapping(
            VarMappingConfig config,
            String varName,
            String stateTopic,
            String cmdTopic,
            boolean mqttToPlcOnly,
            String haName,
            String haComponent,
            String haDeviceClass,
            String haUnitOfMeas
    ) {
        this.config = config;
        this.varName = varName;
        this.stateTopic = stateTopic;
        this.cmdTopic = cmdTopic;
        this.mqttToPlcOnly = mqttToPlcOnly;
        this.haName = haName;
        this.haComponent = haComponent;
        this.haDeviceClass = haDeviceClass;
        this.haUnitOfMeas = haUnitOfMeas;
    }
}
