/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.config.bootstrap;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.config.configcenter.DynamicConfigurationFactory;
import org.apache.dubbo.common.config.configcenter.wrapper.CompositeDynamicConfiguration;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.ExtensionDirector;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.lang.ShutdownHookCallbacks;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.AbstractConfig;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConfigCenterConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.DubboShutdownHook;
import org.apache.dubbo.config.MetadataReportConfig;
import org.apache.dubbo.config.MetricsConfig;
import org.apache.dubbo.config.ModuleConfig;
import org.apache.dubbo.config.MonitorConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.ReferenceConfigBase;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.ServiceConfigBase;
import org.apache.dubbo.config.SslConfig;
import org.apache.dubbo.config.bootstrap.builders.ApplicationBuilder;
import org.apache.dubbo.config.bootstrap.builders.ConsumerBuilder;
import org.apache.dubbo.config.bootstrap.builders.ProtocolBuilder;
import org.apache.dubbo.config.bootstrap.builders.ProviderBuilder;
import org.apache.dubbo.config.bootstrap.builders.ReferenceBuilder;
import org.apache.dubbo.config.bootstrap.builders.RegistryBuilder;
import org.apache.dubbo.config.bootstrap.builders.ServiceBuilder;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.config.utils.ReferenceConfigCache;
import org.apache.dubbo.metadata.MetadataService;
import org.apache.dubbo.metadata.MetadataServiceExporter;
import org.apache.dubbo.metadata.WritableMetadataService;
import org.apache.dubbo.metadata.report.MetadataReportFactory;
import org.apache.dubbo.metadata.report.MetadataReportInstance;
import org.apache.dubbo.metadata.report.support.AbstractMetadataReportFactory;
import org.apache.dubbo.registry.client.DefaultServiceInstance;
import org.apache.dubbo.registry.client.ServiceInstance;
import org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils;
import org.apache.dubbo.registry.client.metadata.store.InMemoryWritableMetadataService;
import org.apache.dubbo.registry.client.metadata.store.RemoteMetadataServiceImpl;
import org.apache.dubbo.registry.support.AbstractRegistryFactory;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.ModuleModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.apache.dubbo.common.config.ConfigurationUtils.parseProperties;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.CommonConstants.REGISTRY_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.function.ThrowableAction.execute;
import static org.apache.dubbo.common.utils.StringUtils.isEmpty;
import static org.apache.dubbo.common.utils.StringUtils.isNotEmpty;
import static org.apache.dubbo.metadata.MetadataConstants.DEFAULT_METADATA_PUBLISH_DELAY;
import static org.apache.dubbo.metadata.MetadataConstants.METADATA_PUBLISH_DELAY_KEY;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.calInstanceRevision;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.setMetadataStorageType;
import static org.apache.dubbo.registry.support.AbstractRegistryFactory.getServiceDiscoveries;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;

/**
 * See {@link ApplicationModel} and {@link ExtensionLoader} for why this class is designed to be singleton.
 * <p>
 * The bootstrap class of Dubbo
 * <p>
 * Get singleton instance by calling static method {@link #getInstance()}.
 * Designed as singleton because some classes inside Dubbo, such as ExtensionLoader, are designed only for one instance per process.
 *
 * @since 2.7.5
 */
public final class DubboBootstrap {

    private static final String NAME = DubboBootstrap.class.getSimpleName();

    private static final Logger logger = LoggerFactory.getLogger(DubboBootstrap.class);

    private static volatile DubboBootstrap instance;

    private final AtomicBoolean awaited = new AtomicBoolean(false);

    private volatile BootstrapTakeoverMode takeoverMode = BootstrapTakeoverMode.AUTO;

    private final Lock lock = new ReentrantLock();

    private final Condition condition = lock.newCondition();

    private final Lock destroyLock = new ReentrantLock();

    private final ExecutorService executorService = newSingleThreadExecutor();

    private ExecutorRepository executorRepository;

    private final ApplicationModel applicationModel;

    protected ScheduledFuture<?> asyncMetadataFuture;

    protected final ConfigManager configManager;

    protected final Environment environment;

    protected ReferenceConfigCache cache;

    protected AtomicBoolean initialized = new AtomicBoolean(false);

    protected AtomicBoolean started = new AtomicBoolean(false);

    protected AtomicBoolean startup = new AtomicBoolean(true);

    protected AtomicBoolean destroyed = new AtomicBoolean(false);

    protected AtomicBoolean shutdown = new AtomicBoolean(false);

    protected volatile boolean isCurrentlyInStart = false;

    protected volatile ServiceInstance serviceInstance;

    protected volatile MetadataService metadataService;

    protected volatile MetadataServiceExporter metadataServiceExporter;

    protected List<ServiceConfigBase<?>> exportedServices = new ArrayList<>();

    protected final List<CompletableFuture<?>> asyncExportingFutures = new ArrayList<>();

    protected final List<CompletableFuture<?>> asyncReferringFutures = new ArrayList<>();

    protected boolean asyncExportFinish = true;

    protected volatile boolean asyncReferFinish = true;

    protected static boolean ignoreConfigState;

    private Module currentModule;

    /**
     * See {@link ApplicationModel} and {@link ExtensionLoader} for why DubboBootstrap is designed to be singleton.
     */
    public static DubboBootstrap getInstance() {
        if (instance == null) {
            synchronized (DubboBootstrap.class) {
                if (instance == null) {
                    instance = new DubboBootstrap(ApplicationModel.defaultModel());
                }
            }
        }
        return instance;
    }

    public static DubboBootstrap getInstance(ApplicationModel applicationModel) {
        Map<String, Object> attribute = applicationModel.getAttribute();
        Object cached = attribute.get(NAME);
        if (cached instanceof DubboBootstrap) {
            return (DubboBootstrap) cached;
        } else {
            synchronized (applicationModel) {
                cached = attribute.get(NAME);
                if (cached instanceof DubboBootstrap) {
                    return (DubboBootstrap) cached;
                } else {
                    return new DubboBootstrap(applicationModel);
                }
            }
        }
    }

    public static DubboBootstrap newInstance() {
        return new DubboBootstrap(new FrameworkModel());
    }

    public static DubboBootstrap newInstance(FrameworkModel frameworkModel) {
        return new DubboBootstrap(frameworkModel);
    }

    /**
     * Try reset dubbo status for new instance.
     *
     * @deprecated For testing purposes only
     */
    @Deprecated
    public static void reset() {
        reset(true);
    }

    /**
     * Try reset dubbo status for new instance.
     *
     * @deprecated For testing purposes only
     */
    @Deprecated
    public static void reset(boolean destroy) {
        ConfigUtils.setProperties(null);
        DubboBootstrap.ignoreConfigState = true;
        if (destroy) {
            if (instance != null) {
                instance.destroy();
                instance = null;
            }
            MetadataReportInstance.reset();
            AbstractRegistryFactory.reset();
            destroyAllProtocols();
            FrameworkModel.destroyAll();
        } else {
            instance = null;
        }

        ApplicationModel.reset();
        ShutdownHookCallbacks.INSTANCE.clear();
    }

    private DubboBootstrap(FrameworkModel frameworkModel) {
        this(new ApplicationModel(frameworkModel));
    }

    private DubboBootstrap(ApplicationModel applicationModel) {
        this.applicationModel = applicationModel;
        applicationModel.getAttribute().put(NAME, this);
        configManager = applicationModel.getApplicationConfigManager();
        environment = applicationModel.getApplicationEnvironment();

        executorRepository = getExtensionLoader(ExecutorRepository.class).getDefaultExtension();
        DubboShutdownHook.getDubboShutdownHook().register();
        ShutdownHookCallbacks.INSTANCE.addCallback(DubboBootstrap.this::destroy);
        cache = ReferenceConfigCache.newCache();
    }

    public ApplicationModel getApplicationModel() {
        return applicationModel;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ExtensionDirector getExtensionDirector() {
        return applicationModel.getExtensionDirector();
    }

    public <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        return applicationModel.getExtensionLoader(type);
    }

    public void unRegisterShutdownHook() {
        DubboShutdownHook.getDubboShutdownHook().unregister();
    }

    private boolean isRegisterConsumerInstance() {
        Boolean registerConsumer = getApplication().getRegisterConsumer();
        return Boolean.TRUE.equals(registerConsumer);
    }

    private String getMetadataType() {
        String type = getApplication().getMetadataType();
        if (StringUtils.isEmpty(type)) {
            type = DEFAULT_METADATA_STORAGE_TYPE;
        }
        return type;
    }

    public DubboBootstrap metadataReport(MetadataReportConfig metadataReportConfig) {
        configManager.addMetadataReport(metadataReportConfig);
        return this;
    }

    public DubboBootstrap metadataReports(List<MetadataReportConfig> metadataReportConfigs) {
        if (CollectionUtils.isEmpty(metadataReportConfigs)) {
            return this;
        }

        configManager.addMetadataReports(metadataReportConfigs);
        return this;
    }

    // {@link ApplicationConfig} correlative methods

    /**
     * Set the name of application
     *
     * @param name the name of application
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap application(String name) {
        return application(name, builder -> {
            // DO NOTHING
        });
    }

    /**
     * Set the name of application and it's future build
     *
     * @param name            the name of application
     * @param consumerBuilder {@link ApplicationBuilder}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap application(String name, Consumer<ApplicationBuilder> consumerBuilder) {
        ApplicationBuilder builder = createApplicationBuilder(name);
        consumerBuilder.accept(builder);
        return application(builder.build());
    }

    /**
     * Set the {@link ApplicationConfig}
     *
     * @param applicationConfig the {@link ApplicationConfig}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap application(ApplicationConfig applicationConfig) {
        applicationConfig.setScopeModel(applicationModel);
        configManager.setApplication(applicationConfig);
        return this;
    }


    // {@link RegistryConfig} correlative methods

    /**
     * Add an instance of {@link RegistryConfig}
     *
     * @param consumerBuilder the {@link Consumer} of {@link RegistryBuilder}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap registry(Consumer<RegistryBuilder> consumerBuilder) {
        return registry(null, consumerBuilder);
    }

    /**
     * Add an instance of {@link RegistryConfig} with the specified ID
     *
     * @param id              the {@link RegistryConfig#getId() id}  of {@link RegistryConfig}
     * @param consumerBuilder the {@link Consumer} of {@link RegistryBuilder}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap registry(String id, Consumer<RegistryBuilder> consumerBuilder) {
        RegistryBuilder builder = createRegistryBuilder(id);
        consumerBuilder.accept(builder);
        return registry(builder.build());
    }

    /**
     * Add an instance of {@link RegistryConfig}
     *
     * @param registryConfig an instance of {@link RegistryConfig}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap registry(RegistryConfig registryConfig) {
        registryConfig.setScopeModel(applicationModel);
        configManager.addRegistry(registryConfig);
        return this;
    }

    /**
     * Add an instance of {@link RegistryConfig}
     *
     * @param registryConfigs the multiple instances of {@link RegistryConfig}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap registries(List<RegistryConfig> registryConfigs) {
        if (CollectionUtils.isEmpty(registryConfigs)) {
            return this;
        }
        registryConfigs.forEach(this::registry);
        return this;
    }


    // {@link ProtocolConfig} correlative methods
    public DubboBootstrap protocol(Consumer<ProtocolBuilder> consumerBuilder) {
        return protocol(null, consumerBuilder);
    }

    public DubboBootstrap protocol(String id, Consumer<ProtocolBuilder> consumerBuilder) {
        ProtocolBuilder builder = createProtocolBuilder(id);
        consumerBuilder.accept(builder);
        return protocol(builder.build());
    }

    public DubboBootstrap protocol(ProtocolConfig protocolConfig) {
        return protocols(singletonList(protocolConfig));
    }

    public DubboBootstrap protocols(List<ProtocolConfig> protocolConfigs) {
        if (CollectionUtils.isEmpty(protocolConfigs)) {
            return this;
        }
        for (ProtocolConfig protocolConfig : protocolConfigs) {
            protocolConfig.setScopeModel(applicationModel);
            configManager.addProtocol(protocolConfig);
        }
        return this;
    }

    private Module getCurrentModule() {
        if (currentModule == null) {
            currentModule = new Module(applicationModel.getDefaultModule());
        }
        return currentModule;
    }

    // {@link ServiceConfig} correlative methods
    public <S> DubboBootstrap service(Consumer<ServiceBuilder<S>> consumerBuilder) {
        getCurrentModule().service(consumerBuilder);
        return this;
    }

    public <S> DubboBootstrap service(String id, Consumer<ServiceBuilder<S>> consumerBuilder) {
        getCurrentModule().service(id, consumerBuilder);
        return this;
    }

    public DubboBootstrap service(ServiceConfig<?> serviceConfig) {
        getCurrentModule().service(serviceConfig);
        return this;
    }

    public DubboBootstrap services(List<ServiceConfig> serviceConfigs) {
        getCurrentModule().services(serviceConfigs);
        return this;
    }

    // {@link Reference} correlative methods
    public <S> DubboBootstrap reference(Consumer<ReferenceBuilder<S>> consumerBuilder) {
        getCurrentModule().reference(consumerBuilder);
        return this;
    }

    public <S> DubboBootstrap reference(String id, Consumer<ReferenceBuilder<S>> consumerBuilder) {
        getCurrentModule().reference(id, consumerBuilder);
        return this;
    }

    public DubboBootstrap reference(ReferenceConfig<?> referenceConfig) {
        getCurrentModule().reference(referenceConfig);
        return this;
    }

    public DubboBootstrap references(List<ReferenceConfig> referenceConfigs) {
        getCurrentModule().references(referenceConfigs);
        return this;
    }

    // {@link ProviderConfig} correlative methods
    public DubboBootstrap provider(Consumer<ProviderBuilder> builderConsumer) {
        getCurrentModule().provider(builderConsumer);
        return this;
    }

    public DubboBootstrap provider(String id, Consumer<ProviderBuilder> builderConsumer) {
        getCurrentModule().provider(id, builderConsumer);
        return this;
    }

    public DubboBootstrap provider(ProviderConfig providerConfig) {
        return providers(singletonList(providerConfig));
    }

    public DubboBootstrap providers(List<ProviderConfig> providerConfigs) {
        getCurrentModule().providers(providerConfigs);
        return this;
    }

    // {@link ConsumerConfig} correlative methods
    public DubboBootstrap consumer(Consumer<ConsumerBuilder> builderConsumer) {
        getCurrentModule().consumer(builderConsumer);
        return this;
    }

    public DubboBootstrap consumer(String id, Consumer<ConsumerBuilder> builderConsumer) {
        getCurrentModule().consumer(id, builderConsumer);
        return this;
    }

    public DubboBootstrap consumer(ConsumerConfig consumerConfig) {
        getCurrentModule().consumer(consumerConfig);
        return this;
    }

    public DubboBootstrap consumers(List<ConsumerConfig> consumerConfigs) {
        getCurrentModule().consumers(consumerConfigs);
        return this;
    }
    // module configs end

    // {@link ConfigCenterConfig} correlative methods
    public DubboBootstrap configCenter(ConfigCenterConfig configCenterConfig) {
        configCenterConfig.setScopeModel(applicationModel);
        configManager.addConfigCenter(configCenterConfig);
        return this;
    }

    public DubboBootstrap configCenters(List<ConfigCenterConfig> configCenterConfigs) {
        if (CollectionUtils.isEmpty(configCenterConfigs)) {
            return this;
        }
        for (ConfigCenterConfig configCenterConfig : configCenterConfigs) {
            this.configCenter(configCenterConfig);
        }
        return this;
    }

    public DubboBootstrap monitor(MonitorConfig monitor) {
        monitor.setScopeModel(applicationModel);
        configManager.setMonitor(monitor);
        return this;
    }

    public DubboBootstrap metrics(MetricsConfig metrics) {
        metrics.setScopeModel(applicationModel);
        configManager.setMetrics(metrics);
        return this;
    }

    public DubboBootstrap module(ModuleConfig module) {
        //TODO module config?
        module.setScopeModel(applicationModel);
        configManager.setModule(module);
        return this;
    }

    public DubboBootstrap ssl(SslConfig sslConfig) {
        sslConfig.setScopeModel(applicationModel);
        configManager.setSsl(sslConfig);
        return this;
    }

    public ReferenceConfigCache getCache() {
        return cache;
    }

    /**
     * Initialize
     */
    public synchronized void initialize() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        startConfigCenter();

        loadConfigsFromProps();

        checkGlobalConfigs();

        // @since 2.7.8
        startMetadataCenter();

        initMetadataService();

        if (logger.isInfoEnabled()) {
            logger.info(NAME + " has been initialized!");
        }
    }

    private void checkGlobalConfigs() {
        // check config types (ignore metadata-center)
        List<Class<? extends AbstractConfig>> multipleConfigTypes = Arrays.asList(
            ApplicationConfig.class,
            ProtocolConfig.class,
            RegistryConfig.class,
            MetadataReportConfig.class,
            ProviderConfig.class,
            ConsumerConfig.class,
            MonitorConfig.class,
            ModuleConfig.class,
            MetricsConfig.class,
            SslConfig.class);

        for (Class<? extends AbstractConfig> configType : multipleConfigTypes) {
            checkDefaultAndValidateConfigs(configType);
        }

        // check port conflicts
        Map<Integer, ProtocolConfig> protocolPortMap = new LinkedHashMap<>();
        for (ProtocolConfig protocol : configManager.getProtocols()) {
            Integer port = protocol.getPort();
            if (port == null || port == -1) {
                continue;
            }
            ProtocolConfig prevProtocol = protocolPortMap.get(port);
            if (prevProtocol != null) {
                throw new IllegalStateException("Duplicated port used by protocol configs, port: " + port +
                    ", configs: " + Arrays.asList(prevProtocol, protocol));
            }
            protocolPortMap.put(port, protocol);
        }

        // check reference and service
        for (ReferenceConfigBase<?> reference : configManager.getReferences()) {
            reference.refresh();
        }
        for (ServiceConfigBase service : configManager.getServices()) {
            service.refresh();
        }
    }

    private <T extends AbstractConfig> void checkDefaultAndValidateConfigs(Class<T> configType) {
        try {
            if (shouldAddDefaultConfig(configType)) {
                T config = createConfig(configType);
                config.refresh();
                if (!isNeedValidation(config) || config.isValid()) {
                    configManager.addConfig(config);
                } else {
                    logger.info("Ignore invalid config: " + config);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Add default config failed: " + configType.getSimpleName(), e);
        }

        //validate configs
        Collection<T> configs = configManager.getConfigs(configType);
        for (T config : configs) {
            validateConfig(config);
        }

        // check required default
        if (isRequired(configType) && configs.isEmpty()) {
            throw new IllegalStateException("Default config not found for " + configType.getSimpleName());
        }
    }

    /**
     * The component configuration that does not affect the main process does not need to be verified.
     *
     * @param config
     * @param <T>
     * @return
     */
    private <T extends AbstractConfig> boolean isNeedValidation(T config) {
        if (config instanceof MetadataReportConfig) {
            return false;
        }
        return true;
    }

    private <T extends AbstractConfig> void validateConfig(T config) {
        if (config instanceof ProtocolConfig) {
            ConfigValidationUtils.validateProtocolConfig((ProtocolConfig) config);
        } else if (config instanceof RegistryConfig) {
            ConfigValidationUtils.validateRegistryConfig((RegistryConfig) config);
        } else if (config instanceof MetadataReportConfig) {
            ConfigValidationUtils.validateMetadataConfig((MetadataReportConfig) config);
        } else if (config instanceof ProviderConfig) {
            ConfigValidationUtils.validateProviderConfig((ProviderConfig) config);
        } else if (config instanceof ConsumerConfig) {
            ConfigValidationUtils.validateConsumerConfig((ConsumerConfig) config);
        } else if (config instanceof ApplicationConfig) {
            ConfigValidationUtils.validateApplicationConfig((ApplicationConfig) config);
        } else if (config instanceof MonitorConfig) {
            ConfigValidationUtils.validateMonitorConfig((MonitorConfig) config);
        } else if (config instanceof ModuleConfig) {
            ConfigValidationUtils.validateModuleConfig((ModuleConfig) config);
        } else if (config instanceof MetricsConfig) {
            ConfigValidationUtils.validateMetricsConfig((MetricsConfig) config);
        } else if (config instanceof SslConfig) {
            ConfigValidationUtils.validateSslConfig((SslConfig) config);
        }
    }

    /**
     * The configuration that does not affect the main process is not necessary.
     *
     * @param clazz
     * @param <T>
     * @return
     */
    private <T extends AbstractConfig> boolean isRequired(Class<T> clazz) {
        if (clazz == RegistryConfig.class ||
            clazz == MetadataReportConfig.class ||
            clazz == MonitorConfig.class ||
            clazz == MetricsConfig.class) {
            return false;
        }
        return true;
    }

    private <T extends AbstractConfig> boolean shouldAddDefaultConfig(Class<T> clazz) {
        // Configurations that are not required will not be automatically added to the default configuration
        if (!isRequired(clazz)) {
            return false;
        }

        return configManager.getDefaultConfigs(clazz).isEmpty();
    }

    private void startConfigCenter() {

        // load application config
        loadConfigs(ApplicationConfig.class);

        // load config centers
        loadConfigs(ConfigCenterConfig.class);

        useRegistryAsConfigCenterIfNecessary();

        // check Config Center
        Collection<ConfigCenterConfig> configCenters = configManager.getConfigCenters();
        if (CollectionUtils.isEmpty(configCenters)) {
            ConfigCenterConfig configCenterConfig = new ConfigCenterConfig();
            configCenterConfig.setScopeModel(applicationModel);
            configCenterConfig.refresh();
            ConfigValidationUtils.validateConfigCenterConfig(configCenterConfig);
            if (configCenterConfig.isValid()) {
                configManager.addConfigCenter(configCenterConfig);
                configCenters = configManager.getConfigCenters();
            }
        } else {
            for (ConfigCenterConfig configCenterConfig : configCenters) {
                configCenterConfig.refresh();
                ConfigValidationUtils.validateConfigCenterConfig(configCenterConfig);
            }
        }

        if (CollectionUtils.isNotEmpty(configCenters)) {
            CompositeDynamicConfiguration compositeDynamicConfiguration = new CompositeDynamicConfiguration();
            for (ConfigCenterConfig configCenter : configCenters) {
                // Pass config from ConfigCenterBean to environment
                environment.updateExternalConfigMap(configCenter.getExternalConfiguration());
                environment.updateAppExternalConfigMap(configCenter.getAppExternalConfiguration());

                // Fetch config from remote config center
                compositeDynamicConfiguration.addConfiguration(prepareEnvironment(configCenter));
            }
            environment.setDynamicConfiguration(compositeDynamicConfiguration);
        }

        configManager.refreshAll();
    }

    private void startMetadataCenter() {

        useRegistryAsMetadataCenterIfNecessary();

        ApplicationConfig applicationConfig = getApplication();

        String metadataType = applicationConfig.getMetadataType();
        // FIXME, multiple metadata config support.
        Collection<MetadataReportConfig> metadataReportConfigs = configManager.getMetadataConfigs();
        if (CollectionUtils.isEmpty(metadataReportConfigs)) {
            if (REMOTE_METADATA_STORAGE_TYPE.equals(metadataType)) {
                throw new IllegalStateException("No MetadataConfig found, Metadata Center address is required when 'metadata=remote' is enabled.");
            }
            return;
        }

        MetadataReportInstance metadataReportInstance = applicationModel.getBeanFactory().getBean(MetadataReportInstance.class);
        for (MetadataReportConfig metadataReportConfig : metadataReportConfigs) {
            ConfigValidationUtils.validateMetadataConfig(metadataReportConfig);
            if (!metadataReportConfig.isValid()) {
                logger.info("Ignore invalid metadata-report config: " + metadataReportConfig);
                continue;
            }
            metadataReportInstance.init(metadataReportConfig);
        }
    }

    /**
     * For compatibility purpose, use registry as the default config center when
     * there's no config center specified explicitly and
     * useAsConfigCenter of registryConfig is null or true
     */
    private void useRegistryAsConfigCenterIfNecessary() {
        // we use the loading status of DynamicConfiguration to decide whether ConfigCenter has been initiated.
        if (environment.getDynamicConfiguration().isPresent()) {
            return;
        }

        if (CollectionUtils.isNotEmpty(configManager.getConfigCenters())) {
            return;
        }

        // load registry
        loadConfigs(RegistryConfig.class);

        List<RegistryConfig> defaultRegistries = configManager.getDefaultRegistries();
        if (defaultRegistries.size() > 0) {
            defaultRegistries
                .stream()
                .filter(this::isUsedRegistryAsConfigCenter)
                .map(this::registryAsConfigCenter)
                .forEach(configCenter -> {
                    if (configManager.getConfigCenter(configCenter.getId()).isPresent()) {
                        return;
                    }
                    configManager.addConfigCenter(configCenter);
                    logger.info("use registry as config-center: " + configCenter);

                });
        }
    }

    private boolean isUsedRegistryAsConfigCenter(RegistryConfig registryConfig) {
        return isUsedRegistryAsCenter(registryConfig, registryConfig::getUseAsConfigCenter, "config",
            DynamicConfigurationFactory.class);
    }

    private ConfigCenterConfig registryAsConfigCenter(RegistryConfig registryConfig) {
        String protocol = registryConfig.getProtocol();
        Integer port = registryConfig.getPort();
        URL url = URL.valueOf(registryConfig.getAddress(), registryConfig.getScopeModel());
        String id = "config-center-" + protocol + "-" + url.getHost() + "-" + port;
        ConfigCenterConfig cc = new ConfigCenterConfig();
        cc.setId(id);
        cc.setScopeModel(applicationModel);
        if (cc.getParameters() == null) {
            cc.setParameters(new HashMap<>());
        }
        if (registryConfig.getParameters() != null) {
            cc.getParameters().putAll(registryConfig.getParameters()); // copy the parameters
        }
        cc.getParameters().put(CLIENT_KEY, registryConfig.getClient());
        cc.setProtocol(protocol);
        cc.setPort(port);
        if (StringUtils.isNotEmpty(registryConfig.getGroup())) {
            cc.setGroup(registryConfig.getGroup());
        }
        cc.setAddress(getRegistryCompatibleAddress(registryConfig));
        cc.setNamespace(registryConfig.getGroup());
        cc.setUsername(registryConfig.getUsername());
        cc.setPassword(registryConfig.getPassword());
        if (registryConfig.getTimeout() != null) {
            cc.setTimeout(registryConfig.getTimeout().longValue());
        }
        cc.setHighestPriority(false);
        return cc;
    }

    private void useRegistryAsMetadataCenterIfNecessary() {

        Collection<MetadataReportConfig> metadataConfigs = configManager.getMetadataConfigs();

        if (CollectionUtils.isNotEmpty(metadataConfigs)) {
            return;
        }

        List<RegistryConfig> defaultRegistries = configManager.getDefaultRegistries();
        if (defaultRegistries.size() > 0) {
            defaultRegistries
                .stream()
                .filter(this::isUsedRegistryAsMetadataCenter)
                .map(this::registryAsMetadataCenter)
                .forEach(metadataReportConfig -> {
                    Optional<MetadataReportConfig> configOptional = configManager.getConfig(MetadataReportConfig.class, metadataReportConfig.getId());
                    if (configOptional.isPresent()) {
                        return;
                    }
                    configManager.addMetadataReport(metadataReportConfig);
                    logger.info("use registry as metadata-center: " + metadataReportConfig);
                });
        }
    }

    private boolean isUsedRegistryAsMetadataCenter(RegistryConfig registryConfig) {
        return isUsedRegistryAsCenter(registryConfig, registryConfig::getUseAsMetadataCenter, "metadata",
            MetadataReportFactory.class);
    }

    /**
     * Is used the specified registry as a center infrastructure
     *
     * @param registryConfig       the {@link RegistryConfig}
     * @param usedRegistryAsCenter the configured value on
     * @param centerType           the type name of center
     * @param extensionClass       an extension class of a center infrastructure
     * @return
     * @since 2.7.8
     */
    private boolean isUsedRegistryAsCenter(RegistryConfig registryConfig, Supplier<Boolean> usedRegistryAsCenter,
                                           String centerType,
                                           Class<?> extensionClass) {
        final boolean supported;

        Boolean configuredValue = usedRegistryAsCenter.get();
        if (configuredValue != null) { // If configured, take its value.
            supported = configuredValue.booleanValue();
        } else {                       // Or check the extension existence
            String protocol = registryConfig.getProtocol();
            supported = supportsExtension(extensionClass, protocol);
            if (logger.isInfoEnabled()) {
                logger.info(format("No value is configured in the registry, the %s extension[name : %s] %s as the %s center"
                    , extensionClass.getSimpleName(), protocol, supported ? "supports" : "does not support", centerType));
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info(format("The registry[%s] will be %s as the %s center", registryConfig,
                supported ? "used" : "not used", centerType));
        }
        return supported;
    }

    /**
     * Supports the extension with the specified class and name
     *
     * @param extensionClass the {@link Class} of extension
     * @param name           the name of extension
     * @return if supports, return <code>true</code>, or <code>false</code>
     * @since 2.7.8
     */
    private boolean supportsExtension(Class<?> extensionClass, String name) {
        if (isNotEmpty(name)) {
            ExtensionLoader extensionLoader = getExtensionLoader(extensionClass);
            return extensionLoader.hasExtension(name);
        }
        return false;
    }

    private MetadataReportConfig registryAsMetadataCenter(RegistryConfig registryConfig) {
        String protocol = registryConfig.getProtocol();
        URL url = URL.valueOf(registryConfig.getAddress(), registryConfig.getScopeModel());
        String id = "metadata-center-" + protocol + "-" + url.getHost() + "-" + url.getPort();
        MetadataReportConfig metadataReportConfig = new MetadataReportConfig();
        metadataReportConfig.setId(id);
        metadataReportConfig.setScopeModel(applicationModel);
        if (metadataReportConfig.getParameters() == null) {
            metadataReportConfig.setParameters(new HashMap<>());
        }
        if (registryConfig.getParameters() != null) {
            metadataReportConfig.getParameters().putAll(registryConfig.getParameters()); // copy the parameters
        }
        metadataReportConfig.getParameters().put(CLIENT_KEY, registryConfig.getClient());
        metadataReportConfig.setGroup(registryConfig.getGroup());
        metadataReportConfig.setAddress(getRegistryCompatibleAddress(registryConfig));
        metadataReportConfig.setUsername(registryConfig.getUsername());
        metadataReportConfig.setPassword(registryConfig.getPassword());
        metadataReportConfig.setTimeout(registryConfig.getTimeout());
        return metadataReportConfig;
    }

    private String getRegistryCompatibleAddress(RegistryConfig registryConfig) {
        String registryAddress = registryConfig.getAddress();
        String[] addresses = REGISTRY_SPLIT_PATTERN.split(registryAddress);
        if (ArrayUtils.isEmpty(addresses)) {
            throw new IllegalStateException("Invalid registry address found.");
        }
        String address = addresses[0];
        // since 2.7.8
        // Issue : https://github.com/apache/dubbo/issues/6476
        StringBuilder metadataAddressBuilder = new StringBuilder();
        URL url = URL.valueOf(address, registryConfig.getScopeModel());
        String protocolFromAddress = url.getProtocol();
        if (isEmpty(protocolFromAddress)) {
            // If the protocol from address is missing, is like :
            // "dubbo.registry.address = 127.0.0.1:2181"
            String protocolFromConfig = registryConfig.getProtocol();
            metadataAddressBuilder.append(protocolFromConfig).append("://");
        }
        metadataAddressBuilder.append(address);
        return metadataAddressBuilder.toString();
    }

    private void loadConfigsFromProps() {

        // application config has load before starting config center
        // load dubbo.applications.xxx
        loadConfigs(ApplicationConfig.class);

        // load dubbo.modules.xxx
        loadConfigs(ModuleConfig.class);

        // load dubbo.monitors.xxx
        loadConfigs(MonitorConfig.class);

        // load dubbo.metricses.xxx
        loadConfigs(MetricsConfig.class);

        // load multiple config types:
        // load dubbo.protocols.xxx
        loadConfigs(ProtocolConfig.class);

        // load dubbo.registries.xxx
        loadConfigs(RegistryConfig.class);

        // load dubbo.providers.xxx
        loadConfigs(ProviderConfig.class);

        // load dubbo.consumers.xxx
        loadConfigs(ConsumerConfig.class);

        // load dubbo.metadata-report.xxx
        loadConfigs(MetadataReportConfig.class);

        // config centers has bean loaded before starting config center
        //loadConfigs(ConfigCenterConfig.class);

    }

    private <T extends AbstractConfig> void loadConfigs(Class<T> cls) {
        // load multiple configs with id
        Set<String> configIds = this.getConfigIds(cls);
        configIds.forEach(id -> {
            if (!configManager.getConfig(cls, id).isPresent()) {
                T config = null;
                try {
                    config = createConfig(cls);
                    config.setId(id);
                } catch (Exception e) {
                    throw new IllegalStateException("create config instance failed, id: " + id + ", type:" + cls.getSimpleName());
                }

                String key = null;
                boolean addDefaultNameConfig = false;
                try {
                    // add default name config (same as id), e.g. dubbo.protocols.rest.port=1234
                    key = DUBBO + "." + AbstractConfig.getPluralTagName(cls) + "." + id + ".name";
                    if (ConfigUtils.getProperties().getProperty(key) == null) {
                        ConfigUtils.getProperties().setProperty(key, id);
                        addDefaultNameConfig = true;
                    }

                    config.refresh();
                    configManager.addConfig(config);
                } catch (Exception e) {
                    logger.error("load config failed, id: " + id + ", type:" + cls.getSimpleName(), e);
                    throw new IllegalStateException("load config failed, id: " + id + ", type:" + cls.getSimpleName());
                } finally {
                    if (addDefaultNameConfig && key != null) {
                        ConfigUtils.getProperties().remove(key);
                    }
                }
            }
        });

        // If none config of the type, try load single config
        if (configManager.getConfigs(cls).isEmpty()) {
            // load single config
            List<Map<String, String>> configurationMaps = environment.getConfigurationMaps();
            if (ConfigurationUtils.hasSubProperties(configurationMaps, AbstractConfig.getTypePrefix(cls))) {
                T config = null;
                try {
                    config = createConfig(cls);
                    config.refresh();
                } catch (Exception e) {
                    throw new IllegalStateException("create default config instance failed, type:" + cls.getSimpleName());
                }

                configManager.addConfig(config);
            }
        }

    }

    private <T extends AbstractConfig> T createConfig(Class<T> cls) throws InstantiationException, IllegalAccessException {
        T config = cls.newInstance();
        if (config instanceof ProviderConfig || config instanceof ConsumerConfig || config instanceof ReferenceConfigBase
            || config instanceof ServiceConfigBase) {
            config.setScopeModel(getCurrentModule().moduleModel);
        } else {
            config.setScopeModel(applicationModel);
        }
        return config;
    }

    /**
     * Search props and extract config ids of specify type.
     * <pre>
     * # properties
     * dubbo.registries.registry1.address=xxx
     * dubbo.registries.registry2.port=xxx
     *
     * # extract
     * Set configIds = getConfigIds(RegistryConfig.class)
     *
     * # result
     * configIds: ["registry1", "registry2"]
     * </pre>
     *
     * @param clazz config type
     * @return ids of specify config type
     */
    private Set<String> getConfigIds(Class<? extends AbstractConfig> clazz) {
        String prefix = CommonConstants.DUBBO + "." + AbstractConfig.getPluralTagName(clazz) + ".";
        return ConfigurationUtils.getSubIds(environment.getConfigurationMaps(), prefix);
    }

    /**
     * Initialize {@link MetadataService} from {@link WritableMetadataService}'s extension
     */
    private void initMetadataService() {
//        startMetadataCenter();
        this.metadataService = getExtensionLoader(WritableMetadataService.class).getDefaultExtension();
        // support injection by super type MetadataService
        applicationModel.getBeanFactory().registerBean(this.metadataService);

        //this.metadataServiceExporter = new ConfigurableMetadataServiceExporter(metadataService);
        this.metadataServiceExporter = getExtensionLoader(MetadataServiceExporter.class).getDefaultExtension();
    }

    /**
     * Start the bootstrap
     */
    public synchronized DubboBootstrap start() {
        // avoid re-entry start method multiple times in same thread
        if (isCurrentlyInStart) {
            return this;
        }

        isCurrentlyInStart = true;
        try {
            if (started.compareAndSet(false, true)) {
                startup.set(false);
                shutdown.set(false);
                awaited.set(false);

                initialize();

                if (logger.isInfoEnabled()) {
                    logger.info(NAME + " is starting...");
                }

                doStart();

                if (logger.isInfoEnabled()) {
                    logger.info(NAME + " has started.");
                }
            } else {
                if (logger.isInfoEnabled()) {
                    logger.info(NAME + " is started, export/refer new services.");
                }

                doStart();

                if (logger.isInfoEnabled()) {
                    logger.info(NAME + " finish export/refer new services.");
                }
            }
            return this;
        } finally {
            isCurrentlyInStart = false;
        }
    }

    private void doStart() {
        // 1. export Dubbo Services
        exportServices();

        // If register consumer instance or has exported services
        if (isRegisterConsumerInstance() || hasExportedServices()) {
            // 2. export MetadataService
            exportMetadataService();
            // 3. Register the local ServiceInstance if required
            registerServiceInstance();
        }

        referServices();

        // wait async export / refer finish if needed
        awaitFinish();

        if (isExportBackground() || isReferBackground()) {
            new Thread(() -> {
                while (!asyncExportFinish || !asyncReferFinish) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        logger.error(NAME + " waiting async export / refer occurred and error.", e);
                    }
                }
                onStarted();
            }).start();
        } else {
            onStarted();
        }
    }

    private boolean hasExportedServices() {
        return CollectionUtils.isNotEmpty(configManager.getServices());
    }

    /**
     * Block current thread to be await.
     *
     * @return {@link DubboBootstrap}
     */
    public DubboBootstrap await() {
        // if has been waited, no need to wait again, return immediately
        if (!awaited.get()) {
            if (!executorService.isShutdown()) {
                executeMutually(() -> {
                    while (!awaited.get()) {
                        if (logger.isInfoEnabled()) {
                            logger.info(NAME + " awaiting ...");
                        }
                        try {
                            condition.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
            }
        }
        return this;
    }

    private void waitAsyncExportIfNeeded() {
        if (asyncExportingFutures.size() > 0) {
            asyncExportFinish = false;
            if (isExportBackground()) {
                new Thread(this::waitExportFinish).start();
            } else {
                waitExportFinish();
            }
        }
    }

    private boolean isExportBackground() {
        List<Boolean> list = configManager.getProviders()
            .stream()
            .map(ProviderConfig::getExportBackground)
            .filter(k -> k != null && k)
            .collect(Collectors.toList());

        return CollectionUtils.isNotEmpty(list);
    }

    private void waitExportFinish() {
        try {
            logger.info(NAME + " waiting services exporting asynchronously...");
            CompletableFuture<?> future = CompletableFuture.allOf(asyncExportingFutures.toArray(new CompletableFuture[0]));
            future.get();
        } catch (Exception e) {
            logger.warn(NAME + " asynchronous export occurred an exception.");
        } finally {
            executorRepository.shutdownServiceExportExecutor();
            logger.info(NAME + " asynchronous export finished.");
            asyncExportFinish = true;
        }
    }

    private void waitAsyncReferIfNeeded() {
        if (asyncReferringFutures.size() > 0) {
            asyncReferFinish = false;
            if (isReferBackground()) {
                new Thread(this::waitReferFinish).start();
            } else {
                waitReferFinish();
            }
        }
    }

    private boolean isReferBackground() {
        List<Boolean> list = configManager.getConsumers()
            .stream()
            .map(ConsumerConfig::getReferBackground)
            .filter(k -> k != null && k)
            .collect(Collectors.toList());

        return CollectionUtils.isNotEmpty(list);
    }

    private void waitReferFinish() {
        try {
            logger.info(NAME + " waiting services referring asynchronously...");
            CompletableFuture<?> future = CompletableFuture.allOf(asyncReferringFutures.toArray(new CompletableFuture[0]));
            future.get();
        } catch (Exception e) {
            logger.warn(NAME + " asynchronous refer occurred an exception.");
        } finally {
            executorRepository.shutdownServiceExportExecutor();
            logger.info(NAME + " asynchronous refer finished.");
            asyncReferFinish = true;
        }
    }

    private void awaitFinish() {
        waitAsyncExportIfNeeded();
        waitAsyncReferIfNeeded();
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    public boolean isStarted() {
        return started.get();
    }

    public boolean isStartup() {
        return startup.get();
    }

    public boolean isShutdown() {
        return shutdown.get();
    }


    public DubboBootstrap stop() throws IllegalStateException {
        destroy();
        return this;
    }
    /* serve for builder apis, begin */

    private ApplicationBuilder createApplicationBuilder(String name) {
        return new ApplicationBuilder().name(name);
    }

    private RegistryBuilder createRegistryBuilder(String id) {
        return new RegistryBuilder().id(id);
    }

    private ProtocolBuilder createProtocolBuilder(String id) {
        return new ProtocolBuilder().id(id);
    }

    private ServiceBuilder createServiceBuilder(String id) {
        return new ServiceBuilder().id(id);
    }

    private ReferenceBuilder createReferenceBuilder(String id) {
        return new ReferenceBuilder().id(id);
    }

    private ProviderBuilder createProviderBuilder(String id) {
        return new ProviderBuilder().id(id);
    }

    private ConsumerBuilder createConsumerBuilder(String id) {
        return new ConsumerBuilder().id(id);
    }
    /* serve for builder apis, end */

    private DynamicConfiguration prepareEnvironment(ConfigCenterConfig configCenter) {
        if (configCenter.isValid()) {
            if (!configCenter.checkOrUpdateInitialized(true)) {
                return null;
            }

            DynamicConfiguration dynamicConfiguration = null;
            try {
                dynamicConfiguration = getDynamicConfiguration(configCenter.toUrl());
            } catch (Exception e) {
                if (!configCenter.isCheck()) {
                    logger.warn("The configuration center failed to initialize", e);
                    configCenter.checkOrUpdateInitialized(false);
                    return null;
                } else {
                    throw new IllegalStateException(e);
                }
            }

            String configContent = dynamicConfiguration.getProperties(configCenter.getConfigFile(), configCenter.getGroup());

            String appGroup = getApplication().getName();
            String appConfigContent = null;
            if (isNotEmpty(appGroup)) {
                appConfigContent = dynamicConfiguration.getProperties
                    (isNotEmpty(configCenter.getAppConfigFile()) ? configCenter.getAppConfigFile() : configCenter.getConfigFile(),
                        appGroup
                    );
            }
            try {
                environment.updateExternalConfigMap(parseProperties(configContent));
                environment.updateAppExternalConfigMap(parseProperties(appConfigContent));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to parse configurations from Config Center.", e);
            }
            return dynamicConfiguration;
        }
        return null;
    }

    /**
     * Get the instance of {@link DynamicConfiguration} by the specified connection {@link URL} of config-center
     *
     * @param connectionURL of config-center
     * @return non-null
     * @since 2.7.5
     */
    private DynamicConfiguration getDynamicConfiguration(URL connectionURL) {
        String protocol = connectionURL.getProtocol();

        DynamicConfigurationFactory factory = ConfigurationUtils.getDynamicConfigurationFactory(applicationModel, protocol);
        return factory.getDynamicConfiguration(connectionURL);
    }

    /**
     * export {@link MetadataService}
     */
    private void exportMetadataService() {
        metadataServiceExporter.export();
    }

    private void unexportMetadataService() {
        if (metadataServiceExporter != null && metadataServiceExporter.isExported()) {
            try {
                metadataServiceExporter.unexport();
            } catch (Exception ignored) {
                // ignored
            }
        }
    }

    private void exportServices() {
        for (ServiceConfigBase sc : configManager.getServices()) {
            // TODO, compatible with ServiceConfig.export()
            ServiceConfig<?> serviceConfig = (ServiceConfig<?>) sc;
            serviceConfig.setBootstrap(this);
            if (!serviceConfig.isRefreshed()) {
                serviceConfig.refresh();
            }
            if (sc.isExported()) {
                continue;
            }
            if (sc.shouldExportAsync()) {
                ExecutorService executor = executorRepository.getServiceExportExecutor();
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        if (!sc.isExported()) {
                            sc.export();
                            exportedServices.add(sc);
                        }
                    } catch (Throwable t) {
                        logger.error("export async catch error : " + t.getMessage(), t);
                    }
                }, executor);

                asyncExportingFutures.add(future);
            } else {
                if (!sc.isExported()) {
                    sc.export();
                    exportedServices.add(sc);
                }
            }
        }
    }

    private void unexportServices() {
        exportedServices.forEach(sc -> {
            try {
                configManager.removeConfig(sc);
                sc.unexport();
            } catch (Exception ignored) {
                // ignored
            }
        });

        asyncExportingFutures.forEach(future -> {
            if (!future.isDone()) {
                future.cancel(true);
            }
        });
        asyncExportingFutures.clear();
        exportedServices.clear();
    }

    private void referServices() {
        configManager.getReferences().forEach(rc -> {
            try {
                // TODO, compatible with  ReferenceConfig.refer()
                ReferenceConfig<?> referenceConfig = (ReferenceConfig<?>) rc;
                referenceConfig.setBootstrap(this);
                if (!referenceConfig.isRefreshed()) {
                    referenceConfig.refresh();
                }

                if (rc.shouldInit()) {
                    if (rc.shouldReferAsync()) {
                        ExecutorService executor = executorRepository.getServiceReferExecutor();
                        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                            try {
                                cache.get(rc);
                            } catch (Throwable t) {
                                logger.error("refer async catch error : " + t.getMessage(), t);
                            }
                        }, executor);

                        asyncReferringFutures.add(future);
                    } else {
                        cache.get(rc);
                    }
                }
            } catch (Throwable t) {
                logger.error("refer catch error", t);
                cache.destroy(rc);
            }
        });
    }

    private void unreferServices() {
        try {
            asyncReferringFutures.forEach(future -> {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            });
            asyncReferringFutures.clear();
            cache.destroyAll();
        } catch (Exception ignored) {
        }
    }

    protected void registerServiceInstance() {
        if (this.serviceInstance != null) {
            return;
        }

        ApplicationConfig application = getApplication();
        String serviceName = application.getName();
        ServiceInstance serviceInstance = createServiceInstance(serviceName);
        boolean registered = true;
        try {
            ServiceInstanceMetadataUtils.registerMetadataAndInstance(serviceInstance);
        } catch (Exception e) {
            registered = false;
            logger.error("Register instance error", e);
        }
        if (registered) {
            // scheduled task for updating Metadata and ServiceInstance
            asyncMetadataFuture = executorRepository.nextScheduledExecutor().scheduleAtFixedRate(() -> {
                InMemoryWritableMetadataService localMetadataService = (InMemoryWritableMetadataService) WritableMetadataService.getDefaultExtension(applicationModel);
                localMetadataService.blockUntilUpdated();
                try {
                    ServiceInstanceMetadataUtils.refreshMetadataAndInstance(serviceInstance);
                } catch (Exception e) {
                    logger.error("Refresh instance and metadata error", e);
                } finally {
                    localMetadataService.releaseBlock();
                }
            }, 0, ConfigurationUtils.get(applicationModel, METADATA_PUBLISH_DELAY_KEY, DEFAULT_METADATA_PUBLISH_DELAY), TimeUnit.MILLISECONDS);
        }
    }

    private void doRegisterServiceInstance(ServiceInstance serviceInstance) {
        // register instance only when at least one service is exported.
        if (serviceInstance.getPort() > 0) {
            publishMetadataToRemote(serviceInstance);
            logger.info("Start registering instance address to registry.");
            getServiceDiscoveries().forEach(serviceDiscovery ->
            {
                ServiceInstance serviceInstanceForRegistry = new DefaultServiceInstance((DefaultServiceInstance) serviceInstance);
                calInstanceRevision(serviceDiscovery, serviceInstanceForRegistry);
                if (logger.isDebugEnabled()) {
                    logger.info("Start registering instance address to registry" + serviceDiscovery.getUrl() + ", instance " + serviceInstanceForRegistry);
                }
                // register metadata
                serviceDiscovery.register(serviceInstanceForRegistry);
            });
        }
    }

    private void publishMetadataToRemote(ServiceInstance serviceInstance) {
//        InMemoryWritableMetadataService localMetadataService = (InMemoryWritableMetadataService)WritableMetadataService.getDefaultExtension();
//        localMetadataService.blockUntilUpdated();
        if (logger.isInfoEnabled()) {
            logger.info("Start publishing metadata to remote center, this only makes sense for applications enabled remote metadata center.");
        }
        RemoteMetadataServiceImpl remoteMetadataService = applicationModel.getBeanFactory().getBean(RemoteMetadataServiceImpl.class);
        remoteMetadataService.publishMetadata(serviceInstance.getServiceName());
    }


    private void unregisterServiceInstance() {
        if (serviceInstance != null) {
            getServiceDiscoveries().forEach(serviceDiscovery -> {
                try {
                    serviceDiscovery.unregister(serviceInstance);
                } catch (Exception ignored) {
                    // ignored
                }
            });
        }
    }

    private ServiceInstance createServiceInstance(String serviceName) {
        this.serviceInstance = new DefaultServiceInstance(serviceName, applicationModel);
        setMetadataStorageType(serviceInstance, getMetadataType());
        ServiceInstanceMetadataUtils.customizeInstance(this.serviceInstance);
        return this.serviceInstance;
    }

    public void destroy() {
        if (destroyLock.tryLock()
            && shutdown.compareAndSet(false, true)) {
            try {
                if (destroyed.compareAndSet(false, true)) {
                    if (started.compareAndSet(true, false)) {
                        unregisterServiceInstance();
                        unexportMetadataService();
                        unexportServices();
                        unreferServices();
                        if (asyncMetadataFuture != null) {
                            asyncMetadataFuture.cancel(true);
                        }
                    }

                    destroyRegistries();
                    destroyProtocols(applicationModel.getFrameworkModel());
                    destroyServiceDiscoveries();
                    destroyExecutorRepository();
                    destroyMetadataReports();

                    // check config
                    checkConfigState();

                    clear();
                    shutdown();
                    release();

                    onStop();
                }

                destroyDynamicConfigurations();
                ShutdownHookCallbacks.INSTANCE.clear();
            } catch (Throwable ignored) {
                // ignored
                logger.warn(ignored.getMessage(), ignored);
            } finally {
                initialized.set(false);
                startup.set(false);
                destroyLock.unlock();
            }

            applicationModel.destroy();
        }
    }

    private void onStarted() {
        startup.set(true);
        if (logger.isInfoEnabled()) {
            logger.info(NAME + " is ready.");
        }
        ExtensionLoader<DubboBootstrapStartStopListener> exts = getExtensionLoader(DubboBootstrapStartStopListener.class);
        exts.getSupportedExtensionInstances().forEach(ext -> ext.onStart(this));
    }

    private void onStop() {
        ExtensionLoader<DubboBootstrapStartStopListener> exts = getExtensionLoader(DubboBootstrapStartStopListener.class);
        exts.getSupportedExtensionInstances().forEach(ext -> ext.onStop(this));
    }

    private void checkConfigState() {
        // config manager should not be cleared at this moment
        if (!ignoreConfigState && !configManager.getApplication().isPresent()) {
            logger.error("Dubbo config was cleaned prematurely");
            throw new IllegalStateException("Dubbo config was cleaned prematurely");
        }
    }

    private void destroyExecutorRepository() {
        getExtensionLoader(ExecutorRepository.class).getDefaultExtension().destroyAll();
    }

    private void destroyRegistries() {
        AbstractRegistryFactory.destroyAll();
    }

    /**
     * Destroy all the protocols.
     */
    private static void destroyProtocols(FrameworkModel frameworkModel) {
        //TODO destroy protocol in framework scope
        ExtensionLoader<Protocol> loader = frameworkModel.getExtensionLoader(Protocol.class);
        for (String protocolName : loader.getLoadedExtensions()) {
            try {
                Protocol protocol = loader.getLoadedExtension(protocolName);
                if (protocol != null) {
                    protocol.destroy();
                }
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }
    }

    private static void destroyAllProtocols() {
        for (FrameworkModel frameworkModel : FrameworkModel.getAllInstances()) {
            destroyProtocols(frameworkModel);
        }
    }

    private void destroyServiceDiscoveries() {
        getServiceDiscoveries().forEach(serviceDiscovery -> {
            try {
                execute(serviceDiscovery::destroy);
            } catch (Throwable ignored) {
                logger.warn(ignored.getMessage(), ignored);
            }
        });
        if (logger.isDebugEnabled()) {
            logger.debug(NAME + "'s all ServiceDiscoveries have been destroyed.");
        }
    }

    private void destroyMetadataReports() {
        AbstractMetadataReportFactory.destroy();
        MetadataReportInstance.reset();
    }

    private void destroyDynamicConfigurations() {
        // DynamicConfiguration may be cached somewhere, and maybe used during destroy
        // destroy them may cause some troubles, so just clear instances cache
        // ExtensionLoader.resetExtensionLoader(DynamicConfigurationFactory.class);
    }

    private void clear() {
        clearConfigs();
        clearApplicationModel();
    }

    private void clearApplicationModel() {

    }

    private void clearConfigs() {
        configManager.destroy();
        if (logger.isDebugEnabled()) {
            logger.debug(NAME + "'s configs have been clear.");
        }
    }

    private void release() {
        executeMutually(() -> {
            if (awaited.compareAndSet(false, true)) {
                if (logger.isInfoEnabled()) {
                    logger.info(NAME + " is about to shutdown...");
                }
                condition.signalAll();
            }
        });
    }

    private void shutdown() {
        if (!executorService.isShutdown()) {
            // Shutdown executorService
            try {
                executorService.shutdown();
            } catch (Throwable ignored) {
                // ignored
                logger.warn(ignored.getMessage(), ignored);
            }
        }
    }

    private void executeMutually(Runnable runnable) {
        try {
            lock.lock();
            runnable.run();
        } finally {
            lock.unlock();
        }
    }

    public ApplicationConfig getApplication() {
        return configManager.getApplicationOrElseThrow();
    }

    public void setTakeoverMode(BootstrapTakeoverMode takeoverMode) {
        this.started.set(false);
        this.takeoverMode = takeoverMode;
    }

    public BootstrapTakeoverMode getTakeoverMode() {
        return takeoverMode;
    }

    public Module addModule(ModuleModel moduleModel) {
        applicationModel.addModule(moduleModel);
        currentModule = new Module(moduleModel);
        return currentModule;
    }

    public Module addModule() {
        return this.addModule(new ModuleModel(applicationModel));
    }

    public DubboBootstrap endModule() {
        currentModule = new Module(applicationModel.getDefaultModule());
        return this;
    }

    public class Module {
        private ModuleModel moduleModel;
        private DubboBootstrap bootstrap;

        public Module(ModuleModel moduleModel) {
            this.moduleModel = moduleModel;
            this.bootstrap = DubboBootstrap.this;
        }

        public DubboBootstrap endModule() {
            return this.bootstrap.endModule();
        }

        // {@link ServiceConfig} correlative methods
        public <S> Module service(Consumer<ServiceBuilder<S>> consumerBuilder) {
            return service(null, consumerBuilder);
        }

        public <S> Module service(String id, Consumer<ServiceBuilder<S>> consumerBuilder) {
            ServiceBuilder builder = createServiceBuilder(id);
            consumerBuilder.accept(builder);
            return service(builder.build());
        }

        public Module service(ServiceConfig<?> serviceConfig) {
            serviceConfig.setBootstrap(this.bootstrap);
            serviceConfig.setScopeModel(moduleModel);
            configManager.addService(serviceConfig);
            return this;
        }

        public Module services(List<ServiceConfig> serviceConfigs) {
            if (CollectionUtils.isEmpty(serviceConfigs)) {
                return this;
            }
            serviceConfigs.forEach(configManager::addService);
            return this;
        }

        // {@link Reference} correlative methods
        public <S> Module reference(Consumer<ReferenceBuilder<S>> consumerBuilder) {
            return reference(null, consumerBuilder);
        }

        public <S> Module reference(String id, Consumer<ReferenceBuilder<S>> consumerBuilder) {
            ReferenceBuilder builder = createReferenceBuilder(id);
            consumerBuilder.accept(builder);
            return reference(builder.build());
        }

        public Module reference(ReferenceConfig<?> referenceConfig) {
            referenceConfig.setBootstrap(this.bootstrap);
            referenceConfig.setScopeModel(moduleModel);
            configManager.addReference(referenceConfig);
            return this;
        }

        public Module references(List<ReferenceConfig> referenceConfigs) {
            if (CollectionUtils.isEmpty(referenceConfigs)) {
                return this;
            }

            referenceConfigs.forEach(configManager::addReference);
            return this;
        }

        // {@link ProviderConfig} correlative methods
        public Module provider(Consumer<ProviderBuilder> builderConsumer) {
            return provider(null, builderConsumer);
        }

        public Module provider(String id, Consumer<ProviderBuilder> builderConsumer) {
            ProviderBuilder builder = createProviderBuilder(id);
            builderConsumer.accept(builder);
            return provider(builder.build());
        }

        public Module provider(ProviderConfig providerConfig) {
            return providers(singletonList(providerConfig));
        }

        public Module providers(List<ProviderConfig> providerConfigs) {
            if (CollectionUtils.isEmpty(providerConfigs)) {
                return this;
            }
            for (ProviderConfig providerConfig : providerConfigs) {
                providerConfig.setScopeModel(moduleModel);
                configManager.addProvider(providerConfig);
            }
            return this;
        }

        // {@link ConsumerConfig} correlative methods
        public Module consumer(Consumer<ConsumerBuilder> builderConsumer) {
            return consumer(null, builderConsumer);
        }

        public Module consumer(String id, Consumer<ConsumerBuilder> builderConsumer) {
            ConsumerBuilder builder = createConsumerBuilder(id);
            builderConsumer.accept(builder);
            return consumer(builder.build());
        }

        public Module consumer(ConsumerConfig consumerConfig) {
            return consumers(singletonList(consumerConfig));
        }

        public Module consumers(List<ConsumerConfig> consumerConfigs) {
            if (CollectionUtils.isEmpty(consumerConfigs)) {
                return this;
            }
            for (ConsumerConfig consumerConfig : consumerConfigs) {
                consumerConfig.setScopeModel(moduleModel);
                configManager.addConsumer(consumerConfig);
            }
            return this;
        }
    }
}
