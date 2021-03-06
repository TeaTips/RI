/**
 *  Copyright 2011 Terracotta, Inc.
 *  Copyright 2011 Oracle America Incorporated
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.jsr107.ri;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.Configuration;
import javax.cache.OptionalFeature;
import javax.cache.Status;
import javax.cache.spi.CachingProvider;
import javax.cache.transaction.IsolationLevel;
import javax.cache.transaction.Mode;
import javax.transaction.UserTransaction;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The reference implementation of the {@link CacheManager}.
 *
 * @author Yannis Cosmadopoulos
 * @since 1.0
 */
public class RICacheManager implements CacheManager {

    private static final Logger LOGGER = Logger.getLogger("javax.cache");
    private final HashMap<String, Cache<?, ?>> caches = new HashMap<String, Cache<?, ?>>();

    private final RICachingProvider cachingProvider;

    private final URI uri;
    private final WeakReference<ClassLoader> classLoaderReference;
    private final Properties properties;

    private volatile Status status;

    /**
     * Constructs a new RICacheManager with the specified name.
     *
     * @param cachingProvider  the CachingProvider that created the CacheManager
     * @param uri              the name of this cache manager
     * @param classLoader      the ClassLoader that should be used in converting values into Java Objects.
     * @param properties       the vendor specific Properties for the CacheManager
     *
     * @throws NullPointerException if the URI and/or classLoader is null.
     */
    public RICacheManager(RICachingProvider cachingProvider, URI uri, ClassLoader classLoader, Properties properties) {
        if (cachingProvider == null) {
            throw new NullPointerException("No CachingProvider specified");
        }
        this.cachingProvider = cachingProvider;

        if (uri == null) {
            throw new NullPointerException("No CacheManager URI specified");
        }
        this.uri = uri;

        if (classLoader == null) {
            throw new NullPointerException("No ClassLoader specified");
        }
        this.classLoaderReference = new WeakReference<ClassLoader>(classLoader);

        this.properties = properties == null ? new Properties() : new Properties(properties);

        status = Status.UNINITIALISED;
        status = Status.STARTED;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CachingProvider getCachingProvider() {
        return cachingProvider;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public URI getURI() {
        return uri;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Properties getProperties() {
        return properties;
    }

    /**
     * Getter
     * @return the class loader
     */
    protected ClassLoader getClassLoader() {
        return classLoaderReference.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Status getStatus() {
        return status;
    }

    
    /**
     * {@inheritDoc}
     */
    @Override
    public <K, V> Cache<K, V> configureCache(String cacheName, Configuration<K, V> configuration) {
        if (status != Status.STARTED) {
            throw new IllegalStateException();
        }

        if (cacheName == null) {
            throw new NullPointerException("cacheName must not be null");
        }
        
        if (configuration == null) {
            throw new NullPointerException("configuration must not be null");
        }
        
        if (configuration.getTransactionIsolationLevel() == IsolationLevel.NONE &&
            configuration.getTransactionMode() != Mode.NONE) {
            throw new IllegalArgumentException("isolation level expected when mode specified");
        }
        
        if (configuration.getTransactionIsolationLevel() != IsolationLevel.NONE &&
            configuration.getTransactionMode() == Mode.NONE) {
            throw new IllegalArgumentException("mode expected when isolation level specified");
        }
        
        synchronized (caches) {
            Cache<?, ?> cache = caches.get(cacheName);
            
            if (cache == null) {
                cache = new RICache<K, V>(this, cacheName, getClassLoader(), configuration);
                caches.put(cache.getName(), cache);
                
                cache.start();
            } else {
                //note: we must clone the provided configuration as it needs to be
                //      the same internal type as our internal configuration
                RIConfiguration<K, V> config = new RIConfiguration<K, V>(configuration);
                
                //ensure that the existing cache has the same configuration as the provided one
                if (!cache.getConfiguration().equals(config)) {
                    throw new CacheException("Cache " + cache.getName() + " already registered but with a different configuration");
                }                
            }
        
            return (Cache<K, V>)cache;
        }
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public <K, V> Cache<K, V> getCache(String cacheName) {
        if (status != Status.STARTED) {
            throw new IllegalStateException();
        }
        synchronized (caches) {
            /*
             * Can't really verify that the K/V cast is safe but it is required by the API, using a 
             * local variable for the cast to allow for a minimal scoping of @SuppressWarnings 
             */
            @SuppressWarnings("unchecked")
            final Cache<K, V> cache = (Cache<K, V>) caches.get(cacheName);
            return cache;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterable<Cache<?, ?>> getCaches() {
        synchronized (caches) {
            HashSet<Cache<?, ?>> set = new HashSet<Cache<?, ?>>();
            for (Cache<?, ?> cache : caches.values()) {
                set.add(cache);
            }
            return Collections.unmodifiableSet(set);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeCache(String cacheName) {
        if (status != Status.STARTED) {
            throw new IllegalStateException();
        }
        if (cacheName == null) {
            throw new NullPointerException();
        }
        Cache<?, ?> oldCache;
        synchronized (caches) {
            oldCache = caches.remove(cacheName);
        }
        if (oldCache != null) {
            oldCache.stop();
        }

        return oldCache != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public UserTransaction getUserTransaction() {
        throw new UnsupportedOperationException("Transactions are not supported.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSupported(OptionalFeature optionalFeature) {
        return getCachingProvider().isSupported(optionalFeature);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void enableStatistics(String cacheName, boolean enabled) {
        if (status != Status.STARTED) {
            throw new IllegalStateException();
        }
        if (cacheName == null) {
            throw new NullPointerException();
        }
        ((RICache)caches.get(cacheName)).setStatisticsEnabled(enabled);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void enableManagement(String cacheName, boolean enabled) {
        if (status != Status.STARTED) {
            throw new IllegalStateException();
        }
        if (cacheName == null) {
            throw new NullPointerException();
        }
        ((RICache)caches.get(cacheName)).setManagementEnabled(enabled);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        //first release the CacheManager from the CacheProvider so that
        //future requests for this CacheManager won't return this one
        cachingProvider.release(getURI(), getClassLoader());

        if (status != Status.STARTED) {
            throw new IllegalStateException();
        }
        ArrayList<Cache<?, ?>> cacheList;
        synchronized (caches) {
            cacheList = new ArrayList<Cache<?, ?>>(caches.values());
            caches.clear();
        }
        for (Cache<?, ?> cache : cacheList) {
            try {
                cache.stop();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error stopping cache: " + cache, e);
            }
        }
        status = Status.STOPPED;
    }

    @Override
    public <T> T unwrap(java.lang.Class<T> cls) {
        if (cls.isAssignableFrom(getClass())) {
            return cls.cast(this);
        }

        throw new IllegalArgumentException("Unwapping to " + cls + " is not a supported by this implementation");
    }

    /**
     * Obtain the logger.
     *
     * @return the logger.
     */
    Logger getLogger() {
        return LOGGER;
    }
}
