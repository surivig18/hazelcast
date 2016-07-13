/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl.application;

import com.hazelcast.core.ICompletableFuture;
import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.core.LifecycleListener;
import com.hazelcast.jet.JetException;
import com.hazelcast.jet.config.ApplicationConfig;
import com.hazelcast.jet.config.JetConfig;
import com.hazelcast.jet.impl.container.ApplicationMaster;
import com.hazelcast.jet.impl.container.applicationmaster.ApplicationMasterResponse;
import com.hazelcast.jet.impl.container.task.nio.DefaultSocketThreadAcceptor;
import com.hazelcast.jet.impl.executor.BalancedExecutor;
import com.hazelcast.jet.impl.executor.Task;
import com.hazelcast.jet.impl.statemachine.applicationmaster.requests.FinalizeApplicationRequest;
import com.hazelcast.jet.impl.util.JetUtil;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.spi.NodeEngine;
import com.hazelcast.spi.RemoteService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.hazelcast.jet.impl.util.JetUtil.uncheckedGet;

public class ApplicationService implements RemoteService {

    public static final int MAX_PORT = 0xFFFF;
    public static final String SERVICE_NAME = "hz:impl:jetService";

    private final Address localJetAddress;
    private final ServerSocketChannel serverSocketChannel;
    private final BalancedExecutor networkExecutor;
    private final BalancedExecutor acceptorExecutor;

    private final BalancedExecutor processingExecutor;

    private final ConcurrentMap<String, ApplicationContext> applicationContexts =
            new ConcurrentHashMap<>(16);

    private final NodeEngine nodeEngine;

    private final ILogger logger;
    private final JetConfig config;

    public ApplicationService(NodeEngine nodeEngine) {
        this.nodeEngine = nodeEngine;
        this.logger = this.getNodeEngine().getLogger(ApplicationService.class);
        this.config = JetUtil.resolveDefaultJetConfig(nodeEngine);

        String host = nodeEngine.getLocalMember().getAddress().getHost();
        this.serverSocketChannel = bindSocketChannel(host);
        try {
            this.localJetAddress = new Address(host, this.serverSocketChannel.socket().getLocalPort());
        } catch (UnknownHostException e) {
            throw JetUtil.reThrow(e);
        }

        this.networkExecutor = new BalancedExecutor(
                "network-reader-writer",
                config.getIoThreadCount(),
                config.getShutdownTimeoutSeconds(),
                nodeEngine
        );

        this.processingExecutor = new BalancedExecutor(
                "application_executor",
                config.getProcessingThreadCount(),
                config.getShutdownTimeoutSeconds(),
                nodeEngine
        );

        this.acceptorExecutor = new BalancedExecutor(
                "network-acceptor",
                1,
                config.getShutdownTimeoutSeconds(),
                nodeEngine
        );

        List<Task> taskList = createAcceptorTask(nodeEngine);
        this.acceptorExecutor.submitTaskContext(taskList);

        addShutdownHook(nodeEngine);
    }

    @Override
    public ApplicationProxy createDistributedObject(String objectName) {
        return new ApplicationProxy(objectName, ApplicationService.this, nodeEngine);
    }

    @Override
    public void destroyDistributedObject(String objectName) {
        ApplicationContext applicationContext = getContext(objectName);
        if (applicationContext == null) {
            throw new JetException("No application with name " + objectName + " found.");
        }
        ApplicationMaster applicationMaster = applicationContext.getApplicationMaster();
        ICompletableFuture<ApplicationMasterResponse> future =
                applicationMaster.handleContainerRequest(new FinalizeApplicationRequest());
        ApplicationMasterResponse response = uncheckedGet(future);
        if (response.isSuccess()) {
            applicationContext.getLocalizationStorage().cleanUp();
            applicationContext.getExecutorContext().getApplicationStateMachineExecutor().shutdown();
            applicationContext.getExecutorContext().getDataContainerStateMachineExecutor().shutdown();
            applicationContext.getExecutorContext().getApplicationMasterStateMachineExecutor().shutdown();
            applicationContexts.remove(objectName);
        } else {
            throw new JetException("Unable to finalize application " + objectName);
        }
    }

    private void addShutdownHook(final NodeEngine nodeEngine) {
        nodeEngine.getHazelcastInstance().getLifecycleService().addLifecycleListener(
                new LifecycleListener() {
                    @Override
                    public void stateChanged(LifecycleEvent event) {
                        if (event.getState() == LifecycleEvent.LifecycleState.SHUTTING_DOWN) {
                            try {
                                serverSocketChannel.close();
                                networkExecutor.shutdown();
                                acceptorExecutor.shutdown();
                                processingExecutor.shutdown();
                            } catch (Exception e) {
                                nodeEngine.getLogger(getClass()).warning(e.getMessage(), e);
                            }
                        }
                    }
                }
        );
    }

    private List<Task> createAcceptorTask(NodeEngine nodeEngine) {
        List<Task> taskList = new ArrayList<Task>();
        taskList.add(
                new DefaultSocketThreadAcceptor(
                        this,
                        nodeEngine,
                        this.serverSocketChannel
                )
        );
        return taskList;
    }

    private ServerSocketChannel bindSocketChannel(String host) {
        try {
            int port = config.getPort();
            while (port <= MAX_PORT) {
                logger.fine("Trying to bind " + host + ":" + port);
                ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
                try {
                    serverSocketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                    serverSocketChannel.bind(new InetSocketAddress(host, port));
                    serverSocketChannel.configureBlocking(false);
                    logger.info("Jet is listening on " + host + ":" + port);
                    return serverSocketChannel;
                } catch (java.nio.channels.AlreadyBoundException | java.net.BindException e) {
                    serverSocketChannel.close();
                    if (!config.getNetworkConfig().isPortAutoIncrement()) {
                        break;
                    } else {
                        port++;
                    }
                }
            }
            throw new RuntimeException("Jet was not able to bind to any port");
        } catch (IOException e) {
            throw JetUtil.reThrow(e);
        }
    }

    public ApplicationContext createApplicationContext(String name,
                                                       ApplicationConfig config) {
        ApplicationContext applicationContext = new ApplicationContext(
                name,
                nodeEngine,
                localJetAddress,
                config,
                ApplicationService.this
        );
        if (applicationContexts.putIfAbsent(name, applicationContext) != null) {
            throw new JetException("ApplicationContext for '" + name + " already exists.");
        }
        return applicationContext;
    }

    public NodeEngine getNodeEngine() {
        return this.nodeEngine;
    }

    public Address getLocalJetAddress() {
        return this.localJetAddress;
    }

    public BalancedExecutor getNetworkExecutor() {
        return this.networkExecutor;
    }

    public BalancedExecutor getProcessingExecutor() {
        return this.processingExecutor;
    }

    public Collection<ApplicationContext> getApplicationContexts() {
        return this.applicationContexts.values();
    }

    public ApplicationContext getContext(String name) {
        return this.applicationContexts.get(name);
    }
}
