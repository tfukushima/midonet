/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent;

import java.io.IOException;
import java.util.Properties;
import java.util.UUID;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.command.NodeCommandWatcher;
import com.midokura.midolman.agent.config.HostAgentConfiguration;
import com.midokura.midolman.agent.midolman.MidolmanProvidedConnectionsModule;
import com.midokura.midolman.agent.modules.ConfigurationBasedAgentModule;
import com.midokura.midolman.agent.state.HostDirectory;
import com.midokura.midolman.agent.state.HostZkManager;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.state.ZkConnection;

/**
 * Main entry point for the Node Agent implementation. This class can start a
 * node agent either in <i>hosted</i> mode inside midolman (and taking advantage
 * of midolman connections to ZooKeeper and OVS) or standalone by providing a
 * config file with connection details.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/8/12
 */
public final class NodeAgent {

    private final static Logger log =
        LoggerFactory.getLogger(NodeAgent.class);
    private UUID hostId;

    @Inject
    private NodeInterfaceWatcher interfaceWatcher;

    @Inject
    HostAgentConfiguration configuration;

    @Inject
    HostZkManager zkManager;

    private Thread watcherThread;

    @Inject
    private NodeCommandWatcher cmdExecutor;

    /**
     * Private constructor so we can control the creation process.
     */
    private NodeAgent() {
    }

    public static void main(String[] args) throws IOException, ParseException {
        // log git commit info
        Properties properties = new Properties();
        properties.load(NodeAgent.class.getClassLoader()
                                       .getResourceAsStream("git.properties"));
        log.info("node agent main start -------------------------");
        log.info("branch: {}", properties.get("git.branch"));
        log.info("commit.time: {}", properties.get("git.commit.time"));
        log.info("commit.id: {}", properties.get("git.commit.id"));
        log.info("commit.user: {}", properties.get("git.commit.user.name"));
        log.info("build.time: {}", properties.get("git.build.time"));
        log.info("build.user: {}", properties.get("git.build.user.name"));
        log.info("-------------------------------------");

        Options options = new Options();
        options.addOption("c", "configFile", true, "config file path");
        CommandLineParser parser = new GnuParser();
        CommandLine commandLine = parser.parse(options, args);

        String configFilePath =
            commandLine.getOptionValue('c', "./conf/midolman-agent.conf");

        // bootstrap the agent configuration using data from the config file.
        NodeAgent agent =
            _internalBootstrap(
                new ConfigurationBasedAgentModule(configFilePath));

        // start and wait to be killed. The ZooKeeper connection will close itself
        // in a shutdownHook.
        agent.start();

        try {
            agent.join();
        } catch (InterruptedException e) {
            //
        }
    }

    /**
     * Call this to bootstrap the agent using a config object loaded from a
     * midolman.conf file, a prebuilt ZooKeeper connection and a prebuilt
     * OvsDatabase connection object.
     * <p/>
     * Useful to allow the NodeAgent to be started in process by midolman.
     *
     * @param config          the config object loaded from a midolman.conf file
     * @param zkConnection    the prebuilt ZooKeeper connection
     * @param ovsdbConnection the prebuilt OpenvSwitchDatabaseConnection object
     * @return the configured NodeAgent object.
     */
    public static NodeAgent bootstrapAgent(HierarchicalConfiguration config,
                                           ZkConnection zkConnection,
                                           OpenvSwitchDatabaseConnection ovsdbConnection) {
        return _internalBootstrap(
            new MidolmanProvidedConnectionsModule(config, zkConnection,
                                                  ovsdbConnection));
    }

    private static NodeAgent _internalBootstrap(AbstractModule module) {
        Injector injector = Guice.createInjector(module);

        // Normally this should be done like in the commented line below
        // but since the constructor is private we are forced to do it the other way.
        // NodeAgent agent = injector.getInstance(NodeAgent.class);

        NodeAgent nodeAgent = new NodeAgent();
        injector.injectMembers(nodeAgent);

        return nodeAgent;
    }

    /**
     * Starts the agent watcher thread.
     */
    public void start() {
        hostId = identifyHost();
        cmdExecutor.checkCommands(hostId);
        watcherThread = new Thread(interfaceWatcher);

        log.info("Starting Midolman node agent.");
        watcherThread.start();
        log.info("Midolman node agent started.");
    }

    /**
     * Signals the agent watcher thread to stop and waits for it's completion.
     */
    public void stop() {
        try {
            join();
        } catch (InterruptedException e) {
            //
        }
    }

    private void join() throws InterruptedException {
        // tell the watcher thread to stop
        interfaceWatcher.stop();

        // wait for the thread to finish running
        watcherThread.join();
    }

    private UUID identifyHost() {
        // Try to get the host Id
        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        // TODO set some meaningful data in the metadata
        metadata.setName("Garbage");
        UUID hostId = null;
        // If an exception is thrown it will loop forever
        while (hostId == null) {
            try {
                hostId = HostIdGenerator.getHostId(configuration, zkManager);
                if (hostId != null) {
                    zkManager.makeAlive(hostId, metadata);
                    break;
                }
            } catch (Exception e) {
                log.warn("Cannot create a unique Id.", e);
                // Reset the hostId to null to continue looping
                hostId = null;
            }
            try {
                Thread.sleep(configuration.getWaitTimeForUniqueHostId());
            } catch (InterruptedException e) {
                //TODO throw another exception
                log.debug("Got interrupted. Stopping watcher loop");
                break;
            }
        }
        return hostId;
    }

    public UUID getHostId() {
        return hostId;
    }
}
