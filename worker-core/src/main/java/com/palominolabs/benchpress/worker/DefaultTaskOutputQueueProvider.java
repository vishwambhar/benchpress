package com.palominolabs.benchpress.worker;

import com.google.inject.Singleton;
import com.palominolabs.benchpress.job.task.TaskOutputProcessor;
import com.palominolabs.benchpress.job.task.TaskOutputProcessorFactory;
import com.palominolabs.benchpress.job.task.TaskOutputQueueProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Queue provider that uses a single thread for all tasks of the same type in a single job.
 */
@Singleton
final class DefaultTaskOutputQueueProvider implements TaskOutputQueueProvider {
    private static final Logger logger = LoggerFactory.getLogger(DefaultTaskOutputQueueProvider.class);

    private static final int QUEUE_SIZE = 1024;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final Map<UUID, BlockingQueue<Object>> queueMap = new HashMap<>();
    private final Map<UUID, Future<?>> futureMap = new HashMap<>();

    @Nonnull
    @Override
    public BlockingQueue<Object> getQueue(@Nonnull UUID jobId,
        @Nonnull TaskOutputProcessorFactory taskOutputProcessorFactory) {
        BlockingQueue<Object> blockingQueue = queueMap.get(jobId);
        if (blockingQueue == null) {
            blockingQueue = new ArrayBlockingQueue<>(QUEUE_SIZE);
            queueMap.put(jobId, blockingQueue);

            final TaskOutputProcessor taskOutputProcessor = taskOutputProcessorFactory.getTaskOutputProcessor();
            final BlockingQueue<?> finalBlockingQueue = blockingQueue;
            Future<?> future = executorService.submit(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            taskOutputProcessor.handleOutput(finalBlockingQueue.take());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            logger.info("Queue consumer interrupted");
                            break;
                        } catch (RuntimeException e) {
                            logger.warn("TaskOutputProcessor failed", e);
                        }
                    }
                }
            });

            futureMap.put(jobId, future);
        }

        return blockingQueue;
    }

    @Override
    public void removeJob(@Nonnull UUID jobId) {
        Future<?> future = futureMap.remove(jobId);
        if (future != null) {
            future.cancel(true);
        }

        queueMap.remove(jobId);
    }
}