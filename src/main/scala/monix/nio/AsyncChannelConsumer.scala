/*
 * Copyright (c) 2014-2017 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
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

package monix.nio

import java.nio.ByteBuffer

import monix.eval.Callback
import monix.execution.Ack.{ Continue, Stop }
import monix.execution.{ Ack, Cancelable, Scheduler }
import monix.execution.atomic.Atomic
import monix.execution.cancelables.{ AssignableCancelable, SingleAssignCancelable }
import monix.reactive.Consumer
import monix.reactive.observers.Subscriber

import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

private[nio] abstract class AsyncChannelConsumer extends Consumer[Array[Byte], Long] {
  def channel: Option[AsyncChannel]
  def withInitialPosition: Long = 0l
  def init(subscriber: AsyncChannelSubscriber): Future[Unit] = Future.successful(())

  class AsyncChannelSubscriber(consumerCallback: Callback[Long])(implicit val scheduler: Scheduler)
    extends Subscriber[Array[Byte]] { self =>

    private[this] lazy val initFuture = init(self)
    private[this] val callbackCalled = Atomic(false)
    private[this] var position = withInitialPosition

    override def onNext(elem: Array[Byte]): Future[Ack] = {
      def write(): Future[Ack] = {
        val promise = Promise[Ack]()
        channel.foreach { sc =>
          try {
            sc
              .write(ByteBuffer.wrap(elem), position)
              .runAsync(
                new Callback[Int] {
                  override def onError(exc: Throwable) = {
                    closeChannel()
                    sendError(exc)
                    promise.success(Stop)
                  }

                  override def onSuccess(result: Int): Unit = {
                    position += result
                    promise.success(Continue)
                  }
                })
          } catch {
            case NonFatal(ex) =>
              sendError(ex)
              promise.success(Stop)
          }
        }

        promise.future
      }

      if (initFuture.value.isEmpty) {
        initFuture.flatMap(_ => write())
      } else {
        write()
      }
    }

    override def onComplete(): Unit = {
      channel.collect { case sc if sc.closeOnComplete => closeChannel() }
      if (callbackCalled.compareAndSet(expect = false, update = true))
        consumerCallback.onSuccess(position)
    }

    override def onError(ex: Throwable): Unit = {
      closeChannel()
      sendError(ex)
    }

    private[nio] def onCancel(): Unit = {
      callbackCalled.set(true) /* the callback should not be called after cancel */
      closeChannel()
    }

    private[nio] def sendError(t: Throwable) =
      if (callbackCalled.compareAndSet(expect = false, update = true)) {
        scheduler.execute(new Runnable {
          def run() = consumerCallback.onError(t)
        })
      }

    private[nio] final def closeChannel()(implicit scheduler: Scheduler) =
      channel.foreach(_.close().runAsync)
  }

  override def createSubscriber(cb: Callback[Long], s: Scheduler): (Subscriber[Array[Byte]], AssignableCancelable) = {
    val out = new AsyncChannelSubscriber(cb)(s)

    val extraCancelable = Cancelable(() => out.onCancel())
    val conn = SingleAssignCancelable.plusOne(extraCancelable)
    (out, conn)
  }
}
