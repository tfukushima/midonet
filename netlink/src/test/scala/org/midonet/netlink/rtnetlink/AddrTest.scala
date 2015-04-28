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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers, OneInstancePerTest}
import org.slf4j.{Logger, LoggerFactory}

import org.midonet.netlink.{BytesUtil, NetlinkUtil}

object AddrTest {
    val RawAddr: Array[Byte] = Array(
        0x44, 0x00, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00, 0x74, 0x4e, 0x3e, 0x55, 0xbe, 0x1a, 0x00, 0x00,
        0x02, 0x20, 0x80, 0x00, 0xdd, 0x01, 0x00, 0x00, 0x08, 0x00, 0x01, 0x00, 0xc0, 0xa8, 0x8e, 0x2a,
        0x08, 0x00, 0x02, 0x00, 0xc0, 0xa8, 0x8e, 0x2a, 0x08, 0x00, 0x03, 0x00, 0x66, 0x6f, 0x6f, 0x00,
        0x14, 0x00, 0x06, 0x00, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xb1, 0x7b, 0x42, 0x15,
        0xb1, 0x7b, 0x42, 0x15
    ).map(_.toByte)
    // Convert the byte array to the native endian.
    val AddrBytes = BytesUtil.instance.allocate(RawAddr.length)
    AddrBytes.put(RawAddr)
    AddrBytes.flip()
}

@RunWith(classOf[JUnitRunner])
class AddrTest extends FeatureSpec
               with BeforeAndAfter
               with Matchers
               with OneInstancePerTest {
    import AddrTest._

    val log: Logger = LoggerFactory.getLogger(classOf[LinkTest])

    feature("Address deserializer") {
        scenario("it should be able to deserialize the address") {
            NetlinkUtil.readNetlinkHeader(AddrBytes)
            val deserialized = Addr.buildFrom(AddrBytes)
        }
    }
}
