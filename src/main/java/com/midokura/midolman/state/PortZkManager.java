/*
 * @(#)PortZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.state.PortDirectory.BridgePortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.PortConfig;
import com.midokura.midolman.state.PortDirectory.RouterPortConfig;

/**
 * This class was created to handle multiple ops feature in Zookeeper.
 * 
 * @version        1.6 08 Sept 2011
 * @author         Ryu Ishimoto
 */
public class PortZkManager extends ZkManager {

    /**
     * PortZkManager constructor.
     * 
     * @param zk Zookeeper object.
     * @param basePath  Directory to set as the base.
     */
    public PortZkManager(ZooKeeper zk, String basePath) {
    	super(zk, basePath);
    }
    
    /**
     * Add a new port.
     * @param id  Port UUID.
     * @param port  PortConfig object.
     * @throws IOException  Error serializing.
     * @throws KeeperException   Zookeeper error.
     * @throws InterruptedException  Thread paused too long.
     */
    public void create(UUID id, PortConfig port) 
            throws IOException, KeeperException, InterruptedException {
        if (!(port instanceof BridgePortConfig
                || port instanceof LogicalRouterPortConfig 
                || port instanceof MaterializedRouterPortConfig))
            throw new IllegalArgumentException("Unrecognized port type.");
        
        List<Op> ops = new ArrayList<Op>();

        // Create /ports/<portId>
        ops.add(Op.create(pathManager.getPortPath(id), serialize(port), 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        if (port instanceof RouterPortConfig) {
            // Create /routers/<routerId>/ports/<portId>
            ops.add(Op.create(
                        pathManager.getRouterPortPath(port.device_id, id),
                        null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
            
            // Create /ports/<portId>/routes
           ops.add(Op.create(pathManager.getPortRoutesPath(id), null,
                   Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        }
        this.zk.multi(ops);        
    }
    
    /**
     * Get a PortConfig object.
     * @param id  Router UUID,
     * @return  A PortConfigs
     * @throws KeeperException  Zookeeper exception.
     * @throws InterruptedException  Paused thread interrupted.
     * @throws ClassNotFoundException  Unknown class.
     * @throws IOException  Serialization error.
     */
    public PortConfig get(UUID id) 
            throws KeeperException, InterruptedException,
                IOException, ClassNotFoundException {
        byte[] data = zk.getData(pathManager.getPortPath(id), null, null);
        return deserialize(data, PortConfig.class);
    }

    /**
     * Update a port data.
     * @param id  Port UUID
     * @param port  PortConfig object.
     * @throws IOException  Serialization error.
     * @throws InterruptedException 
     * @throws KeeperException 
     */
    public void update(UUID id, PortConfig port) 
    		throws IOException, KeeperException, InterruptedException {
        // Update any version for now.
        zk.setData(pathManager.getPortPath(id), serialize(port), -1);
    }
    
    /**
     * Get a list of PortConfig objects for a tenant.
     * @param routerId  Router UUID,
     * @return  An array of PortConfigs
     * @throws KeeperException  Zookeeper exception.
     * @throws InterruptedException  Paused thread interrupted.
     * @throws ClassNotFoundException  Unknown class.
     * @throws IOException  Serialization error.
     */
    public HashMap<UUID, PortConfig> list(UUID routerId) 
            throws KeeperException, InterruptedException, 
                IOException, ClassNotFoundException {
        HashMap<UUID, PortConfig> configs = new HashMap<UUID, PortConfig>();
        List<String> portIds = zk.getChildren(
                pathManager.getRouterPortsPath(routerId), null);
        for (String portId : portIds) {
            // For now get each one.
            UUID id = UUID.fromString(portId);
            configs.put(id, get(id));
        }
        return configs;
    }
}
