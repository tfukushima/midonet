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

package org.midonet.odp.test

import scala.concurrent.{Future, Promise}
import scala.sys.process._

// import org.slf4j.{Logger, LoggerFactory}
import rx.Observer

import org.midonet.netlink.Netlink.Address
import org.midonet.netlink.rtnetlink.{Link, RtnetlinkConnection}
import org.midonet.netlink.{BufferPool, Netlink, NetlinkProtocol}
import org.midonet.odp.util.TapWrapper
import org.midonet.util.IntegrationTests.TestSuite
import org.midonet.util.concurrent.SystemNanoClock

object UnexpectedResultException extends Exception

object RtnetlinkTest {
    val OK = "ok"
}

trait RtnetlinkTest {
    import RtnetlinkTest._

    val conn: RtnetlinkConnection
    // The length of a tap name should be less than 15
    val tapName = "rtnetlink_test"

    var tap: TapWrapper = null

    def start(): Unit = {
        tap = new TapWrapper(tapName, true)
        tap.up()
    }

    def end(): Unit = {
        tap.down()
        tap.remove()
    }

    // def testObserver[T, U](observer: Observer[T], promise: Promise[U]): Observer[T] = {
    // }

    def listLinkNumberTest: (String, Future[String]) = {
        val desc = """|the number of listing lists should equal to the result of
                      |ip link list`""".stripMargin
        val promise = Promise[String]()
        val obs = new Observer[Set[Link]] {
            override def onCompleted(): Unit = { promise.success(OK) }
            override def onError(t: Throwable): Unit = {
                promise.failure(t)
            }
            override def onNext(links: Set[Link]): Unit = try {
                val ipLinkNum = ("ip link list" #| "wc -l" !!).trim.toInt / 2
                if (links.size != ipLinkNum) {
                    promise.failure(UnexpectedResultException)
                }
            } catch {
                case t: Throwable => promise.failure(t)
            }
        }
        conn.linksList(obs)
        conn.requestBroker.readReply()

        (desc, promise.future)
    }

    def showLinkTest: (String, Future[String]) = {
        val desc = """|the interface id should be identical to the result of `ip
                      |link show`.""".stripMargin
        val promise = Promise[String]()
        val ifIndex = (s"ip link show $tapName" #|
            "head -n 1" #|
            "cut -b 1,2,3" !!).replace(":", "").trim.toInt
        val obs = new Observer[Link] {
            override def onCompleted(): Unit = { promise.success(OK) }
            override def onError(t: Throwable): Unit = {
                promise.failure(t)
            }
            override def onNext(link: Link): Unit = try {
                if (ifIndex != link.ifi.ifi_index) {
                    promise.failure(UnexpectedResultException)
                }
            } catch {
                case t: Throwable => promise.failure(t)
            }
        }

        conn.linksGet(ifIndex, obs)
        conn.requestBroker.readReply()

        (desc, promise.future)
    }

    def linkTests: TestSuite = Seq(listLinkNumberTest, showLinkTest)
}

class RtnetlinkIntegrationTestBase extends RtnetlinkTest {
    import org.midonet.util.IntegrationTests._

    // val log: Logger = LoggerFactory.getLogger(classOf[RtnetlinkIntegrationTestBase])
    val sendPool = new BufferPool(10, 20, 1024)
    val channel = Netlink.selectorProvider.openNetlinkSocketChannel(
        NetlinkProtocol.NETLINK_ROUTE)

    {
        channel.connect(new Address(0))
    }

    override val conn: RtnetlinkConnection =
        new RtnetlinkConnection(channel, sendPool, new SystemNanoClock)

    def run(): Boolean = {
        var passed = true
        try {
            start()

            passed = printReport(runSuite(linkTests))
        } finally {
            end()
        }
        passed
    }

    def main(args: Array[String]): Unit = {
        val status = run()
        System.exit(if (status) 0 else 1)
    }
}

object RtnetlinkIntegrationTest extends RtnetlinkIntegrationTestBase
