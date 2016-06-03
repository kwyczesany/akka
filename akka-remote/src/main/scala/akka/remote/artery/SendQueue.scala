/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.Queue
import akka.stream.stage.GraphStage
import akka.stream.stage.OutHandler
import akka.stream.Attributes
import akka.stream.Outlet
import akka.stream.SourceShape
import akka.stream.stage.GraphStageLogic
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue
import akka.stream.stage.GraphStageWithMaterializedValue
import org.agrona.concurrent.ManyToOneConcurrentLinkedQueueTail
import org.agrona.concurrent.ManyToOneConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.concurrent.Promise

/**
 * INTERNAL API
 */
private[remote] object SendQueue {
  trait QueueValue[T] {
    def inject(queue: Queue[T]): Unit
    def offer(message: T): Boolean
  }

  private trait WakeupSignal {
    def wakeup(): Unit
  }
}

/**
 * INTERNAL API
 */
private[remote] final class SendQueue[T] extends GraphStageWithMaterializedValue[SourceShape[T], SendQueue.QueueValue[T]] {
  import SendQueue._

  val out: Outlet[T] = Outlet("SendQueue.out")
  override val shape: SourceShape[T] = SourceShape(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, QueueValue[T]) = {
    @volatile var needWakeup = false
    @volatile var producerQueue: Queue[T] = null
    val queuePromise = Promise[Queue[T]]()
    // TODO perhaps try similar with ManyToOneConcurrentLinkedQueue or AbstractNodeQueue

    val logic = new GraphStageLogic(shape) with OutHandler with WakeupSignal {

      // using a local field for the consumer side of queue to avoid volatile access
      private var consumerQueue: Queue[T] = null

      private val wakeupCallback = getAsyncCallback[Unit] { _ ⇒
        if (isAvailable(out))
          tryPush()
      }

      override def preStart(): Unit = {
        implicit val ec = materializer.executionContext
        queuePromise.future.foreach(getAsyncCallback[Queue[T]] { q ⇒
          consumerQueue = q
          needWakeup = true
          if (isAvailable(out))
            tryPush()
        }.invoke)
      }

      override def onPull(): Unit = {
        if (consumerQueue ne null)
          tryPush()
      }

      @tailrec private def tryPush(firstAttempt: Boolean = true): Unit = {
        consumerQueue.poll() match {
          case null ⇒
            needWakeup = true
            // additional poll() to grab any elements that might missed the needWakeup
            // and have been enqueued just after it
            if (firstAttempt)
              tryPush(firstAttempt = false)
          case elem ⇒
            needWakeup = false // there will be another onPull
            push(out, elem)
        }
      }

      // external call
      override def wakeup(): Unit = {
        wakeupCallback.invoke(())
      }

      override def postStop(): Unit = {
        if (consumerQueue ne null)
          consumerQueue.clear()
        super.postStop()
      }

      setHandler(out, this)
    }

    val queueValue = new QueueValue[T] {
      override def inject(q: Queue[T]): Unit = {
        producerQueue = q
        queuePromise.success(q)
      }

      override def offer(message: T): Boolean = {
        val q = producerQueue
        if (q eq null) throw new IllegalStateException("offer not allowed before injecting the queue")
        val result = q.offer(message)
        if (result && needWakeup)
          logic.wakeup()
        result
      }
    }

    (logic, queueValue)

  }
}
