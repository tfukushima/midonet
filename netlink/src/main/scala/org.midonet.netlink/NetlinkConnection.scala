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
import scala.concurrent.duration._

import org.slf4j.Logger
import rx.Observer

import org.midonet.netlink.clib.cLibrary
import org.midonet.netlink.exceptions.NetlinkException

case class NetlinkHeader(len: Int, t: Short, flags: Short, seq: Int, pid: Int)

object NetlinkConnection {
    val MaxRequests = 8
    val DefaultMaxPatchIoOps = 200
    val DefaultTimeout = 5 second
    val NetlinkReadBufSize = 0x10000

    val AlwaysTrueReader: Reader[Boolean] = new Reader[Boolean] {
        override def deserializeFrom(source: ByteBuffer) = true
    }
}

/**
 * Netlink connection interface
 */
trait NetlinkConnection {

    val pid: Int
    protected val log: Logger
    protected val requestPool: BufferPool
    protected val requestBroker: NetlinkRequestBroker

    def getBuffer(): ByteBuffer = {
        val buf = requestPool.take()
        buf.clear()
        // buf.position(getHeaderLength())
        buf
    }

    protected def getHeaderLength: Int

    protected def sendRequest(observer: Observer[ByteBuffer])
                             (prepare: ByteBuffer => Unit): Unit = {
        val buf = getBuffer()
        prepare(buf)
        requestBroker.writeRequest(buf, observer)
    }

    def readNetlinkHeader(reply: ByteBuffer): NetlinkHeader = {
        reply.flip()
        reply.mark()
        val finalLimit = reply.limit()

        val position: Int = reply.position

        val len: Int = reply.getInt
        val t: Short = reply.getShort
        val flags: Short = reply.getShort
        val seq: Int = reply.getInt
        val pid: Int = reply.getInt

        val nextPosition: Int = position + len
        reply.limit(nextPosition)

        new NetlinkHeader(len, t, flags, seq, pid)
    }

    private
    def processSuccessfulRequest(buf: ByteBuffer,
                                 observer: Observer[ByteBuffer]): Unit = {
        observer.onNext(buf)
        observer.onCompleted()
    }


    private def processFailedRequest(seq: Int, error: Int,
                                     observer: Observer[ByteBuffer]): Unit = {
        val errorMessage: String = cLibrary.lib.strerror(-error)
        val err: NetlinkException = new NetlinkException(-error, errorMessage)
        observer.onError(err)
        log.error(cLibrary.lib.strerror(-error))
    }

    private def processRequest(flags: Short, reply: ByteBuffer,
                               observer: Observer[ByteBuffer]): Unit = {
        observer.onNext(reply)
/*        if (!NLFlag.isMultiFlagSet(flags)) {
            observer.onCompleted()
        }*/
    }

    private def readNetlinkBuffer(reply: ByteBuffer,
                                  observer: Observer[ByteBuffer]): Unit = {
        val finalLimit: Int = reply.limit
        reply.flip()
        reply.mark()

        while (reply.remaining >= getHeaderLength) {
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
                        processSuccessfulRequest(reply, observer)
                    }
                    else {
                        processFailedRequest(seq, error, observer)
                    }
                case NLMessageType.DONE if seq != 0 =>
                    processSuccessfulRequest(reply, observer)
                case _ if seq == 0 =>  // Should never happen
                case _ =>
                    processRequest(flags, reply, observer)
                    // processMessageType(bucket, `type`, flags, seq, pid, reply)
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
    def bb2Resources[T](reader: Reader[T])
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
