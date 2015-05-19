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

class BlockingRtnetlinkConnection(channel: NetlinkChannel,
                                  maxPendingRequests: Int,
                                  maxRequestSize: Int,
                                  clock: NanoClock)
        extends RtnetlinkConnection(channel, maxPendingRequests,
            maxRequestSize, clock) {

    lazy val name = this.getClass.getName + pid

    private val thread = new Thread(name) {
        override def run(): Unit = {
            while (channel.isOpen) {
                try {
                    if (requestBroker.hasRequestsToWrite) {
                        requestBroker.writePublishedRequests()
                    }
                    requestBroker.readReply()
                } catch { case t: Throwable =>
                    log.error("Error while writing and reading Netlink " +
                        "messages", t)
                }
            }
        }
    }

    def start(): Unit = {
        thread.setDaemon(true)
        thread.start()
    }

    def stop(): Unit = {
        log.info("Stopping rtnetlink connection")
        channel.close()
        thread.interrupt()
    }
}
