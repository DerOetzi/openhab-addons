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
package org.openhab.binding.dwdpollenflug.internal.config;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.dwdpollenflug.internal.handler.DWDPollenflugRegionHandler;

/**
 * Configuration for the {@link DWDPollenflugRegionHandler}
 *
 * @author Johannes DerOetzi Ott - Initial contribution
 */
@NonNullByDefault
public class DWDPollenflugBridgeConfiguration {
    private int refresh;

    public int getRefresh() {
        return refresh;
    }

    public boolean isValid() {
        return refresh >= 15;
    }
}
