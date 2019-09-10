package com.emc.mongoose.storage.driver.preempt;

import static com.github.akurilov.commons.lang.Exceptions.throwUnchecked;

import com.emc.mongoose.base.data.DataInput;
import com.emc.mongoose.base.config.IllegalConfigurationException;
import com.emc.mongoose.base.item.Item;
import com.emc.mongoose.base.item.op.Operation;
import com.emc.mongoose.base.item.op.Operation.Status;
import com.emc.mongoose.base.logging.Loggers;
import com.emc.mongoose.base.storage.driver.StorageDriver;
import com.emc.mongoose.base.storage.driver.StorageDriverBase;
import com.github.akurilov.confuse.Config;
import java.io.EOFException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public abstract class PreemptStorageDriverBase<I extends Item, O extends Operation<I>>
				extends StorageDriverBase<I, O> implements StorageDriver<I, O> {

	public static final int BATCH_MODE_INPUT_OP_COUNT_LIMIT = 1_000_000;

	private final ThreadPoolExecutor ioExecutor;
	private final BlockingQueue<Runnable> incomingOps;

	protected abstract ThreadFactory ioWorkerThreadFactory();

	protected PreemptStorageDriverBase(
					final String stepId,
					final DataInput itemDataInput,
					final Config storageConfig,
					final boolean verifyFlag,
					final int batchSize)
					throws IllegalConfigurationException {
		super(stepId, itemDataInput, storageConfig, verifyFlag);
		final var inQueueSize = storageConfig.intVal("driver-limit-queue-input");
		final var maxOpCount = inQueueSize * batchSize;
		if(BATCH_MODE_INPUT_OP_COUNT_LIMIT < maxOpCount) {
			Loggers.ERR.warn(
				"The product of the batch size and input queue size is " + maxOpCount + " which may cause out of " +
					"memory, please consider tuning"
			);
		}
		incomingOps = new ArrayBlockingQueue<>(inQueueSize);
		ioExecutor = new ThreadPoolExecutor(
						ioWorkerCount,
						ioWorkerCount,
						0,
						TimeUnit.SECONDS,
						incomingOps,
						ioWorkerThreadFactory());
	}

	@Override
	public final boolean put(final O op)  {
		if(!isStarted()) {
			throwUnchecked(new EOFException());
		}
		return incomingOps.offer(wrapToBlocking(op));
	}

	@Override
	public final int put(final List<O> ops, final int from, final int to) {
		if(!isStarted()) {
			throwUnchecked(new EOFException());
		}
		int i = from;
		if(isBatch(ops, from, to)) {
			if(incomingOps.offer(wrapToBlocking(ops, from, to))) {
				i = to;
			}
		} else {
			while(i < to) {
				incomingOps.offer(wrapToBlocking(ops.get(i)));
				i ++;
			}
		}
		return i - from;
	}

	@Override
	public final int put(final List<O> ops)  {
		return put(ops, 0, ops.size());
	}

	private Runnable wrapToBlocking(final O op)  {
		if (prepare(op)) {
			return () -> execute(op);
		} else {
			return () -> op.status(Status.FAIL_UNKNOWN);
		}
	}

	private Runnable wrapToBlocking(final List<O> ops, final int from, final int to) {
		// should copy the ops into the other buffer as far as invoker will clean the source buffer after put(...) exit
		final var opsRangeCopy = new ArrayList<O>();
		O op;
		for(var i = from; i < to; i ++) {
			op = ops.get(i);
			prepare(op);
			opsRangeCopy.add(op);
		}
		return () -> execute(opsRangeCopy);
	}

	protected abstract boolean isBatch(final List<O> ops, final int from, final int to);

	/**
	 * Should invoke or schedule handleCompleted call
	 * @param op
	 */
	protected abstract void execute(final O op);

	/**
	 * Should invoke or scheduled handleCompleted call for each operation
	 * @param ops
	 */
	protected abstract void execute(final List<O> ops);

	@Override
	public final int activeOpCount() {
		return ioExecutor.getActiveCount();
	}

	@Override
	public final long scheduledOpCount() {
		return ioExecutor.getTaskCount();
	}

	@Override
	public final long completedOpCount() {
		return ioExecutor.getCompletedTaskCount();
	}

	@Override
	public final boolean isIdle() {
		return ioExecutor.getActiveCount() == 0;
	}

	@Override
	protected void doStart() {
		ioExecutor.prestartAllCoreThreads();
		Loggers.MSG.debug("{}: started", toString());
	}

	@Override
	protected void doShutdown() {
		// prevent enqueuing new load operations
		ioExecutor.shutdown();
		// drop all pending load operations
		incomingOps.clear();
		Loggers.MSG.debug("{}: shut down", toString());
	}

	@Override
	protected void doStop()  {
		Loggers.MSG.debug("{}: interrupting...", toString());
		try {
			if (ioExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
				Loggers.MSG.debug("{}: interrupting finished in 1 seconds", toString());
			} else {
				Loggers.ERR.debug("{}: interrupting did not finish in 1 second, forcing", toString());
			}
		} catch (final InterruptedException e) {
			ioExecutor.shutdownNow();
			throwUnchecked(e);
		} finally {
			Loggers.MSG.debug("{}: interrupted", toString());
		}
	}

	@Override
	public boolean await(final long timeout, final TimeUnit timeUnit) throws InterruptedException {
		return ioExecutor.awaitTermination(timeout, timeUnit);
	}
}
