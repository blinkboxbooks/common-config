package com.blinkbox.books.logging

import java.util.concurrent.{ArrayBlockingQueue, Executors, ThreadPoolExecutor, TimeUnit}

import com.blinkbox.books.config.ThreadPoolConfig
import com.blinkbox.books.metrics.{InstrumentedThreadPoolExecutor, NamedThreadFactory}
import com.codahale.metrics.MetricRegistry
import org.slf4j.MDC

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

/**
 * Contains factory methods for creating diagnostic execution contexts.
 */
object DiagnosticExecutionContext {
  def apply(delegate: ExecutionContext): ExecutionContextExecutor = new DiagnosticExecutionContext(delegate)

  def apply(config: ThreadPoolConfig): ExecutionContextExecutor = {
    val executor = newThreadPoolExecutor(config, None)
    DiagnosticExecutionContext(ExecutionContext.fromExecutor(executor))
  }

  def apply(config: ThreadPoolConfig, registry: MetricRegistry, name: String): ExecutionContextExecutor = {
    val executor = new InstrumentedThreadPoolExecutor(newThreadPoolExecutor(config, Some(name)), registry, name)
    DiagnosticExecutionContext(ExecutionContext.fromExecutor(executor))
  }

  private def newThreadPoolExecutor(config: ThreadPoolConfig, name: Option[String]): ThreadPoolExecutor = {
    val factory = name.fold(Executors.defaultThreadFactory)(new NamedThreadFactory(Executors.defaultThreadFactory, _))
    val executor = new ThreadPoolExecutor(
      config.corePoolSize,
      config.maxPoolSize,
      config.keepAliveTime.toMillis,
      TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue[Runnable](config.queueSize),
      factory)
    executor.prestartAllCoreThreads()
    executor
  }
}

/**
 * A wrapper around execution contexts that copies the Mapped Diagnostic Context (MDC) between threads
 * and sets/resets it around execution of runnables.
 *
 * When wrapping an execution context in a `DiagnosticExecutionContext` you should ensure that any other
 * execution contexts that are subsequently used are also wrapped, otherwise the results can be strange
 * and unpredictable.
 *
 * If using this from inside an actor, then ensure that the actor extends `DiagnosticActorLogging` and
 * wrap the `ActorRefFactory` dispatcher execution context. This ensures that the MDC is correctly managed
 * around the receive function and is flowed to spawned futures, for example:
 *
 * {{{
 * class MyActor extends Actor with DiagnosticActorLogging {
 *   implicit val executionContext = DiagnosticExecutionContext(actorRefFactory.dispatcher)
 * }
 * }}}
 *
 * @param delegate The execution context to delegate running to.
 */
class DiagnosticExecutionContext(delegate: ExecutionContext) extends ExecutionContextExecutor {
  override def prepare(): ExecutionContext = new ChainedDiagnosticExecutionContext(delegate.prepare())
  override def execute(runnable: Runnable) = unsupported
  override def reportFailure(t: Throwable) = unsupported
  private def unsupported = throw new UnsupportedOperationException("Use prepare() to obtain a valid execution context.")
}

/**
 * Execution context wrapper that captures the MDC state of a thread at the point of creation.
 * @param delegate The execution context to delegate running to.
 */
private class ChainedDiagnosticExecutionContext(delegate: ExecutionContext) extends ExecutionContextExecutor {
  private val capturedMDC = MDC.getCopyOfContextMap

  override def execute(runnable: Runnable) = delegate.execute(new Runnable {
    def run(): Unit = {
      val originalMDC = MDC.getCopyOfContextMap
      setMDC(capturedMDC)
      try runnable.run()
      finally setMDC(originalMDC)
    }
  })

  override def reportFailure(t: Throwable) = delegate.reportFailure(t)

  private def setMDC(mdc: java.util.Map[String, String]): Unit = if (mdc == null) MDC.clear() else MDC.setContextMap(mdc)
}