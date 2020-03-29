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
package org.openhab.binding.dwdpollenflug.internal.dto;

import static org.openhab.binding.dwdpollenflug.internal.DWDPollenflugBindingConstants.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.dwdpollenflug.internal.DWDPollingException;

/**
 *
 * @author Johannes DerOetzi Ott - Initial contribution
 */
@NonNullByDefault
public class DWDRegion {

    private int regionID;

    private @Nullable final String regionName;

    private @Nullable String partRegionName;

    private final Map<String, @Nullable String> channels = new HashMap<>();

    public DWDRegion(final DWDRegionJSON json) {
        regionID = json.regionID;
        regionName = json.regionName;

        if (json.partregionID != null) {
            regionID = json.partregionID;
            partRegionName = json.partRegionName;
        }

        parseChannels(json.pollen);
    }

    private void parseChannels(@Nullable final Map<String, DWDPollentypeJSON> pollen) {
        if (pollen == null) {
            return;
        }

        for (final Entry<String, DWDPollentypeJSON> entry : pollen.entrySet()) {
            final String pollentype = CHANNELS_POLLEN_MAP.get(entry.getKey());
            final DWDPollentypeJSON jsonType = entry.getValue();
            createChannel(pollentype, CHANNEL_TODAY, jsonType.today);
            createChannel(pollentype, CHANNEL_TOMORROW, jsonType.tomorrow);
            createChannel(pollentype, CHANNEL_DAYAFTER_TO, jsonType.dayafterTomorrow);
        }
    }

    private void createChannel(final String pollentype, final String subchannel, @Nullable final String value) {
        final String channelName = pollentype + "#" + subchannel;
        channels.put(channelName, value);
    }

    public int getRegionID() {
        return regionID;
    }

    public Map<String, @Nullable String> getProperties() {
        final Map<String, @Nullable String> map = new HashMap<>();
        map.put(PROPERTY_REGION_ID, Integer.toString(regionID));
        map.put(PROPERTY_REGION_NAME, regionName);

        if (partRegionName == null) {
            map.put(PROPERTY_PARTREGION_NAME, partRegionName);
        }

        return Collections.unmodifiableMap(map);
    }

    public Map<String, @Nullable String> getChannels() {
        return Collections.unmodifiableMap(channels);
    }
}