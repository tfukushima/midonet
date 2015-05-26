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

package org.midonet.midolman.host.scanner

import scala.concurrent.Await
import scala.concurrent.duration._

import com.typesafe.scalalogging.Logger
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{BeforeAndAfterAll, FeatureSpec}
import org.slf4j.LoggerFactory

import org.midonet.midolman.host.scanner.test.InterfaceScannerIntegrationTest
import org.midonet.util.IntegrationTests.{LazyTest, LazyTestSuite}

@RunWith(classOf[JUnitRunner])
class InterfaceScannerTest extends FeatureSpec
                           with BeforeAndAfterAll
                           with ShouldMatchers
                           with InterfaceScannerIntegrationTest {
    override val scanner: InterfaceScanner = DefaultInterfaceScanner()
    override val logger: Logger =
        Logger(LoggerFactory.getLogger(classOf[InterfaceScannerTest]))

    override def beforeAll(): Unit = start()
    override def afterAll(): Unit = stop()

    private def checkResults(lazyTests: LazyTestSuite): Unit =
        lazyTests.foreach { lazyTest: LazyTest =>
            val (desc, test) = lazyTest()
            scenario(desc) {
                Await.result(test, 2.seconds)
            }
        }

    feature("DefaultInterfaceScanner integration test") {
        checkResults(LinkTests)
        checkResults(AddrTests)
    }
}
