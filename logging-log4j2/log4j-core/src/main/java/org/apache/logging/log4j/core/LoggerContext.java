/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.core;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.net.URI;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.async.AsyncLogger;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
import org.apache.logging.log4j.core.config.ConfigurationListener;
import org.apache.logging.log4j.core.config.ConfigurationSource; // SUPPRESS CHECKSTYLE
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.config.NullConfiguration;
import org.apache.logging.log4j.core.config.Reconfigurable;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.jmx.Server;
import org.apache.logging.log4j.core.util.Cancellable;
import org.apache.logging.log4j.core.util.NanoClockFactory;
import org.apache.logging.log4j.core.util.NetUtils;
import org.apache.logging.log4j.core.util.ShutdownCallbackRegistry;
import org.apache.logging.log4j.message.MessageFactory;
import org.apache.logging.log4j.spi.AbstractLogger;
import org.apache.logging.log4j.spi.LoggerContextFactory;
import org.apache.logging.log4j.spi.LoggerContextKey;

import static org.apache.logging.log4j.core.util.ShutdownCallbackRegistry.*;

/**
 * The LoggerContext is the anchor for the logging system. It maintains a list of all the loggers requested by
 * applications and a reference to the Configuration. The Configuration will contain the configured loggers, appenders,
 * filters, etc and will be atomically updated whenever a reconfigure occurs.
 */
public class LoggerContext extends AbstractLifeCycle implements org.apache.logging.log4j.spi.LoggerContext,
        ConfigurationListener {

    /**
     * Property name of the property change event fired if the configuration is changed.
     */
    public static final String PROPERTY_CONFIG = "config";

    private static final long serialVersionUID = 1L;
    private static final Configuration NULL_CONFIGURATION = new NullConfiguration();

    private final ConcurrentMap<String, Logger> loggers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<PropertyChangeListener> propertyChangeListeners = new CopyOnWriteArrayList<>();

    /**
     * The Configuration is volatile to guarantee that initialization of the Configuration has completed before the
     * reference is updated.
     */
    private volatile Configuration configuration = new DefaultConfiguration();
    private Object externalContext;
    private String contextName;
    private volatile URI configLocation;
    private Cancellable shutdownCallback;

    private final Lock configLock = new ReentrantLock();

    /**
     * Constructor taking only a name.
     * 
     * @param name The context name.
     */
    public LoggerContext(final String name) {
        this(name, null, (URI) null);
    }

    /**
     * Constructor taking a name and a reference to an external context.
     * 
     * @param name The context name.
     * @param externalContext The external context.
     */
    public LoggerContext(final String name, final Object externalContext) {
        this(name, externalContext, (URI) null);
    }

    /**
     * Constructor taking a name, external context and a configuration URI.
     * 
     * @param name The context name.
     * @param externalContext The external context.
     * @param configLocn The location of the configuration as a URI.
     */
    public LoggerContext(final String name, final Object externalContext, final URI configLocn) {
        this.contextName = name;
        this.externalContext = externalContext;
        this.configLocation = configLocn;
    }

    /**
     * Constructor taking a name external context and a configuration location String. The location must be resolvable
     * to a File.
     *
     * @param name The configuration location.
     * @param externalContext The external context.
     * @param configLocn The configuration location.
     */
    public LoggerContext(final String name, final Object externalContext, final String configLocn) {
        this.contextName = name;
        this.externalContext = externalContext;
        if (configLocn != null) {
            URI uri;
            try {
                uri = new File(configLocn).toURI();
            } catch (final Exception ex) {
                uri = null;
            }
            configLocation = uri;
        } else {
            configLocation = null;
        }
    }

    /**
     * Returns the current LoggerContext.
     * <p>
     * Avoids the type cast for:
     * </p>
     *
     * <pre>
     * (LoggerContext) LogManager.getContext();
     * </pre>
     *
     * <p>
     * WARNING - The LoggerContext returned by this method may not be the LoggerContext used to create a Logger for the
     * calling class.
     * </p>
     * 
     * @return The current LoggerContext.
     * @see LogManager#getContext()
     */
    public static LoggerContext getContext() {
        return (LoggerContext) LogManager.getContext();
    }

    /**
     * Returns a LoggerContext.
     * <p>
     * Avoids the type cast for:
     * </p>
     *
     * <pre>
     * (LoggerContext) LogManager.getContext(currentContext);
     * </pre>
     *
     * @param currentContext if false the LoggerContext appropriate for the caller of this method is returned. For
     *            example, in a web application if the caller is a class in WEB-INF/lib then one LoggerContext may be
     *            returned and if the caller is a class in the container's classpath then a different LoggerContext may
     *            be returned. If true then only a single LoggerContext will be returned.
     * @return a LoggerContext.
     * @see LogManager#getContext(boolean)
     */
    public static LoggerContext getContext(final boolean currentContext) {
        return (LoggerContext) LogManager.getContext(currentContext);
    }

    /**
     * Returns a LoggerContext.
     * <p>
     * Avoids the type cast for:
     * </p>
     *
     * <pre>
     * (LoggerContext) LogManager.getContext(loader, currentContext, configLocation);
     * </pre>
     *
     * @param loader The ClassLoader for the context. If null the context will attempt to determine the appropriate
     *            ClassLoader.
     * @param currentContext if false the LoggerContext appropriate for the caller of this method is returned. For
     *            example, in a web application if the caller is a class in WEB-INF/lib then one LoggerContext may be
     *            returned and if the caller is a class in the container's classpath then a different LoggerContext may
     *            be returned. If true then only a single LoggerContext will be returned.
     * @param configLocation The URI for the configuration to use.
     * @return a LoggerContext.
     * @see LogManager#getContext(ClassLoader, boolean, URI)
     */
    public static LoggerContext getContext(final ClassLoader loader, final boolean currentContext,
            final URI configLocation) {
        return (LoggerContext) LogManager.getContext(loader, currentContext, configLocation);
    }

    @Override
    public void start() {
        LOGGER.debug("Starting LoggerContext[name={}, {}]...", getName(), this);
        if (configLock.tryLock()) {
            try {
                if (this.isInitialized() || this.isStopped()) {
                    this.setStarting();
                    reconfigure();
                    if (this.configuration.isShutdownHookEnabled()) {
                        setUpShutdownHook();
                    }
                    this.setStarted();
                }
            } finally {
                configLock.unlock();
            }
        }
        LOGGER.debug("LoggerContext[name={}, {}] started OK.", getName(), this);
    }

    /**
     * Starts with a specific configuration.
     * 
     * @param config The new Configuration.
     */
    public void start(final Configuration config) {
        LOGGER.debug("Starting LoggerContext[name={}, {}] with configuration {}...", getName(), this, config);
        if (configLock.tryLock()) {
            try {
                if (this.isInitialized() || this.isStopped()) {
                    if (this.configuration.isShutdownHookEnabled()) {
                        setUpShutdownHook();
                    }
                    this.setStarted();
                }
            } finally {
                configLock.unlock();
            }
        }
        setConfiguration(config);
        LOGGER.debug("LoggerContext[name={}, {}] started OK with configuration {}.", getName(), this, config);
    }

    private void setUpShutdownHook() {
        if (shutdownCallback == null) {
            final LoggerContextFactory factory = LogManager.getFactory();
            if (factory instanceof ShutdownCallbackRegistry) {
                LOGGER.debug(SHUTDOWN_HOOK_MARKER, "Shutdown hook enabled. Registering a new one.");
                try {
                    this.shutdownCallback = ((ShutdownCallbackRegistry) factory).addShutdownCallback(new Runnable() {
                        @Override
                        public void run() {
                            final LoggerContext context = LoggerContext.this;
                            LOGGER.debug(SHUTDOWN_HOOK_MARKER, "Stopping LoggerContext[name={}, {}]",
                                    context.getName(), context);
                            context.stop();
                        }

                        @Override
                        public String toString() {
                            return "Shutdown callback for LoggerContext[name=" + LoggerContext.this.getName() + ']';
                        }
                    });
                } catch (final IllegalStateException e) {
                    LOGGER.error(SHUTDOWN_HOOK_MARKER,
                            "Unable to register shutdown hook because JVM is shutting down.", e);
                } catch (final SecurityException e) {
                    LOGGER.error(SHUTDOWN_HOOK_MARKER, "Unable to register shutdown hook due to security restrictions",
                            e);
                }
            }
        }
    }

    @Override
    public void stop() {
        LOGGER.debug("Stopping LoggerContext[name={}, {}]...", getName(), this);
        configLock.lock();
        try {
            if (this.isStopped()) {
                return;
            }

            this.setStopping();
            try {
                Server.unregisterLoggerContext(getName()); // LOG4J2-406, LOG4J2-500
            } catch (final Exception ex) {
                LOGGER.error("Unable to unregister MBeans", ex);
            }
            if (shutdownCallback != null) {
                shutdownCallback.cancel();
                shutdownCallback = null;
            }
            final Configuration prev = configuration;
            configuration = NULL_CONFIGURATION;
            updateLoggers();
            prev.stop();
            externalContext = null;
            LogManager.getFactory().removeContext(this);
            this.setStopped();
        } finally {
            configLock.unlock();
        }
        LOGGER.debug("Stopped LoggerContext[name={}, {}]...", getName(), this);
    }

    /**
     * Gets the name.
     *
     * @return the name.
     */
    public String getName() {
        return contextName;
    }
    
    /**
     * Gets the root logger.
     * 
     * @return the root logger.
     */
    public Logger getRootLogger() {
        return getLogger(LogManager.ROOT_LOGGER_NAME);
    }
    
    /**
     * Sets the name.
     * 
     * @param name the new LoggerContext name
     * @throws NullPointerException if the specified name is {@code null}
     */
    public void setName(final String name) {
    	contextName = Objects.requireNonNull(name);
    }

    /**
     * Sets the external context.
     * 
     * @param context The external context.
     */
    public void setExternalContext(final Object context) {
        this.externalContext = context;
    }

    /**
     * Returns the external context.
     * 
     * @return The external context.
     */
    @Override
    public Object getExternalContext() {
        return this.externalContext;
    }

    /**
     * Gets a Logger from the Context.
     * 
     * @param name The name of the Logger to return.
     * @return The Logger.
     */
    @Override
    public Logger getLogger(final String name) {
        return getLogger(name, null);
    }

    /**
     * Gets a collection of the current loggers.
     * <p>
     * Whether this collection is a copy of the underlying collection or not is undefined. Therefore, modify this
     * collection at your own risk.
     * </p>
     *
     * @return a collection of the current loggers.
     */
    public Collection<Logger> getLoggers() {
        return loggers.values();
    }

    /**
     * Obtains a Logger from the Context.
     * 
     * @param name The name of the Logger to return.
     * @param messageFactory The message factory is used only when creating a logger, subsequent use does not change the
     *            logger but will log a warning if mismatched.
     * @return The Logger.
     */
    @Override
    public Logger getLogger(final String name, final MessageFactory messageFactory) {
        // Note: This is the only method where we add entries to the 'loggers' ivar. 
        // The loggers map key is the logger name plus the messageFactory FQCN.
        String key = LoggerContextKey.create(name, messageFactory);
        Logger logger = loggers.get(key);
        if (logger != null) {
            AbstractLogger.checkMessageFactory(logger, messageFactory);
            return logger;
        }

        logger = newInstance(this, name, messageFactory);
        // If messageFactory was null then we need to pull it out of the logger now
        key = LoggerContextKey.create(name, logger.getMessageFactory());
        final Logger prev = loggers.putIfAbsent(key, logger);
        return prev == null ? logger : prev;
    }

    /**
     * Determines if the specified Logger exists.
     * 
     * @param name The Logger name to search for.
     * @return True if the Logger exists, false otherwise.
     */
    @Override
    public boolean hasLogger(final String name) {
        return loggers.containsKey(LoggerContextKey.create(name));
    }

    /**
     * Determines if the specified Logger exists.
     * 
     * @param name The Logger name to search for.
     * @return True if the Logger exists, false otherwise.
     */
    @Override
    public boolean hasLogger(final String name, MessageFactory messageFactory) {
        return loggers.containsKey(LoggerContextKey.create(name, messageFactory));
    }

    /**
     * Determines if the specified Logger exists.
     * 
     * @param name The Logger name to search for.
     * @return True if the Logger exists, false otherwise.
     */
    @Override
    public boolean hasLogger(final String name, Class<? extends MessageFactory> messageFactoryClass) {
        return loggers.containsKey(LoggerContextKey.create(name, messageFactoryClass));
    }

    /**
     * Returns the current Configuration. The Configuration will be replaced when a reconfigure occurs.
     *
     * @return The Configuration.
     */
    public Configuration getConfiguration() {
        return configuration;
    }

    /**
     * Adds a Filter to the Configuration. Filters that are added through the API will be lost when a reconfigure
     * occurs.
     * 
     * @param filter The Filter to add.
     */
    public void addFilter(final Filter filter) {
        configuration.addFilter(filter);
    }

    /**
     * Removes a Filter from the current Configuration.
     * 
     * @param filter The Filter to remove.
     */
    public void removeFilter(final Filter filter) {
        configuration.removeFilter(filter);
    }

    /**
     * Sets the Configuration to be used.
     * 
     * @param config The new Configuration.
     * @return The previous Configuration.
     */
    private Configuration setConfiguration(final Configuration config) {
        Objects.requireNonNull(config, "No Configuration was provided");
        configLock.lock();
        try {
            final Configuration prev = this.configuration;
            config.addListener(this);
            final ConcurrentMap<String, String> map = config.getComponent(Configuration.CONTEXT_PROPERTIES);

            try { // LOG4J2-719 network access may throw android.os.NetworkOnMainThreadException
                map.putIfAbsent("hostName", NetUtils.getLocalHostname());
            } catch (final Exception ex) {
                LOGGER.debug("Ignoring {}, setting hostName to 'unknown'", ex.toString());
                map.putIfAbsent("hostName", "unknown");
            }
            map.putIfAbsent("contextName", contextName);
            config.start();
            this.configuration = config;
            updateLoggers();
            if (prev != null) {
                prev.removeListener(this);
                prev.stop();
            }

            firePropertyChangeEvent(new PropertyChangeEvent(this, PROPERTY_CONFIG, prev, config));

            try {
                Server.reregisterMBeansAfterReconfigure();
            } catch (final Throwable t) {
                // LOG4J2-716: Android has no java.lang.management
                LOGGER.error("Could not reconfigure JMX", t);
            }
            Log4jLogEvent.setNanoClock(NanoClockFactory.createNanoClock());
            AsyncLogger.setNanoClock(NanoClockFactory.createNanoClock());
            return prev;
        } finally {
            configLock.unlock();
        }
    }

    private void firePropertyChangeEvent(final PropertyChangeEvent event) {
        for (final PropertyChangeListener listener : propertyChangeListeners) {
            listener.propertyChange(event);
        }
    }

    public void addPropertyChangeListener(final PropertyChangeListener listener) {
        propertyChangeListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removePropertyChangeListener(final PropertyChangeListener listener) {
        propertyChangeListeners.remove(listener);
    }

    /**
     * Returns the initial configuration location or {@code null}. The returned value may not be the location of the
     * current configuration. Use {@link #getConfiguration()}.{@link Configuration#getConfigurationSource()
     * getConfigurationSource()}.{@link ConfigurationSource#getLocation() getLocation()} to get the actual source of the
     * current configuration.
     * 
     * @return the initial configuration location or {@code null}
     */
    public URI getConfigLocation() {
        return configLocation;
    }

    /**
     * Sets the configLocation to the specified value and reconfigures this context.
     * 
     * @param configLocation the location of the new configuration
     */
    public void setConfigLocation(final URI configLocation) {
        this.configLocation = configLocation;

        reconfigure(configLocation);
    }

    /**
     * Reconfigures the context.
     */
    private void reconfigure(final URI configURI) {
        final ClassLoader cl = ClassLoader.class.isInstance(externalContext) ? (ClassLoader) externalContext : null;
        LOGGER.debug("Reconfiguration started for context[name={}] at URI {} ({}) with optional ClassLoader: {}",
                contextName, configURI, this, cl);
        final Configuration instance = ConfigurationFactory.getInstance().getConfiguration(contextName, configURI, cl);
        setConfiguration(instance);
        /*
         * instance.start(); Configuration old = setConfiguration(instance); updateLoggers(); if (old != null) {
         * old.stop(); }
         */
        final String location = configuration == null ? "?" : String.valueOf(configuration.getConfigurationSource());
        LOGGER.debug("Reconfiguration complete for context[name={}] at URI {} ({}) with optional ClassLoader: {}",
                contextName, location, this, cl);
    }

    /**
     * Reconfigures the context. Log4j does not remove Loggers during a reconfiguration. Log4j will create new
     * LoggerConfig objects and Log4j will point the Loggers at the new LoggerConfigs. Log4j will free the old
     * LoggerConfig, along with old Appenders and Filters.
     */
    public void reconfigure() {
        reconfigure(configLocation);
    }

    /**
     * Causes all Loggers to be updated against the current Configuration.
     */
    public void updateLoggers() {
        updateLoggers(this.configuration);
    }

    /**
     * Causes all Logger to be updated against the specified Configuration.
     * 
     * @param config The Configuration.
     */
    public void updateLoggers(final Configuration config) {
        for (final Logger logger : loggers.values()) {
            logger.updateConfiguration(config);
        }
    }

    /**
     * Causes a reconfiguration to take place when the underlying configuration file changes.
     *
     * @param reconfigurable The Configuration that can be reconfigured.
     */
    @Override
    public synchronized void onChange(final Reconfigurable reconfigurable) {
        LOGGER.debug("Reconfiguration started for context {} ({})", contextName, this);
        final Configuration newConfig = reconfigurable.reconfigure();
        if (newConfig != null) {
            setConfiguration(newConfig);
            LOGGER.debug("Reconfiguration completed for {} ({})", contextName, this);
        } else {
            LOGGER.debug("Reconfiguration failed for {} ({})", contextName, this);
        }
    }

    // LOG4J2-151: changed visibility from private to protected
    protected Logger newInstance(final LoggerContext ctx, final String name, final MessageFactory messageFactory) {
        return new Logger(ctx, name, messageFactory);
    }

}
