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

package org.midonet.midolman.host.scanner.test

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.sys.process._

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import rx.Observer

import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.{DefaultInterfaceScanner, InterfaceScanner}
import org.midonet.odp.util.TapWrapper
import org.midonet.packets.IPv4Addr

import org.midonet.util.IntegrationTests._
import org.midonet.util.concurrent.NanoClock
import org.midonet.util.functors._

object InterfaceScannerIntegrationTest {
    val TestIfName = "if-scanner-test"
    val TestIpAddr = "192.168.142.42"
}

trait InterfaceScannerIntegrationTest {
    import InterfaceScannerIntegrationTest._

    val scanner: InterfaceScanner
    val logger: Logger
    private var interfaceDescriptions: Set[InterfaceDescription] = Set.empty

    def start(): Unit = {
        scanner.start()
        scanner.subscribe(new Observer[Set[InterfaceDescription]] {
            override def onCompleted(): Unit =
                logger.debug("notification observer is completed.")
            override def onError(t: Throwable): Unit =
                logger.error(s"notification observer got the error $t")
            override def onNext(ifDescs: Set[InterfaceDescription]): Unit = {
                logger.debug(s"notification observer got the ifDescs: $ifDescs")
                interfaceDescriptions = ifDescs
            }
        })
    }

    def stop(): Unit = {
        scanner.stop()
    }

    def newLinkTest: Test = {
        val desc = """Creating a new link triggeres the notification
                   """.stripMargin.replaceAll("\n", " ")
        implicit val promise = Promise[String]()

        val originalIfDescSize: Int = interfaceDescriptions.size

        scanner.subscribe(new Observer[Set[InterfaceDescription]] {
            private var initialScanned = false
            val obs = TestObserver { ifDescs: Set[InterfaceDescription] =>
                ifDescs.size == (originalIfDescSize + 1) &&
                    ifDescs.exists(ifDesc => ifDesc.getName == TestIfName)
            }
            override def onCompleted(): Unit = obs.onCompleted()
            override def onError(t: Throwable): Unit = obs.onError(t)
            override def onNext(ifDescs: Set[InterfaceDescription]): Unit = {
                if (initialScanned) {
                    obs.onNext(ifDescs)
                    obs.onCompleted()
                } else {
                    initialScanned = true  // Ignore the initial scan.
                }
            }
        })

        val tap = new TapWrapper(TestIfName, true)
        tap.up()

        promise.future.andThen { case _ =>
            tap.down()
            tap.remove()
        }

        (desc, promise.future)
    }
    val NewLinkTest: LazyTest = () => newLinkTest

    def delLinkTest: Test = {
        val desc = """Creating a new link triggeres the notification
                   """.stripMargin.replaceAll("\n", " ")
        implicit val promise = Promise[String]()
        val tap = new TapWrapper(TestIfName, true)
        tap.up()

        val originalIfDescSize: Int = interfaceDescriptions.size

        scanner.subscribe(new Observer[Set[InterfaceDescription]] {
            private var initialScanned = false
            val obs = TestObserver { ifDescs: Set[InterfaceDescription] =>
                ifDescs.size == (originalIfDescSize + 1) &&
                    ifDescs.size == (originalIfDescSize) &&
                    ifDescs.exists(ifDesc => ifDesc.getName == TestIfName)
            }
            override def onCompleted(): Unit = obs.onCompleted()
            override def onError(t: Throwable): Unit = obs.onError(t)
            override def onNext(ifDescs: Set[InterfaceDescription]): Unit = {
                if (initialScanned) {
                    obs.onNext(ifDescs)
                    obs.onCompleted()
                } else {
                    initialScanned = true  // Ignore the initial scan.
                }
            }
        })

        tap.down()
        tap.remove()

        promise.future.andThen { case _ =>
            tap.down()
            tap.remove()
        }

        (desc, promise.future)
    }
    val DelLinkTest: LazyTest = () => delLinkTest

    def newAddrTest: Test = {
        val desc = """Creating a new addr triggeres the notification
                   """.stripMargin.replaceAll("\n", " ")
        implicit val promise = Promise[String]()
        val tap = new TapWrapper(TestIfName, true)
        tap.up()

        val originalIfDescSize: Int = interfaceDescriptions.size

        scanner.subscribe(new Observer[Set[InterfaceDescription]] {
            private var initialScanned = false
            val obs = TestObserver { ifDescs: Set[InterfaceDescription] =>
                ifDescs.exists(ifDesc =>
                    ifDesc.getInetAddresses.contains(
                        IPv4Addr.fromString(TestIpAddr)))
            }
            override def onCompleted(): Unit = obs.onCompleted()
            override def onError(t: Throwable): Unit = obs.onError(t)
            override def onNext(ifDescs: Set[InterfaceDescription]): Unit = {
                if (initialScanned) {
                    obs.onNext(ifDescs)
                    obs.onCompleted()
                } else {
                    initialScanned = true  // Ignore the initial scan.
                }
            }
        })

        if (s"ip a add $TestIpAddr dev $TestIfName".! != 0) {
            promise.failure(TestPrepareException)
        }

        promise.future.andThen { case _ =>
            s"ip address flush dev $TestIfName".!
            tap.down()
            tap.remove()
        }

        (desc, promise.future)
    }
    val NewAddrTest: LazyTest = () => newAddrTest

    def delAddrTest: Test = {
        val desc = """Deleting a new addr triggeres the notification
                   """.stripMargin.replaceAll("\n", " ")
        implicit val promise = Promise[String]()
        val tap = new TapWrapper(TestIfName, true)
        tap.up()

        val originalIfDescSize: Int = interfaceDescriptions.size

        if (s"ip a add $TestIpAddr dev $TestIfName".! != 0) {
            promise.failure(TestPrepareException)
        }

        scanner.subscribe(new Observer[Set[InterfaceDescription]] {
            private var initialScanned = false
            val obs = TestObserver { ifDescs: Set[InterfaceDescription] =>
                !ifDescs.exists(ifDesc =>
                    ifDesc.getInetAddresses.contains(
                        IPv4Addr.fromString(TestIpAddr)))
            }
            override def onCompleted(): Unit = obs.onCompleted()
            override def onError(t: Throwable): Unit = obs.onError(t)
            override def onNext(ifDescs: Set[InterfaceDescription]): Unit = {
                if (initialScanned) {
                    obs.onNext(ifDescs)
                    obs.onCompleted()
                } else {
                    initialScanned = true  // Ignore the initial scan.
                }
            }
        })

        if (s"ip address del $TestIpAddr dev $TestIfName".! != 0) {
            promise.tryFailure(TestPrepareException)
        }

        promise.future.andThen { case _ =>
            tap.down()
            tap.remove()
        }

        (desc, promise.future)
    }
    val DelAddrTest: LazyTest = () => delAddrTest

    val LinkTests: LazyTestSuite = Seq(NewLinkTest, DelLinkTest)
    val AddrTests: LazyTestSuite = Seq(NewAddrTest, DelAddrTest)
}

class DefaultInterfaceScannerIntegrationTest
        extends InterfaceScannerIntegrationTest {
    override val scanner = DefaultInterfaceScanner()
    override val logger: Logger = Logger(LoggerFactory.getLogger(
        classOf[DefaultInterfaceScannerIntegrationTest]))

    def run(): Boolean = {
        var passed = true
        try {
            start()
            passed &= printReport(runLazySuite(LinkTests))
            passed &= printReport(runLazySuite(AddrTests))
        } finally {
            stop()
        }
        passed
    }

    def main(args: Array[String]): Unit =
        System.exit(if (run()) 0 else 1)
}

object DefaultInterfaceScannerIntegrationTest
        extends DefaultInterfaceScannerIntegrationTest