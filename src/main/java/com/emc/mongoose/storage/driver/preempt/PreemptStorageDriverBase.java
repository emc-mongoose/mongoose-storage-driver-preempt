package com.emc.mongoose.storage.driver.preempt;

import static com.emc.mongoose.base.item.op.Operation.Status.FAIL_UNKNOWN;
import static com.github.akurilov.commons.lang.Exceptions.throwUnchecked;

import com.emc.mongoose.base.data.DataInput;
import com.emc.mongoose.base.config.IllegalConfigurationException;
import com.emc.mongoose.base.item.Item;
import com.emc.mongoose.base.item.op.Operation;
import com.emc.mongoose.base.logging.Loggers;
import com.emc.mongoose.base.storage.driver.StorageDriver;
import com.emc.mongoose.base.storage.driver.StorageDriverBase;
import com.github.akurilov.confuse.Config;
import java.io.EOFException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

public abstract class PreemptStorageDriverBase<I extends Item, O extends Operation<I>>
				extends StorageDriverBase<I, O> implements StorageDriver<I, O> {

	public static final int BATCH_MODE_INPUT_OP_COUNT_LIMIT = 1_000_000;

	private final BlockingQueue<O> incomingOps;
	private final List<Thread> ioWorkers;
	private final LongAdder scheduledOpCount = new LongAdder();
	private final LongAdder completedOpCount = new LongAdder();

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
		ioWorkers = new ArrayList<>(ioWorkerCount);
		final var ioWorkerThreadFactory = ioWorkerThreadFactory();
		for(var i = 0; i < ioWorkerCount; i ++) {
			final var ioWorkerTask = new WorkerTask<>(
				batchSize, incomingOps, this::prepareAndExecute, this::prepareAndExecuteBatch, this::state
			);
			final var ioWorker = ioWorkerThreadFactory.newThread(ioWorkerTask);
			ioWorkers.add(ioWorker);
		}
	}

	@Override
	public final boolean put(final O op)  {
		if(!isStarted()) {
			throwUnchecked(new EOFException());
		}
		final var submitted = incomingOps.offer(op);
		if(submitted) {
			scheduledOpCount.increment();
		}
		return submitted;
	}

	@Override
	public final int put(final List<O> ops, final int from, final int to) {
		if(!isStarted()) {
			throwUnchecked(new EOFException());
		}
		int i = from;
		while(i < to && incomingOps.offer(ops.get(i))) {
			i ++;
		}
		final var n = i - from;
		scheduledOpCount.add(n);
		return n;
	}

	@Override
	public final int put(final List<O> ops)  {
		return put(ops, 0, ops.size());
	}

	final void prepareAndExecute(final O op) {
		if(prepare(op)) {
			execute(op);
		} else {
			op.status(FAIL_UNKNOWN);
		}
	}

	final void prepareAndExecuteBatch(final List<O> ops) {
		// should copy the ops into the other buffer as far as invoker will clean the source buffer after put(...) exit
		final var n = ops.size();
		final var opsRangeCopy = new ArrayList<O>(n);
		O op;
		for(var i = 0; i < n; i ++) {
			op = ops.get(i);
			prepare(op);
			opsRangeCopy.add(op);
		}
		execute(opsRangeCopy);
	}

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
	protected boolean handleCompleted(final O op) {
		completedOpCount.increment();
		return super.handleCompleted(op);
	}

	@Override
	public final int activeOpCount() {
		return (int) (scheduledOpCount() - completedOpCount() - incomingOps.size());
	}

	@Override
	public final long scheduledOpCount() {
		return scheduledOpCount.sum();
	}

	@Override
	public final long completedOpCount() {
		return completedOpCount.sum();
	}

	@Override
	public final boolean isIdle() {
		return activeOpCount() == 0;
	}

	@Override
	protected void doStart() {
		ioWorkers.forEach(Thread::start);
		Loggers.MSG.debug("{}: started", toString());
	}

	@Override
	protected void doShutdown() {
		Loggers.MSG.debug("{}: shut down", toString());
	}

	@Override
	protected void doStop()  {
		Loggers.MSG.debug("{}: interrupting...", toString());
		incomingOps.clear(); // drop all internally pending load operations
		ioWorkers.forEach(Thread::interrupt);
	}

	@Override
	public boolean await(final long timeout, final TimeUnit timeUnit) throws InterruptedException {
		return ioWorkers
			.parallelStream()
			.noneMatch(
				ioWorker -> {
					try {
						ioWorker.join(timeUnit.toMillis(timeout));
					} catch (final InterruptedException e) {
						throwUnchecked(e);
					}
					return ioWorker.isAlive();
				}
			);
	}
}
