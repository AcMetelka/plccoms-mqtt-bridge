package ocervinka.plcmqttbridge.plccoms;

public class PlccomsDiff {
    public final String name;
    public final String value;

    public final boolean snapshot;

    public PlccomsDiff(String name, String value) {
        this(name, value, false);
    }

    public PlccomsDiff(String name, String value, boolean snapshot) {
        this.name = name;
        this.value = value;
        this.snapshot = snapshot;
    }
}
