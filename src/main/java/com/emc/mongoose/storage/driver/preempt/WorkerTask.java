package com.emc.mongoose.storage.driver.preempt;

import com.emc.mongoose.base.logging.LogUtil;
import com.emc.mongoose.base.logging.Loggers;
import com.github.akurilov.commons.concurrent.AsyncRunnable.State;
import org.apache.logging.log4j.Level;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.emc.mongoose.base.Exceptions.throwUncheckedIfInterrupted;
import static com.github.akurilov.commons.concurrent.AsyncRunnable.State.INITIAL;
import static com.github.akurilov.commons.concurrent.AsyncRunnable.State.SHUTDOWN;
import static com.github.akurilov.commons.concurrent.AsyncRunnable.State.STARTED;

public class WorkerTask<T extends List<?>>
implements Runnable {

    private final Queue<T> inQueue;
    private final Semaphore inQueueLimiter;
    private final Consumer<T> batchAction;
    private final Supplier<State> stateSupplier;

    public WorkerTask(
        final Queue<T> inQueue, final Semaphore inQueueLimiter, final Consumer<T> batchAction,
        final Supplier<State> stateSupplier
    ) {
        this.inQueue = inQueue;
        this.inQueueLimiter = inQueueLimiter;
        this.batchAction = batchAction;
        this.stateSupplier = stateSupplier;
    }

    @Override
    public final void run() {
        final var workerName = Thread.currentThread().getName();
        Loggers.MSG.debug("{}: started", workerName);
        try {
            while(true) {
                final var ops = inQueue.poll();
                Loggers.MSG.info("queue head is: {}", ops);
                if(null == ops) {
                    final var state = stateSupplier.get();
                    Loggers.MSG.info("curent state for queue head {} is {}", ops, state);
                    if(SHUTDOWN.equals(state)) {
                        Loggers.MSG.debug("{}: the state is shutdown and nothing to do more, exit", workerName);
                        break;
                    } else if(!INITIAL.equals(state) && !STARTED.equals(state)) {
                        Loggers.MSG.debug("{}: the state is {}, exit", workerName, state);
                        break;
                    } else {
                        LockSupport.parkNanos(1);
                    }
                } else {
                    Loggers.MSG.info("Semaphore releases operations: {}", ops.size());
                    inQueueLimiter.release(ops.size());
                    batchAction.accept(ops);
                }
            }
        } catch (final Throwable e) {
            throwUncheckedIfInterrupted(e);
            LogUtil.exception(Level.WARN, e, "Unexpected worker failure");
        } finally {
            Loggers.MSG.debug("{}: finished", Thread.currentThread().getName());
        }
    }
}
