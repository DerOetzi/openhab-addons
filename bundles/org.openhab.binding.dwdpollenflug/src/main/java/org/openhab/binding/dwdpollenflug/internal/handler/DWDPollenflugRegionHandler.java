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
package org.openhab.binding.dwdpollenflug.internal.handler;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.dwdpollenflug.internal.config.DWDPollenflugRegionConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link DWDPollenflugBridgeHandler}
 *
 * @author Johannes DerOetzi Ott - Initial contribution
 */
@NonNullByDefault
public class DWDPollenflugRegionHandler extends BaseThingHandler implements DWDPollenflugRegionListener {

    private final Logger logger = LoggerFactory.getLogger(DWDPollenflugRegionHandler.class);

    private @NonNullByDefault({}) DWDPollenflugRegionConfiguration thingConfig = null;

    private @Nullable DWDPollenflugBridgeHandler bridgeHandler;

    public DWDPollenflugRegionHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        logger.debug("Initializing DWD Pollenflug region handler");
        thingConfig = getConfigAs(DWDPollenflugRegionConfiguration.class);

        if (thingConfig.isValid()) {
            DWDPollenflugBridgeHandler handler = syncToBridge();
            if (handler == null) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Bridge handler missing");
            } else {
                updateStatus(ThingStatus.ONLINE);
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR);
        }
    }

    private synchronized @Nullable DWDPollenflugBridgeHandler syncToBridge() {
        if (bridgeHandler == null) {
            Bridge bridge = getBridge();
            if (bridge == null) {
                return null;
            }
            ThingHandler handler = bridge.getHandler();
            if (handler instanceof DWDPollenflugBridgeHandler) {
                bridgeHandler = (DWDPollenflugBridgeHandler) handler;
                bridgeHandler.registerRegionListener(this);
            } else {
                return null;
            }
        }

        return bridgeHandler;
    }

    @Override
    public void dispose() {
        logger.debug("DWDPollenflug region handler disposes. Unregistering listener.");
        DWDPollenflugBridgeHandler handler = syncToBridge();
        if (handler != null) {
            handler.unregisterRegionListener(this);
            bridgeHandler = null;
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
    }
}
