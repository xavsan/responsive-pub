/*
 * Copyright 2024 Responsive Computing, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dev.responsive.kafka.api.async.internals;

import static dev.responsive.kafka.internal.utils.Utils.extractStreamThreadIndex;

import dev.responsive.kafka.api.async.internals.queues.ForwardingQueue;
import dev.responsive.kafka.api.async.internals.queues.ProcessingQueue;
import dev.responsive.kafka.api.async.internals.queues.SchedulingQueue;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.processor.TaskId;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO:
//  1) add an equivalent form for FixedKeyProcessorSupplier and the like
//  2) share single thread pool across all async processors per StreamThread
//  3) make thread pool size configurable
//  6) Should we reuse the same async context or is it ok to construct a new wrapper each #init?
//  7) Need to somehow associate per-record metadata with each input record AND provide
//     thread-safe access to the AsyncProcessorContext (perhaps via a wrapper that
//     designates a single, separate instance of the underlying context for each thread
//     (in the thread pool as well as the StreamThread itself)
/**
 * Threading notes:
 * -Is exclusively owned and accessed by the StreamThread
 * -Coordinates the handoff of records between the StreamThread and AyncThreads
 */
public class AsyncProcessor<KIn, VIn, KOut, VOut> implements Processor<KIn, VIn, KOut, VOut> {

  private static final int THREAD_POOL_SIZE = 10;

  private Logger log = LoggerFactory.getLogger(AsyncProcessor.class);

  private final Processor<KIn, VIn, KOut, VOut> userProcessor;
  private final Map<String, AsyncStoreBuilder<?>> connectedStores;

  // Owned and solely accessed by this StreamThread
  private final SchedulingQueue<KIn, VIn> schedulingQueue = new SchedulingQueue<>();

  // Exclusively written to by this StreamThread (and exclusively read by AsyncThreads)
  private final ProcessingQueue<KIn, VIn> processableRecords = new ProcessingQueue<>();

  // Exclusively read by this StreamThread (and exclusively written to by AsyncThreads)
  private final ForwardingQueue<KOut, VOut> forwardingQueue = new ForwardingQueue<>();

  // Effectively final -- initialized in #init
  private DelayedRecordForwarder<KOut, VOut> recordForwarder;
  private TaskId taskId;
  private AsyncProcessorContext<KOut, VOut> streamThreadContext;
  private AsyncThreadPool threadPool;

  // Note: the constructor will be called from the main application thread (ie the
  // one that creates/starts the KafkaStreams object) so we have to delay the creation
  // of most objects until #init since (a) that will be invoked by the actual
  // StreamThread processing this, and (b) we need the context supplied to init for
  // some of the setup
  public AsyncProcessor(
      final Processor<KIn, VIn, KOut, VOut> userProcessor,
      final Map<String, AsyncStoreBuilder<?>> connectedStores
  ) {
    this.userProcessor = userProcessor;
    this.connectedStores = connectedStores;
  }

  @Override
  public void init(final ProcessorContext<KOut, VOut> context) {
    this.threadPool = new AsyncThreadPool(
        THREAD_POOL_SIZE,
        context,
        extractStreamThreadIndex(Thread.currentThread().getName()),
        processableRecords
    );
    this.taskId = context.taskId();
    this.streamThreadContext = new AsyncProcessorContext<>(context);
    this.recordForwarder = new DelayedRecordForwarder<>(context);

    this.log = new LogContext(String.format(
        "async-processor [%s-%d]", streamThreadContext.currentProcessorName(), taskId.partition()
    )).logger(AsyncProcessor.class);

    // Wrap the context handed to the user in the async router, to ensure that:
    //  a) user calls are delegated to the context owned by the current thread
    //  b) async calls can be intercepted (ie forwards and StateStore reads/writes)
    final AsyncContextRouter<KOut, VOut> userContext =
        new AsyncContextRouter<>(asyncThreadToContext(threadPool));

    userProcessor.init(userContext);
    verifyConnectedStateStores();
  }

  @Override
  public void process(final Record<KIn, VIn> record) {
    schedulingQueue.offer(record);

    // Drain the forwarding queue first to free up any records in the scheduling queue
    // that are blocked on these forwards
    drainForwardingQueue();

    // Drain the scheduling queue by passing any processable records to the async thread pool
    drainSchedulingQueue();
  }

  @Override
  public void close() {
    userProcessor.close();
  }

  /**
   * Block on all pending records to be scheduled, executed, and fully complete processing through
   * the topology, as well as all state store operations to be applied. Called at the beginning of
   * each commit, similar to #flushCache
   */
  public void awaitCompletion() {
    while (!isCompleted()) {
      drainSchedulingQueue();
      drainForwardingQueue();

      // TODO: use a Condition to avoid busy waiting
      try {
        Thread.sleep(1);
      } catch (final InterruptedException e) {
        throw new StreamsException("Interrupted while waiting for async completion", taskId);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static <KOut, VOut> Map<String, AsyncProcessorContext<KOut, VOut>> asyncThreadToContext(
      final AsyncThreadPool threadPool
  ) {
    final Set<String> asyncThreadNames = threadPool.asyncThreadNames();

    final Map<String, AsyncProcessorContext<KOut, VOut>> asyncThreadToContext =
        new HashMap<>(asyncThreadNames.size());

    for (final String asyncThread : asyncThreadNames) {
      asyncThreadToContext.put(
          asyncThread,
          (AsyncProcessorContext<KOut, VOut>) threadPool.asyncContextForThread(asyncThread));
    }

    return asyncThreadToContext;
  }

  private void drainSchedulingQueue() {
    Record<KIn, VIn> nextProcessableRecord = schedulingQueue.poll();
    while (nextProcessableRecord != null) {

      nextProcessableRecord = schedulingQueue.poll();
      // pass all records ready for processing to thread pool

    }
  }

  private void drainForwardingQueue() {
    while (!forwardingQueue.isEmpty()) {
      recordForwarder.forward(forwardingQueue.poll());
    }
  }

  /**
   * @return true iff all records have been fully processed from start to finish
   */
  private boolean isCompleted() {
    return forwardingQueue.isEmpty() && schedulingQueue.isEmpty();
  }

  /**
   * Verify that
   */
  private void verifyConnectedStateStores() {
    final Map<String, AsyncKeyValueStore<?, ?>> actualStores = streamThreadContext.getAllAsyncStores();
    if (actualStores.size() != connectedStores.size()) {
      log.error("Number of connected store names is not equal to the number of stores retrieved "
                    + "via ProcessorContext#getStateStore during initialization. Make sure to pass "
                    + "all state stores used by this processor to the AsyncProcessorSupplier, and "
                    + "they are (all) initialized during the Processor#init call before actual "
                    + "processing begins. Found {} connected store names and {} actual stores used",
                connectedStores.size(), actualStores.keySet().size());
      throw new IllegalStateException("Number of actual stores initialized by this processor does"
                                          + "not match the number of connected store names that were provided to the "
                                          + "AsyncProcessorSupplier");
    } else if (!connectedStores.keySet().containsAll(actualStores.keySet())) {
      log.error("The list of connected store names was not identical to the set of store names "
                    + "that were used to access state stores via the ProcessorContext#getStateStore "
                    + "method during initialization. Double check the list of store names that are "
                    + "being passed in to the AsyncProcessorSupplier, and make sure it aligns with "
                    + "the actual store names being used by the processor itself. "
                    + "Got connectedStoreNames=[{}] and actualStoreNames=[{}]",
                connectedStores.keySet(), actualStores.keySet());
      throw new IllegalStateException("The names of actual stores initialized by this processor do"
                                          + "not match the names of connected stores that were "
                                          + "provided to the AsyncProcessorSupplier");
    }
  }
}