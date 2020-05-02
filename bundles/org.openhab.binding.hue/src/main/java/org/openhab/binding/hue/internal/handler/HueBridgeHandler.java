/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.hue.internal.handler;

import static org.eclipse.smarthome.core.thing.Thing.*;
import static org.openhab.binding.hue.internal.HueBindingConstants.*;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.config.core.status.ConfigStatusMessage;
import org.eclipse.smarthome.core.library.types.HSBType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.binding.ConfigStatusBridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.hue.internal.ApiVersionUtils;
import org.openhab.binding.hue.internal.Config;
import org.openhab.binding.hue.internal.ConfigUpdate;
import org.openhab.binding.hue.internal.FullConfig;
import org.openhab.binding.hue.internal.FullGroup;
import org.openhab.binding.hue.internal.FullLight;
import org.openhab.binding.hue.internal.FullSensor;
import org.openhab.binding.hue.internal.HueBridge;
import org.openhab.binding.hue.internal.HueConfigStatusMessage;
import org.openhab.binding.hue.internal.StateUpdate;
import org.openhab.binding.hue.internal.config.HueBridgeConfig;
import org.openhab.binding.hue.internal.discovery.HueLightDiscoveryService;
import org.openhab.binding.hue.internal.exceptions.ApiException;
import org.openhab.binding.hue.internal.exceptions.DeviceOffException;
import org.openhab.binding.hue.internal.exceptions.EntityNotAvailableException;
import org.openhab.binding.hue.internal.exceptions.LinkButtonException;
import org.openhab.binding.hue.internal.exceptions.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link HueBridgeHandler} is the handler for a hue bridge and connects it to
 * the framework. All {@link HueLightHandler}s use the {@link HueBridgeHandler} to execute the actual commands.
 *
 * @author Dennis Nobel - Initial contribution
 * @author Oliver Libutzki - Adjustments
 * @author Kai Kreuzer - improved state handling
 * @author Andre Fuechsel - implemented getFullLights(), startSearch()
 * @author Thomas Höfer - added thing properties
 * @author Stefan Bußweiler - Added new thing status handling
 * @author Jochen Hiller - fixed status updates, use reachable=true/false for state compare
 * @author Denis Dudnik - switched to internally integrated source of Jue library
 * @author Samuel Leisering - Added support for sensor API
 * @author Christoph Weitkamp - Added support for sensor API
 * @author Laurent Garnier - Added support for groups
 */
@NonNullByDefault
public class HueBridgeHandler extends ConfigStatusBridgeHandler implements HueClient {

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Collections.singleton(THING_TYPE_BRIDGE);

    private static final String DEVICE_TYPE = "EclipseSmartHome";

    private static enum StatusType {
        ADDED,
        REMOVED,
        GONE,
        CHANGED
    }

    private final Logger logger = LoggerFactory.getLogger(HueBridgeHandler.class);

    private final Map<String, FullLight> lastLightStates = new ConcurrentHashMap<>();
    private final Map<String, FullSensor> lastSensorStates = new ConcurrentHashMap<>();
    private final Map<String, FullGroup> lastGroupStates = new ConcurrentHashMap<>();

    private final List<LightStatusListener> lightStatusListeners = new CopyOnWriteArrayList<>();
    private final List<SensorStatusListener> sensorStatusListeners = new CopyOnWriteArrayList<>();
    private final List<GroupStatusListener> groupStatusListeners = new CopyOnWriteArrayList<>();

    final ReentrantLock pollingLock = new ReentrantLock();

    abstract class PollingRunnable implements Runnable {
        @Override
        public void run() {
            try {
                pollingLock.lock();
                if (!lastBridgeConnectionState) {
                    // if user is not set in configuration try to create a new user on Hue bridge
                    if (hueBridgeConfig.getUserName() == null) {
                        hueBridge.getFullConfig();
                    }
                    lastBridgeConnectionState = tryResumeBridgeConnection();
                }
                if (lastBridgeConnectionState) {
                    doConnectedRun();
                }
            } catch (UnauthorizedException | IllegalStateException e) {
                if (isReachable(hueBridge.getIPAddress())) {
                    lastBridgeConnectionState = false;
                    onNotAuthenticated();
                } else if (lastBridgeConnectionState || thing.getStatus() == ThingStatus.INITIALIZING) {
                    lastBridgeConnectionState = false;
                    onConnectionLost();
                }
            } catch (ApiException | IOException e) {
                if (hueBridge != null && lastBridgeConnectionState) {
                    logger.debug("Connection to Hue Bridge {} lost.", hueBridge.getIPAddress());
                    lastBridgeConnectionState = false;
                    onConnectionLost();
                }
            } catch (RuntimeException e) {
                logger.warn("An unexpected error occurred: {}", e.getMessage(), e);
                lastBridgeConnectionState = false;
                onConnectionLost();
            } finally {
                pollingLock.unlock();
            }
        }

        protected abstract void doConnectedRun() throws IOException, ApiException;

        private boolean isReachable(String ipAddress) {
            try {
                // note that InetAddress.isReachable is unreliable, see
                // http://stackoverflow.com/questions/9922543/why-does-inetaddress-isreachable-return-false-when-i-can-ping-the-ip-address
                // That's why we do an HTTP access instead

                // If there is no connection, this line will fail
                hueBridge.authenticate("invalid");
            } catch (IOException e) {
                return false;
            } catch (ApiException e) {
                if (e.getMessage().contains("SocketTimeout") || e.getMessage().contains("ConnectException")
                        || e.getMessage().contains("SocketException")
                        || e.getMessage().contains("NoRouteToHostException")) {
                    return false;
                } else {
                    // this seems to be only an authentication issue
                    return true;
                }
            }
            return true;
        }
    }

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Collections.singleton(THING_TYPE_BRIDGE);

    private static final String DEVICE_TYPE = "EclipseSmartHome";

    private final Logger logger = LoggerFactory.getLogger(HueBridgeHandler.class);

    private final Map<String, FullLight> lastLightStates = new ConcurrentHashMap<>();
    private final Map<String, FullSensor> lastSensorStates = new ConcurrentHashMap<>();

    private boolean lastBridgeConnectionState = false;

    private boolean propertiesInitializedSuccessfully = false;

    private @Nullable HueLightDiscoveryService discoveryListener;
    private final Map<String, LightStatusListener> lightStatusListeners = new ConcurrentHashMap<>();
    private final Map<String, SensorStatusListener> sensorStatusListeners = new ConcurrentHashMap<>();

    private @Nullable ScheduledFuture<?> lightPollingJob;
    private @Nullable ScheduledFuture<?> sensorPollingJob;

    private @NonNullByDefault({}) HueBridge hueBridge = null;
    private @NonNullByDefault({}) HueBridgeConfig hueBridgeConfig = null;

    private final Runnable sensorPollingRunnable = new PollingRunnable() {
        @Override
        protected void doConnectedRun() throws IOException, ApiException {
            Map<String, FullSensor> lastSensorStateCopy = new HashMap<>(lastSensorStates);

            final SensorStatusListener discovery = discoveryListener;

            for (final FullSensor sensor : hueBridge.getSensors()) {
                String sensorId = sensor.getId();
                lastSensorStateCopy.remove(sensorId);

                final SensorStatusListener sensorStatusListener = sensorStatusListeners.get(sensorId);
                if (sensorStatusListener == null) {
                    logger.debug("Hue sensor '{}' added.", sensorId);

                    if (discovery != null) {
                        discovery.onSensorAdded(hueBridge, sensor);
                    }

                    lastSensorStates.put(sensorId, sensor);
                } else {
                    logger.trace("Hue sensor '{}' state update.", sensorId);
                    if (sensorStatusListener.onSensorStateChanged(hueBridge, sensor)) {
                        lastSensorStates.put(sensorId, sensor);
                    }
                }
            }

            // Check for removed sensors
            lastSensorStateCopy.forEach((sensorId, sensor) -> {
                logger.debug("Hue sensor '{}' removed.", sensorId);
                lastSensorStates.remove(sensorId);
                final SensorStatusListener sensorStatusListener = sensorStatusListeners.get(sensorId);
                if (sensorStatusListener != null) {
                    try {
                        sensorStatusListener.onSensorRemoved(hueBridge, sensor);
                    } catch (Exception e) {
                        logger.error("An exception occurred while calling the Sensor Listeners", e);
                    }
                }

                if (discovery != null) {
                    discovery.onSensorRemoved(hueBridge, sensor);
                }
            });
        }
    };

    private final Runnable lightPollingRunnable = new PollingRunnable() {
        @Override
        protected void doConnectedRun() throws IOException, ApiException {
            Map<String, FullLight> lastLightStateCopy = new HashMap<>(lastLightStates);

            List<FullLight> lights;
            if (ApiVersionUtils.supportsFullLights(hueBridge.getVersion())) {
                lights = hueBridge.getFullLights();
            } else {
                lights = hueBridge.getFullConfig().getLights();
            }

            final LightStatusListener discovery = discoveryListener;

            for (final FullLight fullLight : lights) {
                final String lightId = fullLight.getId();
                lastLightStates.put(lightId, fullLight);
                if (lastLightStateCopy.containsKey(lightId)) {
                    final FullLight lastFullLight = lastLightStateCopy.remove(lightId);
                    final State lastFullLightState = lastFullLight.getState();
                    if (!lastFullLightState.equals(fullLight.getState())) {
                        logger.debug("Status update for Hue light '{}' detected.", lightId);
                        notifyLightStatusListeners(fullLight, StatusType.CHANGED);
                    }
                } else {
                    logger.debug("Hue light '{}' added.", lightId);
                    notifyLightStatusListeners(fullLight, StatusType.ADDED);
                }
            }

            // Check for removed lights
            for (Entry<String, FullLight> fullLightEntry : lastLightStateCopy.entrySet()) {
                lastLightStates.remove(fullLightEntry.getKey());
                logger.debug("Hue light '{}' removed.", fullLightEntry.getKey());
                notifyLightStatusListeners(fullLightEntry.getValue(), StatusType.REMOVED);
            }

            Map<String, FullGroup> lastGroupStateCopy = new HashMap<>(lastGroupStates);

            for (final FullGroup fullGroup : hueBridge.getGroups()) {
                State groupState = new State();
                boolean on = false;
                int sumBri = 0;
                int nbBri = 0;
                State colorRef = null;
                HSBType firstColorHsb = null;
                for (String lightId : fullGroup.getLights()) {
                    FullLight light = lastLightStates.get(lightId);
                    if (light != null) {
                        final State lightState = light.getState();
                        logger.trace("Group {}: light {}: on {} bri {} hue {} sat {} temp {} mode {} XY {}",
                                fullGroup.getName(), light.getName(), lightState.isOn(), lightState.getBrightness(),
                                lightState.getHue(), lightState.getSaturation(), lightState.getColorTemperature(),
                                lightState.getColorMode(), lightState.getXY());
                        if (lightState.isOn()) {
                            on = true;
                            sumBri += lightState.getBrightness();
                            nbBri++;
                            if (lightState.getColorMode() != null) {
                                HSBType lightHsb = LightStateConverter.toHSBType(lightState);
                                if (firstColorHsb == null) {
                                    // first color light
                                    firstColorHsb = lightHsb;
                                    colorRef = lightState;
                                } else if (!lightHsb.equals(firstColorHsb)) {
                                    colorRef = null;
                                }
                            }
                        }
                    }
                }
                groupState.setOn(on);
                groupState.setBri(nbBri == 0 ? 0 : sumBri / nbBri);
                if (colorRef != null) {
                    groupState.setColormode(colorRef.getColorMode());
                    groupState.setHue(colorRef.getHue());
                    groupState.setSaturation(colorRef.getSaturation());
                    groupState.setColorTemperature(colorRef.getColorTemperature());
                    groupState.setXY(colorRef.getXY());
                }
                fullGroup.setState(groupState);
                logger.trace("Group {} ({}): on {} bri {} hue {} sat {} temp {} mode {} XY {}", fullGroup.getName(),
                        fullGroup.getType(), groupState.isOn(), groupState.getBrightness(), groupState.getHue(),
                        groupState.getSaturation(), groupState.getColorTemperature(), groupState.getColorMode(),
                        groupState.getXY());
                String groupId = fullGroup.getId();
                lastGroupStates.put(groupId, fullGroup);
                if (lastGroupStateCopy.containsKey(groupId)) {
                    final FullGroup lastFullGroup = lastGroupStateCopy.remove(groupId);
                    final State lastFullGroupState = lastFullGroup.getState();
                    if (!lastFullGroupState.equals(fullGroup.getState())) {
                        logger.debug("Status update for Hue group '{}' detected.", groupId);
                        notifyGroupStatusListeners(fullGroup, StatusType.CHANGED);
                    }
                } else {
                    logger.debug("Hue group '{}' ({}) added (nb lights {}).", groupId, fullGroup.getName(),
                            fullGroup.getLights().size());
                    notifyGroupStatusListeners(fullGroup, StatusType.ADDED);
                }
            }

            // Check for removed groups
            for (Entry<String, FullGroup> fullGroupEntry : lastGroupStateCopy.entrySet()) {
                lastGroupStates.remove(fullGroupEntry.getKey());
                logger.debug("Hue group '{}' removed.", fullGroupEntry.getKey());
                notifyGroupStatusListeners(fullGroupEntry.getValue(), StatusType.REMOVED);
            }
        }
    };

    private long lightPollingInterval = TimeUnit.SECONDS.toSeconds(10);
    private long sensorPollingInterval = TimeUnit.MILLISECONDS.toMillis(500);

    private boolean lastBridgeConnectionState = false;

    private boolean propertiesInitializedSuccessfully = false;

    private @Nullable ScheduledFuture<?> lightPollingJob;
    private @Nullable ScheduledFuture<?> sensorPollingJob;

    private @NonNullByDefault({}) HueBridge hueBridge = null;
    private @NonNullByDefault({}) HueBridgeConfig hueBridgeConfig = null;

    public HueBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // not needed
    }

    @Override
    public void updateLightState(FullLight light, StateUpdate stateUpdate) {
        if (hueBridge != null) {
            hueBridge.setLightState(light, stateUpdate).thenAccept(result -> {
                try {
                    hueBridge.handleErrors(result);
                } catch (Exception e) {
                    handleStateUpdateException(light, stateUpdate, e);
                }
            }).exceptionally(e -> {
                handleStateUpdateException(light, stateUpdate, e);
                return null;
            });
        } else {
            logger.debug("No bridge connected or selected. Cannot set light state.");
        }
    }

    @Override
    public void updateSensorState(FullSensor sensor, StateUpdate stateUpdate) {
        if (hueBridge != null) {
            hueBridge.setSensorState(sensor, stateUpdate).thenAccept(result -> {
                try {
                    hueBridge.handleErrors(result);
                } catch (Exception e) {
                    handleStateUpdateException(sensor, stateUpdate, e);
                }
            }).exceptionally(e -> {
                handleStateUpdateException(sensor, stateUpdate, e);
                return null;
            });
        } else {
            logger.debug("No bridge connected or selected. Cannot set sensor state.");
        }
    }

    @Override
    public void updateSensorConfig(FullSensor sensor, ConfigUpdate configUpdate) {
        if (hueBridge != null) {
            hueBridge.updateSensorConfig(sensor, configUpdate).thenAccept(result -> {
                try {
                    hueBridge.handleErrors(result);
                } catch (Exception e) {
                    handleConfigUpdateException(sensor, configUpdate, e);
                }
            }).exceptionally(e -> {
                handleConfigUpdateException(sensor, configUpdate, e);
                return null;
            });
        } else {
            logger.debug("No bridge connected or selected. Cannot set sensor config.");
        }
    }

    @Override
    public void updateGroupState(FullGroup group, StateUpdate stateUpdate) {
        if (hueBridge != null) {
            hueBridge.setGroupState(group, stateUpdate).thenAccept(result -> {
                try {
                    hueBridge.handleErrors(result);
                } catch (Exception e) {
                    handleStateUpdateException(group, stateUpdate, e);
                }
            }).exceptionally(e -> {
                handleStateUpdateException(group, stateUpdate, e);
                return null;
            });
        } else {
            logger.debug("No bridge connected or selected. Cannot set group state.");
        }
    }

    private void handleStateUpdateException(FullLight light, StateUpdate stateUpdate, Throwable e) {
        if (e instanceof DeviceOffException) {
            if (stateUpdate.getColorTemperature() != null && stateUpdate.getBrightness() == null) {
                // If there is only a change of the color temperature, we do not want the light
                // to be turned on (i.e. change its brightness).
                return;
            } else {
                updateLightState(light, LightStateConverter.toOnOffLightState(OnOffType.ON));
                updateLightState(light, stateUpdate);
            }
        } else if (e instanceof IOException) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        } else if (e instanceof EntityNotAvailableException) {
            logger.debug("Error while accessing light: {}", e.getMessage(), e);
            final LightStatusListener discovery = discoveryListener;
            if (discovery != null) {
                discovery.onLightGone(hueBridge, light);
            }

            final LightStatusListener lightStatusListener = lightStatusListeners.get(light.getId());
            if (lightStatusListener != null) {
                lightStatusListener.onLightGone(hueBridge, light);
            }
        } else if (e instanceof ApiException) {
            // This should not happen - if it does, it is most likely some bug that should be reported.
            logger.warn("Error while accessing light: {}", e.getMessage(), e);
        } else if (e instanceof IllegalStateException) {
            logger.trace("Error while accessing light: {}", e.getMessage());
        }
    }

    private void handleStateUpdateException(FullSensor sensor, StateUpdate stateUpdate, Throwable e) {
        if (e instanceof IOException) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        } else if (e instanceof EntityNotAvailableException) {
            logger.debug("Error while accessing sensor: {}", e.getMessage(), e);
            final SensorStatusListener discovery = discoveryListener;
            if (discovery != null) {
                discovery.onSensorGone(hueBridge, sensor);
            }

            final SensorStatusListener sensorStatusListener = sensorStatusListeners.get(sensor.getId());
            if (sensorStatusListener != null) {
                sensorStatusListener.onSensorGone(hueBridge, sensor);
            }
        } else if (e instanceof ApiException) {
            // This should not happen - if it does, it is most likely some bug that should be reported.
            logger.warn("Error while accessing sensor: {}", e.getMessage(), e);
        } else if (e instanceof IllegalStateException) {
            logger.trace("Error while accessing sensor: {}", e.getMessage());
        }
    }

    private void handleStateUpdateException(FullGroup group, StateUpdate stateUpdate, Throwable e) {
        if (e instanceof IOException) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        } else if (e instanceof EntityNotAvailableException) {
            logger.debug("Error while accessing group: {}", e.getMessage(), e);
            notifyGroupStatusListeners(group, StatusType.GONE);
        } else if (e instanceof ApiException) {
            // This should not happen - if it does, it is most likely some bug that should be reported.
            logger.warn("Error while accessing group: {}", e.getMessage(), e);
        } else if (e instanceof IllegalStateException) {
            logger.trace("Error while accessing group: {}", e.getMessage());
        }
    }

    private void handleConfigUpdateException(FullSensor sensor, ConfigUpdate configUpdate, Throwable e) {
        if (e instanceof IOException) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        } else if (e instanceof EntityNotAvailableException) {
            logger.debug("Error while accessing sensor: {}", e.getMessage(), e);
            final SensorStatusListener discovery = discoveryListener;
            if (discovery != null) {
                discovery.onSensorGone(hueBridge, sensor);
            }

            final SensorStatusListener sensorStatusListener = sensorStatusListeners.get(sensor.getId());
            if (sensorStatusListener != null) {
                sensorStatusListener.onSensorGone(hueBridge, sensor);
            }
        } else if (e instanceof ApiException) {
            // This should not happen - if it does, it is most likely some bug that should be reported.
            logger.warn("Error while accessing sensor: {}", e.getMessage(), e);
        } else if (e instanceof IllegalStateException) {
            logger.trace("Error while accessing sensor: {}", e.getMessage());
        }
    }

    private void startLightPolling() {
        if (lightPollingJob == null || lightPollingJob.isCancelled()) {
            if (hueBridgeConfig.getPollingInterval() < 1) {
                logger.info("Wrong configuration value for polling interval. Using default value: {}s",
                        lightPollingInterval);
            } else {
                lightPollingInterval = hueBridgeConfig.getPollingInterval();
            }
            lightPollingJob = scheduler.scheduleWithFixedDelay(lightPollingRunnable, 1, lightPollingInterval,
                    TimeUnit.SECONDS);
        }
    }

    private void stopLightPolling() {
        if (lightPollingJob != null && !lightPollingJob.isCancelled()) {
            lightPollingJob.cancel(true);
            lightPollingJob = null;
        }
    }

    private void startSensorPolling() {
        if (sensorPollingJob == null || sensorPollingJob.isCancelled()) {
            if (hueBridgeConfig.getSensorPollingInterval() < 50) {
                logger.info("Wrong configuration value for sensor polling interval. Using default value: {}ms",
                        sensorPollingInterval);
            } else {
                sensorPollingInterval = hueBridgeConfig.getSensorPollingInterval();
            }
            sensorPollingJob = scheduler.scheduleWithFixedDelay(sensorPollingRunnable, 1, sensorPollingInterval,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void stopSensorPolling() {
        if (sensorPollingJob != null && !sensorPollingJob.isCancelled()) {
            sensorPollingJob.cancel(true);
            sensorPollingJob = null;
        }
    }

    @Override
    public void dispose() {
        logger.debug("Handler disposed.");
        stopLightPolling();
        stopSensorPolling();
        if (hueBridge != null) {
            hueBridge = null;
        }
    }

    @Override
    public void initialize() {
        logger.debug("Initializing hue bridge handler.");
        hueBridgeConfig = getConfigAs(HueBridgeConfig.class);

        String ip = hueBridgeConfig.getIpAddress();
        if (ip == null || ip.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error-no-ip-address");
        } else {
            if (hueBridge == null) {
                hueBridge = new HueBridge(ip, hueBridgeConfig.getPort(), hueBridgeConfig.getProtocol(), scheduler);
                hueBridge.setTimeout(5000);
            }
            onUpdate();
        }
    }

    private synchronized void onUpdate() {
        if (hueBridge != null) {
            startLightPolling();
            startSensorPolling();
        }
    }

    /**
     * This method is called whenever the connection to the {@link HueBridge} is lost.
     */
    public void onConnectionLost() {
        logger.debug("Bridge connection lost. Updating thing status to OFFLINE.");
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE, "@text/offline.bridge-connection-lost");
    }

    /**
     * This method is called whenever the connection to the {@link HueBridge} is resumed.
     *
     * @throws ApiException if the physical device does not support this API call
     * @throws IOException if the physical device could not be reached
     */
    private void onConnectionResumed() throws IOException, ApiException {
        logger.debug("Bridge connection resumed. Updating thing status to ONLINE.");

        if (!propertiesInitializedSuccessfully) {
            FullConfig fullConfig = hueBridge.getFullConfig();
            Config config = fullConfig.getConfig();
            if (config != null) {
                Map<String, String> properties = editProperties();
                String serialNumber = config.getBridgeId().substring(0, 6) + config.getBridgeId().substring(10);
                properties.put(PROPERTY_SERIAL_NUMBER, serialNumber);
                properties.put(PROPERTY_MODEL_ID, config.getModelId());
                properties.put(PROPERTY_MAC_ADDRESS, config.getMACAddress());
                properties.put(PROPERTY_FIRMWARE_VERSION, config.getSoftwareVersion());
                updateProperties(properties);
                propertiesInitializedSuccessfully = true;
            }
        }

        updateStatus(ThingStatus.ONLINE);
    }

    /**
     * Check USER_NAME config for null. Call onConnectionResumed() otherwise.
     *
     * @return True if USER_NAME was not null.
     * @throws ApiException if the physical device does not support this API call
     * @throws IOException if the physical device could not be reached
     */
    private boolean tryResumeBridgeConnection() throws IOException, ApiException {
        logger.debug("Connection to Hue Bridge {} established.", hueBridge.getIPAddress());
        if (hueBridgeConfig.getUserName() == null) {
            logger.warn(
                    "User name for Hue bridge authentication not available in configuration. Setting ThingStatus to OFFLINE.");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error-no-username");
            return false;
        } else {
            onConnectionResumed();
            return true;
        }
    }

    /**
     * This method is called whenever the connection to the {@link HueBridge} is available,
     * but requests are not allowed due to a missing or invalid authentication.
     * <p>
     * If there is a user name available, it attempts to re-authenticate. Otherwise new authentication credentials will
     * be requested from the bridge.
     *
     * @param bridge the hue bridge the connection is not authorized
     * @return returns {@code true} if re-authentication was successful, {@code false} otherwise
     */
    public boolean onNotAuthenticated() {
        if (hueBridge == null) {
            return false;
        }
        String userName = hueBridgeConfig.getUserName();
        if (userName == null) {
            createUser();
        } else {
            try {
                hueBridge.authenticate(userName);
                return true;
            } catch (Exception e) {
                handleAuthenticationFailure(e, userName);
            }
        }
        return false;
    }

    private void createUser() {
        try {
            String newUser = createUserOnPhysicalBridge();
            updateBridgeThingConfiguration(newUser);
        } catch (LinkButtonException ex) {
            handleLinkButtonNotPressed(ex);
        } catch (Exception ex) {
            handleExceptionWhileCreatingUser(ex);
        }
    }

    private String createUserOnPhysicalBridge() throws IOException, ApiException {
        logger.info("Creating new user on Hue bridge {} - please press the pairing button on the bridge.",
                hueBridgeConfig.getIpAddress());
        String userName = hueBridge.link(DEVICE_TYPE);
        logger.info("User '{}' has been successfully added to Hue bridge.", userName);
        return userName;
    }

    private void updateBridgeThingConfiguration(String userName) {
        Configuration config = editConfiguration();
        config.put(USER_NAME, userName);
        try {
            updateConfiguration(config);
            logger.debug("Updated configuration parameter '{}' to '{}'", USER_NAME, userName);
            hueBridgeConfig = getConfigAs(HueBridgeConfig.class);
        } catch (IllegalStateException e) {
            logger.trace("Configuration update failed.", e);
            logger.warn("Unable to update configuration of Hue bridge.");
            logger.warn("Please configure the following user name manually: {}", userName);
        }
    }

    private void handleAuthenticationFailure(Exception ex, String userName) {
        logger.warn("User {} is not authenticated on Hue bridge {}", userName, hueBridgeConfig.getIpAddress());
        logger.warn("Please configure a valid user or remove user from configuration to generate a new one.");
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                "@text/offline.conf-error-invalid-username");
    }

    private void handleLinkButtonNotPressed(LinkButtonException ex) {
        logger.debug("Failed creating new user on Hue bridge: {}", ex.getMessage());
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                "@text/offline.conf-error-press-pairing-button");
    }

    private void handleExceptionWhileCreatingUser(Exception ex) {
        logger.warn("Failed creating new user on Hue bridge", ex);
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                "@text/offline.conf-error-creation-username");
    }

    public boolean registerDiscoveryListener(HueLightDiscoveryService listener) {
        if (discoveryListener == null) {
            discoveryListener = listener;
            return true;
        }

        return false;
    }

    public boolean unregisterDiscoveryListener(HueLightDiscoveryService listener) {
        if (discoveryListener != null) {
            discoveryListener = null;
            return true;
        }

        return false;
    }

    @Override
    public boolean registerLightStatusListener(LightStatusListener lightStatusListener) {
        boolean result = lightStatusListeners.add(lightStatusListener);
        if (result && hueBridge != null) {
            // inform the listener initially about all lights and their states
            for (FullLight light : lastLightStates.values()) {
                lightStatusListener.onLightAdded(hueBridge, light);
            }
        }

        return true;
    }

    @Override
    public boolean unregisterLightStatusListener(LightStatusListener lightStatusListener) {
        return lightStatusListeners.remove(lightStatusListener);
    }

    @Override
    public boolean registerSensorStatusListener(SensorStatusListener sensorStatusListener) {
        boolean result = sensorStatusListeners.add(sensorStatusListener);
        if (result && hueBridge != null) {
            // inform the listener initially about all sensors and their states
            for (FullSensor sensor : lastSensorStates.values()) {
                sensorStatusListener.onSensorAdded(hueBridge, sensor);
            }
        }

        return true;
    }

    @Override
    public boolean unregisterSensorStatusListener(SensorStatusListener sensorStatusListener) {
        return sensorStatusListeners.remove(sensorStatusListener);
    }

    @Override
    public boolean registerGroupStatusListener(GroupStatusListener groupStatusListener) {
        boolean result = groupStatusListeners.add(groupStatusListener);
        if (result && hueBridge != null) {
            // inform the listener initially about all groups and their states
            for (FullGroup group : lastGroupStates.values()) {
                groupStatusListener.onGroupAdded(hueBridge, group);
            }
        }

        return true;
    }

    @Override
    public boolean unregisterGroupStatusListener(GroupStatusListener groupStatusListener) {
        return groupStatusListeners.remove(groupStatusListener);
    }

    @Override
    public @Nullable FullLight getLightById(String lightId) {
        return lastLightStates.get(lightId);
    }

    @Override
    public @Nullable FullSensor getSensorById(String sensorId) {
        return lastSensorStates.get(sensorId);
    }

    @Override
    public @Nullable FullGroup getGroupById(String groupId) {
        return lastGroupStates.get(groupId);
    }

    public List<FullLight> getFullLights() {
        List<FullLight> ret = withReAuthentication("search for new lights", () -> {
            return hueBridge.getFullLights();
        });
        return ret != null ? ret : Collections.emptyList();
    }

    public List<FullSensor> getFullSensors() {
        List<FullSensor> ret = withReAuthentication("search for new sensors", () -> {
            return hueBridge.getSensors();
        });
        return ret != null ? ret : Collections.emptyList();
    }

    public List<FullGroup> getFullGroups() {
        List<FullGroup> ret = withReAuthentication("search for new groups", () -> {
            return hueBridge.getGroups();
        });
        return ret != null ? ret : Collections.emptyList();
    }

    public void startSearch() {
        withReAuthentication("start search mode", () -> {
            hueBridge.startSearch();
            return null;
        });
    }

    public void startSearch(List<String> serialNumbers) {
        withReAuthentication("start search mode", () -> {
            hueBridge.startSearch(serialNumbers);
            return null;
        });
    }

    private <T> T withReAuthentication(String taskDescription, Callable<T> runnable) {
        if (hueBridge != null) {
            try {
                try {
                    return runnable.call();
                } catch (UnauthorizedException | IllegalStateException e) {
                    lastBridgeConnectionState = false;
                    if (onNotAuthenticated()) {
                        return runnable.call();
                    }
                }
            } catch (Exception e) {
                logger.debug("Bridge cannot {}.", taskDescription, e);
            }
        }
        return null;
    }

    /**
     * Iterate through lightStatusListeners and notify them about a status change.
     *
     * @param fullLight
     * @param type the type of change
     */
    private void notifyLightStatusListeners(final FullLight fullLight, StatusType type) {
        if (lightStatusListeners.isEmpty()) {
            logger.debug("No light status listeners to notify of light change for light '{}'", fullLight.getId());
            return;
        }

        for (LightStatusListener lightStatusListener : lightStatusListeners) {
            try {
                switch (type) {
                    case ADDED:
                        logger.debug("Sending lightAdded for light '{}'", fullLight.getId());
                        lightStatusListener.onLightAdded(hueBridge, fullLight);
                        break;
                    case REMOVED:
                        lightStatusListener.onLightRemoved(hueBridge, fullLight);
                        break;
                    case GONE:
                        lightStatusListener.onLightGone(hueBridge, fullLight);
                        break;
                    case CHANGED:
                        logger.debug("Sending lightStateChanged for light '{}'", fullLight.getId());
                        lightStatusListener.onLightStateChanged(hueBridge, fullLight);
                        break;
                }
            } catch (Exception e) {
                logger.debug("An exception occurred while calling the BridgeHeartbeatListener", e);
            }
        }
    }

    private void notifySensorStatusListeners(final FullSensor fullSensor, StatusType type) {
        if (sensorStatusListeners.isEmpty()) {
            logger.debug("No sensor status listeners to notify of sensor change for sensor '{}'", fullSensor.getId());
            return;
        }

        for (SensorStatusListener sensorStatusListener : sensorStatusListeners) {
            try {
                switch (type) {
                    case ADDED:
                        logger.debug("Sending sensorAdded for sensor '{}'", fullSensor.getId());
                        sensorStatusListener.onSensorAdded(hueBridge, fullSensor);
                        break;
                    case REMOVED:
                        sensorStatusListener.onSensorRemoved(hueBridge, fullSensor);
                        break;
                    case GONE:
                        sensorStatusListener.onSensorGone(hueBridge, fullSensor);
                        break;
                    case CHANGED:
                        logger.debug("Sending sensorStateChanged for sensor '{}'", fullSensor.getId());
                        sensorStatusListener.onSensorStateChanged(hueBridge, fullSensor);
                        break;
                }
            } catch (Exception e) {
                logger.debug("An exception occurred while calling the Sensor Listeners", e);
            }
        }
    }

    private void notifyGroupStatusListeners(final FullGroup fullGroup, StatusType type) {
        if (groupStatusListeners.isEmpty()) {
            logger.debug("No group status listeners to notify of group change for group '{}'", fullGroup.getId());
            return;
        }

        for (GroupStatusListener groupStatusListener : groupStatusListeners) {
            try {
                switch (type) {
                    case ADDED:
                        logger.debug("Sending groupAdded for group '{}'", fullGroup.getId());
                        groupStatusListener.onGroupAdded(hueBridge, fullGroup);
                        break;
                    case REMOVED:
                        groupStatusListener.onGroupRemoved(hueBridge, fullGroup);
                        break;
                    case GONE:
                        groupStatusListener.onGroupGone(hueBridge, fullGroup);
                        break;
                    case CHANGED:
                        logger.debug("Sending groupStateChanged for group '{}'", fullGroup.getId());
                        groupStatusListener.onGroupStateChanged(hueBridge, fullGroup);
                        break;
                }
            } catch (Exception e) {
                logger.debug("An exception occurred while calling the Group Listeners", e);
            }
        }
    }

    @Override
    public Collection<ConfigStatusMessage> getConfigStatus() {
        // The bridge IP address to be used for checks
        Collection<ConfigStatusMessage> configStatusMessages;

        // Check whether an IP address is provided
        if (hueBridgeConfig.getIpAddress() == null || hueBridgeConfig.getIpAddress().isEmpty()) {
            configStatusMessages = Collections.singletonList(ConfigStatusMessage.Builder.error(HOST)
                    .withMessageKeySuffix(HueConfigStatusMessage.IP_ADDRESS_MISSING).withArguments(HOST).build());
        } else {
            configStatusMessages = Collections.emptyList();
        }

        return configStatusMessages;
    }
}
