/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.state.forst;

import org.apache.flink.runtime.asyncprocessing.AsyncRequestContainer;
import org.apache.flink.runtime.asyncprocessing.StateExecutor;
import org.apache.flink.runtime.asyncprocessing.StateRequest;
import org.apache.flink.state.forst.fs.cache.FileBasedCache;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.concurrent.ExecutorThreadFactory;
import org.apache.flink.util.concurrent.FutureUtils;

import org.forstdb.RocksDB;
import org.forstdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.flink.state.forst.ForStStateRequestClassifier.convertRequests;

/**
 * The {@link StateExecutor} implementation which executing batch {@link StateRequest}s for
 * ForStStateBackend.
 */
public class ForStStateExecutor implements StateExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ForStStateExecutor.class);

    /**
     * The coordinator thread which schedules the execution of multiple batches of stateRequests.
     * The number of coordinator threads is 1 to ensure that multiple batches of stateRequests can
     * be executed sequentially.
     */
    private final ExecutorService coordinatorThread;

    /** The worker thread that actually executes the read {@link StateRequest}s. */
    private final ExecutorService readThreads;

    /** The worker thread that actually executes the write {@link StateRequest}s. */
    private final ExecutorService writeThreads;

    private final int readThreadCount;

    /** Whether the write thread is sharing with others. */
    private final boolean sharedWriteThread;

    private final RocksDB db;

    private final WriteOptions writeOptions;

    private Throwable executionError;

    /** The ongoing sub-processes count. */
    private final AtomicLong ongoing;

    private final ExecutorService directExecutor =
            org.apache.flink.util.concurrent.Executors.newDirectExecutorService();

    public ForStStateExecutor(
            boolean coordinatorInline,
            boolean isWriteInline,
            int readIoParallelism,
            int writeIoParallelism,
            RocksDB db,
            WriteOptions writeOptions) {
        if (isWriteInline) {
            Preconditions.checkState(readIoParallelism > 0);
            this.coordinatorThread =
                    coordinatorInline
                            ? directExecutor
                            : Executors.newSingleThreadExecutor(
                                    new ForStExecutorThreadFactory(
                                            "ForSt-StateExecutor-Coordinator-And-Write",
                                            FileBasedCache::setFlinkThread));
            this.readThreadCount = readIoParallelism;
            this.readThreads =
                    Executors.newFixedThreadPool(
                            readIoParallelism,
                            new ForStExecutorThreadFactory(
                                    "ForSt-StateExecutor-read-IO", FileBasedCache::setFlinkThread));
            this.writeThreads = directExecutor;
            this.sharedWriteThread = true;
        } else {
            Preconditions.checkState(readIoParallelism > 0 || writeIoParallelism > 0);
            this.coordinatorThread =
                    coordinatorInline
                            ? directExecutor
                            : Executors.newSingleThreadExecutor(
                                    new ForStExecutorThreadFactory(
                                            "ForSt-StateExecutor-Coordinator",
                                            FileBasedCache::setFlinkThread));
            if (readIoParallelism <= 0 || writeIoParallelism <= 0) {
                this.readThreadCount = Math.max(readIoParallelism, writeIoParallelism);
                this.readThreads =
                        Executors.newFixedThreadPool(
                                readThreadCount,
                                new ForStExecutorThreadFactory(
                                        "ForSt-StateExecutor-IO", FileBasedCache::setFlinkThread));
                this.writeThreads = readThreads;
                this.sharedWriteThread = true;
            } else {
                this.readThreadCount = readIoParallelism;
                this.readThreads =
                        Executors.newFixedThreadPool(
                                readIoParallelism,
                                new ForStExecutorThreadFactory(
                                        "ForSt-StateExecutor-read-IO",
                                        FileBasedCache::setFlinkThread));
                this.writeThreads =
                        Executors.newFixedThreadPool(
                                writeIoParallelism,
                                new ForStExecutorThreadFactory(
                                        "ForSt-StateExecutor-write-IO",
                                        FileBasedCache::setFlinkThread));
                this.sharedWriteThread = false;
            }
        }
        this.db = db;
        this.writeOptions = writeOptions;
        this.ongoing = new AtomicLong();
    }

    @Override
    public CompletableFuture<Void> executeBatchRequests(
            AsyncRequestContainer asyncRequestContainer) {
        checkState();
        Preconditions.checkArgument(asyncRequestContainer instanceof ForStStateRequestClassifier);
        ForStStateRequestClassifier stateRequestClassifier =
                (ForStStateRequestClassifier) asyncRequestContainer;
        // Calculate ongoing sub-processes. Only count read ones.
        // The fully loaded only consider read requests for now, since the write ones are quick.
        final List<ForStDBGetRequest<?, ?, ?, ?>> getRequests =
                stateRequestClassifier.pollDbGetRequests();
        final List<ForStDBIterRequest<?, ?, ?, ?, ?>> iterRequests =
                stateRequestClassifier.pollDbIterRequests();
        if (!getRequests.isEmpty()) {
            ongoing.addAndGet(1);
        }
        if (!iterRequests.isEmpty()) {
            ongoing.addAndGet(1);
        }
        CompletableFuture<Void> resultFuture = new CompletableFuture<>();
        coordinatorThread.execute(
                () -> {
                    long startTime = System.currentTimeMillis();
                    List<CompletableFuture<Void>> futures = new ArrayList<>(3);

                    if (!getRequests.isEmpty()) {
                        ForStGeneralMultiGetOperation getOperations =
                                new ForStGeneralMultiGetOperation(
                                        db,
                                        getRequests,
                                        readThreads,
                                        readThreadCount,
                                        ongoing::decrementAndGet);
                        // sub process count should -1, since we have added 1 on top.
                        ongoing.addAndGet(getOperations.subProcessCount() - 1);
                        futures.add(getOperations.process());
                    }

                    if (!iterRequests.isEmpty()) {
                        ForStIterateOperation iterOperations =
                                new ForStIterateOperation(
                                        db, iterRequests, readThreads, ongoing::decrementAndGet);
                        // sub process count should -1, since we have added 1 on top.
                        ongoing.addAndGet(iterOperations.subProcessCount() - 1);
                        futures.add(iterOperations.process());
                    }

                    List<ForStDBPutRequest<?, ?, ?>> putRequests =
                            stateRequestClassifier.pollDbPutRequests();
                    if (!putRequests.isEmpty()) {
                        ForStWriteBatchOperation writeOperations =
                                new ForStWriteBatchOperation(
                                        db, putRequests, writeOptions, writeThreads);
                        futures.add(writeOperations.process());
                    }

                    FutureUtils.combineAll(futures)
                            .thenAcceptAsync(
                                    (e) -> {
                                        long duration = System.currentTimeMillis() - startTime;
                                        LOG.debug(
                                                "Complete executing a batch of state requests, putRequest size {}, getRequest size {}, iterRequest size {}, duration {} ms",
                                                putRequests.size(),
                                                getRequests.size(),
                                                iterRequests.size(),
                                                duration);
                                        resultFuture.complete(null);
                                    },
                                    coordinatorThread)
                            .exceptionally(
                                    e -> {
                                        try {
                                            for (ForStDBIterRequest<?, ?, ?, ?, ?> iterRequest :
                                                    iterRequests) {
                                                iterRequest.close();
                                            }
                                        } catch (IOException ioException) {
                                            LOG.error("Close iterRequests fail", ioException);
                                        }
                                        executionError = e;
                                        resultFuture.completeExceptionally(e);
                                        return null;
                                    });
                });
        return resultFuture;
    }

    @Override
    public AsyncRequestContainer<StateRequest<?, ?, ?, ?>> createRequestContainer() {
        checkState();
        return new ForStStateRequestClassifier();
    }

    @Override
    public void executeRequestSync(StateRequest<?, ?, ?, ?> stateRequest) {
        checkState();
        Object forstRequest = convertRequests(stateRequest);
        try {
            ForStDBOperation operation;
            if (forstRequest instanceof ForStDBGetRequest) {
                operation =
                        new ForStGeneralMultiGetOperation(
                                db,
                                Collections.singletonList(
                                        (ForStDBGetRequest<?, ?, ?, ?>) forstRequest),
                                directExecutor,
                                1,
                                null);
            } else if (forstRequest instanceof ForStDBIterRequest) {
                operation =
                        new ForStIterateOperation(
                                db,
                                Collections.singletonList(
                                        (ForStDBIterRequest<?, ?, ?, ?, ?>) forstRequest),
                                directExecutor,
                                null);
            } else if (forstRequest instanceof ForStDBPutRequest) {
                operation =
                        new ForStWriteBatchOperation(
                                db,
                                Collections.singletonList(
                                        (ForStDBPutRequest<?, ?, ?>) forstRequest),
                                writeOptions,
                                directExecutor);
            } else {
                throw new IllegalArgumentException("Unknown request type: " + forstRequest);
            }
            operation
                    .process()
                    .exceptionally(
                            throwable -> {
                                executionError = throwable;
                                return null;
                            });
        } catch (Exception e) {
            executionError = e;
        }
        checkState();
    }

    @Override
    public boolean fullyLoaded() {
        return ongoing.get() >= readThreadCount;
    }

    private void checkState() {
        if (executionError != null) {
            throw new IllegalStateException(
                    "previous state request already failed : ", executionError);
        }
    }

    @Override
    public void shutdown() {
        // Coordinator should be shutdown before others, since it submit jobs to others.
        shutdownAndWait(coordinatorThread);
        shutdownAndWait(readThreads);
        if (!sharedWriteThread) {
            shutdownAndWait(writeThreads);
        }
        LOG.info("Shutting down the ForStStateExecutor.");
    }

    private void shutdownAndWait(ExecutorService executorService) {
        try {
            executorService.shutdown();
            while (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {}
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }

    /**
     * An {@link ExecutorThreadFactory} that could run a initializer before running the actual
     * runnable for each created thread.
     */
    private static class ForStExecutorThreadFactory extends ExecutorThreadFactory {

        private final Runnable initializer;

        public ForStExecutorThreadFactory(String name, Runnable initializer) {
            super(name);
            this.initializer = initializer;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            return super.newThread(
                    () -> {
                        initializer.run();
                        runnable.run();
                    });
        }
    }
}
