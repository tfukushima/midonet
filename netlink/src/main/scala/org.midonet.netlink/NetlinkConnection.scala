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

package org.midonet.netlink

import java.nio.ByteBuffer

import scala.collection.mutable

import com.typesafe.scalalogging.Logger
import rx.Observer

import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.rtnetlink.Rtnetlink

case class NetlinkHeader(len: Int, t: Short, flags: Short, seq: Int, pid: Int)

object NetlinkConnection {
    val NotificationSeq: Int = 0
    val DefaultMaxRequests: Int  = 8
    val DefaultMaxRequestSize: Int = 512
    val DefaultRetries: Int = 10
    val DefaultRetryIntervalMillis: Int = 50
    val DefaultRtnetlinkGroup: Int = Rtnetlink.Group.LINK.bitmask |
        Rtnetlink.Group.NOTIFY.bitmask |
        Rtnetlink.Group.NEIGH.bitmask |
        Rtnetlink.Group.TC.bitmask |
        Rtnetlink.Group.IPV4_IFADDR.bitmask |
        Rtnetlink.Group.IPV4_MROUTE.bitmask |
        Rtnetlink.Group.IPV4_ROUTE.bitmask |
        Rtnetlink.Group.IPV4_RULE.bitmask |
        Rtnetlink.Group.IPV6_IFADDR.bitmask |
        Rtnetlink.Group.IPV6_MROUTE.bitmask |
        Rtnetlink.Group.IPV6_ROUTE.bitmask |
        Rtnetlink.Group.IPV6_PREFIX.bitmask |
        Rtnetlink.Group.IPV6_RULE.bitmask
    // $ python -c "print hex(0b00000000000000111000000000000011)"
    // 0x38003
    //   http://lxr.free-electrons.com/source/net/netlink/genetlink.c#L66
    val DefaultOvsGroups: Int = 0x38003
    val InitialSeq: Int = -1
    val NetlinkReadBufSize: Int = 0x10000

    val AlwaysTrueReader: Reader[Boolean] = new Reader[Boolean] {
        override def deserializeFrom(source: ByteBuffer) = true
    }

    def readNetlinkHeader(reply: ByteBuffer): NetlinkHeader = {
        val position: Int = reply.position

        val len: Int = reply.getInt()
        val nlType: Short = reply.getShort()
        val flags: Short = reply.getShort()
        val seq: Int = reply.getInt()
        val pid: Int = reply.getInt()

        val nextPosition: Int = position + len
        reply.limit(nextPosition)

        new NetlinkHeader(len, nlType, flags, seq, pid)
    }
}

/**
 * Netlink connection interface with NetlinkRequestBroker
 */
trait NetlinkConnection {
    import NetlinkConnection._

    val pid: Int
    val notificationObserver: Observer[ByteBuffer] = null

    protected val logger: Logger
    protected val requestBroker: NetlinkRequestBroker

    protected def sendRequest(observer: Observer[ByteBuffer])
                             (prepare: ByteBuffer => Unit): Int = {
        val seq: Int = requestBroker.nextSequence()
        val buf: ByteBuffer = requestBroker.get(seq)
        prepare(buf)
        requestBroker.publishRequest(seq, observer)
        requestBroker.writePublishedRequests()
        seq
    }

    private def doSendRetryRequest(retryObserver: BaseRetryObserver[_])
                                  (implicit obs: Observer[ByteBuffer]): Int = {
        val seq: Int = requestBroker.nextSequence()
        val buf: ByteBuffer = requestBroker.get(seq)
        retryObserver.seq = seq
        retryObserver.prepare(buf)
        requestBroker.publishRequest(seq, obs)
        requestBroker.writePublishedRequests()
        seq
    }

    protected def sendRetryRequest[T](retryObserver: RetryObserver[T]): Int = {
        val obs: Observer[ByteBuffer] =
            bb2Resource(retryObserver.reader)(retryObserver)
        doSendRetryRequest(retryObserver)(obs)
    }

    protected
    def sendRetryRequest[T](retryObserver: RetrySetObserver[T]): Int = {
        val obs: Observer[ByteBuffer] =
            bb2ResourceSet(retryObserver.reader)(retryObserver)
        doSendRetryRequest(retryObserver)(obs)
    }

    protected trait BaseRetryObserver[T] extends Observer[T] {
        var seq: Int
        val retryCount: Int
        val observer: Observer[T]
        val reader: Reader[_]
        val prepare: ByteBuffer => Unit

        override def onCompleted(): Unit = {
            logger.debug(
                s"Retry for seq $seq was succeeded at retry $retryCount")
            observer.onCompleted()
        }

        def retry(): Unit

        override def onError(t: Throwable): Unit = t match {
            case e: NetlinkException
                if e.getErrorCodeEnum == NetlinkException.ErrorCode.EBUSY =>
                if (retryCount > 0) {
                    logger.debug(s"Scheduling new RetryObserver($retryCount) " +
                        s"for seq $seq, $reader")
                    retry()
                    logger.debug(
                        s"Resent a request for seq $seq at retry " +
                            s" $retryCount")
                }
            case e: Exception =>
                logger.debug(s"Other errors happened for seq $seq: $e")
                observer.onError(t)
        }

        override def onNext(r: T): Unit = {
            logger.debug(s"Retry for seq $seq got $r at retry $retryCount")
            observer.onNext(r)
        }
    }

    protected
    class RetryObserver[T](val observer: Observer[T],
                           override var seq: Int,
                           override val retryCount: Int,
                           override val prepare: ByteBuffer => Unit)
                          (implicit override val reader: Reader[T])
            extends BaseRetryObserver[T] {

        override def retry(): Unit = {
            val newSeq = requestBroker.nextSequence()
            val retryObserver = new RetryObserver[T](
                observer, newSeq, retryCount - 1, prepare)
            sendRetryRequest(retryObserver)
        }
    }

    protected
    class RetrySetObserver[T](val observer: Observer[Set[T]],
                              override var seq: Int,
                              override val retryCount: Int,
                              override val prepare: ByteBuffer => Unit)
                             (implicit override val reader: Reader[T])
            extends BaseRetryObserver[Set[T]] {

        override def retry(): Unit = {
            val newSeq = requestBroker.nextSequence()
            val retryObserver = new RetrySetObserver[T](
                observer, newSeq, retryCount - 1, prepare)
            sendRetryRequest(retryObserver)
        }
    }

    protected
    def toRetriable[T](observer: Observer[T], seq: Int = InitialSeq,
                       retryCounter: Int = DefaultRetries)
                      (prepare: ByteBuffer => Unit)
                      (implicit reader: Reader[T]): RetryObserver[T] =
        new RetryObserver[T](observer, seq, retryCounter, prepare)

    protected
    def toRetriableSet[T](observer: Observer[Set[T]], seq: Int = InitialSeq,
                          retryCount: Int = DefaultRetries)
                         (prepare: ByteBuffer => Unit)
                         (implicit reader: Reader[T]): RetrySetObserver[T] =
        new RetrySetObserver[T](observer, seq, retryCount, prepare)

    protected
    def bb2Resource[T](reader: Reader[T])
                      (observer: Observer[T]): Observer[ByteBuffer] =
        new Observer[ByteBuffer] {
            override def onCompleted(): Unit = observer.onCompleted()
            override def onError(e: Throwable): Unit = observer.onError(e)
            override def onNext(buf: ByteBuffer): Unit = {
                val resource: T = reader.deserializeFrom(buf)
                observer.onNext(resource)
            }
        }

    protected
    def bb2ResourceSet[T](reader: Reader[T])
                         (observer: Observer[Set[T]]): Observer[ByteBuffer] =
        new Observer[ByteBuffer] {
            private val resources =  mutable.Set[T]()
            override def onCompleted(): Unit = {
                observer.onNext(resources.toSet)
                observer.onCompleted()
            }
            override def onError(e: Throwable): Unit = observer.onError(e)
            override def onNext(buf: ByteBuffer): Unit = {
                val resource: T = reader.deserializeFrom(buf)
                resources += resource
            }
        }
}
