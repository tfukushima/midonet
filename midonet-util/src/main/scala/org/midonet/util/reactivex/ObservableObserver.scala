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

package org.midonet.util.reactivex

import rx.{Observable, Observer}

object ObservableObserver {
    def apply[T](observable: Observable[T]) =
        new ObservableObserver[T](observable)
}

class ObservableObserver[T](observable: Observable[T]) extends Observer[T] {
    @volatile private var completed: Boolean = false

    observable.subscribe(this)

    def isCompleted(): Boolean = completed
    override def onCompleted(): Unit = {
        completed = true
    }
    override def onError(t: Throwable): Unit = {
        completed = true
    }
    override def onNext(r: T): Unit = {}
}
