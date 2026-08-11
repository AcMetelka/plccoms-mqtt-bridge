package ocervinka.plcmqttbridge.mqtt;

import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

interface MqttClientFacade {
    void setCallback(MqttCallbackExtended callback);
    void connect(MqttConnectOptions options) throws MqttException;
    boolean isConnected();
    void publish(String topic, MqttMessage message) throws MqttException;
    void subscribe(String topic, int qos, IMqttMessageListener listener) throws MqttException;
    void disconnect() throws MqttException;
    void close() throws MqttException;
}
