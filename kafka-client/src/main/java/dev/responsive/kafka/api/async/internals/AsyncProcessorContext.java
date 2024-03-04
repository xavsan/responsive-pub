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

import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.StreamsMetrics;
import org.apache.kafka.streams.processor.Cancellable;
import org.apache.kafka.streams.processor.CommitCallback;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.Punctuator;
import org.apache.kafka.streams.processor.StateRestoreCallback;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.processor.TaskId;
import org.apache.kafka.streams.processor.To;
import org.apache.kafka.streams.processor.api.FixedKeyRecord;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.api.RecordMetadata;
import org.apache.kafka.streams.processor.internals.InternalProcessorContext;
import org.apache.kafka.streams.processor.internals.ProcessorMetadata;
import org.apache.kafka.streams.processor.internals.ProcessorNode;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.apache.kafka.streams.processor.internals.RecordCollector;
import org.apache.kafka.streams.processor.internals.StreamTask;
import org.apache.kafka.streams.processor.internals.Task.TaskType;
import org.apache.kafka.streams.processor.internals.metrics.StreamsMetricsImpl;
import org.apache.kafka.streams.query.Position;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.internals.ThreadCache;
import org.apache.kafka.streams.state.internals.ThreadCache.DirtyEntryFlushListener;

// TODO: evaluate whether we can just extend the actual processor context implementation class
//  to avoid having to mock/delegate every single ProcessorContext method
//  This is complicated since there are multiple types of processor context and because we might
//  be better off manually wrapping all method calls to ensure we intercept anything that
//  might have side effects or be vulnerable to changing (eg RecordMetadata) outside #process
//  Update: we may need to implement InternalProcessorContext or even just extend
//  AbstractProcessorContext itself, in order to set up all internal fields needed for delayed
//  forwarding. This will make async processing incompatible with the mock processor context, though
//  it will still work with both the global and regular processor context types.
public class AsyncProcessorContext<KOut, VOut> implements ProcessorContext<KOut, VOut> {

  private final InternalProcessorContext<KOut, VOut> delegate;
  private final Map<String, AsyncKeyValueStore<?, ?>> stateStores = new HashMap<>();

  // We're being overly cautious here as the taskId is not actually mutated during
  // normal processing, but better safe than sorry (and to protect ourselves against future changes)
  private final TaskId taskId;
  private final Serde<?> keySerde;
  private final Serde<?> valueSerde;

  // is empty for records that originate from upstream punctuators rather than input topics
  private final Optional<RecordMetadata> recordMetadata;

  public AsyncProcessorContext(final ProcessorContext<KOut, VOut> delegate) {
    this.delegate = (InternalProcessorContext<KOut, VOut>) delegate;

    taskId = delegate.taskId();
    recordMetadata = delegate.recordMetadata();
    keySerde = delegate.keySerde();
    valueSerde = delegate.valueSerde();
  }

  @Override
  public <K extends KOut, V extends VOut> void forward(final Record<K, V> record) {
    // TODO -- how do we make sure the delegate is "set" correctly? need to ensure things like
    //  #recordMetadata and currentNode (maybe others) correspond to the original processor state
    //  since the delegate will be mutated during normal processing flow
    //  Need to set: currentNode, recordContext
    delegate.forward(record);
  }

  @Override
  public <K extends KOut, V extends VOut> void forward(final Record<K, V> record, final String childName) {
    // TODO -- see above comment
    delegate.forward(record, childName);
  }

  @Override
  public String applicationId() {
    return delegate.applicationId();
  }

  @Override
  public TaskId taskId() {
    return taskId;
  }

  @Override
  public Optional<RecordMetadata> recordMetadata() {
    return recordMetadata;
  }

  @Override
  public Serde<?> keySerde() {
    return keySerde;
  }

  @Override
  public Serde<?> valueSerde() {
    return valueSerde;
  }

  @Override
  public File stateDir() {
    return delegate.stateDir();
  }

  @Override
  public StreamsMetricsImpl metrics() {
    return delegate.metrics();
  }

  ////// InterProcessorContext methods below this line //////
  // Unlike the ProcessorContext methods (above) these are not exposed to the user, and so we
  // don't need to intercept calls or worry about preserving original values

  @Override
  public void register(final StateStore store, final StateRestoreCallback stateRestoreCallback) {
    // TODO track which stores are registered to cross-reference with those that are connected to
    //  the AsyncProcessorSupplier and the ones that are actually retrieved by the processor itself
    delegate.register(store, stateRestoreCallback);
  }

  @Override
  public void register(
      final StateStore store,
      final StateRestoreCallback stateRestoreCallback,
      final CommitCallback commitCallback
  ) {
    delegate.register(store, stateRestoreCallback, commitCallback);
  }

  @Override
  public void setSystemTimeMs(final long timeMs) {
    delegate.setSystemTimeMs(timeMs);
  }

  @Override
  public ProcessorRecordContext recordContext() {
    return delegate.recordContext();
  }

  @Override
  public void setRecordContext(final ProcessorRecordContext recordContext) {
    delegate.setRecordContext(recordContext);
  }

  @Override
  public void setCurrentNode(final ProcessorNode<?, ?, ?, ?> currentNode) {
    delegate.setCurrentNode(currentNode);
  }

  @Override
  public ProcessorNode<?, ?, ?, ?> currentNode() {
    return delegate.currentNode();
  }

  @Override
  public ThreadCache cache() {
    return delegate.cache();
  }

  @Override
  public void initialize() {
    throw new IllegalStateException("initialize should never be invoked on the async context");
  }

  @Override
  public void uninitialize() {

  }

  @Override
  public TaskType taskType() {
    return null;
  }

  @Override
  public void transitionToActive(final StreamTask streamTask, final RecordCollector recordCollector, final ThreadCache newCache) {

  }

  @Override
  public void transitionToStandby(final ThreadCache newCache) {

  }

  @Override
  public void registerCacheFlushListener(final String namespace, final DirtyEntryFlushListener listener) {

  }

  @Override
  public <T extends StateStore> T getStateStore(final StoreBuilder<T> builder) {
    return InternalProcessorContext.super.getStateStore(builder);
  }

  @Override
  public void logChange(final String storeName, final Bytes key, final byte[] value, final long timestamp, final Position position) {

  }

  @Override
  public String changelogFor(final String storeName) {
    return null;
  }

  @Override
  public void addProcessorMetadataKeyValue(final String key, final long value) {

  }

  @Override
  public Long processorMetadataForKey(final String key) {
    return null;
  }

  @Override
  public void setProcessorMetadata(final ProcessorMetadata metadata) {

  }

  @Override
  public ProcessorMetadata getProcessorMetadata() {
    return null;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <S extends StateStore> S getStateStore(final String name) {
    // TODO:
    //  1) We should be enforcing that this is called exactly once per store and only from #init
    //  This delegate call relies on mutable internal context fields and hence should never
    //  be called from the processors' #process method. This is good practice in vanilla Streams
    //  anyway, but it's especially important for the async processor
    //  2) Implement async StateStore for window and session stores
    final S userDelegate = delegate.getStateStore(name);
    if (userDelegate instanceof KeyValueStore) {
      final var asyncStore = new AsyncKeyValueStore<>(delegate, name);
      stateStores.put(name, asyncStore);
      return (S) asyncStore;
    } else {
      throw new UnsupportedOperationException(
          "Window and Session stores are not yet supported with async processing");
    }
  }

  @Override
  public Cancellable schedule(final Duration interval, final PunctuationType type, final Punctuator callback) {
    throw new UnsupportedOperationException("Punctuators not yet supported for async processors");
  }

  @Override
  public <K, V> void forward(final K key, final V value) {

  }

  @Override
  public <K, V> void forward(final K key, final V value, final To to) {

  }

  @Override
  public void commit() {
    // this is only a best-effort request and not a guarantee, so it's fine to potentially delay
    delegate.commit();
  }

  @Override
  public String topic() {
    return null;
  }

  @Override
  public int partition() {
    return 0;
  }

  @Override
  public long offset() {
    return 0;
  }

  @Override
  public Headers headers() {
    return null;
  }

  @Override
  public long timestamp() {
    return 0;
  }

  @Override
  public Map<String, Object> appConfigs() {
    return delegate.appConfigs();
  }

  @Override
  public Map<String, Object> appConfigsWithPrefix(final String prefix) {
    return delegate.appConfigsWithPrefix(prefix);
  }

  @Override
  public long currentSystemTimeMs() {
    // TODO: asssess how/when this is actually used in custom processors, and whether we should
    //  simply pass the new/true system time, or save the system time on initial invocation
    return delegate.currentSystemTimeMs();
  }

  @Override
  public long currentStreamTimeMs() {
    // TODO: The semantics here are up for debate, should we return the "true" stream-time
    //  at the point when {@link #currentStreamTimeMs} is invoked, or the "original" stream-time
    //  as of when the record was first passed to the processor?
    //  Right now we choose to delegate this since the "true" stream-time is the one that is
    //  generally going to match up with what the underlying store is seeing/tracking and making
    //  decisions based off, giving more internally consistent results.
    //  It's probably worth giving a bit more consideration to, however, and possibly
    //  even soliciting user feedback on
    return delegate.currentStreamTimeMs();
  }

  public AsyncKeyValueStore<?, ?> getAsyncStore(final String storeName) {
    return stateStores.get(storeName);
  }

  public Map<String, AsyncKeyValueStore<?, ?>> getAllAsyncStores() {
    return stateStores;
  }

  @Override
  public <K extends KOut, V extends VOut> void forward(final FixedKeyRecord<K, V> record) {

  }

  @Override
  public <K extends KOut, V extends VOut> void forward(final FixedKeyRecord<K, V> record, final String childName) {

  }
}
