/**
 *  Copyright 2015 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package org.openshift.activemq.discoveryagent;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.apache.activemq.command.DiscoveryEvent;
import org.apache.activemq.thread.Scheduler;
import org.apache.activemq.transport.discovery.DiscoveryAgent;
import org.apache.activemq.transport.discovery.DiscoveryListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenshiftDiscoveryAgent
 * <p/>
 * Provides support for a discovery agent that queries for services implemented
 * using an openwire (tcp) transport. The URI takes the form:
 * 
 * <pre>
 * (dns|kube)://<serviceName>:<servicePort>/?queryInterval=30&transportType=tcp
 * </pre>
 * 
 * <code>serviceName</code> is required and is the name of the service.
 * <code>servicePort</code> is optional. If not specified, the agent will query
 * to determine the port on which the services are running.
 * <code>queryInterval</code> is the period, in seconds, at which polling is
 * conducted; the default is 30s. <code>transportType</code> is the type of
 * transport; the default is <code>tcp</code>.
 */
public class OpenShiftDiscoveryAgent implements DiscoveryAgent {

    private final static Logger LOGGER = LoggerFactory.getLogger(OpenShiftDiscoveryAgent.class);

    /** The query interval in seconds. */
    private long queryInterval = 30;
    /** The transportType, e.g. tcp, amqp, etc., defaults to tcp. */
    private PeerAddressResolver resolver;
    private String transportType = "tcp";
    private long minConnectTime = 1000;
    private long initialReconnectDelay = minConnectTime;
    private long maxReconnectDelay = 16000;
    private int maxReconnectAttempts = 4;
    /** The periodic poller of OpenShift information for the service. */
    private Scheduler openshiftPoller;
    private ConcurrentMap<String, OpenShiftDiscoveryEvent> services = new ConcurrentHashMap<String, OpenShiftDiscoveryAgent.OpenShiftDiscoveryEvent>();
    private DiscoveryListener listener;

    /**
     * Create a new OpenshiftDiscoveryAgent.
     * 
     * @param resolver the service endpoint resolver
     */
    public OpenShiftDiscoveryAgent(PeerAddressResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public synchronized void start() throws Exception {
        LOGGER.info("Starting OpenShift discovery agent for service {} transport type {}", resolver.getServiceName(),
                transportType);
        openshiftPoller = new Scheduler("OpenShift discovery agent Scheduler: " + resolver.getServiceName());
        openshiftPoller.start();
        openshiftPoller.executePeriodically(new OpenShiftQueryTask(), TimeUnit.SECONDS.toMillis(queryInterval));
    }

    @Override
    public synchronized void stop() throws Exception {
        LOGGER.info("Stopping OpenShift discovery agent for service {} transport type {}", resolver.getServiceName(),
                transportType);
        if (openshiftPoller != null) {
            openshiftPoller.stop();
            openshiftPoller = null;
        }
        services.clear();
    }

    @Override
    public void setDiscoveryListener(DiscoveryListener listener) {
        this.listener = listener;
    }

    @Override
    public void registerService(String name) throws IOException {
    }

    @Override
    public void serviceFailed(DiscoveryEvent event) throws IOException {
        final OpenShiftDiscoveryEvent dnsEvent = (OpenShiftDiscoveryEvent) event;
        dnsEvent.fail();
    }

    /**
     * Get the queryInterval.
     * 
     * @return the queryInterval.
     */
    public long getQueryInterval() {
        return queryInterval;
    }

    /**
     * Set the queryInterval.
     * 
     * @param queryInterval The queryInterval to set.
     */
    public void setQueryInterval(long queryInterval) {
        this.queryInterval = queryInterval;
    }

    /**
     * Get the transportType.
     * 
     * @return the transportType.
     */
    public String getTransportType() {
        return transportType;
    }

    /**
     * Set the transportType.
     * 
     * @param transportType The transportType to set.
     */
    public void setTransportType(String transportType) {
        this.transportType = transportType;
    }

    private final class OpenShiftDiscoveryEvent extends DiscoveryEvent {

        private int connectFailures;
        private long reconnectDelay = 1000;
        private long connectTime = System.currentTimeMillis();
        private boolean failed;

        public OpenShiftDiscoveryEvent(String transportType, String ip, int port) {
            super(String.format("%s://%s:%d", transportType, ip, port));
        }

        @Override
        public String toString() {
            return "[" + serviceName + ", failed:" + failed + ", connectionFailures:" + connectFailures + "]";
        }

        private synchronized void present() {
            if (failed) {
                return;
            }
            connectFailures = 0;
            reconnectDelay = initialReconnectDelay;
        }

        private synchronized void fail() {
            if (failed) {
                return;
            }
            if (!services.containsValue(this)) {
                // no longer tracking this discovery event
                return;
            }
            final long retryDelay;
            if (connectTime + minConnectTime > System.currentTimeMillis()) {
                connectFailures++;
                retryDelay = reconnectDelay;
            } else {
                retryDelay = minConnectTime;
            }

            failed = true;
            listener.onServiceRemove(this);

            if (maxReconnectAttempts > 0 && connectFailures >= maxReconnectAttempts) {
                // This service will forever be exempted from this broker's mesh
                LOGGER.warn("Reconnect attempts exceeded after {} tries.  Reconnecting has been disabled for: {}",
                        maxReconnectAttempts, this);
                return;
            }

            openshiftPoller.executeAfterDelay(new Runnable() {
                @Override
                public void run() {
                    reconnect();
                }
            }, retryDelay);
        }

        private synchronized void reconnect() {
            if (!services.containsValue(this)) {
                // no longer tracking this discovery event
                return;
            }

            // Exponential increment of reconnect delay.
            reconnectDelay *= 2;
            if (reconnectDelay > maxReconnectDelay) {
                reconnectDelay = maxReconnectDelay;
            }
            connectTime = System.currentTimeMillis();
            failed = false;

            listener.onServiceAdd(this);
        }
    }

    /**
     * OpenShiftQueryTask
     */
    private class OpenShiftQueryTask implements Runnable {

        /**
         * Create a new OpenShiftQueryTask.
         */
        public OpenShiftQueryTask() {
        }

        @Override
        public void run() {
            try {
                synchronized (services) {
                    final Set<String> endpoints = new HashSet<String>(Arrays.asList(resolver.getPeerIPs()));
                    final int servicePort = resolver.getServicePort();
                    final Set<String> removed = new HashSet<String>(services.keySet());
                    final Set<String> added = new HashSet<String>(endpoints);
                    removed.removeAll(endpoints);
                    for (String service : removed) {
                        final OpenShiftDiscoveryEvent event = services.remove(service);
                        if (event != null) {
                            LOGGER.info("Removing service: {}", event);
                            listener.onServiceRemove(event);
                        }
                    }
                    for (Map.Entry<String, OpenShiftDiscoveryEvent> entry : services.entrySet()) {
                        added.remove(entry.getKey());
                        entry.getValue().present();
                    }
                    for (String service : added) {
                        if (service.equals(InetAddress.getLocalHost().getHostAddress())) {
                            // skip ourself
                            continue;
                        }
                        final OpenShiftDiscoveryEvent event = new OpenShiftDiscoveryEvent(transportType, service, servicePort);
                        services.put(service, event);
                        LOGGER.info("Adding service: {}", event);
                        listener.onServiceAdd(event);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Error polling OpenShift", e);
            }
        }

    }

}
