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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.streams.KafkaClientSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple router that stores a reference to the pool
 * for each StreamThread in the Streams client, so that we can get a handle
 * on the thread pool on any StreamThread from anywhere in the app.
 * It is also used to register and deregister the AsyncThreadPool by
 * tying it to the lifecycle of the main consumer client that is
 * owned and managed by each StreamThread.
 * <p>
 * Each KafkaStreams app will create an instance of this class and pass
 * it around the application by adding it to the config map. We have to
 * append the MAIN_CONSUMER prefix to the config name so that it's
 * included in the configs passed to the {@link KafkaClientSupplier}
 *
 */
public class AsyncThreadPoolRegistry {
  private static final Logger LOG = LoggerFactory.getLogger(AsyncThreadPoolRegistry.class);

  private final int asyncThreadPoolSize;
  private final Map<String, AsyncThreadPool> streamThreadToAsyncPool;

  public AsyncThreadPoolRegistry(
      final int numStreamThreads,
      final int asyncThreadPoolSize
  ) {
    this.asyncThreadPoolSize = asyncThreadPoolSize;
    this.streamThreadToAsyncPool = new ConcurrentHashMap<>(numStreamThreads);
  }

  /**
   * Registers and starts up a new AsyncThreadPool for the given StreamThread
   */
  public void startNewAsyncThreadPool(final String streamThreadName) {
    final AsyncThreadPool newThreadPool = new AsyncThreadPool(
        streamThreadName, asyncThreadPoolSize
    );

    final AsyncThreadPool oldThreadPool = streamThreadToAsyncPool.put(
        streamThreadName,
        newThreadPool
    );
    if (oldThreadPool != null) {
      LOG.warn(
          "Shutting down old orphaned async thread pool for StreamThread {}",
          streamThreadName
      );
      oldThreadPool.shutdown();
    }
  }

  public AsyncThreadPool asyncThreadPoolForStreamThread(
      final String streamThreadName
  ) {
    return streamThreadToAsyncPool.get(streamThreadName);
  }

  /**
   * Unregister and shutdown the async thread pool that belongs to this StreamThread.
   * This is a non-blocking call that guarantees the pool will be unregistered
   * and a shutdown initiated, but will not wait for the shutdown to complete.
   * <p>
   * This method is idempotent
   */
  public void shutdownAsyncThreadPool(final String streamThreadName) {
    final AsyncThreadPool threadPool = streamThreadToAsyncPool.remove(streamThreadName);

    // It's possible the consumer was closed twice for some reason, in which case
    // we have already unregistered and begun shutdown for this pool
    if (threadPool != null) {
      threadPool.shutdown();
    }
  }

}
