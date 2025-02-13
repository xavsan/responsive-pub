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

import static dev.responsive.kafka.api.config.ResponsiveConfig.ASYNC_FLUSH_INTERVAL_MS_CONFIG;
import static dev.responsive.kafka.internal.config.InternalSessionConfigs.loadAsyncThreadPoolRegistry;

import dev.responsive.kafka.api.async.AsyncProcessorSupplier;
import dev.responsive.kafka.api.async.internals.contexts.AsyncUserProcessorContext;
import dev.responsive.kafka.api.async.internals.contexts.StreamThreadProcessorContext;
import dev.responsive.kafka.api.async.internals.events.AsyncEvent;
import dev.responsive.kafka.api.async.internals.events.DelayedForward;
import dev.responsive.kafka.api.async.internals.events.DelayedWrite;
import dev.responsive.kafka.api.async.internals.queues.FinalizingQueue;
import dev.responsive.kafka.api.async.internals.queues.SchedulingQueue;
import dev.responsive.kafka.api.async.internals.stores.AbstractAsyncStoreBuilder;
import dev.responsive.kafka.api.async.internals.stores.AsyncKeyValueStore;
import dev.responsive.kafka.api.async.internals.stores.StreamThreadFlushListeners.AsyncFlushListener;
import dev.responsive.kafka.api.config.ResponsiveConfig;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.processor.Cancellable;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.TaskId;
import org.apache.kafka.streams.processor.api.FixedKeyProcessor;
import org.apache.kafka.streams.processor.api.FixedKeyProcessorContext;
import org.apache.kafka.streams.processor.api.FixedKeyRecord;
import org.apache.kafka.streams.processor.api.ProcessingContext;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.internals.InternalProcessorContext;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.slf4j.Logger;

/**
 * Threading notes:
 * -Is exclusively owned and accessed by the StreamThread
 * -Coordinates the handoff of records between the StreamThread and AyncThreads
 * -The starting and ending point of all async events -- see {@link AsyncEvent}
 */
public class AsyncProcessor<KIn, VIn, KOut, VOut>
    implements Processor<KIn, VIn, KOut, VOut>, FixedKeyProcessor<KIn, VIn, VOut> {

  // Exactly one of these is non-null and the other is null
  private final Processor<KIn, VIn, KOut, VOut> userProcessor;
  private final FixedKeyProcessor<KIn, VIn, VOut> userFixedKeyProcessor;

  private final Map<String, AbstractAsyncStoreBuilder<?, ?, ?>> connectedStoreBuilders;

  // Owned and solely accessed by this StreamThread, simply keeps track of the events
  // that are waiting to be scheduled or are currently "in flight", ie all events
  // for which we received an input record but have not yet finished processing either
  // the input record itself, or any output records that were a side effect of this
  // input record (such as records forwarded to the context or written to a state
  // store during #process).
  // An event is placed into this set when the corresponding record is first passed
  // in to this AsyncProcessor, and is removed from the set when it reaches the
  // DONE state and has completed all input and output execution in full.
  // The StreamThread must wait until this set is empty before proceeding
  // with an offset commit
  private final Set<AsyncEvent> pendingEvents = new HashSet<>();

  // Everything below this line is effectively final and just has to be initialized in #init
  // TODO: extract into class that allows us to make these final, eg an InitializedAsyncProcessor
  //  that doesn't get created until #init is invoked on this processor
  private String logPrefix;
  private Logger log;

  private String streamThreadName;
  private String asyncProcessorName;
  private TaskId taskId;

  private AsyncThreadPool threadPool;
  private FinalizingQueue finalizingQueue;
  private SchedulingQueue<KIn> schedulingQueue;

  private Cancellable punctuator;

  // the context passed to us in init, ie the one created for this task and owned by Kafka Streams
  private ProcessingContext taskContext;

  // the async context owned by the StreamThread that is running this processor/task
  private StreamThreadProcessorContext<KOut, VOut> streamThreadContext;

  // Wrap the context handed to the user in the async router, to ensure that
  // subsequent calls to processor context APIs made within the user's #process
  // implementation will be routed to the specific async context corresponding
  // to the thread on which the call was made.
  // Will route to the streamThreadContext when inside the user's #init or #close
  // Will route to the asyncThreadContext belonging to the currently executing
  //   AsyncThread in between those, ie when the user's #process is invoked
  private AsyncUserProcessorContext<KOut, VOut> userContext;

  public static <KIn, VIn, KOut, VOut> AsyncProcessor<KIn, VIn, KOut, VOut> createAsyncProcessor(
      final Processor<KIn, VIn, KOut, VOut> userProcessor,
      final Map<String, AbstractAsyncStoreBuilder<?, ?, ?>> connectedStoreBuilders
  ) {
    return new AsyncProcessor<>(userProcessor, null, connectedStoreBuilders);
  }

  public static <KIn, VIn, VOut> AsyncProcessor<KIn, VIn, KIn, VOut> createAsyncFixedKeyProcessor(
      final FixedKeyProcessor<KIn, VIn, VOut> userProcessor,
      final Map<String, AbstractAsyncStoreBuilder<?, ?, ?>> connectedStoreBuilders
  ) {
    return new AsyncProcessor<>(null, userProcessor, connectedStoreBuilders);
  }

  // Note: the constructor will be called from the main application thread (ie the
  // one that creates/starts the KafkaStreams object) so we have to delay the creation
  // of most objects until #init since (a) that will be invoked by the actual
  // StreamThread processing this, and (b) we need the context supplied to init for
  // some of the setup
  private AsyncProcessor(
      final Processor<KIn, VIn, KOut, VOut> userProcessor,
      final FixedKeyProcessor<KIn, VIn, VOut> userFixedKeyProcessor,
      final Map<String, AbstractAsyncStoreBuilder<?, ?, ?>> connectedStoreBuilders
  ) {
    this.userProcessor = userProcessor;
    this.userFixedKeyProcessor = userFixedKeyProcessor;
    this.connectedStoreBuilders = connectedStoreBuilders;

    if (userProcessor == null && userFixedKeyProcessor == null) {
      throw new IllegalStateException("Both the Processor and FixedKeyProcessor were null");
    } else if (userProcessor != null && userFixedKeyProcessor != null) {
      throw new IllegalStateException("Both the Processor and FixedKeyProcessor were non-null");
    }
  }

  @Override
  public void init(final ProcessorContext<KOut, VOut> context) {

    initFields((InternalProcessorContext<KOut, VOut>) context);

    userProcessor.init(userContext);

    completeInitialization();
  }

  // Note: we have to cast and suppress warnings in this version of #init but
  // not the other due to the KOut parameter being squashed into KIn in the
  // fixed-key version of the processor. However, we know this cast is safe,
  // since by definition KIn and KOut are the same type
  @SuppressWarnings("unchecked")
  @Override
  public void init(final FixedKeyProcessorContext<KIn, VOut> context) {

    initFields((InternalProcessorContext<KOut, VOut>) context);

    userFixedKeyProcessor.init((FixedKeyProcessorContext<KIn, VOut>) userContext);

    completeInitialization();
  }

  /**
   * Performs the first half of initialization by setting all the class fields
   * that have to wait for the context to be passed in to #init to be initialized.
   * Initialization itself is broken up into two stages, field initialization and
   *
   */
  private void initFields(
      final InternalProcessorContext<KOut, VOut> internalContext
  ) {
    this.taskContext = internalContext;

    this.streamThreadName = Thread.currentThread().getName();
    this.taskId = internalContext.taskId();
    this.asyncProcessorName = internalContext.currentNode().name();

    this.logPrefix = String.format(
        "stream-thread [%s] %s[%d] ",
        streamThreadName, asyncProcessorName, taskId.partition()
    );
    this.log = new LogContext(logPrefix).logger(AsyncProcessor.class);
    this.userContext = new AsyncUserProcessorContext<>(
        streamThreadName,
        internalContext,
        logPrefix
    );
    this.streamThreadContext = new StreamThreadProcessorContext<>(
        logPrefix,
        internalContext,
        userContext
    );
    userContext.setDelegateForStreamThread(streamThreadContext);

    this.threadPool = getAsyncThreadPool(taskContext, streamThreadName);

    final ResponsiveConfig configs = ResponsiveConfig.responsiveConfig(userContext.appConfigs());
    final long punctuationInterval = configs.getLong(ASYNC_FLUSH_INTERVAL_MS_CONFIG);
    final int maxEventsPerKey = configs.getInt(ResponsiveConfig.ASYNC_MAX_EVENTS_PER_KEY_CONFIG);

    this.schedulingQueue = new SchedulingQueue<>(logPrefix, maxEventsPerKey);
    this.finalizingQueue = new FinalizingQueue(logPrefix);


    this.punctuator = taskContext.schedule(
        Duration.ofMillis(punctuationInterval),
        PunctuationType.WALL_CLOCK_TIME,
        ts -> executeAvailableEvents()
    );

  }

  private void completeInitialization() {
    final Map<String, AsyncKeyValueStore<?, ?>> accessedStores =
        streamThreadContext.getAllAsyncStores();

    verifyConnectedStateStores(accessedStores, connectedStoreBuilders);

    registerFlushListenerForStoreBuilders(
        streamThreadName,
        taskId.partition(),
        connectedStoreBuilders.values(),
        this::flushAndAwaitPendingEvents
    );
  }

  @Override
  public void process(final Record<KIn, VIn> record) {
    final AsyncEvent newEvent = new AsyncEvent(
        logPrefix,
        record,
        taskId.partition(),
        extractRecordContext(taskContext),
        taskContext.currentStreamTimeMs(),
        taskContext.currentSystemTimeMs(),
        () -> userProcessor.process(record)
    );

    processNewAsyncEvent(newEvent);
  }

  @Override
  public void process(final FixedKeyRecord<KIn, VIn> record) {
    final AsyncEvent newEvent = new AsyncEvent(
        logPrefix,
        record,
        taskId.partition(),
        extractRecordContext(taskContext),
        taskContext.currentStreamTimeMs(),
        taskContext.currentSystemTimeMs(),
        () -> userFixedKeyProcessor.process(record)
    );

    processNewAsyncEvent(newEvent);
  }

  private void processNewAsyncEvent(final AsyncEvent event) {
    pendingEvents.add(event);

    final KIn eventKey = event.inputRecordKey();
    if (schedulingQueue.keyQueueIsFull(eventKey)) {
      backOffSchedulingQueueForKey(eventKey);
    }

    schedulingQueue.offer(event);
    executeAvailableEvents();
  }

  @SuppressWarnings("unchecked")
  private ProcessorRecordContext extractRecordContext(final ProcessingContext context) {
    // Alternatively we could use the ProcessingContext#recordMetadata and then cast that
    // to a ProcessorRecordContext, but either way a cast somewhere is unavoidable
    return ((InternalProcessorContext<KOut, VOut>) context).recordContext();
  }

  @Override
  public void close() {
    if (!pendingEvents.isEmpty()) {
      // This doesn't necessarily indicate an issue, it just should only ever
      // happen if the task is closed dirty, but unfortunately we can't tell
      // from here whether that was the case. Log a warning here so that it's
      // possible to determine whether something went wrong or not by looking
      // at the complete logs for the task/thread
      log.warn("Closing async processor with {} in-flight events, this should only "
                   + "happen if the task was shut down dirty and not flushed/committed "
                   + "prior to being closed", pendingEvents.size());
    }

    punctuator.cancel();
    threadPool.removeProcessor(asyncProcessorName, taskId.partition());
    unregisterFlushListenerForStoreBuilders(
        streamThreadName,
        taskId.partition(),
        connectedStoreBuilders.values()
    );

    if (userProcessor != null) {
      userProcessor.close();
    } else {
      userFixedKeyProcessor.close();
    }
  }

  private static void unregisterFlushListenerForStoreBuilders(
      final String streamThreadName,
      final int partition,
      final Collection<AbstractAsyncStoreBuilder<?, ?, ?>> asyncStoreBuilders
  ) {
    for (final AbstractAsyncStoreBuilder<?, ?, ?> builder : asyncStoreBuilders) {
      builder.unregisterFlushListenerForPartition(streamThreadName, partition);
    }
  }

  private static void registerFlushListenerForStoreBuilders(
      final String streamThreadName,
      final int partition,
      final Collection<AbstractAsyncStoreBuilder<?, ?, ?>> asyncStoreBuilders,
      final AsyncFlushListener flushPendingEvents
  ) {
    for (final AbstractAsyncStoreBuilder<?, ?, ?> builder : asyncStoreBuilders) {
      builder.registerFlushListenerWithAsyncStore(streamThreadName, partition, flushPendingEvents);
    }
  }

  /**
   * Block on all pending records to be scheduled, executed, and fully complete processing through
   * the topology, as well as all state store operations to be applied. Called at the beginning of
   * each commit, similar to #flushCache
   */
  public void flushAndAwaitPendingEvents() {

    // Make a (non-blocking) pass through the finalizing queue up front, to
    // free up any recently-processed events before we attempt to drain the
    // scheduling queue
    drainFinalizingQueue();

    while (!isCompleted()) {

      // Start by scheduling all unblocked events to hand off any events that
      // were just unblocked by whatever we just finalized
      final int numScheduled = drainSchedulingQueue();

      // Need to finalize at least one event per iteration, otherwise there's no
      // point returning to the scheduling queue since nothing new was unblocked
      final int numFinalized = finalizeAtLeastOneEvent();
      log.debug("Scheduled {} events and finalized {} events",
                numScheduled, numFinalized
      );
    }
  }

  /**
   * Blocking API that guarantees at least one event has been finalized.
   * <p>
   * Drains the finalizing queue to complete any/all events that were already
   * processed and waiting to be finalized. If no events are ready for
   * finalization when this method is called, it will block until the next
   * one becomes available and will finalize that event.
   *
   * @return the number of events that were finalized
   */
  private int finalizeAtLeastOneEvent() {
    final int numFinalized = drainFinalizingQueue();
    if (numFinalized > 0) {
      return numFinalized;
    }

    try {
      final AsyncEvent finalizableEvent = finalizingQueue.waitForNextFinalizableEvent();
      completePendingEvent(finalizableEvent);
      return 1;
    } catch (final Exception e) {
      log.error("Exception caught while waiting for an event to finalize", e);
      throw new StreamsException("Failed to flush async processor", e, taskId);
    }
  }

  /**
   * Executes async events that are in-flight until the SchedulingQueue has
   * adequate space for new events.
   * <p>
   * Applies backpressure before proceeding with a new AsyncEvent ie adding it
   * to the SchedulingQueue. This method waits for new events to finish being
   * processed by the async threads and handed back to the StreamThread for
   * finalization, thus freeing up blocked record(s) that were waiting in the
   * SchedulingQueue and can be moved to the ProcessingQueue for processing.
   */
  private void backOffSchedulingQueueForKey(final KIn key) {
    while (schedulingQueue.keyQueueIsFull(key)) {
      drainSchedulingQueue();

      if (schedulingQueue.keyQueueIsFull(key)) {
        // we may not actually have finalized an event with this key, but we
        // may as well return to the loop start so we can potentially schedule
        // a newly-unblocked event of a different key
        finalizeAtLeastOneEvent();
      }
    }
  }

  /**
   * Does a single, non-blocking pass over all queues to pull any events that are
   * currently available and ready to pick up to transition to the next stage in
   * the async event lifecycle. See {@link AsyncEvent.State} for more details on
   * the lifecycle states and requirements for transition.
   * <p>
   * While this method will execute all events that are returned from the queues
   * when polled, it does not attempt to fully drain the queues and will not
   * re-check the queues. Any events that become unblocked or are added to a
   * given queue while processing the other queues is not guaranteed to be
   * executed in this method invocation.
   * The queues are checked in an order that maximizes overall throughput, and
   * prioritizes moving events through the async processing pipeline over
   * maximizing the number of events we can get through in each call
   * <p>
   * Note: all per-event logging should be at TRACE to avoid spam
   */
  private void executeAvailableEvents() {
    // Start by going through the events waiting to be finalized and finish executing their
    // outputs, if any, so we can mark them complete and potentially free up blocked events
    // waiting to be scheduled.
    final int numFinalized = drainFinalizingQueue();
    log.trace("Finalized {} events", numFinalized);

    // Then we check the scheduling queue and hand everything that is able to be processed
    // off to the processing queue for the AsyncThread to continue from here
    final int numScheduled = drainSchedulingQueue();
    log.trace("Scheduled {} events", numScheduled);
  }

  /**
   * Polls all the available records from the {@link SchedulingQueue}
   * without waiting for any blocked events to become schedulable.
   * Makes a single pass and schedules only the current set of
   * schedulable events.
   * <p>
   * There may be blocked events still waiting in the scheduling queue
   * after each invocation of this method, so the caller should make
   * sure to test whether the scheduling queue is empty after this method
   * returns, if the goal is to schedule all pending events.
   *
   * @return the number of events that were scheduled
   */
  private int drainSchedulingQueue() {
    final List<AsyncEvent> eventsToSchedule = new LinkedList<>();

    while (schedulingQueue.hasProcessableRecord()) {
      final AsyncEvent processableEvent = schedulingQueue.poll();
      processableEvent.transitionToToProcess();
      eventsToSchedule.add(processableEvent);
    }
    final int numScheduled = eventsToSchedule.size();
    if (numScheduled > 0) {
      threadPool.scheduleForProcessing(
          asyncProcessorName,
          taskId.partition(),
          eventsToSchedule,
          finalizingQueue,
          taskContext,
          userContext
      );
    }

    return numScheduled;
  }

  /**
   * Polls all the available records from the {@link FinalizingQueue}
   * without waiting for any new events to arrive. Makes a single pass
   * and completes only the current set of finalizable events, which
   * means there will most likely be pending events still in flight
   * for this processor that will need to be finalized later.
   *
   * @return the number of events that were finalized
   */
  private int drainFinalizingQueue() {
    int count = 0;
    while (!finalizingQueue.isEmpty()) {
      final AsyncEvent event = finalizingQueue.nextFinalizableEvent();
      completePendingEvent(event);
      ++count;
    }
    return count;
  }

  /**
   * Complete processing one pending event.
   * Accepts an event pulled from the {@link FinalizingQueue} and finalizes
   * it before marking the event as done.
   */
  private void completePendingEvent(final AsyncEvent finalizableEvent) {
    preFinalize(finalizableEvent);
    finalize(finalizableEvent);
    postFinalize(finalizableEvent);
  }

  /**
   * Prepare to finalize an event by
   */
  private void preFinalize(final AsyncEvent event) {
    streamThreadContext.prepareToFinalizeEvent(event);
    event.transitionToFinalizing();
  }

  /**
   * Perform finalization of this event by processing output records,
   * ie executing forwards and writes that were intercepted from #process
   */
  private void finalize(final AsyncEvent event) {
    DelayedWrite<?, ?> nextDelayedWrite = event.nextWrite();
    DelayedForward<KOut, VOut> nextDelayedForward = event.nextForward();

    while (nextDelayedWrite != null || nextDelayedForward != null) {

      if (nextDelayedWrite != null) {
        streamThreadContext.executeDelayedWrite(nextDelayedWrite);
      }


      if (nextDelayedForward != null) {
        streamThreadContext.executeDelayedForward(nextDelayedForward);
      }

      nextDelayedWrite = event.nextWrite();
      nextDelayedForward = event.nextForward();
    }
  }

  /**
   * After finalization, the event can be transitioned to {@link AsyncEvent.State#DONE}
   * and cleared from the set of pending events, unblocking that key.
   */
  private void postFinalize(final AsyncEvent event) {
    event.transitionToDone();

    pendingEvents.remove(event);
    schedulingQueue.unblockKey(event.inputRecordKey());
  }

  /**
   * @return true iff all records have been fully processed from start to finish
   */
  private boolean isCompleted() {
    return pendingEvents.isEmpty();
  }

  /**
   * Verify that all the stores accessed by the user via
   * {@link ProcessorContext#getStateStore(String)} during their processor's
   * #init method were connected to the processor following the appropriate
   * procedure for async processors. For more details, see the instructions
   * in the javadocs for {@link AsyncProcessorSupplier}.
   */
  private void verifyConnectedStateStores(
      final Map<String, AsyncKeyValueStore<?, ?>> accessedStores,
      final Map<String, AbstractAsyncStoreBuilder<?, ?, ?>> connectedStores
  ) {
    if (accessedStores.size() != connectedStores.size()) {
      log.error(
          "Number of connected store names is not equal to the number of stores retrieved "
              + "via ProcessorContext#getStateStore during initialization. Make sure to pass "
              + "all state stores used by this processor to the AsyncProcessorSupplier, and "
              + "they are (all) initialized during the Processor#init call before actual "
              + "processing begins. Found {} connected store names and {} actual stores used",
          connectedStores.size(), accessedStores.keySet().size());
      throw new IllegalStateException(
          "Number of actual stores initialized by this processor does not "
              + "match the number of connected store names that were provided "
              + "to the AsyncProcessorSupplier");
    }
  }

  private static AsyncThreadPool getAsyncThreadPool(
      final ProcessingContext context,
      final String streamThreadName
  ) {
    final AsyncThreadPoolRegistry registry = loadAsyncThreadPoolRegistry(
        context.appConfigsWithPrefix(StreamsConfig.MAIN_CONSUMER_PREFIX)
    );

    return registry.asyncThreadPoolForStreamThread(streamThreadName);
  }

}
