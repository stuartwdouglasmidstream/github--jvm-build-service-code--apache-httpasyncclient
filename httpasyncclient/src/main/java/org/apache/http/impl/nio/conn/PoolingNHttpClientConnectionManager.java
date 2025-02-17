/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.http.impl.nio.conn;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHost;
import org.apache.http.annotation.Contract;
import org.apache.http.annotation.ThreadingBehavior;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.config.Lookup;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.SchemePortResolver;
import org.apache.http.conn.UnsupportedSchemeException;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.conn.DefaultSchemePortResolver;
import org.apache.http.impl.conn.SystemDefaultDnsResolver;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.conn.ManagedNHttpClientConnection;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.conn.NHttpConnectionFactory;
import org.apache.http.nio.conn.NoopIOSessionStrategy;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.nio.pool.NIOConnFactory;
import org.apache.http.nio.pool.SocketAddressResolver;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.pool.ConnPoolControl;
import org.apache.http.pool.PoolStats;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.Args;
import org.apache.http.util.Asserts;

/**
 * {@code PoolingNHttpClientConnectionManager} maintains a pool of
 * {@link NHttpClientConnection}s and is able to service connection requests
 * from multiple execution threads. Connections are pooled on a per route
 * basis. A request for a route which already the manager has persistent
 * connections for available in the pool will be services by leasing
 * a connection from the pool rather than creating a brand new connection.
 * <p>
 * {@code PoolingNHttpClientConnectionManager} maintains a maximum limit
 * of connection on a per route basis and in total. Per default this
 * implementation will create no more than than 2 concurrent connections
 * per given route and no more 20 connections in total. For many real-world
 * applications these limits may prove too constraining, especially if they
 * use HTTP as a transport protocol for their services. Connection limits,
 * however, can be adjusted using {@link ConnPoolControl} methods.
 *
 * @since 4.0
 */
@Contract(threading = ThreadingBehavior.SAFE)
public class PoolingNHttpClientConnectionManager
       implements NHttpClientConnectionManager, ConnPoolControl<HttpRoute> {

    private final Log log = LogFactory.getLog(getClass());

    static final String IOSESSION_FACTORY_REGISTRY = "http.ioSession-factory-registry";

    private final ConnectingIOReactor ioReactor;
    private final ConfigData configData;
    private final CPool pool;
    private final Registry<SchemeIOSessionStrategy> ioSessionFactoryRegistry;

    private static Registry<SchemeIOSessionStrategy> getDefaultRegistry() {
        return RegistryBuilder.<SchemeIOSessionStrategy>create()
                .register("http", NoopIOSessionStrategy.INSTANCE)
                .register("https", SSLIOSessionStrategy.getDefaultStrategy())
                .build();
    }

    public PoolingNHttpClientConnectionManager(final ConnectingIOReactor ioReactor) {
        this(ioReactor, getDefaultRegistry());
    }

    public PoolingNHttpClientConnectionManager(
            final ConnectingIOReactor ioReactor,
            final Registry<SchemeIOSessionStrategy> ioSessionFactoryRegistry) {
        this(ioReactor, null, ioSessionFactoryRegistry, (DnsResolver) null);
    }

    public PoolingNHttpClientConnectionManager(
            final ConnectingIOReactor ioReactor,
            final NHttpConnectionFactory<ManagedNHttpClientConnection> connFactory,
            final DnsResolver dnsResolver) {
        this(ioReactor, connFactory, getDefaultRegistry(), dnsResolver);
    }

    public PoolingNHttpClientConnectionManager(
            final ConnectingIOReactor ioReactor,
            final NHttpConnectionFactory<ManagedNHttpClientConnection> connFactory,
            final SocketAddressResolver<HttpRoute> socketAddressResolver) {
        this(ioReactor, connFactory, getDefaultRegistry(), socketAddressResolver);
    }

    public PoolingNHttpClientConnectionManager(
            final ConnectingIOReactor ioReactor,
            final NHttpConnectionFactory<ManagedNHttpClientConnection> connFactory) {
        this(ioReactor, connFactory, getDefaultRegistry(), (DnsResolver) null);
    }

    public PoolingNHttpClientConnectionManager(
            final ConnectingIOReactor ioReactor,
            final NHttpConnectionFactory<ManagedNHttpClientConnection> connFactory,
            final Registry<SchemeIOSessionStrategy> ioSessionFactoryRegistry) {
        this(ioReactor, connFactory, ioSessionFactoryRegistry, (DnsResolver) null);
    }

    public PoolingNHttpClientConnectionManager(
            final ConnectingIOReactor ioReactor,
            final NHttpConnectionFactory<ManagedNHttpClientConnection> connFactory,
            final Registry<SchemeIOSessionStrategy> ioSessionFactoryRegistry,
            final DnsResolver dnsResolver) {
        this(ioReactor, connFactory, ioSessionFactoryRegistry, null, dnsResolver,
            -1, TimeUnit.MILLISECONDS);
    }

    public PoolingNHttpClientConnectionManager(
            final ConnectingIOReactor ioReactor,
            final NHttpConnectionFactory<ManagedNHttpClientConnection> connFactory,
            final Registry<SchemeIOSessionStrategy> ioSessionFactoryRegistry,
            final SocketAddressResolver<HttpRoute> socketAddressResolver) {
        this(ioReactor, connFactory, ioSessionFactoryRegistry, socketAddressResolver,
                -1, TimeUnit.MILLISECONDS);
    }

    public PoolingNHttpClientConnectionManager(
            final ConnectingIOReactor ioReactor,
            final NHttpConnectionFactory<ManagedNHttpClientConnection> connFactory,
            final Registry<SchemeIOSessionStrategy> ioSessionFactoryRegistry,
            final SchemePortResolver schemePortResolver,
            final DnsResolver dnsResolver,
            final long timeToLive, final TimeUnit timeUnit) {
        this(ioReactor, connFactory, ioSessionFactoryRegistry,
                new InternalAddressResolver(schemePortResolver, dnsResolver), timeToLive, timeUnit);
    }

    public PoolingNHttpClientConnectionManager(
            final ConnectingIOReactor ioReactor,
            final NHttpConnectionFactory<ManagedNHttpClientConnection> connFactory,
            final Registry<SchemeIOSessionStrategy> ioSessionFactoryRegistry,
            final SocketAddressResolver<HttpRoute> socketAddressResolver,
            final long timeToLive, final TimeUnit timeUnit) {
        super();
        Args.notNull(ioReactor, "I/O reactor");
        Args.notNull(ioSessionFactoryRegistry, "I/O session factory registry");
        Args.notNull(socketAddressResolver, "Socket address resolver");
        this.ioReactor = ioReactor;
        this.configData = new ConfigData();
        this.pool = new CPool(ioReactor,
                new InternalConnectionFactory(this.configData, connFactory),
                socketAddressResolver,
                2, 20, timeToLive, timeUnit != null ? timeUnit : TimeUnit.MILLISECONDS);
        this.ioSessionFactoryRegistry = ioSessionFactoryRegistry;
    }

    PoolingNHttpClientConnectionManager(
            final ConnectingIOReactor ioReactor,
            final CPool pool,
            final Registry<SchemeIOSessionStrategy> ioSessionFactoryRegistry) {
        super();
        this.ioReactor = ioReactor;
        this.configData = new ConfigData();
        this.pool = pool;
        this.ioSessionFactoryRegistry = ioSessionFactoryRegistry;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            shutdown();
        } finally {
            super.finalize();
        }
    }

    @Override
    public void execute(final IOEventDispatch eventDispatch) throws IOException {
        this.ioReactor.execute(eventDispatch);
    }

    public void shutdown(final long waitMs) throws IOException {
        this.log.debug("Connection manager is shutting down");
        this.pool.shutdown(waitMs);
        this.log.debug("Connection manager shut down");
    }

    @Override
    public void shutdown() throws IOException {
        this.log.debug("Connection manager is shutting down");
        this.pool.shutdown(2000);
        this.log.debug("Connection manager shut down");
    }

    private String format(final HttpRoute route, final Object state) {
        final StringBuilder buf = new StringBuilder();
        buf.append("[route: ").append(route).append("]");
        if (state != null) {
            buf.append("[state: ").append(state).append("]");
        }
        return buf.toString();
    }

    private String formatStats(final HttpRoute route) {
        final StringBuilder buf = new StringBuilder();
        final PoolStats totals = this.pool.getTotalStats();
        final PoolStats stats = this.pool.getStats(route);
        buf.append("[total kept alive: ").append(totals.getAvailable()).append("; ");
        buf.append("route allocated: ").append(stats.getLeased() + stats.getAvailable());
        buf.append(" of ").append(stats.getMax()).append("; ");
        buf.append("total allocated: ").append(totals.getLeased() + totals.getAvailable());
        buf.append(" of ").append(totals.getMax()).append("]");
        return buf.toString();
    }

    private String format(final CPoolEntry entry) {
        final StringBuilder buf = new StringBuilder();
        buf.append("[id: ").append(entry.getId()).append("]");
        buf.append("[route: ").append(entry.getRoute()).append("]");
        final Object state = entry.getState();
        if (state != null) {
            buf.append("[state: ").append(state).append("]");
        }
        return buf.toString();
    }

    @Override
    public Future<NHttpClientConnection> requestConnection(
            final HttpRoute route,
            final Object state,
            final long connectTimeout,
            final long leaseTimeout,
            final TimeUnit timeUnit,
            final FutureCallback<NHttpClientConnection> callback) {
        Args.notNull(route, "HTTP route");
        if (this.log.isDebugEnabled()) {
            this.log.debug("Connection request: " + format(route, state) + formatStats(route));
        }
        final BasicFuture<NHttpClientConnection> resultFuture = new BasicFuture<NHttpClientConnection>(callback);
        final HttpHost host;
        if (route.getProxyHost() != null) {
            host = route.getProxyHost();
        } else {
            host = route.getTargetHost();
        }
        final SchemeIOSessionStrategy sf = this.ioSessionFactoryRegistry.lookup(
                host.getSchemeName());
        if (sf == null) {
            resultFuture.failed(new UnsupportedSchemeException(host.getSchemeName() +
                    " protocol is not supported"));
            return resultFuture;
        }
        final Future<CPoolEntry> leaseFuture = this.pool.lease(route, state,
                connectTimeout, leaseTimeout, timeUnit != null ? timeUnit : TimeUnit.MILLISECONDS,
                new FutureCallback<CPoolEntry>() {

                    @Override
                    public void completed(final CPoolEntry entry) {
                        Asserts.check(entry.getConnection() != null, "Pool entry with no connection");
                        if (log.isDebugEnabled()) {
                            log.debug("Connection leased: " + format(entry) + formatStats(entry.getRoute()));
                        }
                        final NHttpClientConnection managedConn = CPoolProxy.newProxy(entry);
                        synchronized (managedConn) {
                            if (!resultFuture.completed(managedConn)) {
                                pool.release(entry, true);
                            }
                        }
                    }

                    @Override
                    public void failed(final Exception ex) {
                        if (log.isDebugEnabled()) {
                            log.debug("Connection request failed", ex);
                        }
                        resultFuture.failed(ex);
                    }

                    @Override
                    public void cancelled() {
                        log.debug("Connection request cancelled");
                        resultFuture.cancel(true);
                    }

                });
        return new Future<NHttpClientConnection>() {

            @Override
            public boolean cancel(final boolean mayInterruptIfRunning) {
                try {
                    leaseFuture.cancel(mayInterruptIfRunning);
                } finally {
                    return resultFuture.cancel(mayInterruptIfRunning);
                }
            }

            @Override
            public boolean isCancelled() {
                return resultFuture.isCancelled();
            }

            @Override
            public boolean isDone() {
                return resultFuture.isDone();
            }

            @Override
            public NHttpClientConnection get() throws InterruptedException, ExecutionException {
                return resultFuture.get();
            }

            @Override
            public NHttpClientConnection get(final long timeout, final TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                return resultFuture.get(timeout, unit);
            }

        };
    }

    @Override
    public void releaseConnection(
            final NHttpClientConnection managedConn,
            final Object state,
            final long keepalive,
            final TimeUnit timeUnit) {
        Args.notNull(managedConn, "Managed connection");
        synchronized (managedConn) {
            final CPoolEntry entry = CPoolProxy.detach(managedConn);
            if (entry == null) {
                return;
            }
            if (this.log.isDebugEnabled()) {
                this.log.debug("Releasing connection: " + format(entry) + formatStats(entry.getRoute()));
            }
            final NHttpClientConnection conn = entry.getConnection();
            try {
                if (conn.isOpen()) {
                    entry.setState(state);
                    entry.updateExpiry(keepalive, timeUnit != null ? timeUnit : TimeUnit.MILLISECONDS);
                    if (this.log.isDebugEnabled()) {
                        final String s;
                        if (keepalive > 0) {
                            s = "for " + (double) keepalive / 1000 + " seconds";
                        } else {
                            s = "indefinitely";
                        }
                        this.log.debug("Connection " + format(entry) + " can be kept alive " + s);
                    }
                }
            } finally {
                this.pool.release(entry, conn.isOpen() && entry.isRouteComplete());
                if (this.log.isDebugEnabled()) {
                    this.log.debug("Connection released: " + format(entry) + formatStats(entry.getRoute()));
                }
            }
        }
    }

    private Lookup<SchemeIOSessionStrategy> getIOSessionFactoryRegistry(final HttpContext context) {
        @SuppressWarnings("unchecked")
        Lookup<SchemeIOSessionStrategy> reg = (Lookup<SchemeIOSessionStrategy>) context.getAttribute(
                IOSESSION_FACTORY_REGISTRY);
        if (reg == null) {
            reg = this.ioSessionFactoryRegistry;
        }
        return reg;
    }

    @Override
    public void startRoute(
            final NHttpClientConnection managedConn,
            final HttpRoute route,
            final HttpContext context) throws IOException {
        Args.notNull(managedConn, "Managed connection");
        Args.notNull(route, "HTTP route");
        final HttpHost host;
        if (route.getProxyHost() != null) {
            host = route.getProxyHost();
        } else {
            host = route.getTargetHost();
        }
        final Lookup<SchemeIOSessionStrategy> reg = getIOSessionFactoryRegistry(context);
        final SchemeIOSessionStrategy sf = reg.lookup(host.getSchemeName());
        if (sf == null) {
            throw new UnsupportedSchemeException(host.getSchemeName() +
                    " protocol is not supported");
        }
        if (sf.isLayeringRequired()) {
            synchronized (managedConn) {
                final CPoolEntry entry = CPoolProxy.getPoolEntry(managedConn);
                final ManagedNHttpClientConnection conn = entry.getConnection();
                final IOSession ioSession = conn.getIOSession();
                final IOSession currentSession = sf.upgrade(host, ioSession);
                conn.bind(currentSession);
            }
        }
    }

    @Override
    public void upgrade(
            final NHttpClientConnection managedConn,
            final HttpRoute route,
            final HttpContext context) throws IOException {
        Args.notNull(managedConn, "Managed connection");
        Args.notNull(route, "HTTP route");
        final HttpHost host  = route.getTargetHost();
        final Lookup<SchemeIOSessionStrategy> reg = getIOSessionFactoryRegistry(context);
        final SchemeIOSessionStrategy sf = reg.lookup(host.getSchemeName());
        if (sf == null) {
            throw new UnsupportedSchemeException(host.getSchemeName() +
                    " protocol is not supported");
        }
        if (!sf.isLayeringRequired()) {
            throw new UnsupportedSchemeException(host.getSchemeName() +
                    " protocol does not support connection upgrade");
        }
        synchronized (managedConn) {
            final CPoolEntry entry = CPoolProxy.getPoolEntry(managedConn);
            final ManagedNHttpClientConnection conn = entry.getConnection();
            final IOSession currentSession = sf.upgrade(host, conn.getIOSession());
            conn.bind(currentSession);
        }
    }

    @Override
    public void routeComplete(
            final NHttpClientConnection managedConn,
            final HttpRoute route,
            final HttpContext context) {
        Args.notNull(managedConn, "Managed connection");
        Args.notNull(route, "HTTP route");
        synchronized (managedConn) {
            final CPoolEntry entry = CPoolProxy.getPoolEntry(managedConn);
            entry.markRouteComplete();
        }
    }

    @Override
    public boolean isRouteComplete(
            final NHttpClientConnection managedConn) {
        Args.notNull(managedConn, "Managed connection");
        synchronized (managedConn) {
            final CPoolEntry entry = CPoolProxy.getPoolEntry(managedConn);
            return entry.isRouteComplete();
        }
    }

    @Override
    public void closeIdleConnections(final long idleTimeout, final TimeUnit timeUnit) {
        if (this.log.isDebugEnabled()) {
            this.log.debug("Closing connections idle longer than " + idleTimeout + " " + timeUnit);
        }
        this.pool.closeIdle(idleTimeout, timeUnit);
    }

    @Override
    public void closeExpiredConnections() {
        log.debug("Closing expired connections");
        this.pool.closeExpired();
    }

    public void validatePendingRequests() {
        log.debug("Validating pending requests");
        this.pool.validatePendingRequests();
    }

    @Override
    public int getMaxTotal() {
        return this.pool.getMaxTotal();
    }

    @Override
    public void setMaxTotal(final int max) {
        this.pool.setMaxTotal(max);
    }

    @Override
    public int getDefaultMaxPerRoute() {
        return this.pool.getDefaultMaxPerRoute();
    }

    @Override
    public void setDefaultMaxPerRoute(final int max) {
        this.pool.setDefaultMaxPerRoute(max);
    }

    @Override
    public int getMaxPerRoute(final HttpRoute route) {
        return this.pool.getMaxPerRoute(route);
    }

    @Override
    public void setMaxPerRoute(final HttpRoute route, final int max) {
        this.pool.setMaxPerRoute(route, max);
    }

    @Override
    public PoolStats getTotalStats() {
        return this.pool.getTotalStats();
    }

    @Override
    public PoolStats getStats(final HttpRoute route) {
        return this.pool.getStats(route);
    }

    /**
     * @since 4.1
     */
    public Set<HttpRoute> getRoutes() {
        return this.pool.getRoutes();
    }

    public ConnectionConfig getDefaultConnectionConfig() {
        return this.configData.getDefaultConnectionConfig();
    }

    public void setDefaultConnectionConfig(final ConnectionConfig defaultConnectionConfig) {
        this.configData.setDefaultConnectionConfig(defaultConnectionConfig);
    }

    public ConnectionConfig getConnectionConfig(final HttpHost host) {
        return this.configData.getConnectionConfig(host);
    }

    public void setConnectionConfig(final HttpHost host, final ConnectionConfig connectionConfig) {
        this.configData.setConnectionConfig(host, connectionConfig);
    }

    static class ConfigData {

        private final Map<HttpHost, ConnectionConfig> connectionConfigMap;
        private volatile ConnectionConfig defaultConnectionConfig;

        ConfigData() {
            super();
            this.connectionConfigMap = new ConcurrentHashMap<HttpHost, ConnectionConfig>();
        }

        public ConnectionConfig getDefaultConnectionConfig() {
            return this.defaultConnectionConfig;
        }

        public void setDefaultConnectionConfig(final ConnectionConfig defaultConnectionConfig) {
            this.defaultConnectionConfig = defaultConnectionConfig;
        }

        public ConnectionConfig getConnectionConfig(final HttpHost host) {
            return this.connectionConfigMap.get(host);
        }

        public void setConnectionConfig(final HttpHost host, final ConnectionConfig connectionConfig) {
            this.connectionConfigMap.put(host, connectionConfig);
        }

    }

    static class InternalConnectionFactory implements NIOConnFactory<HttpRoute, ManagedNHttpClientConnection> {

        private final ConfigData configData;
        private final NHttpConnectionFactory<ManagedNHttpClientConnection> connFactory;

        InternalConnectionFactory(
                final ConfigData configData,
                final NHttpConnectionFactory<ManagedNHttpClientConnection> connFactory) {
            super();
            this.configData = configData != null ? configData : new ConfigData();
            this.connFactory = connFactory != null ? connFactory :
                ManagedNHttpClientConnectionFactory.INSTANCE;
        }

        @Override
        public ManagedNHttpClientConnection create(
                final HttpRoute route, final IOSession ioSession) throws IOException {
            ConnectionConfig config = null;
            if (route.getProxyHost() != null) {
                config = this.configData.getConnectionConfig(route.getProxyHost());
            }
            if (config == null) {
                config = this.configData.getConnectionConfig(route.getTargetHost());
            }
            if (config == null) {
                config = this.configData.getDefaultConnectionConfig();
            }
            if (config == null) {
                config = ConnectionConfig.DEFAULT;
            }
            final ManagedNHttpClientConnection conn = this.connFactory.create(ioSession, config);
            ioSession.setAttribute(IOEventDispatch.CONNECTION_KEY, conn);
            return conn;
        }

    }

    static class InternalAddressResolver implements SocketAddressResolver<HttpRoute> {

        private final SchemePortResolver schemePortResolver;
        private final DnsResolver dnsResolver;

        public InternalAddressResolver(
                final SchemePortResolver schemePortResolver,
                final DnsResolver dnsResolver) {
            super();
            this.schemePortResolver = schemePortResolver != null ? schemePortResolver :
                DefaultSchemePortResolver.INSTANCE;
            this.dnsResolver = dnsResolver != null ? dnsResolver :
                    SystemDefaultDnsResolver.INSTANCE;
        }

        @Override
        public SocketAddress resolveLocalAddress(final HttpRoute route) throws IOException {
            return route.getLocalAddress() != null ? new InetSocketAddress(route.getLocalAddress(), 0) : null;
        }

        @Override
        public SocketAddress resolveRemoteAddress(final HttpRoute route) throws IOException {
            final HttpHost host;
            if (route.getProxyHost() != null) {
                host = route.getProxyHost();
            } else {
                host = route.getTargetHost();
            }
            final int port = this.schemePortResolver.resolve(host);
            final InetAddress[] addresses = this.dnsResolver.resolve(host.getHostName());
            return new InetSocketAddress(addresses[0], port);
        }

    }
}
