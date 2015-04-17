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

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.util.concurrent.ExecutionException

import rx.Observer

import org.midonet.netlink._
import org.midonet.util.concurrent.NanoClock

object SelectorBasedRtnetlinkConnection extends
        RtnetlinkConnectionFactory[SelectorBasedRtnetlinkConnection] {

    override def apply() = {
        val conn = super.apply()
        conn.start()
        conn
    }
}

class SelectorBasedRtnetlinkConnection(channel: NetlinkChannel,
                                       maxPendingRequests: Int,
                                       maxRequestSize: Int,
                                       clock: NanoClock)
        extends RtnetlinkConnection(channel, maxPendingRequests,
            maxRequestSize, clock)
        with SelectorBasedNetlinkChannelReader {

    logger.info(s"Starting rtnetlink connection $name")
    channel.register(channel.selector,
        SelectionKey.OP_READ | SelectionKey.OP_WRITE)

    protected def readMessage(observer: Observer[ByteBuffer] =
                              notificationObserver): Unit =
        if (channel.isOpen) {
            if (observer != null) {
                requestBroker.readReply(observer)
            } else {
                requestBroker.readReply()
            }
        }

    @throws[IOException]
    @throws[InterruptedException]
    @throws[ExecutionException]
    def start(): Unit = try {
        startReadThread(channel) {
            requestBroker.readReply()
        }
    } catch {
        case ex: IOException => try {
            stop()
        } catch {
            case _: Exception => throw ex
        }
    }

    def stop(): Unit = {
        logger.info(s"Stopping rtnetlink connection: $name")
        stopReadThread(channel)
        if (notificationObserver != null) {
            notificationObserver.onCompleted()
        }
    }
}