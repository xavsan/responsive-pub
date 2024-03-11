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

package dev.responsive.kafka.api.async.internals.queues;

import dev.responsive.kafka.api.async.internals.AsyncProcessor;
import dev.responsive.kafka.api.async.internals.AsyncProcessorRecordContext;
import dev.responsive.kafka.api.async.internals.records.ScheduleableRecord;
import java.util.function.Predicate;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;

// TODO:
//  1) implement predicate/isProcessable checking,
//  2) potentially save "cursor" in dll and have it automatically "reset" to the next oldest
//  node if/when they go from un-processable to processable via completion of pending records
//  3) make #poll blocking with Condition to notify when record becomes processable
/**
 * A non-blocking queue for input records waiting to be passed from the StreamThread to
 * the async thread pool and scheduled for execution. This queue is not thread safe and
 * should be owned and exclusively accessed by the StreamThread. Records that are
 * processable -- that is, not blocked on previous records with the same key that have
 * not yet been fully processed -- will be polled from this queue and then passed on to
 * the async thread pool by the StreamThread.
 * <p>
 * We implement a custom doubly-linked list to enable conditional queue-like semantics
 * with efficient arbitrary removal of the first element that meets the condition.
 * <p>
 * Threading notes:
 * -Should only be accessed from the StreamThread
 * -One per physical AsyncProcessor instance
 *   (ie per logical processor per partition per StreamThread)
 */
public class SchedulingQueue<KIn, VIn> {

  private final RecordNode<KIn, VIn> head;
  private final RecordNode<KIn, VIn> tail;

  public SchedulingQueue() {
    this.head = new RecordNode<>(null, null, null, null);
    this.tail = new RecordNode<>(null, null, null, null);

    head.next = tail;
    tail.previous = head;
  }

  /**
   * @return true iff there are any pending records, whether or not they're processable
   */
  public boolean isEmpty() {
    return head == tail;
  }

  /**
   * Get the next oldest record that satisfies the constraint for processing, namely
   * that all previous records with the same {@link KIn key type} have been completed
   *
   * @return the next available record that is ready for processing
   *         or {@code null} if there are no processable records
   */
  public ScheduleableRecord<KIn, VIn> poll() {
    final RecordNode<KIn, VIn> nextProcessableRecordNode = nextProcessableRecordNode();
    return nextProcessableRecordNode != null
        ? nextProcessableRecordNode.record
        : null;
  }

  /**
   * @return whether there are any remaining records in the queue which are currently
   *         ready for processing
   *         Note: this differs from {@link #isEmpty()} in that it tests whether there
   *         are processable records, so it's possible for #isEmpty to return false
   *         while this also returns false
   */
  public boolean hasProcessableRecord() {
    return nextProcessableRecordNode() != null;
  }

  private RecordNode<KIn, VIn> nextProcessableRecordNode() {
    RecordNode<KIn, VIn> current = head.next;
    while (current != tail) {
      if (current.isProcessable()) {
        return current;
      } else {
        current = current.next;
      }
    }
    return null;
  }

  /**
   * Add a new input record to the queue. Records will be processing in modified FIFO
   * order; essentially picking up the next oldest record that is ready to be processed,
   * in other words, excluding those that are awaiting previous same-key records to complete.
   *
   * @param record a new record to schedule for processing
   * @param originalRecordContext the actual recordContext of the processor context
   *                              at the time this input record was passed to the
   *                              {@link AsyncProcessor#process} method
   */
  public void put(
      final Record<KIn, VIn> record,
      final ProcessorRecordContext originalRecordContext
  ) {
    addToTail(new ScheduleableRecord<>(
        record,
        new AsyncProcessorRecordContext(originalRecordContext)
    ));
  }

  private void addToTail(final ScheduleableRecord<KIn, VIn> record) {
    final RecordNode<KIn, VIn> node = new RecordNode<>(record, k -> {throw new RuntimeException("Need to implement Predicate!");}, tail, tail.previous);
    tail.previous.next = node;
    tail.previous = node;
  }

  private ScheduleableRecord<KIn, VIn> remove(final RecordNode<KIn, VIn> node) {
    node.next.previous = node.previous;
    node.previous.next = node.next;
    return node.record;
  }

  private static class RecordNode<KIn, VIn> {
    private final ScheduleableRecord<KIn, VIn> record;
    private final Predicate<KIn> isProcessable;

    private RecordNode<KIn, VIn> next;
    private RecordNode<KIn, VIn> previous;

    public RecordNode(
        final ScheduleableRecord<KIn, VIn> record,
        final Predicate<KIn> isProcessable,
        final RecordNode<KIn, VIn> next,
        final RecordNode<KIn, VIn> previous
    ) {
      this.record = record;
      this.isProcessable = isProcessable;
      this.next = next;
      this.previous = previous;
    }

    public boolean isProcessable() {
      return isProcessable.test(record.key());
    }
  }
}
