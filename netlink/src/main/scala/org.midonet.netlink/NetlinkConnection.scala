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

import org.slf4j.Logger
import rx.Observer

import org.midonet.netlink.clib.cLibrary
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.rtnetlink.Rtnetlink

case class NetlinkHeader(len: Int, t: Short, flags: Short, seq: Int, pid: Int)

object NetlinkConnection {
    val DefaultMaxRequests = 8
    val DefaultMaxRequestSize = 512
    val NetlinkReadBufSize = 0x10000
    val DefaultRetries = 10
    val DefaultRetryIntervalSec = 1 * 1000
    val InitialSeq = -1
    val DefaultNetlinkGroup = Rtnetlink.Group.LINK.bitmask |
        Rtnetlink.Group.NOTIFY.bitmask |
        Rtnetlink.Group.NEIGH.bitmask |
        Rtnetlink.Group.TC.bitmask |
        Rtnetlink.Group.IPV4_IFADDR.bitmask |
        Rtnetlink.Group.IPV4_MROUTE.bitmask |
        Rtnetlink.Group.IPV4_ROUTE.bitmask |
        Rtnetlink.Group.IPV6_IFADDR.bitmask |
        Rtnetlink.Group.IPV6_MROUTE.bitmask |
        Rtnetlink.Group.IPV6_ROUTE.bitmask |
        Rtnetlink.Group.IPV6_PREFIX.bitmask |
        Rtnetlink.Group.IPV6_RULE.bitmask

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

    protected val log: Logger
    protected val requestBroker: NetlinkRequestBroker

    val retryTable = mutable.Map[Int, ByteBuffer => Unit]()

    protected def sendRequest(observer: Observer[ByteBuffer])
                             (prepare: ByteBuffer => Unit): Unit = {
        val seq: Int = requestBroker.nextSequence()
        val buf: ByteBuffer = requestBroker.get(seq)
        observer match {
            case retryObserver: RetryObserver[ByteBuffer]
                if retryObserver.seq == InitialSeq =>
                retryObserver.seq = seq
            case _ =>
        }
        prepare(buf)
        retryTable += (seq -> prepare)
        requestBroker.publishRequest(seq, observer)
        requestBroker.writePublishedRequests()
    }

    protected def sendRetriableRequest(observer: RetryObserver[ByteBuffer])
                                      (prepare: ByteBuffer => Unit): Int = {
        val seq: Int = requestBroker.nextSequence()
        val buf: ByteBuffer = requestBroker.get(seq)
        if (observer.seq == InitialSeq) {
            observer.seq = seq
        }
        prepare(buf)
        retryTable += (seq -> prepare)
        requestBroker.publishRequest(seq, observer)
        requestBroker.writePublishedRequests()
        seq
    }

    private def processFailedRequest(seq: Int, error: Int,
                                     observer: Observer[ByteBuffer]): Unit = {
        val errorMessage: String = cLibrary.lib.strerror(-error)
        val err: NetlinkException = new NetlinkException(-error, errorMessage)
        observer.onError(err)
        log.error(cLibrary.lib.strerror(-error))
    }

    private def readNetlinkBuffer(reply: ByteBuffer,
                                  observer: Observer[ByteBuffer]): Unit = {
        val finalLimit: Int = reply.limit
        reply.flip()
        reply.mark()

        while (reply.remaining >= NetlinkMessage.HEADER_SIZE) {
            val position: Int = reply.position
            val len: Int = reply.getInt
            val `type`: Short = reply.getShort
            val flags: Short = reply.getShort
            val seq: Int = reply.getInt
            val pid: Int = reply.getInt
            val nextPosition: Int = position + len
            reply.limit(nextPosition)
            `type` match {
                case NLMessageType.NOOP => // NOOP
                case NLMessageType.ERROR if seq != 0 =>
                    val error: Int = reply.getInt
                    val errLen: Int = reply.getInt
                    val errType: Short = reply.getShort
                    val errFlags: Short = reply.getShort
                    val errSeq: Int = reply.getInt
                    val errPid: Int = reply.getInt
                    if (error == 0) {
                        observer.onNext(reply)
                    } else {
                        processFailedRequest(seq, error, observer)
                    }
                case NLMessageType.DONE if seq != 0 =>
                    observer.onNext(reply)
                case _ if seq == 0 => // Should never happen
                case _ =>
                    observer.onNext(reply)
            }
            reply.limit(finalLimit)
            reply.position(nextPosition)
        }
    }

    def netlinkPayloadObserver(observer: Observer[ByteBuffer]) =
        new Observer[ByteBuffer] {
            override def onCompleted(): Unit = observer.onCompleted()
            override def onError(e: Throwable): Unit = observer.onError(e)
            override def onNext(buf: ByteBuffer): Unit = {
                readNetlinkBuffer(buf, observer)
            }
        }

    protected
    class RetryObserver[T](var seq: Int = InitialSeq, retryCount: Int,
                           observer: Observer[T])
                          (implicit reader: Reader[T]) extends Observer[T] {
        override def onCompleted(): Unit = {
            retryTable -= seq
            observer.onCompleted()
        }

        override def onError(t: Throwable): Unit = t match {
            case e: NetlinkException
                if e.getErrorCodeEnum == NetlinkException.ErrorCode.EBUSY =>
                if (retryCount > 0) {
                    // sendRequest(bb2Resource(reader)(
/*                    val retryObs: RetryObserver[ByteBuffer] =
                        bb2RetryResource[T](reader)(
                            seq, retryCount - 1, observer)*/
                    val retryObs = bb2Resource(reader)(
                        new RetryObserver[T](seq, retryCount - 1, observer))
                        // toRetriable(seq, retryCount - 1, observer)
                    // sendRetriableRequest(retryObs)(retryTable(seq))
                    sendRequest(retryObs)(retryTable(seq))
                    // sendRetriableRequest(retryObs)(retryTable(seq))
                    // sendRetriableRequest(bb2RetryResource(reader)(retryObs))(retryTable(seq))
                }
            case _ =>
                observer.onError(t)
        }

        override def onNext(r: T): Unit = {
            observer.onNext(r)
        }
    }

    protected
    class RetrySetObserver[T](var seq: Int = InitialSeq, retryCount: Int,
                              observer: Observer[Set[T]])
                             (implicit reader: Reader[T]) extends Observer[Set[T]] {
/*        protected
        class RetrySetObserver[T](var seq: Int = InitialSeq, retryCount: Int,
                                  observer: Observer[Container[T]])
                                 (implicit reader: Reader[T]) extends Observer[Container[T]] {*/
        override def onCompleted(): Unit = {
            retryTable -= seq
        }

        override def onError(t: Throwable): Unit = t match {
            case e: NetlinkException
                if e.getErrorCodeEnum == NetlinkException.ErrorCode.EBUSY =>
                if (retryCount > 0) {
                    val timer = new Timer()
                    timer.schedule(new TimerTask() {
                        def run(): Unit = {
                            val retryObs: Observer[ByteBuffer] = bb2ResourceSet(reader)(
                                new RetrySetObserver[T](seq, retryCount - 1, observer))
                            sendRequest(retryObs)(retryTable(seq))
                        }
                    }, DefaultRetryIntervalSec)
                    // val retryObs: RetryObserver[ByteBuffer] =
                    //     bb2RetryResourceSet[T](reader)(
                    //         seq, retryCount - 1, observer)
                    // sendRetriableRequest(retryObs)(retryTable(seq))
/*                    sendRetriableRequest(
                        bb2RetryResourceSet(reader)(
                            seq, retryCount - 1,
                            toRetriableSet[T](observer)))(retryTable(seq))*/
                }
            case _ =>
        }

        override def onNext(r: Set[T]): Unit = {
            // observer.onNext(r)
        }
    }

    // def toRetriable[T](observer: Observer[T])
    //                 (implicit reader: Reader[T]): RetryObserver[T] = {
/*    protected
    def toRetriable[T](seq: Int, retryCounter: Int, observer: Observer[T])
                      (implicit reader: Reader[T]): Observer[T] = {
                      // (implicit reader: Reader[T]): RetryObserver[T] = {
        new RetryObserver[T](seq, retryCounter, observer)(reader) {
            override def onCompleted(): Unit = {
                super.onCompleted()
                observer.onCompleted()
            }

            override def onError(t: Throwable): Unit = {
                super.onError(t)
                observer.onError(t)
            }

            override def onNext(resource: T): Unit = {
                observer.onNext(resource)
            }
        }
    }*/
    protected
    def toRetriable[T](observer: Observer[T], seq: Int = InitialSeq,
                       retryCounter: Int = DefaultRetries)
                      (implicit reader: Reader[T]): RetryObserver[T] = {
        // (implicit reader: Reader[T]): RetryObserver[T] = {
        new RetryObserver[T](seq, retryCounter, observer)(reader) {
            override def onCompleted(): Unit = {
                super.onCompleted()
                observer.onCompleted()
            }

            override def onError(t: Throwable): Unit = {
                super.onError(t)
                observer.onError(t)
            }

            override def onNext(resource: T): Unit = {
                observer.onNext(resource)
            }
        }
    }

/*    protected
    def toRetriableSet[T, Container[_] <: Set[T]](observer: Observer[Container[T]])
                                                 (implicit reader: Reader[T]): RetryObserver[Container[T]] = {
        new RetrySetObserver[T, Container](InitialSeq, DefaultRetries, observer)(reader) {
            private val resources = mutable.Set[T]()
            override def onCompleted(): Unit = {
                super.onCompleted()
                observer.onCompleted()
            }

            override def onError(t: Throwable): Unit = {
                super.onError(t)
                observer.onError(t)
            }

            override def onNext(resources: Container[T]): Unit = {
                observer.onNext(resources)
            }
        }
    }*/

    protected
    def toRetriableSet[T](observer: Observer[Set[T]], seq: Int = InitialSeq,
                          retryCount: Int = DefaultRetries)
                         (implicit reader: Reader[T]): RetrySetObserver[T] = {
        new RetrySetObserver[T](seq, retryCount, observer)(reader) {
                private val resources = mutable.Set[T]()
                override def onCompleted(): Unit = {
                    super.onCompleted()
                    observer.onCompleted()
                }

                override def onError(t: Throwable): Unit = {
                    super.onError(t)
                    observer.onError(t)
                }

                override def onNext(resources: Set[T]): Unit = {
                    observer.onNext(resources)
                }
            }
        }

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

/*    protected
    def bb2RetryResource[T](reader: Reader[T])
                           (seq: Int, retryCount: Int,
                            observer: Observer[T]): RetryObserver[ByteBuffer] =
        new RetryObserver[ByteBuffer](seq, retryCount, bb2Resource(reader)(observer)) {
            override def onCompleted(): Unit = observer.onCompleted()
            override def onError(e: Throwable): Unit = observer.onError(e)
            override def onNext(buf: ByteBuffer): Unit = {
                val resource: T = reader.deserializeFrom(buf)
                observer.onNext(resource)
            }
        }

    protected
    def bb2RetryResourceSet[T](reader: Reader[T])
                              (seq: Int, retryCount: Int,
                               observer: Observer[Set[T]]): RetryObserver[ByteBuffer] =
        new RetryObserver[ByteBuffer](seq, retryCount, bb2ResourceSet(reader)(observer)) {
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
        }*/
}
