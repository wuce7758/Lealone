/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.cluster.locator;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.lealone.cluster.db.SystemKeyspace;
import org.lealone.cluster.exceptions.ConfigurationException;
import org.lealone.cluster.gms.ApplicationState;
import org.lealone.cluster.gms.EndpointState;
import org.lealone.cluster.gms.Gossiper;
import org.lealone.cluster.service.StorageService;
import org.lealone.cluster.utils.FBUtilities;
import org.lealone.cluster.utils.ResourceWatcher;
import org.lealone.cluster.utils.WrappedRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GossipingPropertyFileSnitch extends AbstractNetworkTopologySnitch// implements IEndpointStateChangeSubscriber
{
    private static final Logger logger = LoggerFactory.getLogger(GossipingPropertyFileSnitch.class);

    private PropertyFileSnitch psnitch;

    private volatile String myDC;
    private volatile String myRack;
    private volatile boolean preferLocal;
    private final AtomicReference<ReconnectableSnitchHelper> snitchHelperReference;
    private volatile boolean gossipStarted;

    private Map<InetAddress, Map<String, String>> savedEndpoints;
    private static final String DEFAULT_DC = "UNKNOWN_DC";
    private static final String DEFAULT_RACK = "UNKNOWN_RACK";

    private static final int DEFAULT_REFRESH_PERIOD_IN_SECONDS = 60;

    public GossipingPropertyFileSnitch() throws ConfigurationException {
        this(DEFAULT_REFRESH_PERIOD_IN_SECONDS);
    }

    public GossipingPropertyFileSnitch(int refreshPeriodInSeconds) throws ConfigurationException {
        snitchHelperReference = new AtomicReference<ReconnectableSnitchHelper>();

        reloadConfiguration();

        try {
            psnitch = new PropertyFileSnitch();
            logger.info("Loaded {} for compatibility", PropertyFileSnitch.SNITCH_PROPERTIES_FILENAME);
        } catch (ConfigurationException e) {
            logger.info("Unable to load {}; compatibility mode disabled", PropertyFileSnitch.SNITCH_PROPERTIES_FILENAME);
        }

        try {
            FBUtilities.resourceToFile(SnitchProperties.RACKDC_PROPERTY_FILENAME);
            Runnable runnable = new WrappedRunnable() {
                @Override
                protected void runMayThrow() throws ConfigurationException {
                    reloadConfiguration();
                }
            };
            ResourceWatcher.watch(SnitchProperties.RACKDC_PROPERTY_FILENAME, runnable, refreshPeriodInSeconds * 1000);
        } catch (ConfigurationException ex) {
            logger.error("{} found, but does not look like a plain file. Will not watch it for changes",
                    SnitchProperties.RACKDC_PROPERTY_FILENAME);
        }
    }

    /**
     * Return the data center for which an endpoint resides in
     *
     * @param endpoint the endpoint to process
     * @return string of data center
     */
    @Override
    public String getDatacenter(InetAddress endpoint) {
        if (endpoint.equals(FBUtilities.getBroadcastAddress()))
            return myDC;

        EndpointState epState = Gossiper.instance.getEndpointStateForEndpoint(endpoint);
        if (epState == null || epState.getApplicationState(ApplicationState.DC) == null) {
            if (psnitch == null) {
                if (savedEndpoints == null)
                    savedEndpoints = SystemKeyspace.loadDcRackInfo();
                if (savedEndpoints.containsKey(endpoint))
                    return savedEndpoints.get(endpoint).get("data_center");
                return DEFAULT_DC;
            } else
                return psnitch.getDatacenter(endpoint);
        }
        return epState.getApplicationState(ApplicationState.DC).value;
    }

    /**
     * Return the rack for which an endpoint resides in
     *
     * @param endpoint the endpoint to process
     * @return string of rack
     */
    @Override
    public String getRack(InetAddress endpoint) {
        if (endpoint.equals(FBUtilities.getBroadcastAddress()))
            return myRack;

        EndpointState epState = Gossiper.instance.getEndpointStateForEndpoint(endpoint);
        if (epState == null || epState.getApplicationState(ApplicationState.RACK) == null) {
            if (psnitch == null) {
                if (savedEndpoints == null)
                    savedEndpoints = SystemKeyspace.loadDcRackInfo();
                if (savedEndpoints.containsKey(endpoint))
                    return savedEndpoints.get(endpoint).get("rack");
                return DEFAULT_RACK;
            } else
                return psnitch.getRack(endpoint);
        }
        return epState.getApplicationState(ApplicationState.RACK).value;
    }

    @Override
    public void gossiperStarting() {
        super.gossiperStarting();

        Gossiper.instance.addLocalApplicationState(ApplicationState.INTERNAL_IP,
                StorageService.instance.valueFactory.internalIP(FBUtilities.getLocalAddress().getHostAddress()));

        reloadGossiperState();

        gossipStarted = true;
    }

    private void reloadConfiguration() throws ConfigurationException {
        final SnitchProperties properties = new SnitchProperties();

        String newDc = properties.get("dc", null);
        String newRack = properties.get("rack", null);
        if (newDc == null || newRack == null)
            throw new ConfigurationException("DC or rack not found in snitch properties, check your configuration in: "
                    + SnitchProperties.RACKDC_PROPERTY_FILENAME);

        newDc = newDc.trim();
        newRack = newRack.trim();
        final boolean newPreferLocal = Boolean.parseBoolean(properties.get("prefer_local", "false"));

        if (!newDc.equals(myDC) || !newRack.equals(myRack) || (preferLocal != newPreferLocal)) {
            myDC = newDc;
            myRack = newRack;
            preferLocal = newPreferLocal;

            reloadGossiperState();

            if (StorageService.instance != null)
                StorageService.instance.getTokenMetadata().invalidateCachedRings();

            if (gossipStarted)
                StorageService.instance.gossipSnitchInfo();
        }
    }

    private void reloadGossiperState() {
        if (Gossiper.instance != null) {
            ReconnectableSnitchHelper pendingHelper = new ReconnectableSnitchHelper(this, myDC, preferLocal);
            Gossiper.instance.register(pendingHelper);

            pendingHelper = snitchHelperReference.getAndSet(pendingHelper);
            if (pendingHelper != null)
                Gossiper.instance.unregister(pendingHelper);
        }
        // else this will eventually rerun at gossiperStarting()
    }
}
