package ocervinka.plcmqttbridge.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import ocervinka.plcmqttbridge.functions.Decimals;
import ocervinka.plcmqttbridge.functions.Noop;
import ocervinka.plcmqttbridge.functions.OnToOne;
import ocervinka.plcmqttbridge.functions.OneToOn;
import org.apache.logging.log4j.Level;

import java.text.MessageFormat;
import java.util.function.Function;
import java.util.regex.Pattern;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE)
public class VarMappingConfig {
    private static final Function<String, String> NOOP = new Noop();
    private static final Function<String, String> ONE_TO_ON = new OneToOn();
    private static final Function<String, String> ON_TO_ONE = new OnToOne();

    public final Pattern varPattern;
    public final Double varDelta;
    public final MessageFormat stateTopic;
    public final Function<String, String> stateFunction;
    public final Function<String, String> decimalFunction;
    public final MessageFormat cmdTopic;
    public final Function<String, String> cmdFunction;
    public final boolean mqttToPlcOnly;
    public final MessageFormat haName;
    public final MessageFormat haComponent;
    public final MessageFormat haDeviceClass;
    public final MessageFormat haUnitOfMeas;
    public final Level logLevel;

    @JsonCreator
    public VarMappingConfig(
            @JsonProperty(value = "var", required = true) String var,
            @JsonProperty("var-delta") Double varDelta,
            @JsonProperty(value = "state-topic", required = true) String stateTopic,
            @JsonProperty("state-function") String stateFunction,
            @JsonProperty("state-decimals") Integer stateDecimals,
            @JsonProperty("cmd-topic") String cmdTopic,
            @JsonProperty("cmd-function") String cmdFunction,
            @JsonProperty("mqtt-to-plc-only") Boolean mqttToPlcOnly,
            @JsonProperty("ha-name") String haName,
            @JsonProperty("ha-component") String haComponent,
            @JsonProperty("ha-device-class") String haDeviceClass,
            @JsonProperty("ha-unit-of-meas") String haUnitOfMeas,
            @JsonProperty("log-level") String logLevel)
    {
        this.varPattern = Pattern.compile(var);
        this.varDelta = varDelta;
        this.stateTopic = new MessageFormat(stateTopic);
        this.stateFunction = getFunction(stateFunction);
        this.decimalFunction = stateDecimals != null ? new Decimals(stateDecimals) : NOOP;
        this.cmdTopic = cmdTopic == null ? null : new MessageFormat(cmdTopic);
        this.cmdFunction = getFunction(cmdFunction);
        this.mqttToPlcOnly = mqttToPlcOnly != null && mqttToPlcOnly;
        this.haName = haName == null ? null : new MessageFormat(haName);
        this.haComponent = haComponent == null ? null : new MessageFormat(haComponent);
        this.haDeviceClass = haDeviceClass == null ? null : new MessageFormat(haDeviceClass);
        this.haUnitOfMeas = haUnitOfMeas == null ? null : new MessageFormat(haUnitOfMeas);
        this.logLevel = Level.toLevel(logLevel, Level.INFO);
    }

    public static Function<String, String> getFunction(String functionName) {
        if (functionName == null) {
            return NOOP;
        } else if ("OneToOn".equals(functionName)) {
            return ONE_TO_ON;
        } else if ("OnToOne".equals(functionName)) {
            return ON_TO_ONE;
        } else {
            throw new IllegalArgumentException("Unknown converter " + functionName);
        }
    }

    public boolean isOneToOnState() { return stateFunction == ONE_TO_ON; }
    public boolean isOnToOneCmd() { return cmdFunction == ON_TO_ONE; }

}
