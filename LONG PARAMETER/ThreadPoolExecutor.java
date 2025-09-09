package java.util.concurrent;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.*;

public class ThreadPoolExecutor extends AbstractExecutorService {
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
    private static final int COUNT_BITS = Integer.SIZE - 3;
    private static final int CAPACITY = (1 << COUNT_BITS) - 1;
    private static final int RUNNING = -1 << COUNT_BITS;
    private static final int SHUTDOWN = 0 << COUNT_BITS;
    private static final int STOP = 1 << COUNT_BITS;
    private static final int TIDYING = 2 << COUNT_BITS;
    private static final int TERMINATED = 3 << COUNT_BITS;

    private static int runStateOf(int c) { return c & ~CAPACITY; }
    private static int workerCountOf(int c) { return c & CAPACITY; }
    private static int ctlOf(int rs, int wc) { return rs | wc; }
    private static boolean runStateLessThan(int c, int s) { return c < s; }
    private static boolean runStateAtLeast(int c, int s) { return c >= s; }
    private static boolean isRunning(int c) { return c < SHUTDOWN; }

    private boolean compareAndIncrementWorkerCount(int expect) { return ctl.compareAndSet(expect, expect + 1); }
    private boolean compareAndDecrementWorkerCount(int expect) { return ctl.compareAndSet(expect, expect - 1); }
    private void decrementWorkerCount() { do {} while (!compareAndDecrementWorkerCount(ctl.get())); }

    private final BlockingQueue<Runnable> workQueue;
    private final ReentrantLock mainLock = new ReentrantLock();
    private final HashSet<Worker> workers = new HashSet<>();
    private final Condition termination = mainLock.newCondition();
    private int largestPoolSize;
    private long completedTaskCount;

    private volatile ThreadFactory threadFactory;
    private volatile RejectedExecutionHandler handler;
    private volatile long keepAliveTime;
    private volatile boolean allowCoreThreadTimeOut;
    private volatile int corePoolSize;
    private volatile int maximumPoolSize;

    private static final RejectedExecutionHandler defaultHandler = new AbortPolicy();
    private static final RuntimePermission shutdownPerm = new RuntimePermission("modifyThread");
    private static final boolean ONLY_ONE = true;

    private final class Worker extends AbstractQueuedSynchronizer implements Runnable {
        private static final long serialVersionUID = 6138294804551838833L;
        final Thread thread;
        Runnable firstTask;
        volatile long completedTasks;
        Worker(Runnable firstTask) {
            setState(-1);
            this.firstTask = firstTask;
            this.thread = getThreadFactory().newThread(this);
        }
        public void run() { runWorker(this); }
        protected boolean isHeldExclusively() { return getState() != 0; }
        protected boolean tryAcquire(int unused) {
            if (compareAndSetState(0, 1)) { setExclusiveOwnerThread(Thread.currentThread()); return true; }
            return false;
        }
        protected boolean tryRelease(int unused) { setExclusiveOwnerThread(null); setState(0); return true; }
        public void lock() { acquire(1); }
        public boolean tryLock() { return tryAcquire(1); }
        public void unlock() { release(1); }
        public boolean isLocked() { return isHeldExclusively(); }
        void interruptIfStarted() {
            Thread t;
            if (getState() >= 0 && (t = thread) != null && !t.isInterrupted()) {
                try { t.interrupt(); } catch (SecurityException ignore) {}
            }
        }
    }

    private void advanceRunState(int targetState) {
        for (;;) {
            int c = ctl.get();
            if (runStateAtLeast(c, targetState) || ctl.compareAndSet(c, ctlOf(targetState, workerCountOf(c)))) break;
        }
    }

    final void tryTerminate() {
        for (;;) {
            int c = ctl.get();
            if (isRunning(c) || runStateAtLeast(c, TIDYING) || (runStateOf(c) == SHUTDOWN && !workQueue.isEmpty())) return;
            if (workerCountOf(c) != 0) { interruptIdleWorkers(ONLY_ONE); return; }
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                    try { terminated(); } finally { ctl.set(ctlOf(TERMINATED, 0)); termination.signalAll(); }
                    return;
                }
            } finally { mainLock.unlock(); }
        }
    }

    private void checkShutdownAccess() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkPermission(shutdownPerm);
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try { for (Worker w : workers) security.checkAccess(w.thread); }
            finally { mainLock.unlock(); }
        }
    }

    private void interruptWorkers() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try { for (Worker w : workers) w.interruptIfStarted(); }
        finally { mainLock.unlock(); }
    }

    private void interruptIdleWorkers(boolean onlyOne) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker w : workers) {
                Thread t = w.thread;
                if (!t.isInterrupted() && w.tryLock()) {
                    try { t.interrupt(); } catch (SecurityException ignore) {} finally { w.unlock(); }
                }
                if (onlyOne) break;
            }
        } finally { mainLock.unlock(); }
    }

    private void interruptIdleWorkers() { interruptIdleWorkers(false); }

    final void reject(Runnable command) { handler.rejectedExecution(command, this); }

    void onShutdown() {}

    private List<Runnable> drainQueue() {
        BlockingQueue<Runnable> q = workQueue;
        ArrayList<Runnable> taskList = new ArrayList<>();
        q.drainTo(taskList);
        if (!q.isEmpty()) {
            for (Runnable r : q.toArray(new Runnable[0])) {
                if (q.remove(r)) taskList.add(r);
            }
        }
        return taskList;
    }

    private void addWorkerFailed(Worker w) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            if (w != null) workers.remove(w);
            decrementWorkerCount();
            tryTerminate();
        } finally { mainLock.unlock(); }
    }

    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory, RejectedExecutionHandler handler) {
        if (corePoolSize < 0 || maximumPoolSize <= 0 || maximumPoolSize < corePoolSize || keepAliveTime < 0) throw new IllegalArgumentException();
        if (workQueue == null || threadFactory == null || handler == null) throw new NullPointerException();
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.workQueue = workQueue;
        this.keepAliveTime = unit.toNanos(keepAliveTime);
        this.threadFactory = threadFactory;
        this.handler = handler;
    }


    public boolean isShutdown() { return !isRunning(ctl.get()); }
    public boolean isTerminated() { return runStateAtLeast(ctl.get(), TERMINATED); }

    protected void finalize() { shutdown(); }

    public void setThreadFactory(ThreadFactory threadFactory) {
        if (threadFactory == null) throw new NullPointerException();
        this.threadFactory = threadFactory;
    }

    public ThreadFactory getThreadFactory() { return threadFactory; }

    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        if (handler == null) throw new NullPointerException();
        this.handler = handler;
    }

    public RejectedExecutionHandler getRejectedExecutionHandler() { return handler; }

    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 0) throw new IllegalArgumentException();
        int delta = corePoolSize - this.corePoolSize;
        this.corePoolSize = corePoolSize;
        if (workerCountOf(ctl.get()) > corePoolSize) interruptIdleWorkers();
        else if (delta > 0) {
            int k = Math.min(delta, workQueue.size());
            while (k-- > 0 && addWorker(null, true)) { if (workQueue.isEmpty()) break; }
        }
    }

    public int getCorePoolSize() { return corePoolSize; }

    public void allowCoreThreadTimeOut(boolean value) {
        if (value && keepAliveTime <= 0) throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        if (value != allowCoreThreadTimeOut) {
            allowCoreThreadTimeOut = value;
            if (value) interruptIdleWorkers();
        }
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        if (maximumPoolSize <= 0 || maximumPoolSize < corePoolSize) throw new IllegalArgumentException();
        this.maximumPoolSize = maximumPoolSize;
        if (workerCountOf(ctl.get()) > maximumPoolSize) interruptIdleWorkers();
    }

    public int getMaximumPoolSize() { return maximumPoolSize; }

    public void setKeepAliveTime(long time, TimeUnit unit) {
        if (time < 0) throw new IllegalArgumentException();
        if (time == 0 && allowCoreThreadTimeOut) throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        long v = unit.toNanos(time);
        long delta = v - this.keepAliveTime;
        this.keepAliveTime = v;
        if (delta < 0) interruptIdleWorkers();
    }

    public long getKeepAliveTime(TimeUnit unit) { return unit.convert(keepAliveTime, TimeUnit.NANOSECONDS); }

    public boolean remove(Runnable task) {
        boolean removed = workQueue.remove(task);
        tryTerminate();
        return removed;
    }

    protected void beforeExecute(Thread t, Runnable r) {}
    protected void afterExecute(Runnable r, Throwable t) {}
    protected void terminated() {}

}

