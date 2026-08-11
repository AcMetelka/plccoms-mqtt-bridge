package ocervinka.plcmqttbridge.mqtt;

import org.eclipse.paho.client.mqttv3.*;

final class PahoMqttClientFacade implements MqttClientFacade {
    private final IMqttClient delegate;

    PahoMqttClientFacade(IMqttClient delegate) {
        this.delegate = delegate;
    }

    public void setCallback(MqttCallbackExtended callback) { delegate.setCallback(callback); }
    public void connect(MqttConnectOptions options) throws MqttException { delegate.connect(options); }
    public boolean isConnected() { return delegate.isConnected(); }
    public void publish(String topic, MqttMessage message) throws MqttException { delegate.publish(topic, message); }
    public void subscribe(String topic, int qos, IMqttMessageListener listener) throws MqttException {
        delegate.subscribe(topic, qos, listener);
    }
    public void disconnect() throws MqttException { delegate.disconnect(); }
    public void close() throws MqttException { delegate.close(); }
}
