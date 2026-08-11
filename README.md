# plccoms-mqtt-bridge

##### Bi-directional communication bridge between Teco's [PLCComS](https://www.tecomat.com/download/software-and-firmware/plccoms/) and [MQTT](https://en.wikipedia.org/wiki/MQTT) broker.

`plccoms-mqtt-bridge` was written to integrate home automation
hardware based on Tecomat
[Foxtrot](https://www.tecomat.com/products/cat/cz/plc-tecomat-foxtrot-3/)
(CP-1xxx, CP-2xxx) or the original Inels CU2-01M PLC with open source home
automation software
([OpenHAB](https://www.openhab.org/),
[Home Assistant](https://www.home-assistant.io/), ...).
There are potentially other use-cases for PLC - MQTT integration and
it may also work with other Teco's PLCs.

### How it works

When `PLCComS` and `plccoms-mqtt-bridge` are configured to work together,
`plccoms-mqtt-bridge` publishes changes of selected PLC variables (e.g. wall
switch pressed) to MQTT topics. When a home automation software issues
a command (e.g. turn on a light) `plccoms-mqtt-bridge` reads it from MQTT topic
and sends it to `PLCComS` to set a PLC variable. If all components runs in the
same LAN, both the monitor and control paths take only few milliseconds to
execute which is fast enough to program actions in home automation software
or in [Node-RED](https://nodered.org/).

Note that Teco provides MQTT publisher and subscriber libraries for their
development tool Mosaic, however there is no single point of control over
what variables get published and what topics get subscribed.

### PLC Variable mapping 

`plccoms-mqtt-bridge` uses [YAML configuration](#yaml-configuration) file to
define mapping between PLC variables and topic names. The definition uses
regular expressions with capturing groups to allow creating simple and
flexible mapping.

The following configuration results in forwarding two PLC variables to two
topics. All other PLC variable are ignored. 

```yaml
var-mapping:
  - var: "OUTPUT_R0B_LIGHT_LIVINGROOM"
    state-topic: "home/living_room/light"
  - var: "OUTPUT_R0B_LIGHT_KITCHEN"
    state-topic: "home/kitchen/light"
```

The following configuration results in forwarding all PLC variables to
topics "home/{variable-name}". No PLC variables are ignored.

```yaml
var-mapping:
  - var: ".*"
    state-topic: "home/{0}"
```

Where {0} is a full match (in this case a full variable name).
The same result can be achived using capturing group which are indexed from
one: 

```yaml
var-mapping:
  - var: "(.*)"
    state-topic: "home/{1}"
```
    
If a PLC program uses some naming convention for variables,
e.g. `{UNIT_TYPE}_{UNIT_NAME}_{ITEM}_{LOCATION}` with
variables e.g. `RELAY_R02_LIGHT_LIVINGROOM` or `RELAY_R05_LIGHT_KITCHEN`,
these can be mapped to topics `home/livingroom/light` and
`home/kitchen/light` using a single mapping:

```yaml
var-mapping:
  - var: "RELAY_(.*)_(.*)_(.*)"
    state-topic: "home/{3}/{2}"
```

The same result with non-capturing groups: 

```yaml
var-mapping:
  - var: "RELAY_(?:.*)_(.*)_(.*)"
    state-topic: "home/{2}/{1}"
```

In case PLC program does not use the word "LIGHT" in variable names but all
lights are controlled by certain units:

```yaml
var-mapping:
  - var: "RELAY_(?:R01|R03|R05)_(.*)"
    state-topic: "home/{1}/light"
```

Note that only the first match in the `var-mapping` collection is used and
therefore each variable is forwarded to either a single topic or, if there
is no match in the collection, it is not forwarded at all.
`plccoms-mqtt-bridge` subscribes to `PLCComS` to be notified about changes of
matched variables only.

It is possible to exclude some PLC variables using `var-blacklist` which,
in some cases, can simplify mapping definition. 

It is possible to add simple value conversion (rather than pure forwarding)
E.g. Home Assistant uses `ON` and `OFF` defaults for binary values rather than 
`1` and `0` used by PLC. Adding a conversion function along the mapping
definition will spare two or four (if using command topic) lines of every
item/topic in Home Assistant's configuration.

### YAML configuration

Root fields:

#### plccoms:

Field | Default value | Description
------| --------------|------------------------
host  | localhost     | PLCComS host name or IP
port  | 5010          | PLCComS TCP port

#### mqtt:

Field    | Default value  | Description
---------| ---------------|------------------------
scheme   | tcp            | tcp or tls 
host     | localhost      | MQTT broker host name or IP
port     | 1883 for tcp and 8883 fot tls | MQTT TCP port
username | N/A            | optional parameter
password | N/A            | optional parameter
clientId | MQTT client id | random UUID 
watchdog-enabled | Enable MQTT publish/subscribe round-trip monitoring | true
watchdog-interval-seconds | Interval between MQTT transport probes | 30
watchdog-timeout-seconds | Maximum wait for the probe message | 10
watchdog-failure-threshold | Consecutive failures before client recreation | 3
reconnect-cooldown-seconds | Minimum delay between forced client recreations | 60
availability-topic | Retained MQTT transport availability topic | `plccoms-mqtt-bridge/<clientId>/mqtt-availability`

The availability topic reports only the MQTT transport state (`online` or
`offline`); it does not assert that PLCComS or the PLC is healthy. The client
uses a retained Last Will with value `offline`. The watchdog subscribes to
`<availability-topic>/probe` and periodically performs a non-retained QoS 1
round trip. Broker ACLs must allow the configured client to publish and
subscribe to both topics.

`cleanSession=true` is intentionally retained. Command subscriptions are kept
in a thread-safe local registry and restored after every initial connection and
automatic reconnect. A stable configured `clientId` is recommended for clear
broker logs and stable health topic names, but no persistent broker session is
required.
 
#### var-blacklist:

A collection of regular expressions. When `plccoms-mqtt-bridge` starts each PLC
variable (enumerated by LIST command) is tested against this blacklist.
If matched, it is dropped and not used in variable mapping.
 
#### var-mapping:
 
Field          | Default value  | Description
---------------| ---------------|------------------------
var            | N/A (required) | Regular expression matching PLC variable(s).
state-topic    | N/A (required) | MQTT topic used by `plccoms-mqtt-bridge` to publish PLC variable changes. The topic name can include {index} to refer to (one-based) capturing group or {0} to refer to a full match.  
state-function | Noop           | A function to convert value from PLC before it is published to topic (Noop, OneToOn, OnToOne)
cmd-topic      | N/A            | MQTT topic `plccoms-mqtt-bridge` subscribes to and forwards messages to PLC. The topic name can include {index} to refer to (one-based) capturing group or {0} to refer to a full match.
cmd-function   | Noop           | A function to convert value read from topic before it is sent to PLC. (Noop, OneToOn, OnToOne)
var-delta      | N/A (optional) | Instructs `plccoms` to send updates of numerical values only if they change by more than delta. This can reduce network traffic (including MQTT) caused by e.g. some temperature sensors.
log-level      | info           | Log4Jv2 log levels (info, debug, trace)
 
### Build and run locally

```bash
./gradlew clean runLocal
```

### Manually verify MQTT lifecycle recovery

1. Start the bridge, broker and PLCComS, then wait for the availability topic
   to become retained `online`. Confirm the log reports the command topic
   subscription and successful watchdog round trips.
2. Restart the MQTT broker. Confirm `connectionLost`, followed by
   `connectComplete` and restoration of every command subscription. Publish one
   command once (for example `mosquitto_pub -q 1 -t home/light/relay-set -m ON`)
   and verify the PLCComS log or capture contains exactly one corresponding
   `SET` command.
3. Repeat with a short network interruption (for example temporarily block the
   broker port for longer than one keep-alive interval), then restore the
   network. Again publish one command and verify exactly one PLC `SET`.
4. To exercise forced recovery, block only the probe topic using broker ACLs or
   drop its messages. After `watchdog-failure-threshold` timeouts, verify one
   client recreation, then verify that further attempts respect
   `reconnect-cooldown-seconds` rather than forming a reconnect storm.

Application state/discovery publications retain their existing QoS 0 and
retained behavior; command subscriptions retain their existing QoS 0 behavior.

### Build and run in container

```bash
./gradlew clean jibDockerBuild
docker run --network host -v $(pwd)/deploy/docker/config.yaml:/etc/plccoms-mqtt-bridge/config.yaml ocervinka/plccoms-mqtt-bridge
```

If only the second command is called, a pre-build image will be pulled from
public docker registry. 

`plccoms-mqtt-bridge` needs to be configured
(See [YAML configuration](#yaml-configuration)) to connect to to `PLCComS` and
MQTT broker (e.g. [Mosquitto](https://mosquitto.org/)) both ideally running in
containers. Some newer Teco PLC models have `PLCComS` integrated so there is
no need to host the process outside of PLC.

### TODO: Helm Chart
