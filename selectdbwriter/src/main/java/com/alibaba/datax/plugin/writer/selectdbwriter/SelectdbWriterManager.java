// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.alibaba.datax.plugin.writer.selectdbwriter;

import com.google.common.base.Strings;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class SelectdbWriterManager {

    private static final Logger LOG = LoggerFactory.getLogger(SelectdbWriterManager.class);

    private final SelectdbCopyIntoObserver visitor;
    private final Keys options;
    private final List<byte[]> buffer = new ArrayList<>();
    private int batchCount = 0;
    private long batchSize = 0;
    private volatile boolean closed = false;
    private volatile Exception flushException;
    private final LinkedBlockingDeque<WriterTuple> flushQueue;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledFuture;

    public SelectdbWriterManager(Keys options) {
        this.options = options;
        this.visitor = new SelectdbCopyIntoObserver(options);
        flushQueue = new LinkedBlockingDeque<>(options.getFlushQueueLength());
        this.startScheduler();
        this.startAsyncFlushing();
    }

    public void startScheduler() {
        stopScheduler();
        this.scheduler = Executors.newScheduledThreadPool(1, new BasicThreadFactory.Builder().namingPattern("Doris-interval-flush").daemon(true).build());
        this.scheduledFuture = this.scheduler.schedule(() -> {
            synchronized (SelectdbWriterManager.this) {
                if (!closed) {
                    try {
                        String label = createBatchLabel();
                        LOG.info(String.format("Selectdb interval Sinking triggered: label[%s].", label));
                        if (batchCount == 0) {
                            startScheduler();
                        }
                        flush(label, false);
                    } catch (Exception e) {
                        flushException = e;
                    }
                }
            }
        }, options.getFlushInterval(), TimeUnit.MILLISECONDS);
    }

    public void stopScheduler() {
        if (this.scheduledFuture != null) {
            scheduledFuture.cancel(false);
            this.scheduler.shutdown();
        }
    }

    public final synchronized void writeRecord(String record) throws IOException {
        checkFlushException();
        try {
            byte[] bts = record.getBytes(StandardCharsets.UTF_8);
            buffer.add(bts);
            batchCount++;
            batchSize += bts.length;
            if (batchCount >= options.getBatchRows() || batchSize >= options.getBatchSize()) {
                String label = createBatchLabel();
                if (LOG.isDebugEnabled()) {
                    LOG.debug(String.format("buffer Sinking triggered: rows[%d] label [%s].", batchCount, label));
                }
                flush(label, false);
            }
        } catch (Exception e) {
            throw new SelectdbWriterException("Writing records to selectdb failed.", e);
        }
    }

    public synchronized void flush(String label, boolean waitUtilDone) throws Exception {
        checkFlushException();
        if (batchCount == 0) {
            if (waitUtilDone) {
                waitAsyncFlushingDone();
            }
            return;
        }
        flushQueue.put(new WriterTuple(label, batchSize, new ArrayList<>(buffer)));
        if (waitUtilDone) {
            // wait the last flush
            waitAsyncFlushingDone();
        }
        buffer.clear();
        batchCount = 0;
        batchSize = 0;
    }

    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                String label = createBatchLabel();
                if (batchCount > 0) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(String.format("Selectdb Sink is about to close: label[%s].", label));
                    }
                }
                flush(label, true);
            } catch (Exception e) {
                throw new RuntimeException("Writing records to Selectdb failed.", e);
            }
        }
        checkFlushException();
    }

    public String createBatchLabel() {
        StringBuilder sb = new StringBuilder();
        if (!Strings.isNullOrEmpty(options.getLabelPrefix())) {
            sb.append(options.getLabelPrefix());
        }
        return sb.append(UUID.randomUUID().toString())
                .toString();
    }

    private void startAsyncFlushing() {
        // start flush thread
        Thread flushThread = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        asyncFlush();
                    } catch (Exception e) {
                        flushException = e;
                    }
                }
            }
        });
        flushThread.setDaemon(true);
        flushThread.start();
    }

    private void waitAsyncFlushingDone() throws InterruptedException {
        // wait previous flushings
        for (int i = 0; i <= options.getFlushQueueLength(); i++) {
            flushQueue.put(new WriterTuple("", 0l, null));
        }
        checkFlushException();
    }

    private void asyncFlush() throws Exception {
        WriterTuple flushData = flushQueue.take();
        if (Strings.isNullOrEmpty(flushData.getLabel())) {
            return;
        }
        stopScheduler();
        for (int i = 0; i <= options.getMaxRetries(); i++) {
            try {
                // copy into
                visitor.streamLoad(flushData);
                startScheduler();
                break;
            } catch (Exception e) {
                LOG.warn("Failed to flush batch data to selectdb, retry times = {}", i, e);
                if (i >= options.getMaxRetries()) {
                    throw new RuntimeException(e);
                }
                if (e instanceof SelectdbWriterException && ((SelectdbWriterException) e).needReCreateLabel()) {
                    String newLabel = createBatchLabel();
                    LOG.warn(String.format("Batch label changed from [%s] to [%s]", flushData.getLabel(), newLabel));
                    flushData.setLabel(newLabel);
                }
                try {
                    Thread.sleep(1000l * Math.min(i + 1, 100));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Unable to flush, interrupted while doing another attempt", e);
                }
            }
        }
    }

    private void checkFlushException() {
        if (flushException != null) {
            throw new RuntimeException("Writing records to selectdb failed.", flushException);
        }
    }
}
