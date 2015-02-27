/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.netlink.rtnetlink

import java.nio.ByteBuffer

import org.slf4j.LoggerFactory
import rx.Observer

import org.midonet.netlink._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.NanoClock

object RtnetlinkConnection {
}

class RtnetlinkConnection(channel: NetlinkChannel,
                          sendPool: BufferPool,
                          clock: NanoClock) extends NetlinkConnection {
    import org.midonet.netlink.NetlinkConnection._

    override val pid: Int = channel.getLocalAddress.getPid
    override protected val log = LoggerFactory.getLogger(
        "org.midonet.netlink.rtnetlink-conn-" + pid)
    override protected val requestPool = sendPool

    private val protocol = new RtnetlinkProtocol(pid)

    protected val reader = new NetlinkReader(channel)
    protected val writer = new NetlinkBlockingWriter(channel)
    protected val replyBuf =
        BytesUtil.instance.allocateDirect(NetlinkReadBufSize)
    override val requestBroker = new NetlinkRequestBroker(reader, writer,
        MaxRequests, replyBuf, clock, timeout = DefaultTimeout)

    override def getHeaderLength = 20

    implicit
    def linkObserver(observer: Observer[Link]): Observer[ByteBuffer] =
        netlinkPayloadObserver(bb2Resource(Link.deserializer)(observer))
    implicit
    def addrObserver(observer: Observer[Addr]): Observer[ByteBuffer] =
        netlinkPayloadObserver(bb2Resource(Addr.deserializer)(observer))
    implicit
    def routeObserver(observer: Observer[Route]): Observer[ByteBuffer] =
        netlinkPayloadObserver(bb2Resource(Route.deserializer)(observer))
    implicit
    def neighObserver(observer: Observer[Neigh]): Observer[ByteBuffer] =
        netlinkPayloadObserver(bb2Resource(Neigh.deserializer)(observer))

    implicit
    def linkSetObserver(observer: Observer[Set[Link]]): Observer[ByteBuffer] =
        netlinkPayloadObserver(bb2Resources(Link.deserializer)(observer))
    implicit
    def addrSetObserver(observer: Observer[Set[Addr]]): Observer[ByteBuffer] =
        netlinkPayloadObserver(bb2Resources(Addr.deserializer)(observer))
    implicit
    def routeSetObserver(observer: Observer[Set[Route]]): Observer[ByteBuffer] =
        netlinkPayloadObserver(bb2Resources(Route.deserializer)(observer))
    implicit
    def neighSetObserver(observer: Observer[Set[Neigh]]): Observer[ByteBuffer] =
        netlinkPayloadObserver(bb2Resources(Neigh.deserializer)(observer))

    implicit
    def booleanObserver(observer: Observer[Boolean]): Observer[ByteBuffer] =
        bb2Resource(AlwaysTrueReader)(observer)

    def linksList(observer: Observer[Set[Link]]): Unit =
        sendRequest(observer)(protocol.prepareLinkList)

/*    def linksGet(ifName: String, observer: Observer[Link]): Unit =
        sendRequest(observer)(buf => protocol.prepareLinkGet(buf, ))*/

    def linksGet(ifindex: Int, observer: Observer[Link]): Unit =
        sendRequest(observer)(buf => protocol.prepareLinkGet(buf, ifindex))

/*    def linksCreate(link: Link, observer: Observer[Link]): Unit =
        sendRequest(observer)(buf => protocol.prepareLinkCreate(buf, link))*/

    def linksSetAddr(link: Link, mac: MAC,
                     observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf =>
            protocol.prepareLinkSetAddr(buf, link, mac))

    def linksSet(link: Link, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => protocol.prepareLinkSet(buf, link))

/*    def linksDel(link: Link, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => /* prepareLinkDel(buf, link */)*/

    def addrsList(observer: Observer[Set[Addr]]): Unit =
        sendRequest(observer)(protocol.prepareAddrList)

    def addrsGet(ifIndex: Int, observer: Observer[Addr]): Unit =
        sendRequest(observer)(buf => protocol.prepareAddrGet(buf, ifIndex))

/*    def addrsCreate(addr: Addr, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => /* protocol.prepareAddrCreate(buf, addr) */)*/

/*    def addrsDel(addr: Addr, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => protocol.prepareAddrDel(buf, addr))*/

    def routesList(observer: Observer[Set[Route]]): Unit =
        sendRequest(observer)(protocol.prepareRouteList)

    def routesGet(dst: IPv4Addr, observer: Observer[Route]): Unit =
        sendRequest(observer)(buf => protocol.prepareRouteGet(buf, dst))

    def routesCreate(dst: IPv4Addr, prefix: Int, gw: IPv4Addr, link: Link,
                     observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf =>
            protocol.prepareRouteNew(buf, dst, prefix, gw, link))

/*    def routesDel(route: Route, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => /* protocol.prepareRouteDel(buf, route) */)*/

    def neighsList(observer: Observer[Set[Neigh]]): Unit =
        sendRequest(observer)(protocol.prepareNeighList)
/*
    def neighsGet(ifName: String, observer: Observer[Neigh]): Unit =
        sendRequest(obsrever)(protocol.prepareNeighGet)

    def neighsCreate(neigh: Neigh, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => /* protocol.prepareNeighCreate(buf, neight) */)

    def neighsDel(neigh: Neigh, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => /* protocol.prepareNeighDel(buf, neight) */)*/
}
