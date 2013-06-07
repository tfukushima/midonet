/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import org.midonet.api.ResourceUriBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * DTO for interior bridge port.
 */
public class InteriorBridgePort extends BridgePort implements InteriorPort {

    /**
     * Peer port ID
     */
    private UUID peerId;

    private Short vlanId;

    /**
     * Default constructor
     */
    public InteriorBridgePort() {
        super();
    }

    /**
     * Constructor
     *
     * @param portData
     */
    public InteriorBridgePort(
            org.midonet.cluster.data.ports.LogicalBridgePort
                    portData) {
        super(portData);
        this.peerId = portData.getPeerId();
        this.vlanId = portData.getVlanId();
    }

    @Override
    public UUID getPeerId() {
        return peerId;
    }

    /**
     * @param peerId Peer port ID
     */
    @Override
    public void setPeerId(UUID peerId) {
        this.peerId = peerId;
    }

    public Short getVlanId() {
        return this.vlanId;
    }

    public void setVlanId(Short vlanId) {
        this.vlanId = vlanId;
    }

    /**
     * @return the peer port URI
     */
    @Override
    public URI getPeer() {
        if (peerId != null) {
            return ResourceUriBuilder.getPort(getBaseUri(), peerId);
        } else {
            return null;
        }
    }

    @Override
    public URI getLink() {
        if (id != null) {
            return ResourceUriBuilder.getPortLink(getBaseUri(), id);
        } else {
            return null;
        }
    }

    @Override
    public String getType() {
        return PortType.INTERIOR_BRIDGE;
    }

    @Override
    public boolean isInterior() {
        return true;
    }

    @Override
    public UUID getAttachmentId() {
        return this.peerId;
    }

    @Override
    public void setAttachmentId(UUID id) {
        this.peerId = id;
    }

    @Override
    public org.midonet.cluster.data.Port toData() {
        org.midonet.cluster.data.ports.LogicalBridgePort data =
                new org.midonet.cluster.data.ports.LogicalBridgePort()
                        .setPeerId(this.peerId)
                        .setVlanId(this.vlanId);
        super.setConfig(data);
        return data;
    }

    @Override
    public String toString() {
        return super.toString() + ", peerId=" + peerId + ", vlanId = " + vlanId;
    }
}
