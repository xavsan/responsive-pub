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

package dev.responsive.kafka.api.async;

import dev.responsive.kafka.api.async.internals.AsyncProcessor;
import dev.responsive.kafka.api.async.internals.stores.AsyncStoreBuilder;
import dev.responsive.kafka.internal.stores.ResponsiveStoreBuilder;
import dev.responsive.kafka.internal.stores.ResponsiveStoreBuilder.StoreType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.processor.ConnectedStoreProvider;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.state.KeyValueBytesStoreSupplier;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.internals.AsyncTimestampedKeyValueStoreBuilder;

/**
 * Instructions:
 * 1) Simply wrap your regular {@link ProcessorSupplier} in the async supplier by passing
 *    it into the {@link AsyncProcessorSupplier} constructor, and then pass in the
 *    AsyncProcessorSupplier instead of your ProcessorSupplier when defining the application
 *    topology. The async framework will take care of the rest, and no further code
 *    changes are required to enable async processing!
 *
 * <p>
 *
 * Requirements/Setup:
 * 1) To use state stores within an async processor, you must connect the state stores via
 *    automatic connection. In other words, you must have your ProcessorSupplier override the
 *    {@link ConnectedStoreProvider#stores()} method and supply your store builders there,
 *    rather than connecting them to the processor manually via APIs like
 *    {@link StreamsBuilder#addStateStore(StoreBuilder)} (DSL) and
 *    {@link Topology#addStateStore} (PAPI)
 * 2) As is the case with regular, non-async processors, it is strongly recommended to
 *    use only "safe forwarding" in your processor, as "unsafe forwarding" can break casuality
 *    and lead to unexpected results. In other words, you should avoid mutating the input record
 *    and forwarding the mutated record as the output. The "safe forwarding" method
 *    entails creating a new {@link org.apache.kafka.streams.processor.api.Record} object with
 *    the desired key, value, and timestamp, as well as making a copy of the input record's
 *    {@link Headers} if you use headers and need to modify them or do anything other than passing
 *    them as-is. Headers are inherently mutatable and do not protect their backing array by making
 *    copies of it in the constructor or anywhere else. Therefore, if you ever add, remove, or
 *    update the input record's Headers, you must protect them by making a copy of the backing
 *    array and then creating a new instance of {@link Headers} with the new clone of the array.
 *    See the {@link ProcessorContext} javadocs for more details and examples of safe vs
 *    unsafe forwarding techniques.
 * 3) It is required to initialize any state stores connected to this processor inside of its
 *    {@link Processor#init(ProcessorContext)} method. Attempting to call
 *    {@link ProcessorContext#getStateStore(String)} after #init, for example inside the
 *    {@link Processor#process} method instead, will result in an exception being thrown.
 *
 * <p>
 *
 * Current limitations:
 * 0) Does not yet support read-your-write semantics -- a #get after a #put is not necessarily
 *    guaranteed to return the inserted record, or include it in the case of range scans.
 *    This is only true within an invocation of #process -- all previous input records of the
 *    same key will always be processed in full before a new record with that key is picked up.
 * 1) *Not compatible with punctuators or input records that originate in upstream punctuators
 * 2) Key-Value stores only at this time: async window and session stores coming soon
 * 3) Cannot be used for global state stores
 * 4) Supports only one upstream inputsenderte topic (specifically for records which will be forwarded to
 *    this processor -- multiple input topics for a subtopology are allowed if the records from
 *    additional input topics do not flow through this processor
 * 5) State stores within an async processor must have the same key and value types
 */
public final class AsyncProcessorSupplier<KIn, VIn, KOut, VOut> implements ProcessorSupplier<KIn, VIn, KOut, VOut> {

  private final ProcessorSupplier<KIn, VIn, KOut, VOut> userProcessorSupplier;
  private final Map<String, AsyncStoreBuilder<?>> asyncStoreBuilders = new HashMap<>();

  /**
   * Create an async wrapper around your custom {@link ProcessorSupplier} to enable parallel
   * processing of long/blocking calls and state store accesses. All the usual requirements for
   * the underlying {@link ProcessorSupplier} remain, such as connecting state stores to the
   * processor in your topology, which you must do via the automatic process ie overriding the
   * {@link ConnectedStoreProvider#stores()} method in your {@link ProcessorSupplier}
   * implementation and supplying a {@link StoreBuilder} for each state store that will be
   * accessed by this processor using {@link ProcessorContext#getStateStore(String)}
   *
   * @param processorSupplier the {@link ProcessorSupplier} that returns a (new) instance
   *                          of your custom {@link Processor} on each invocation of
   *                          {@link ProcessorSupplier#get}
   */
  public AsyncProcessorSupplier(
      final ProcessorSupplier<KIn, VIn, KOut, VOut> processorSupplier
  ) {
    this.userProcessorSupplier = processorSupplier;
    this.asyncStoreBuilders.putAll(initializeAsyncBuilders(userProcessorSupplier));
  }

  @Override
  public AsyncProcessor<KIn, VIn, KOut, VOut> get() {
    return new AsyncProcessor<>(userProcessorSupplier.get(), asyncStoreBuilders);
  }

  @Override
  public Set<StoreBuilder<?>> stores() {
    return new HashSet<>(asyncStoreBuilders.values());
  }

  private static <KIn, VIn, KOut, VOut> Map<String, AsyncStoreBuilder<?>> initializeAsyncBuilders(
      final ProcessorSupplier<KIn, VIn, KOut, VOut> userProcessorSupplier
  ) {
    final Map<String, AsyncStoreBuilder<?>> asyncStoreBuilders = new HashMap<>();
    for (final StoreBuilder<?> builder : userProcessorSupplier.stores()) {
      final String storeName = builder.name();
      if (builder instanceof ResponsiveStoreBuilder) {
        final ResponsiveStoreBuilder<?, ?, ?> responsiveBuilder = (ResponsiveStoreBuilder<?, ?, ?>) builder;

        final StoreType storeType = responsiveBuilder.storeType();
        switch (storeType) {
          case TIMESTAMPED_KEY_VALUE:
            asyncStoreBuilders.put(
                storeName,
                new AsyncTimestampedKeyValueStoreBuilder<>(
                    responsiveBuilder)
                    (KeyValueBytesStoreSupplier) responsiveBuilder.storeSupplier(),
                    responsiveBuilder.keySerde(),
                    responsiveBuilder.valueSerde(),
                    responsiveBuilder.time())
            );
            break;
          default:
            throw new UnsupportedOperationException("Only timestamped key-value stores are "
                                                        + "supported by async processors at this time");
        }
      } else {
        throw new IllegalStateException(String.format(
            "Detected the StoreBuilder for %s was not created via the ResponsiveStores factory, "
                + "please ensure that all store builders and suppliers are provided through the "
                + "appropriate API from ResponsiveStores", storeName));
      }
    }
    return asyncStoreBuilders;
  }
}
