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
import java.util.{TimerTask, Timer}

import scala.collection.mutable

import com.typesafe.scalalogging.Logger
import rx.Observer

import org.midonet.netlink.clib.cLibrary
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.rtnetlink.Rtnetlink

case class NetlinkHeader(len: Int, t: Short, flags: Short, seq: Int, pid: Int)

object NetlinkConnection {
    val NotificationSeq = 0
    val DefaultMaxRequests = 8
    val DefaultMaxRequestSize = 512
    val DefaultRetries = 10
    val DefaultRetryIntervalMillis = 50
    val DefaultRtnetlinkGroup = Rtnetlink.Group.LINK.bitmask |
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
    val DefaultOvsGroups = 0x38003
    val InitialSeq = -1
    val NetlinkReadBufSize = 0x10000

    val AlwaysTrueReader: Reader[Boolean] = new Reader[Boolean] {
        override def deserializeFrom(source: ByteBuffer) = true
    }

    def readNetlinkHeader(reply: ByteBuffer): NetlinkHeader = {
        reply.flip()
        reply.mark()
        val finalLimit = reply.limit()

        val position: Int = reply.position

        val len: Int = reply.getInt
        val nlType: Short = reply.getShort
        val flags: Short = reply.getShort
        val seq: Int = reply.getInt
        val pid: Int = reply.getInt

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

    val retryTable = mutable.Map[Int, ByteBuffer => Unit]()

    protected def sendRequest(observer: Observer[ByteBuffer])
                             (prepare: ByteBuffer => Unit): Int = {
        val seq: Int = requestBroker.nextSequence()
        val buf: ByteBuffer = requestBroker.get(seq)
        prepare(buf)
        requestBroker.publishRequest(seq, observer)
        requestBroker.writePublishedRequests()
        seq
    }

    protected def sendRetryRequest(observer: Observer[ByteBuffer],
                                   retryObserver: RetryObserver[_])
                                  (prepare: ByteBuffer => Unit): Int = {
        val seq: Int = requestBroker.nextSequence()
        val buf: ByteBuffer = requestBroker.get(seq)
        if (retryObserver.seq == InitialSeq) {
            retryObserver.seq = seq
            retryTable += (seq -> prepare)
        }
        prepare(buf)
        requestBroker.publishRequest(seq, observer)
        requestBroker.writePublishedRequests()
        seq
    }

    protected def sendRetryRequest(observer: Observer[ByteBuffer],
                                   retryObserver: RetrySetObserver[_])
                                  (prepare: ByteBuffer => Unit): Int = {
        val seq: Int = requestBroker.nextSequence()
        val buf: ByteBuffer = requestBroker.get(seq)
        if (retryObserver.seq == InitialSeq) {
            retryObserver.seq = seq
            retryTable += (seq -> prepare)
        }
        prepare(buf)
        requestBroker.publishRequest(seq, observer)
        requestBroker.writePublishedRequests()
        seq
    }

    private def processFailedRequest(seq: Int, error: Int,
                                     observer: Observer[ByteBuffer]): Unit = {
        val errorMessage: String = cLibrary.lib.strerror(-error)
        val err: NetlinkException = new NetlinkException(-error, errorMessage)
        observer.onError(err)
        logger.error(cLibrary.lib.strerror(-error))
    }

    protected
    class RetryObserver[T](observer: Observer[T],
                           var seq: Int, retryCount: Int)
                          (implicit reader: Reader[T]) extends Observer[T] {
        override def onCompleted(): Unit = {
            logger.debug(
                s"Retry for seq $seq was succeeded at retry $retryCount")
            retryTable -= seq
            observer.onCompleted()
        }

        override def onError(t: Throwable): Unit = t match {
            case e: NetlinkException
                if e.getErrorCodeEnum == NetlinkException.ErrorCode.EBUSY =>
                if (retryCount > 0 && retryTable.contains(seq)) {
                    logger.debug(s"Scheduling new RetryObserver($retryCount) " +
                        s"for seq $seq, $reader")
                    val timer = new Timer(this.getClass.getName + "-resend")
                    timer.schedule(new TimerTask() {
                        def run(): Unit = {
                            val retryObserver = new RetryObserver[T](observer,
                                seq, retryCount - 1)
                            val obs: Observer[ByteBuffer] =
                                bb2Resource(reader)(retryObserver)
                            sendRetryRequest(obs, retryObserver)(
                                retryTable(seq))
                            logger.debug(
                                s"Resent a request for seq $seq at retry " +
                                    s" $retryCount")
                        }
                    }, DefaultRetryIntervalMillis)
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
    class RetrySetObserver[T](observer: Observer[Set[T]],
                              var seq: Int, retryCount: Int)
                             (implicit reader: Reader[T])
            extends Observer[Set[T]] {
        override def onCompleted(): Unit = {
            logger.debug(
                s"Retry for seq $seq was succeeded at retry $retryCount")
            retryTable -= seq
            observer.onCompleted()
        }

        override def onError(t: Throwable): Unit = t match {
            case e: NetlinkException
                if e.getErrorCodeEnum == NetlinkException.ErrorCode.EBUSY =>
                if (retryCount > 0 && retryTable.contains(seq)) {
                    logger.debug(s"Scheduling new RetryObserver($retryCount) " +
                        s"for seq $seq, $reader")
                    val timer = new Timer(this.getClass.getName + "-resend")
                    timer.schedule(new TimerTask() {
                        override def run(): Unit = {
                            val retryObserver = new RetrySetObserver[T](
                                observer, seq, retryCount - 1)
                            val obs: Observer[ByteBuffer] =
                                bb2ResourceSet(reader)(retryObserver)
                            sendRetryRequest(obs, retryObserver)(
                                retryTable(seq))
                            logger.debug(s"Resent a request for seq $seq at " +
                                s"retry $retryCount")
                        }
                    }, DefaultRetryIntervalMillis)
                }
            case e: Exception =>
                logger.debug(s"Other errors happened for seq $seq: $e")
                observer.onError(t)
        }

        override def onNext(r: Set[T]): Unit = {
            logger.debug(s"Retry for seq $seq got $r at retry $retryCount")
            observer.onNext(r)
        }
    }

    protected
    def toRetriable[T](observer: Observer[T], seq: Int = InitialSeq,
                       retryCounter: Int = DefaultRetries)
                      (implicit reader: Reader[T]): RetryObserver[T] =
        new RetryObserver[T](observer, seq, retryCounter)

    protected
    def toRetriableSet[T](observer: Observer[Set[T]], seq: Int = InitialSeq,
                          retryCount: Int = DefaultRetries)
                         (implicit reader: Reader[T]): RetrySetObserver[T] =
        new RetrySetObserver[T](observer, seq, retryCount)

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
