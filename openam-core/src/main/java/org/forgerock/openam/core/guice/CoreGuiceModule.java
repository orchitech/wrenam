/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2013-2016 ForgeRock AS.
 * Portions Copyrighted 2015 Nomura Research Institute, Ltd.
 */

package org.forgerock.openam.core.guice;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.ServletContext;

import org.forgerock.guice.core.GuiceModule;
import org.forgerock.guice.core.InjectorHolder;
import org.forgerock.json.JsonValue;
import org.forgerock.openam.auditors.SMSAuditFilter;
import org.forgerock.openam.auditors.SMSAuditor;
import org.forgerock.openam.blacklist.Blacklist;
import org.forgerock.openam.blacklist.BloomFilterBlacklist;
import org.forgerock.openam.blacklist.CTSBlacklist;
import org.forgerock.openam.blacklist.CachingBlacklist;
import org.forgerock.openam.blacklist.NoOpBlacklist;
import org.forgerock.openam.core.DNWrapper;
import org.forgerock.openam.core.realms.RealmGuiceModule;
import org.forgerock.openam.cts.CTSPersistentStore;
import org.forgerock.openam.cts.CTSPersistentStoreImpl;
import org.forgerock.openam.cts.CoreTokenConfig;
import org.forgerock.openam.cts.adapters.OAuthAdapter;
import org.forgerock.openam.cts.adapters.SAMLAdapter;
import org.forgerock.openam.cts.adapters.TokenAdapter;
import org.forgerock.openam.cts.api.CoreTokenConstants;
import org.forgerock.openam.cts.api.tokens.SAMLToken;
import org.forgerock.openam.cts.impl.query.worker.CTSWorkerConnection;
import org.forgerock.openam.cts.impl.query.worker.CTSWorkerConstants;
import org.forgerock.openam.cts.impl.query.worker.CTSWorkerQuery;
import org.forgerock.openam.cts.impl.query.worker.queries.CTSWorkerPastExpiryDateQuery;
import org.forgerock.openam.cts.impl.query.worker.queries.MaxSessionTimeExpiredQuery;
import org.forgerock.openam.cts.impl.query.worker.queries.SessionIdleTimeExpiredQuery;
import org.forgerock.openam.cts.impl.queue.ResultHandlerFactory;
import org.forgerock.openam.cts.monitoring.CTSConnectionMonitoringStore;
import org.forgerock.openam.cts.monitoring.CTSOperationsMonitoringStore;
import org.forgerock.openam.cts.monitoring.CTSReaperMonitoringStore;
import org.forgerock.openam.cts.monitoring.impl.CTSMonitoringStoreImpl;
import org.forgerock.openam.cts.monitoring.impl.queue.MonitoredResultHandlerFactory;
import org.forgerock.openam.cts.worker.CTSWorkerTask;
import org.forgerock.openam.cts.worker.CTSWorkerTaskProvider;
import org.forgerock.openam.cts.worker.filter.CTSWorkerSelectAllFilter;
import org.forgerock.openam.cts.worker.process.CTSWorkerDeleteProcess;
import org.forgerock.openam.cts.worker.process.MaxSessionTimeExpiredProcess;
import org.forgerock.openam.cts.worker.process.SessionIdleTimeExpiredProcess;
import org.forgerock.openam.entitlement.monitoring.PolicyMonitor;
import org.forgerock.openam.entitlement.monitoring.PolicyMonitorImpl;
import org.forgerock.openam.entitlement.service.EntitlementConfigurationFactory;
import org.forgerock.openam.federation.saml2.SAML2TokenRepository;
import org.forgerock.openam.identity.idm.AMIdentityRepositoryFactory;
import org.forgerock.openam.oauth2.OAuth2Constants;
import org.forgerock.openam.session.SessionCache;
import org.forgerock.openam.session.SessionCookies;
import org.forgerock.openam.session.SessionPollerPool;
import org.forgerock.openam.session.SessionServiceURLService;
import org.forgerock.openam.session.SessionURL;
import org.forgerock.openam.session.service.access.persistence.InternalSessionPersistenceStoreStep;
import org.forgerock.openam.session.service.access.persistence.InternalSessionStore;
import org.forgerock.openam.session.service.access.persistence.InternalSessionStoreChain;
import org.forgerock.openam.session.service.access.persistence.TimeOutSessionFilterStep;
import org.forgerock.openam.session.service.access.persistence.caching.InternalSessionCache;
import org.forgerock.openam.session.service.access.persistence.caching.InternalSessionCacheStep;
import org.forgerock.openam.session.service.access.persistence.caching.InternalSessionStorage;
import org.forgerock.openam.shared.concurrency.ThreadMonitor;
import org.forgerock.openam.sm.SMSConfigurationFactory;
import org.forgerock.openam.sm.ServerGroupConfiguration;
import org.forgerock.openam.sm.config.ConsoleConfigHandler;
import org.forgerock.openam.sm.config.ConsoleConfigHandlerImpl;
import org.forgerock.openam.sm.datalayer.api.ConnectionFactory;
import org.forgerock.openam.sm.datalayer.api.ConnectionType;
import org.forgerock.openam.sm.datalayer.api.DataLayer;
import org.forgerock.openam.sm.datalayer.api.DataLayerConstants;
import org.forgerock.openam.sm.datalayer.api.DataLayerException;
import org.forgerock.openam.sm.datalayer.api.QueueConfiguration;
import org.forgerock.openam.sso.providers.stateless.StatelessAdminRestriction;
import org.forgerock.openam.sso.providers.stateless.StatelessAdminRestriction.SuperUserDelegate;
import org.forgerock.openam.sso.providers.stateless.StatelessSSOProvider;
import org.forgerock.openam.tokens.TokenType;
import org.forgerock.openam.utils.Config;
import org.forgerock.openam.utils.OpenAMSettings;
import org.forgerock.openam.utils.OpenAMSettingsImpl;
import org.forgerock.util.Function;
import org.forgerock.util.promise.NeverThrowsException;
import org.forgerock.util.thread.ExecutorServiceFactory;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.inject.AbstractModule;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.TypeLiteral;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;
import com.iplanet.am.util.SecureRandomManager;
import com.iplanet.dpro.session.Session;
import com.iplanet.dpro.session.SessionID;
import com.iplanet.dpro.session.monitoring.SessionMonitoringStore;
import com.iplanet.dpro.session.operations.ServerSessionOperationStrategy;
import com.iplanet.dpro.session.operations.SessionOperationStrategy;
import com.iplanet.dpro.session.service.InternalSessionListener;
import com.iplanet.dpro.session.service.SessionNotificationPublisher;
import com.iplanet.dpro.session.service.SessionAuditor;
import com.iplanet.dpro.session.service.SessionConstants;
import com.iplanet.dpro.session.service.InternalSessionEventBroker;
import com.iplanet.dpro.session.service.SessionLogging;
import com.iplanet.dpro.session.service.SessionNotificationSender;
import com.iplanet.dpro.session.service.SessionServerConfig;
import com.iplanet.dpro.session.service.SessionService;
import com.iplanet.dpro.session.service.SessionServiceConfig;
import com.iplanet.dpro.session.service.SessionTimeoutHandlerExecutor;
import com.iplanet.services.ldap.DSConfigMgr;
import com.iplanet.services.ldap.LDAPServiceException;
import com.iplanet.services.naming.WebtopNamingQuery;
import com.iplanet.sso.SSOException;
import com.iplanet.sso.SSOToken;
import com.iplanet.sso.SSOTokenManager;
import com.sun.identity.common.configuration.ConfigurationObserver;
import com.sun.identity.delegation.DelegationManager;
import com.sun.identity.entitlement.EntitlementConfiguration;
import com.sun.identity.entitlement.opensso.EntitlementService;
import com.sun.identity.idm.AMIdentityRepository;
import com.sun.identity.idm.IdRepoCreationListener;
import com.sun.identity.security.AdminTokenAction;
import com.sun.identity.setup.ServicesDefaultValues;
import com.sun.identity.shared.configuration.SystemPropertiesManager;
import com.sun.identity.shared.debug.Debug;
import com.sun.identity.shared.stats.Stats;
import com.sun.identity.shared.validation.URLValidator;
import com.sun.identity.sm.OrganizationConfigManager;
import com.sun.identity.sm.OrganizationConfigManagerFactory;
import com.sun.identity.sm.SMSEntry;
import com.sun.identity.sm.SMSException;
import com.sun.identity.sm.ServiceConfigManager;
import com.sun.identity.sm.ServiceManagementDAO;
import com.sun.identity.sm.ServiceManagementDAOWrapper;
import com.sun.identity.sm.ldap.ConfigAuditorFactory;

/**
 * Guice Module for configuring bindings for the OpenAM Core classes.
 */
@GuiceModule
public class CoreGuiceModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(new AdminTokenType()).toProvider(new AdminTokenProvider()).in(Singleton.class);
        bind(ServiceManagementDAO.class).to(ServiceManagementDAOWrapper.class).in(Singleton.class);
        bind(DNWrapper.class).in(Singleton.class);
        bind(URLValidator.class).toInstance(URLValidator.getInstance());

        bind(new TypeLiteral<TokenAdapter<JsonValue>>(){})
                .annotatedWith(Names.named(OAuth2Constants.CoreTokenParams.OAUTH_TOKEN_ADAPTER))
                .to(OAuthAdapter.class);

        bind(DSConfigMgr.class).toProvider(new Provider<DSConfigMgr>() {
            public DSConfigMgr get() {
                try {
                    return DSConfigMgr.getStableDSConfigMgr();
                } catch (LDAPServiceException e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        bind(SSOTokenManager.class).toProvider(new Provider<SSOTokenManager>() {
            public SSOTokenManager get() {
                try {
                    return SSOTokenManager.getInstance();
                } catch (SSOException e) {
                    throw new IllegalStateException(e);
                }
            }
        }).in(Singleton.class);

        /**
         * Core Token Service bindings are divided into a number of logical groups.
         */
        // CTS General
        bind(CTSPersistentStore.class).to(CTSPersistentStoreImpl.class);
        bind(Debug.class).annotatedWith(Names.named(CoreTokenConstants.CTS_DEBUG))
                .toInstance(Debug.getInstance(CoreTokenConstants.CTS_DEBUG));
        bind(Debug.class).annotatedWith(Names.named(CoreTokenConstants.CTS_REAPER_DEBUG))
                .toInstance(Debug.getInstance(CoreTokenConstants.CTS_REAPER_DEBUG));
        bind(Debug.class).annotatedWith(Names.named(CoreTokenConstants.CTS_ASYNC_DEBUG))
                .toInstance(Debug.getInstance(CoreTokenConstants.CTS_ASYNC_DEBUG));
        bind(Debug.class).annotatedWith(Names.named(CoreTokenConstants.CTS_MONITOR_DEBUG))
                .toInstance(Debug.getInstance(CoreTokenConstants.CTS_MONITOR_DEBUG));
        bind(Debug.class).annotatedWith(Names.named(DataLayerConstants.DATA_LAYER_DEBUG))
                .toInstance(Debug.getInstance(DataLayerConstants.DATA_LAYER_DEBUG));
        bind(Debug.class).annotatedWith(Names.named("amSMS"))
                .toInstance(Debug.getInstance("amSMS"));

        bind(Debug.class).annotatedWith(Names.named(PolicyMonitor.POLICY_MONITOR_DEBUG))
                .toInstance(Debug.getInstance(PolicyMonitor.POLICY_MONITOR_DEBUG));
        bind(Debug.class).annotatedWith(Names.named(OAuth2Constants.DEBUG_LOG_NAME))
                .toInstance(Debug.getInstance(OAuth2Constants.DEBUG_LOG_NAME));

        bind(CoreTokenConstants.class).in(Singleton.class);
        bind(CoreTokenConfig.class).in(Singleton.class);

        // CTS Connection Management
        bind(String.class).annotatedWith(Names.named(DataLayerConstants.ROOT_DN_SUFFIX)).toProvider(new Provider<String>() {
            public String get() {
                return SMSEntry.getRootSuffix();
            }
        }).in(Singleton.class);
        bind(ConfigurationObserver.class).toProvider(new Provider<ConfigurationObserver>() {
            public ConfigurationObserver get() {
                return ConfigurationObserver.getInstance();
            }
        }).in(Singleton.class);

        // CTS Monitoring
        bind(CTSOperationsMonitoringStore.class).to(CTSMonitoringStoreImpl.class);
        bind(CTSReaperMonitoringStore.class).to(CTSMonitoringStoreImpl.class);
        bind(CTSConnectionMonitoringStore.class).to(CTSMonitoringStoreImpl.class);
        // Enable monitoring of all CTS operations
        bind(ResultHandlerFactory.class).to(MonitoredResultHandlerFactory.class);

        // Policy Monitoring
        bind(PolicyMonitor.class).to(PolicyMonitorImpl.class);

        // SAML2 token repository dependencies
        bind(new TypeLiteral<TokenAdapter<SAMLToken>>(){}).to(SAMLAdapter.class);

        // Session related dependencies
        bind(InternalSessionCache.class).to(InternalSessionStorage.class);
        bind(SessionOperationStrategy.class).to(ServerSessionOperationStrategy.class);
        // TODO: Investigate whether or not this lazy-loading "Config<SessionService>" wrapper is still needed
        bind(new TypeLiteral<Config<SessionService>>() {
        }).toInstance(new Config<SessionService>() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public SessionService get() {
                return InjectorHolder.getInstance(SessionService.class);
            }
        });
        bind(Debug.class).annotatedWith(Names.named(SessionConstants.SESSION_DEBUG))
                .toInstance(Debug.getInstance(SessionConstants.SESSION_DEBUG));
        bind(InternalSessionListener.class).to(InternalSessionEventBroker.class).in(Singleton.class);

        bind(new TypeLiteral<Function<String, String, NeverThrowsException>>() {})
                .annotatedWith(Names.named("tagSwapFunc"))
                .toInstance(new Function<String, String, NeverThrowsException>() {

                    @Override
                    public String apply(String text) {
                        return ServicesDefaultValues.tagSwap(text, true);
                    }

                });

        install(new FactoryModuleBuilder()
                .implement(AMIdentityRepository.class, AMIdentityRepository.class)
                .build(AMIdentityRepositoryFactory.class));

        install(new FactoryModuleBuilder()
                .implement(SMSAuditor.class, SMSAuditor.class)
                .build(ConfigAuditorFactory.class));

        Multibinder.newSetBinder(binder(), SMSAuditFilter.class);

        Multibinder.newSetBinder(binder(), IdRepoCreationListener.class);

        /*
         * Must use a provider to ensure initialisation happens after SystemProperties have been set.
         */
        bind(Key.get(Stats.class, Names.named(SessionConstants.STATS_MASTER_TABLE))).toProvider(new Provider<Stats>() {
            @Override
            public Stats get() {
                return Stats.getInstance(SessionConstants.STATS_MASTER_TABLE);
            }
        });

        bind(SessionCache.class).toInstance(SessionCache.getInstance());
        bind(SessionPollerPool.class).toInstance(SessionPollerPool.getInstance());
        /*
         * Must use a provider to ensure initialisation happens after SystemProperties have been set.
         */
        bind(SessionCookies.class).toProvider(new Provider<SessionCookies>() {
            @Override
            public SessionCookies get() {
                return SessionCookies.getInstance();
            }
        });
        /*
         * Must use a provider to ensure initialisation happens after SystemProperties have been set.
         */
        bind(SessionURL.class).toProvider(new Provider<SessionURL>() {
            @Override
            public SessionURL get() {
                return SessionURL.getInstance();
            }
        });
        bind(SessionServiceURLService.class).toInstance(SessionServiceURLService.getInstance());

        bind(ConsoleConfigHandler.class).to(ConsoleConfigHandlerImpl.class);

        bind(StatelessSSOProvider.class);

        /* Entitlement bindings */
        install(new FactoryModuleBuilder()
                .implement(EntitlementConfiguration.class, EntitlementService.class)
                .build(EntitlementConfigurationFactory.class));

        install(new FactoryModuleBuilder()
                .implement(OrganizationConfigManager.class, OrganizationConfigManager.class)
                .build(OrganizationConfigManagerFactory.class));

        install(new RealmGuiceModule());
    }



    @Provides
    @Inject
    InternalSessionStore getInternalSessionStore(TimeOutSessionFilterStep timeOutSessionFilterStep,
                                                 InternalSessionCacheStep internalSessionCacheStep,
                                                 InternalSessionPersistenceStoreStep internalSessionPersistenceStoreStep) {
        return new InternalSessionStoreChain(Arrays.asList(timeOutSessionFilterStep, internalSessionCacheStep),
                internalSessionPersistenceStoreStep);
    }

    @Provides @Inject @Singleton
    InternalSessionEventBroker getSessionEventBroker(
            final SessionLogging sessionLogging,
            final SessionAuditor sessionAuditor,
            final SessionNotificationSender sessionNotificationSender,
            final SessionNotificationPublisher sessionNotificationPublisher,
            final SessionTimeoutHandlerExecutor sessionTimeoutHandlerExecutor) {

        return new InternalSessionEventBroker(
                sessionLogging, sessionAuditor, sessionNotificationSender, sessionNotificationPublisher,
                sessionTimeoutHandlerExecutor);
    }

    @Provides
    @Named("iPlanetAMAuthService")
    OpenAMSettings getSmsAuthServiceSettings() {
        return new OpenAMSettingsImpl("iPlanetAMAuthService", "1.0");
    }

    /**
     * Returns a secure random number generator suitable for generating shared secrets and other key material. This
     * uses the provider configured by system property
     * {@code com.iplanet.security.SecureRandomFactoryImpl}. By default this is the SHA-1 PRNG algorithm, seeded from
     * the system entropy pool.
     *
     * @return the configured secure random number generator.
     * @see SecureRandomManager
     * @throws Exception if an error occurs trying to obtain the secure random instance.
     */
    @Provides @Singleton
    SecureRandom getSecureRandom() throws Exception {
        return SecureRandomManager.getSecureRandom();
    }

    @Provides @Inject @Named(PolicyMonitorImpl.EXECUTOR_BINDING_NAME)
    ExecutorService getPolicyMonitoringExecutorService(ExecutorServiceFactory esf) {
        return esf.createFixedThreadPool(5);
    }

    @Provides @Inject @Named(CTSMonitoringStoreImpl.EXECUTOR_BINDING_NAME)
    ExecutorService getCTSMonitoringExecutorService(ExecutorServiceFactory esf) {
        return esf.createFixedThreadPool(5);
    }

    @Provides @Inject @Named(SessionMonitoringStore.EXECUTOR_BINDING_NAME)
    ExecutorService getSessionMonitoringExecutorService(ExecutorServiceFactory esf) {
        return esf.createFixedThreadPool(5);
    }

    /**
     * The CTS Worker Pool provides a thread pool specifically for CTS usage.
     *
     * This is only utilised by the CTS asynchronous queue implementation, therefore
     * we can size the pool based on the configuration for that.
     *
     * @param esf Factory for generating an appropriate ExecutorService.
     * @param queueConfiguration Required to resolve how many threads are required.
     * @return A configured ExecutorService, appropriate for the CTS usage.
     *
     * @throws java.lang.RuntimeException If there was an error resolving the configuration.
     */
    @Provides @Inject @Named(CoreTokenConstants.CTS_WORKER_POOL)
    ExecutorService getCTSWorkerExecutorService(ExecutorServiceFactory esf,
            @DataLayer(ConnectionType.CTS_ASYNC) QueueConfiguration queueConfiguration) {
        try {
            int size = queueConfiguration.getProcessors();
            return esf.createFixedThreadPool(size, CoreTokenConstants.CTS_WORKER_POOL);
        } catch (DataLayerException e) {
            throw new RuntimeException(e);
        }
    }

    @Provides @Inject @Named(CoreTokenConstants.CTS_SCHEDULED_SERVICE)
    ScheduledExecutorService getCTSScheduledService(ExecutorServiceFactory esf) {
        return esf.createScheduledService(1);
    }

    @Provides @Inject @Named(CTSWorkerConstants.PAST_EXPIRY_DATE)
    CTSWorkerQuery getPastExpiryDateQuery(
            CTSWorkerPastExpiryDateQuery query,
            @DataLayer(ConnectionType.CTS_WORKER) ConnectionFactory factory) {
        return new CTSWorkerConnection<>(factory, query);
    }

    @Provides @Inject @Named(CTSWorkerConstants.MAX_SESSION_TIME_EXPIRED)
    CTSWorkerQuery getMaxSessionTimeExpiredQuery(
            MaxSessionTimeExpiredQuery query,
            @DataLayer(ConnectionType.CTS_WORKER) ConnectionFactory factory) {
        return new CTSWorkerConnection<>(factory, query);
    }

    @Provides @Inject @Named(CTSWorkerConstants.SESSION_IDLE_TIME_EXPIRED)
    CTSWorkerQuery getSessionIdleTimeExpiredQuery(
            SessionIdleTimeExpiredQuery query,
            @DataLayer(ConnectionType.CTS_WORKER) ConnectionFactory factory) {
        return new CTSWorkerConnection<>(factory, query);
    }

    @Provides @Inject @Named(CTSWorkerConstants.DELETE_ALL_MAX_EXPIRED)
    CTSWorkerTask getDeleteAllMaxExpiredReaperTask(
            @Named(CTSWorkerConstants.PAST_EXPIRY_DATE) CTSWorkerQuery query,
            CTSWorkerDeleteProcess deleteProcess,
            CTSWorkerSelectAllFilter selectAllFilter) {
        return new CTSWorkerTask(query, deleteProcess, selectAllFilter);
    }

    @Provides @Inject @Named(CTSWorkerConstants.MAX_SESSION_TIME_EXPIRED)
    CTSWorkerTask getMaxSessionTimeExpiredTask(
            @Named(CTSWorkerConstants.MAX_SESSION_TIME_EXPIRED) CTSWorkerQuery query,
            MaxSessionTimeExpiredProcess maxSessionTimeExpiredProcess,
            CTSWorkerSelectAllFilter selectAllFilter) {
        return new CTSWorkerTask(query, maxSessionTimeExpiredProcess, selectAllFilter);
    }

    @Provides @Inject @Named(CTSWorkerConstants.SESSION_IDLE_TIME_EXPIRED)
    CTSWorkerTask getSessionIdleTimeExpiredTask(
            @Named(CTSWorkerConstants.SESSION_IDLE_TIME_EXPIRED) CTSWorkerQuery query,
            SessionIdleTimeExpiredProcess sessionIdleTimeExpiredProcess,
            CTSWorkerSelectAllFilter selectAllFilter) {
        return new CTSWorkerTask(query, sessionIdleTimeExpiredProcess, selectAllFilter);
    }

    @Provides @Inject
    CTSWorkerTaskProvider getWorkerTaskProvider(
            @Named(CTSWorkerConstants.DELETE_ALL_MAX_EXPIRED) CTSWorkerTask deleteExpiredTokensTask,
            @Named(CTSWorkerConstants.MAX_SESSION_TIME_EXPIRED) CTSWorkerTask maxSessionTimeExpiredTask,
            @Named(CTSWorkerConstants.SESSION_IDLE_TIME_EXPIRED) CTSWorkerTask sessionIdleTimeExpiredTask) {
        return new CTSWorkerTaskProvider(Arrays.asList(
                deleteExpiredTokensTask,
                maxSessionTimeExpiredTask,
                sessionIdleTimeExpiredTask));
    }

    @Provides @Inject @Named(CoreTokenConstants.CTS_SMS_CONFIGURATION)
    ServerGroupConfiguration getCTSServerConfiguration(SMSConfigurationFactory factory) {
        return factory.getSMSConfiguration();
    }

    @Provides @Singleton
    SAML2TokenRepository getSAML2TokenRepository() {

        final String DEFAULT_REPOSITORY_CLASS =
                "org.forgerock.openam.cts.impl.SAML2CTSPersistentStore";

        final String REPOSITORY_CLASS_PROPERTY =
                "com.sun.identity.saml2.plugins.SAML2RepositoryImpl";

        final String CTS_SAML2_REPOSITORY_CLASS_NAME =
                SystemPropertiesManager.get(REPOSITORY_CLASS_PROPERTY, DEFAULT_REPOSITORY_CLASS);

        SAML2TokenRepository result;
        try {
            // Use Guice to create class to get all of its dependency goodness
            result = InjectorHolder.getInstance(
            Class.forName(CTS_SAML2_REPOSITORY_CLASS_NAME).asSubclass(SAML2TokenRepository.class));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }

        return result;
    }

    @Provides
    @Inject
    @Named(DelegationManager.DELEGATION_SERVICE)
    ServiceConfigManager getServiceConfigManagerForDelegation(final PrivilegedAction<SSOToken> adminTokenAction) {
        try {
            final SSOToken adminToken = AccessController.doPrivileged(adminTokenAction);
            return new ServiceConfigManager(DelegationManager.DELEGATION_SERVICE, adminToken);

        } catch (SMSException smsE) {
            throw new IllegalStateException("Failed to retrieve the service config manager for delegation", smsE);
        } catch (SSOException ssoE) {
            throw new IllegalStateException("Failed to retrieve the service config manager for delegation", ssoE);
        }
    }

    /**
     * Provides instances of the OrganizationConfigManager which requires an Admin
     * token to perform its operations.
     *
     * @param provider Non null.
     * @return Non null.
     */
    @Provides @Inject
    OrganizationConfigManager getOrganizationConfigManager(AdminTokenProvider provider) {
        SSOToken token = AccessController.doPrivileged(AdminTokenAction.getInstance());
        try {
            return new OrganizationConfigManager(token, "/");
        } catch (SMSException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * CTS Jackson Object Mapper.
     * <p>
     * Use a static singleton as per <a href="http://wiki.fasterxml.com/JacksonBestPracticesPerformance">performance
     * best practice.</a>
     */
    @Provides @Named(CoreTokenConstants.OBJECT_MAPPER) @Singleton
    ObjectMapper getCTSObjectMapper() {
        ObjectMapper mapper = new ObjectMapper()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(MapperFeature.CAN_OVERRIDE_ACCESS_MODIFIERS, true);

        /**
         * @see http://stackoverflow.com/questions/7105745/how-to-specify-jackson-to-only-use-fields-preferably-globally
         */
        mapper.setVisibilityChecker(mapper.getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withCreatorVisibility(JsonAutoDetect.Visibility.NONE));
        SimpleModule customModule = new SimpleModule("openam", Version.unknownVersion());
        customModule.addKeyDeserializer(SessionID.class, new SessionIDKeyDeserialiser());
        mapper.registerModule(customModule);
        mapper.addHandler(new CompatibilityProblemHandler());
        return mapper;
    }

    /**
     * This simple {@link KeyDeserializer} implementation allows us to use the {@link SessionID#toString()} value as a
     * map key instead of a whole {@link SessionID} object. During deserialization this class will reconstruct the
     * original SessionID object from the session ID string.
     */
    private static class SessionIDKeyDeserialiser extends KeyDeserializer {

        @Override
        public Object deserializeKey(String key, DeserializationContext ctxt)
                throws IOException, JsonProcessingException {
            return new SessionID(key);
        }
    }

    /**
     * This extension allows us to ignore now unmapped fields within InternalSession and its sub-objects.
     *
     * Each field ignored is now calculated dynamically. See field JavaDoc for detail on why the field
     * is ignored and how it is generated.
     */
    private static class CompatibilityProblemHandler extends DeserializationProblemHandler {

        /**
         * InternalSession#restrictedTokensByRestriction, this legacy field is now calculated based on the
         * restrictedTokensBySid map.
         */
        private static final String RESTRICTED_TOKENS_BY_RESTRICTION = "restrictedTokensByRestriction";

        /**
         * SessionID#isParsed, is no longer persisted because of the dynamic nature of server/site configuration
         * it is now not safe to assume that a persisted SessionID has valid S1/SI values.
         */
        private static final String IS_PARSED = "isParsed";
        /**
         * SessionID#extensionPart, is not stored because it is extracted from the encryptedString.
         */
        private static final String EXTENSION_PART = "extensionPart";

        /**
         * SessionID#extensions, is not stored because it is calculated as part of parsing a SessionID.
         */
        private static final String EXTENSIONS = "extensions";

        /**
         * SessionID#tail, is not stored because it is calculated as part of parsing a SessionID.
         */
        private static final String TAIL = "tail";

        private static final Set<String> skipList = new HashSet<>(
                Arrays.asList(RESTRICTED_TOKENS_BY_RESTRICTION, IS_PARSED,
                        EXTENSION_PART, EXTENSIONS, TAIL));

        @Override
        public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser jp,
                JsonDeserializer<?> deserializer, Object beanOrClass, String propertyName)
                throws IOException, JsonProcessingException {
            if (skipList.contains(propertyName)) {
                ctxt.getParser().skipChildren();
                return true;
            }
            return false;
        }
    }

    // Implementation exists to capture the generic type of the PrivilegedAction.
    private static class AdminTokenType extends TypeLiteral<PrivilegedAction<SSOToken>> {
    }

    // Simple provider implementation to return the static instance of AdminTokenAction.
    private static class AdminTokenProvider implements Provider<PrivilegedAction<SSOToken>> {
        public PrivilegedAction<SSOToken> get() {
            // Provider used over bind(..).getInstance(..) to enforce a lazy loading approach.
            return AdminTokenAction.getInstance();
        }
    }

    @Provides
    @Named("AdminToken")
    SSOToken provideAdminSSOToken(Provider<PrivilegedAction<SSOToken>> adminTokenActionProvider) {
        return AccessController.doPrivileged(adminTokenActionProvider.get());
    }

    // provides our stored servlet context to classes which require it
    @Provides @Named(ServletContextCache.CONTEXT_REFERENCE)
    ServletContext getServletContext() {
        return ServletContextCache.getStoredContext();
    }

    @Provides @Named(SessionConstants.PRIMARY_SERVER_URL) @Inject @Singleton
    String getPrimaryServerURL(SessionServerConfig serverConfig) {
        return serverConfig.getPrimaryServerURL().toString();
    }

    @Provides @Singleton @Inject
    public CTSBlacklist<Session> getCtsSessionBlacklist(CTSPersistentStore cts,
            @Named(CoreTokenConstants.CTS_SCHEDULED_SERVICE) ScheduledExecutorService scheduler,
            ThreadMonitor threadMonitor, WebtopNamingQuery serverConfig, SessionServiceConfig serviceConfig) {
        long purgeDelayMs = serviceConfig.getSessionBlacklistPurgeDelay(TimeUnit.MILLISECONDS);
        long pollIntervalMs = serviceConfig.getSessionBlacklistPollInterval(TimeUnit.MILLISECONDS);
        return new CTSBlacklist<>(cts, TokenType.SESSION_BLACKLIST, scheduler, threadMonitor, serverConfig,
                purgeDelayMs, pollIntervalMs);
    }

    @Provides @Singleton @Inject
    public static Blacklist<Session> getSessionBlacklist(final CTSBlacklist<Session> ctsBlacklist,
            final SessionServiceConfig serviceConfig) {

        if (!serviceConfig.isSessionBlacklistingEnabled()) {
            return new NoOpBlacklist<>();
        }

        final long purgeDelayMs = serviceConfig.getSessionBlacklistPurgeDelay(TimeUnit.MILLISECONDS);
        final int cacheSize = serviceConfig.getSessionBlacklistCacheSize();
        final long pollIntervalMs = serviceConfig.getSessionBlacklistPollInterval(TimeUnit.MILLISECONDS);

        Blacklist<Session> blacklist = ctsBlacklist;
        if (cacheSize > 0) {
            blacklist = new CachingBlacklist<>(blacklist, cacheSize, purgeDelayMs);
        }

        if (pollIntervalMs > 0) {
            blacklist = new BloomFilterBlacklist<>(blacklist, purgeDelayMs);
        }

        return blacklist;
    }

    @Provides @Singleton @Inject
    public SuperUserDelegate getSuperUserDelegate() {
        return StatelessAdminRestriction.createAuthDDelegate();
    }

}