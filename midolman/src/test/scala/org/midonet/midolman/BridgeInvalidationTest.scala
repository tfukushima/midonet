/*
* Copyright 2014 Midokura Europe SARL
*/
package org.midonet.midolman

import java.util.UUID
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.concurrent.Eventually._
import org.scalatest._

import org.midonet.cluster.data.{Bridge => ClusterBridge, Router => ClusterRouter}
import org.midonet.cluster.data.ports.{BridgePort, RouterPort}
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.services.{HostIdProviderService, MessageAccumulator}
import org.midonet.midolman.simulation.{Bridge, CustomMatchers}
import org.midonet.midolman.simulation.Coordinator.{TemporaryDropAction, ToPortSetAction, ToPortAction}
import org.midonet.midolman.topology.{FlowTagger, VirtualTopologyActor}
import org.midonet.packets.{IPv4Subnet, MAC}
import org.apache.commons.configuration.HierarchicalConfiguration
import org.midonet.midolman.topology.BridgeManager.CheckExpiredMacPorts


@RunWith(classOf[JUnitRunner])
class BridgeInvalidationTest extends FeatureSpec
        with VirtualConfigurationBuilders
        with Matchers
        with GivenWhenThen
        with CustomMatchers
        with MockMidolmanActors
        with MidolmanServices
        with VirtualTopologyHelper
        with OneInstancePerTest {

    val leftMac = "02:02:01:10:10:aa"
    val leftIp = "192.168.1.1"

    val rightMac = "02:02:02:20:20:aa"
    val rightIp = "192.168.1.2"

    val routerMac = "02:02:03:03:04:04"
    val routerIp = new IPv4Subnet("192.168.1.0", 24)

    var leftPort: BridgePort = null
    var rightPort: BridgePort = null
    var otherPort: BridgePort = null
    var interiorPort: BridgePort = null
    var routerPort: RouterPort = null

    var clusterBridge: ClusterBridge = null
    var clusterRouter: ClusterRouter = null

    val macPortExpiration = 1000

    private def addAndMaterializeBridgePort(br: ClusterBridge): BridgePort = {
        val port = newBridgePort(br)
        port should not be null
        clusterDataClient().portsSetLocalAndActive(port.getId, true)
        port
    }

    private def buildTopology() {
        val host = newHost("myself",
            injector.getInstance(classOf[HostIdProviderService]).getHostId)
        host should not be null

        clusterBridge = newBridge("bridge")
        clusterBridge should not be null
        clusterRouter = newRouter("router")
        clusterRouter should not be null

        leftPort = addAndMaterializeBridgePort(clusterBridge)
        rightPort = addAndMaterializeBridgePort(clusterBridge)
        otherPort = addAndMaterializeBridgePort(clusterBridge)

        interiorPort = newBridgePort(clusterBridge)
        routerPort = newRouterPort(clusterRouter, MAC.fromString(routerMac), routerIp)

        preloadTopology(leftPort, rightPort, otherPort, interiorPort,
                        routerPort, clusterRouter, clusterBridge)
    }

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        super.fillConfig(config)
        config.setProperty("bridge.mac_port_mapping_expire_millis", macPortExpiration)
        config
    }

    protected override def registerActors = {
        List(VirtualTopologyActor -> (() => new VirtualTopologyActor()
                                            with MessageAccumulator))
    }

    override def beforeTest() {
        buildTopology()
    }

    def leftToRightFrame = {
        import org.midonet.packets.util.PacketBuilder._

        { eth addr leftMac -> rightMac } <<
            { ip4 addr leftIp --> rightIp } <<
                { udp ports 53 ---> 53 } <<
                    payload(UUID.randomUUID().toString)
    }

    def leftToRouterFrame = {
        import org.midonet.packets.util.PacketBuilder._

        { eth addr leftMac -> routerMac } <<
            { ip4 addr leftIp --> routerIp.toUnicastString } <<
                { udp ports 53 ---> 53 } <<
                    payload(UUID.randomUUID().toString)
    }

    def leftToRouterArpFrame = {
        import org.midonet.packets.util.PacketBuilder._

        { eth addr leftMac -> eth_bcast } <<
            { arp.req mac leftMac -> eth_bcast ip leftIp --> routerIp.toUnicastString }
    }

    def leftPortUnicastInvalidation =
        FlowTagger.invalidateFlowsByPort(clusterBridge.getId,
            MAC.fromString(leftMac), 0.toShort, leftPort.getId)

    def rightPortUnicastInvalidation =
        FlowTagger.invalidateFlowsByPort(clusterBridge.getId,
            MAC.fromString(rightMac), 0.toShort, rightPort.getId)

    def otherPortUnicastInvalidation =
        FlowTagger.invalidateFlowsByPort(clusterBridge.getId,
            MAC.fromString(rightMac), 0.toShort, otherPort.getId)

    def leftMacFloodInvalidation =
        FlowTagger.invalidateFloodedFlowsByDstMac(clusterBridge.getId,
            MAC.fromString(leftMac), ClusterBridge.UNTAGGED_VLAN_ID)

    def rightMacFloodInvalidation =
        FlowTagger.invalidateFloodedFlowsByDstMac(clusterBridge.getId,
            MAC.fromString(rightMac), ClusterBridge.UNTAGGED_VLAN_ID)

    def routerMacFloodInvalidation =
        FlowTagger.invalidateFloodedFlowsByDstMac(clusterBridge.getId,
            MAC.fromString(routerMac), ClusterBridge.UNTAGGED_VLAN_ID)

    feature("Bridge invalidates flows when a MAC is learned") {
        scenario("flooded flows are properly tagged") {
            When("a packet is sent across the bridge between two VMs")
            val bridge: Bridge = fetchDevice(clusterBridge)
            val (pktContext, action) = simulateDevice(bridge, leftToRightFrame, leftPort.getId)

            Then("the bridge floods the packet to all exterior ports")
            action should be (ToPortSetAction(bridge.id))

            And("The flow is tagged with flood, ingress port, and device tags")
            val expectedTags = Set(
                FlowTagger.invalidateFlowsByDevice(bridge.id),
                leftPortUnicastInvalidation,
                rightMacFloodInvalidation)
            pktContext.getFlowTags should be (expectedTags)
        }

        scenario("unicast flows are properly tagged") {
            When("A bridge learns a MAC address")
            val bridge: Bridge = fetchDevice(clusterBridge)
            val macTable = bridge.vlanMacTableMap(0.toShort)
            macTable.add(MAC.fromString(rightMac), rightPort.getId)

            And("A packet is sent across the bridge to that MAC address")
            val (pktContext, action) = simulateDevice(bridge, leftToRightFrame, leftPort.getId)

            Then("A unicast flow is created")
            action should be (ToPortAction(rightPort.getId))

            And("The flow is tagged with ingress port, egress port and device tags")
            val expectedTags = Set(
                FlowTagger.invalidateFlowsByDevice(bridge.id),
                leftPortUnicastInvalidation,
                rightPortUnicastInvalidation)
            pktContext.getFlowTags should be (expectedTags)
        }

        scenario("VM migration") {
            When("A bridge learns a MAC address for the first time")
            val bridge: Bridge = fetchDevice(clusterBridge)
            val macTable = bridge.vlanMacTableMap(0.toShort)
            macTable.add(MAC.fromString(rightMac), rightPort.getId)

            Then("A flow invalidation for the flooded case should be produced")
            FlowController.getAndClear() should be (
                List(InvalidateFlowsByTag(rightMacFloodInvalidation)))

            When("A MAC address migrates across two ports")
            macTable.add(MAC.fromString(rightMac), otherPort.getId)

            Then("A flow invalidation for the unicast case should be produced")
            FlowController.getAndClear() should be (
                List(InvalidateFlowsByTag(rightPortUnicastInvalidation)))

            And("new packets should be directed to the newly associated port")
            val (pktContext, action) = simulateDevice(bridge, leftToRightFrame, leftPort.getId)
            val expectedTags = Set(
                FlowTagger.invalidateFlowsByDevice(bridge.id),
                leftPortUnicastInvalidation,
                otherPortUnicastInvalidation)

            action should be (ToPortAction(otherPort.getId))
            pktContext.getFlowTags should be (expectedTags)
        }

        scenario("Packet whose dst mac resolves to the inPort is dropped") {

            When("A bridge learns a MAC address")
            val bridge: Bridge = fetchDevice(clusterBridge)
            val macTable = bridge.vlanMacTableMap(0.toShort)
            macTable.add(MAC.fromString(rightMac), leftPort.getId)

            Then("A flow invalidation for the flooded case should be produced")
            FlowController.getAndClear() should be (
                List(InvalidateFlowsByTag(rightMacFloodInvalidation)))

            And("If a packet with the same dst mac comes from that port")
            val (_, action) = simulateDevice(bridge, leftToRightFrame, leftPort.getId)
            action should be (TemporaryDropAction)
        }
    }

    feature("MAC-port mapping expiration") {
        scenario("A MAC-port mapping expires when its flows are removed") {
            When("a packet is sent across the bridge between two VMs")
            val bridge: Bridge = fetchDevice(clusterBridge)
            val (pktContext, action) = simulateDevice(bridge, leftToRightFrame, leftPort.getId)
            FlowController.getAndClear()

            And("The corresponding flow expires")
            pktContext.getFlowRemovedCallbacks foreach { _.call() }

            Then("The MAC port mapping expires too")
            Thread.sleep(macPortExpiration)
            // Since the check for the expiration of the MAC port association is
            // done every 2 seconds, let's trigger it
            val bridgeManagerPath =
                VirtualTopologyActor.path + "/BridgeManager-" + bridge.id.toString
            val bridgeManager = actorSystem.actorFor(bridgeManagerPath)

            And("A flow invalidation is produced")
            eventually {
                bridgeManager ! CheckExpiredMacPorts()
                FlowController.getAndClear() should be (
                    List(InvalidateFlowsByTag(leftPortUnicastInvalidation)))
            }
        }
    }

    feature("Flow invalidations related to interior bridge ports ") {
        scenario("a interior port is linked") {
            When("A VM ARPs for the router's IP address while the router is not connected")
            val bridge: Bridge = fetchDevice(clusterBridge)
            val (pktContext, action) =
                simulateDevice(bridge, leftToRouterArpFrame, leftPort.getId)

            Then("The bridge should flood the packet")
            action should be (ToPortSetAction(bridge.id))

            When("The router is linked to the bridge")
            clusterDataClient().portsLink(routerPort.getId, leftPort.getId)

            Then("Invalidations for flooded and unicast flows should happen")
            val invals = FlowController.getAndClear()
            invals should contain (InvalidateFlowsByTag(routerMacFloodInvalidation))
            invals should contain (InvalidateFlowsByTag(FlowTagger.invalidateArpRequests(bridge.id)))
        }

        scenario("a interior port is unlinked") {
            When("The router is linked to the bridge")
            var bridge: Bridge = fetchDevice(clusterBridge)
            clusterDataClient().portsLink(routerPort.getId, interiorPort.getId)
            eventually {
                val newBridge: Bridge = fetchDevice(clusterBridge)
                newBridge should not be bridge
            }
            bridge = fetchDevice(clusterBridge)
            FlowController.getAndClear()

            val interiorPortTag =
                FlowTagger.invalidateFlowsByLogicalPort(bridge.id, interiorPort.getId)

            And("A flow addressed to the router is installed")
            val (pktContext, action) = simulateDevice(bridge, leftToRouterFrame, leftPort.getId)
            action should be (ToPortAction(interiorPort.getId))
            pktContext.getFlowTags should contain (interiorPortTag)
            FlowController.getAndClear()

            And("The interior port is then unlinked")
            clusterDataClient().portsUnlink(interiorPort.getId)

            Then("A flow invalidation should be produced")
            eventually {
                FlowController.getAndClear() should contain (InvalidateFlowsByTag(interiorPortTag))
            }
        }
    }
}