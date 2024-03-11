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

import dev.responsive.kafka.api.async.internals.AsyncProcessorRecordContext;
import dev.responsive.kafka.api.async.internals.records.WriteableRecord;
import java.util.Comparator;
import org.apache.kafka.streams.processor.api.Record;

/**
 * A queue for holding the intercepted {@code #put} calls to a state store that occur
 * within the {@code #process} method of an async processor.
 * <p>
 * These must be intercepted by the async state stores because {@code #process} is
 * invoked on the AsyncThreads, whereas all puts must be executed on the original
 * StreamThread due to the possibility of a cache eviction and subsequent processing.
 * Essentially, all puts are treated as potential forwards, and handled as such.
 * For this reason the class mirrors the {@link ForwardingQueue} in semantics and usage.
 * <p>
 * Threading notes:
 * -producer to queue --> AsyncThread
 * -consumer from queue --> StreamThread
 * -One per physical AsyncProcessor instance
 *   (ie per logical processor per partition per StreamThread)
 */
public class WritingQueue<K, V> {

  private final OffsetOrderQueue<K, V, WriteableRecord<K, V>> storeToWriteableRecords =
      OffsetOrderQueue.nonBlockingQueue(Comparator.comparing(WriteableRecord::storeName));

  /**
   * @return true iff there are any records available to forward
   */
  public boolean isEmpty() {
    return storeToWriteableRecords.isEmpty();
  }

  /**
   * Add a new record that is ready and able to be forwarded by the StreamThread
   * <p>
   * Should only be invoked by AsyncThreads
   */
  public void write(
      final Record<K, V> record,
      final String childName, // can be null
      final AsyncProcessorRecordContext recordContext,
      final Runnable putListener
  ) {
    storeToWriteableRecords.put(
        new WriteableRecord<>(record, childName, recordContext, putListener)
    );
  }

  /**
   * Add a new record that is ready and able to be forwarded by the StreamThread
   * <p>
   * Should only be invoked by StreamThreads
   */
  public WriteableRecord<K, V> poll() {
    return storeToWriteableRecords.poll();
  }
  
}
