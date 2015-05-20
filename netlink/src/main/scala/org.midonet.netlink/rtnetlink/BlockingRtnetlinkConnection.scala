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

import org.midonet.netlink.NetlinkChannel
import org.midonet.util.concurrent.NanoClock

class BlockingRtnetlinkConnection(override val channel: NetlinkChannel,
                                  maxPendingRequests: Int,
                                  maxRequestSize: Int,
                                  clock: NanoClock)
    extends RtnetlinkConnection(channel: NetlinkChannel,
        maxPendingRequests: Int,
        maxRequestSize: Int,
        clock: NanoClock) {

    protected lazy val name = this.getClass.getName + pid

    private val rtnetlinkReadThread = new Thread(name) {
        override def run(): Unit = try {
            while (channel.isOpen) {
                requestBroker.readReply()
            }
        } catch {
            case ex: InterruptedException =>
                log.info(s"$ex on rtnetlink channel, STOPPING", ex)
            case ex: Exception =>
                log.error(s"$ex on rtnetlink channel, ABORTING", ex)
        }
    }

    def start(): Unit = try {
        rtnetlinkReadThread.setDaemon(true)
        rtnetlinkReadThread.start()
    } catch {
        case ex: Exception =>
            log.error("Error happened on reading rtnetlink messages", ex)
    }

    def stop(): Unit = {
        channel.close()
        rtnetlinkReadThread.interrupt()
    }
}
