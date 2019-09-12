package com.emc.mongoose.storage.driver.preempt;

import com.emc.mongoose.base.logging.LogUtil;
import com.github.akurilov.commons.concurrent.AsyncRunnable.State;
import org.apache.logging.log4j.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.emc.mongoose.base.Exceptions.throwUncheckedIfInterrupted;
import static com.github.akurilov.commons.concurrent.AsyncRunnable.State.SHUTDOWN;
import static com.github.akurilov.commons.concurrent.AsyncRunnable.State.STARTED;

public class WorkerTask<T>
implements Runnable {

    private final int batchSize;
    private final BlockingQueue<T> inQueue;
    private final Consumer<T> action;
    private final Consumer<List<T>> batchAction;
    private final Supplier<State> stateSupplier;

    public WorkerTask(
        final int batchSize, final BlockingQueue<T> inQueue, final Consumer<T> action,
        final Consumer<List<T>> batchAction, final Supplier<State> stateSupplier
    ) {
        this.batchSize = batchSize;
        this.inQueue = inQueue;
        this.action = action;
        this.batchAction = batchAction;
        this.stateSupplier = stateSupplier;
    }

    @Override
    public final void run() {
        final var ops = new ArrayList<T>(batchSize);
        try {
            while(true) {
                final var state = stateSupplier.get();
                final var n = inQueue.drainTo(ops, batchSize);
                if(SHUTDOWN.equals(state)) {
                    if(0 == n) {
                        break; // shut down and nothing to do more, exit the loop
                    }
                } else if(!STARTED.equals(state)) {
                    break; // not shut down and not started, exit the loop
                }
                if(1 == n) {
                    action.accept(ops.get(0));
                } else if(n > 1) {
                    batchAction.accept(ops.subList(0, n));
                }
                ops.clear();
            }
        } catch (final Throwable e) {
            throwUncheckedIfInterrupted(e);
            LogUtil.exception(Level.WARN, e, "Unexpected worker failure");
        }
    }
}
