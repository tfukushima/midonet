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
import java.nio.channels.SelectionKey

import com.typesafe.scalalogging.Logger
import rx.Observer
import rx.subjects.Subject

object SelectorBasedNetlinkChannelReader {
    val SELECTOR_TIMEOUT = 0
}

/**
 * SelectorBasedNetlinkchannelReader provides the methods to start and stop a
 * thread to read Netlink replies from the kernel through NetlinkChannel.
 * You can pass different channels to the methods and start or stop read threads
 * for each channel.
 */
trait SelectorBasedNetlinkChannelReader {
    import SelectorBasedNetlinkChannelReader._

    protected val log: Logger
    val pid: Int
    protected val name = this.getClass.getName + pid

    private def startSelectorThread(channel: NetlinkChannel,
                                    threadName: String = name)
                                   (closure: SelectionKey => Unit): Unit = {
        val thread = new Thread(threadName) {
            override def run(): Unit = try {
                val selector = channel.selector
                while (channel.isOpen) {
                    val readyChannel = selector.select(SELECTOR_TIMEOUT)
                    if (readyChannel > 0) {
                        val keys = selector.selectedKeys
                        val iter = keys.iterator()
                        while (iter.hasNext) {
                            val key: SelectionKey = iter.next()
                            closure(key)
                        }
                        keys.clear()
                    }
                }
            } catch {
                case ex: InterruptedException =>
                    log.error(s"$ex on netlink channel, STOPPTING", ex)
                case ex: Exception =>
                    log.error(s"$ex on netlink channel, ABORTING", ex)
                    System.exit(2)
            }
        }

        thread.setName(threadName)
        thread.setDaemon(true)
        thread.start()
    }

    protected def startReadAndWriteThread(channel: NetlinkChannel,
                                          threadName: String = name)
                                         (readClosure: => Unit)
                                         (writeClosure: => Unit): Unit = {
        startSelectorThread(channel, threadName) { key: SelectionKey =>
            if (key.isWritable) {
                writeClosure
            }
            if (key.isReadable) {
                readClosure
            }
        }
        log.info("Starting netlink read and write thread: {}", threadName)
    }

    protected def startReadThread(channel: NetlinkChannel,
                                  threadName: String = name)
                                 (readClosure: => Unit): Unit = {
        startSelectorThread(channel, threadName) { key: SelectionKey =>
            if (key.isReadable) {
                readClosure
            }
        }
        log.info("Starting netlink read thread: {}", threadName)
    }

    protected def stopReadThread(channel: NetlinkChannel): Unit = {
        channel.selector.wakeup()
        channel.close()
    }
}

/**
 * NetlinkNotificationReader provides the utilities for reading Netlink
 * notification messages from the kernel. The derived class MUST define
 * overridden notificationChannel as a lazy val because it's used in the
 * constructor.
 */
trait NetlinkNotificationReader {
    protected val log: Logger
    protected val pid: Int
    protected val name: String
    // notificationChannel is used right after the definition. So the users MUST
    // override notifiationChannel as a lazy val.
    protected val notificationChannel: NetlinkChannel
    protected val notificationObserver: Observer[ByteBuffer]
    protected val netlinkReadBufSize = NetlinkUtil.NETLINK_READ_BUF_SIZE

    protected val notificationReadBuf =
        BytesUtil.instance.allocateDirect(netlinkReadBufSize)
    protected lazy val notificationReader: NetlinkReader =
        new NetlinkReader(notificationChannel)
    private lazy val headerSize: Int = notificationChannel.getProtocol match {
        case NetlinkProtocol.NETLINK_GENERIC =>
            NetlinkMessage.GENL_HEADER_SIZE
        case _ =>
            NetlinkMessage.HEADER_SIZE
    }

    protected val notificationReadThread = new Thread(s"$name-notification") {
        override def run(): Unit = try {
            while (notificationChannel.isOpen) {
                val nbytes = notificationReader.read(notificationReadBuf)
                notificationReadBuf.flip()
                if (notificationReadBuf.remaining() >= headerSize) {
                    val nlType = notificationReadBuf.getShort(
                        NetlinkMessage.NLMSG_TYPE_OFFSET)
                    val size = notificationReadBuf.getInt(
                        NetlinkMessage.NLMSG_LEN_OFFSET)
                    if (nlType >= NLMessageType.NLMSG_MIN_TYPE &&
                        size >= headerSize) {
                        val oldLimit = notificationReadBuf.limit()
                        notificationReadBuf.limit(size)
                        notificationReadBuf.position(0)
                        notificationObserver.onNext(notificationReadBuf)
                        notificationReadBuf.limit(oldLimit)
                    }
                }
                notificationReadBuf.clear()
            }
        } catch {
            case ex: InterruptedException =>
                log.info(s"$ex on rtnetlink notification channel, STOPPING",
                    ex)
            case ex: Exception =>
                log.error(s"$ex on rtnetlink notification channel, ABORTING",
                    ex)
        }
    }

    protected
    def handleNotification(notificationObserver: Observer[ByteBuffer],
                           start: Int, size: Int): Unit = {
        val nlType = notificationReadBuf.getShort(
            start + NetlinkMessage.NLMSG_TYPE_OFFSET)
        if (nlType >= NLMessageType.NLMSG_MIN_TYPE && size >= headerSize) {
            val oldLimit = notificationReadBuf.limit()
            notificationReadBuf.limit(start + size)
            notificationReadBuf.position(start)
            notificationObserver.onNext(notificationReadBuf)
            notificationReadBuf.limit(oldLimit)
        }
    }

    protected
    def readNotifications(notificationObserver: Observer[ByteBuffer]): Int =
        try {
            val nbytes = notificationReader.read(notificationReadBuf)
            notificationReadBuf.flip()
            var start = 0
            while (notificationReadBuf.remaining() >= headerSize) {
                val size = notificationReadBuf.getInt(
                    start + NetlinkMessage.NLMSG_LEN_OFFSET)
                handleNotification(notificationObserver, start, size)
                start += size
                notificationReadBuf.position(start)
            }
            nbytes
        } catch {
            case e: Exception =>
                log.error(s"Error occurred during reading the notification: $e")
                notificationObserver.onError(e)
                0
        } finally {
            notificationReadBuf.clear()
        }
}
