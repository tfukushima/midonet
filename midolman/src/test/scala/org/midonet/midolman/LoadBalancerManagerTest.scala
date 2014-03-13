/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.simulation.{Pool, PacketContext, LoadBalancer, CustomMatchers}
import org.midonet.midolman.topology.{FlowTagger, VirtualTopologyActor}
import org.midonet.midolman.topology.VirtualTopologyActor.{PoolRequest, LoadBalancerRequest}
import java.util.UUID
import akka.testkit.{ImplicitSender, TestKit}
import akka.actor.ActorSystem
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.packets.{IPv4Addr, TCP}
import scala.compat.Platform
import scala.concurrent.ExecutionContext

@RunWith(classOf[JUnitRunner])
class LoadBalancerManagerTest extends TestKit(ActorSystem("LoadBalancerManagerTest"))
with FeatureSpecLike
with CustomMatchers
with GivenWhenThen
with ImplicitSender
with Matchers
with MidolmanServices
with MockMidolmanActors
with OneInstancePerTest
with VirtualConfigurationBuilders {

    var vta: TestableVTA = null

    protected override def registerActors =
        List(VirtualTopologyActor -> (() => new TestableVTA))

    protected override def beforeTest() {
        vta = VirtualTopologyActor.as[TestableVTA]
    }

    feature("LoadBalancerManager handles loadBalancer's VIPs") {
        scenario("Load loadBalancer with two VIPs") {
            Given("a loadBalancer with two VIPs")
            val loadBalancer = createLoadBalancer()
            val vips = (0 until 2).map(_ => createVipOnLoadBalancer(loadBalancer))
            val vipIds = vips.map(v => v.getId).toSet
            vips.size shouldBe 2

            When("the VTA receives a request for it")
            vta.self ! LoadBalancerRequest(loadBalancer.getId)

            Then("it should return the requested loadBalancer, including the VIPs")
            val lb = expectMsgType[LoadBalancer]
            lb.id shouldEqual loadBalancer.getId
            lb.vips.size shouldBe 2
            lb.vips.map(v => v.id).toSet shouldEqual vipIds

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(flowInvalidationMsg(lb.id)) shouldBe true
        }

        scenario("Receive update when a VIP is added") {
            Given("a loadBalancer with one VIP")
            val loadBalancer = createLoadBalancer()
            val firstVip = createVipOnLoadBalancer(loadBalancer)

            When("the VTA receives a subscription request for it")
            vta.self ! LoadBalancerRequest(loadBalancer.getId, update = true)

            And("it returns the first version of the loadBalancer")
            val lb = expectMsgType[LoadBalancer]
            lb.id shouldEqual loadBalancer.getId
            lb.vips.size shouldBe 1
            vta.getAndClear()

            And("a new VIP is added")
            createVipOnLoadBalancer(loadBalancer)

            Then("the VTA should send an update")
            val lb2 = expectMsgType[LoadBalancer]
            lb2.id shouldEqual loadBalancer.getId
            lb2.vips.size shouldBe 2

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(flowInvalidationMsg(lb.id)) shouldBe true
        }

        scenario("Receive update when a VIP is removed") {
            Given("a loadBalancer with one VIP")
            val loadBalancer = createLoadBalancer()
            val firstVip = createVipOnLoadBalancer(loadBalancer)

            When("the VTA receives a subscription request for it")
            vta.self ! LoadBalancerRequest(loadBalancer.getId, update = true)

            And("it returns the first version of the loadBalancer")
            val lb = expectMsgType[LoadBalancer]
            lb.id shouldEqual loadBalancer.getId
            lb.vips.size shouldBe 1
            vta.getAndClear()

            And("the existing VIP is removed")
            removeVipFromLoadBalancer(firstVip, loadBalancer)

            Then("the VTA should send an update")
            val lb2 = expectMsgType[LoadBalancer]
            lb2.id shouldEqual loadBalancer.getId
            lb2.vips.size shouldBe 0

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(flowInvalidationMsg(lb.id)) shouldBe true
        }

        scenario("Receive update when a VIP is changed") {
            Given("a loadBalancer with one VIP")
            val loadBalancer = createLoadBalancer()
            val firstVip = createVipOnLoadBalancer(loadBalancer)

            When("the VTA receives a subscription request for it")
            vta.self ! LoadBalancerRequest(loadBalancer.getId, update = true)

            And("it returns the first version of the loadBalancer")
            val lb = expectMsgType[LoadBalancer]
            lb.id shouldEqual loadBalancer.getId
            lb.vips.size shouldBe 1
            vta.getAndClear()

            And("the VIP is changed")
            setVipAdminStateUp(firstVip, false)

            Then("the VTA should send an update")
            val lb2 = expectMsgType[LoadBalancer]
            lb2.id shouldEqual loadBalancer.getId
            lb2.vips.size shouldBe 1

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(flowInvalidationMsg(lb.id)) shouldBe true
        }

        scenario("Receive update when loadbalancer is changed") {
            Given("a loadBalancer with one VIP")
            val loadBalancer = createLoadBalancer()
            val firstVip = createVipOnLoadBalancer(loadBalancer)

            When("the VTA receives a subscription request for it")
            vta.self ! LoadBalancerRequest(loadBalancer.getId, update = true)

            And("it returns the first version of the loadBalancer")
            val lb = expectMsgType[LoadBalancer]
            lb.id shouldEqual loadBalancer.getId
            lb.adminStateUp shouldBe true
            lb.vips.size shouldBe 1
            vta.getAndClear()

            And("the loadBalancer gets set to admin state down")
            setLoadBalancerDown(loadBalancer)

            Then("the VTA should send an update")
            val lb2 = expectMsgType[LoadBalancer]
            lb2.id shouldEqual loadBalancer.getId
            lb2.vips.size shouldBe 1
            lb2.adminStateUp shouldBe false

            And("the VTA should receive a flow invalidation")
            vta.getAndClear().contains(flowInvalidationMsg(lb.id)) shouldBe true
        }

    }

    feature("Loadbalancer logic requests Pool when needed") {
        scenario("Pool requested when VIP traffic flows through loadBalancer") {
            Given("a loadBalancer with one VIP")
            val loadBalancer = createLoadBalancer()
            val firstVip = createVipOnLoadBalancer(loadBalancer)

            And("the VIP has an associated Pool")
            val firstPool = createPool()
            setVipPool(firstVip, firstPool)

            When("the VTA receives a subscription request for it")
            vta.self ! LoadBalancerRequest(loadBalancer.getId, update = true)

            And("it returns the first version of the loadBalancer")
            val lb = expectMsgType[LoadBalancer]
            lb.id shouldEqual loadBalancer.getId
            lb.adminStateUp shouldBe true
            lb.vips.size shouldBe 1
            vta.getAndClear()

            And("traffic is sent through the loadbalancer")
            // Ingress match for packet destined to VIP
            val ingressMatch = new WildcardMatch()
                .setNetworkDestination(IPv4Addr.fromString(firstVip.getAddress))
                .setTransportDestination(firstVip.getProtocolPort)
                .setNetworkSource(IPv4Addr.fromString("1.1.1.1"))
                .setTransportSource(1)
                .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            val pktContextIngress = new PacketContext(None, null,
                Platform.currentTime + 10000, null,
                null, null, true, None, ingressMatch)(actorSystem)

            val f = lb.processInbound(pktContextIngress)(executionContext, actorSystem)

            Then("the VTA should receive a pool request")
            val vtaMessages = vta.getAndClear()
            vtaMessages.size shouldBe 3

            vtaMessages.contains(poolReqMsg(firstPool.getId)) shouldBe true

            And("the VTA should receive the pool itself")
            val poolMessages = vtaMessages.filter(m => m.isInstanceOf[Pool])
            poolMessages.size shouldBe 1

            And("the VTA should receive a flow invalidation for the pool")
            vtaMessages.contains(flowInvalidationMsg(firstPool.getId)) shouldBe true
        }
    }

    def poolReqMsg(id: UUID) =
        PoolRequest(id)

    def flowInvalidationMsg(id: UUID) =
        InvalidateFlowsByTag(FlowTagger.invalidateFlowsByDevice(id))
}