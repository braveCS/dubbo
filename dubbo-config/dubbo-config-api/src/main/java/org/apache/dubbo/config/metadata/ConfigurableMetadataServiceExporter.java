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
package org.apache.dubbo.config.metadata;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.metadata.MetadataService;
import org.apache.dubbo.metadata.MetadataServiceExporter;
import org.apache.dubbo.metadata.MetadataServiceType;
import org.apache.dubbo.rpc.model.ApplicationModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static java.util.Collections.emptyList;
import static java.util.EnumSet.allOf;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;

/**
 * {@link MetadataServiceExporter} implementation based on {@link ConfigManager Dubbo configurations}, the clients
 * should make sure the {@link ApplicationConfig}, {@link RegistryConfig} and {@link ProtocolConfig} are ready before
 * {@link #export()}.
 * <p>
 * Typically, do not worry about their ready status, because they are initialized before
 * any {@link ServiceConfig} exports, or The Dubbo export will be failed.
 * <p>
 * Being aware of it's not a thread-safe implementation.
 *
 * @see MetadataServiceExporter
 * @see ServiceConfig
 * @see ConfigManager
 * @since 2.7.5
 */
public class ConfigurableMetadataServiceExporter extends AbstractMetadataServiceExporter {

    private volatile ServiceConfig<MetadataService> serviceConfig;

    public ConfigurableMetadataServiceExporter() {
        super(DEFAULT_METADATA_STORAGE_TYPE, MAX_PRIORITY, allOf(MetadataServiceType.class));
    }

    @Override
    protected void doExport() throws Exception {

        ServiceConfig<MetadataService> serviceConfig = new ServiceConfig<>();
        serviceConfig.setApplication(getApplicationConfig());
        serviceConfig.setRegistries(getRegistries());
        serviceConfig.setProtocol(generateMetadataProtocol());
        serviceConfig.setInterface(MetadataService.class);
        serviceConfig.setRef(metadataService);
        serviceConfig.setGroup(getApplicationConfig().getName());
        serviceConfig.setVersion(metadataService.version());

        // export
        serviceConfig.export();

        if (logger.isInfoEnabled()) {
            logger.info("The MetadataService exports urls : " + serviceConfig.getExportedUrls());
        }

        this.serviceConfig = serviceConfig;
    }

    @Override
    protected void doUnexport() throws Exception {
        if (serviceConfig != null) {
            serviceConfig.unexport();
        }
    }

    @Override
    public List<URL> getExportedURLs() {
        return serviceConfig != null ? serviceConfig.getExportedUrls() : emptyList();
    }

    public boolean isExported() {
        return serviceConfig != null && serviceConfig.isExported();
    }

    @Override
    public int getPriority() {
        return MAX_PRIORITY;
    }

    private ApplicationConfig getApplicationConfig() {
        return ApplicationModel.getConfigManager().getApplication().get();
    }

    private List<RegistryConfig> getRegistries() {
        return new ArrayList<>(ApplicationModel.getConfigManager().getRegistries());
    }

    private ProtocolConfig generateMetadataProtocol() {
        Collection<ProtocolConfig> protocolConfigs=ApplicationModel.getConfigManager().getProtocols();
        Optional<ProtocolConfig> serlvetProtocol=protocolConfigs.stream().filter(pf->"servlet".equalsIgnoreCase(pf.getServer())).findFirst();
        return serlvetProtocol.orElseGet(()->{
            if(protocolConfigs.isEmpty()){
                ProtocolConfig defaultProtocol = new ProtocolConfig();
                defaultProtocol.setName("hessian");
                defaultProtocol.setPort(-1);
                return defaultProtocol;
            }else{
                return protocolConfigs.stream().findFirst().get();
            }
        });

    }
}
