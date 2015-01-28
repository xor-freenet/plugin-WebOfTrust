package plugins.WebOfTrust.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import freenet.support.Executor;
import freenet.support.PooledExecutor;
import freenet.support.PrioritizedTicker;
import plugins.WebOfTrust.util.TickerDelayedBackgroundJob.JobState;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link TickerDelayedBackgroundJob}.
 *
 * @author bertm
 */
public class TickerDelayedBackgroundJobTest {
    private Executor executor;
    private PrioritizedTicker ticker;
    // Value to increment by running jobs.
    private AtomicInteger value;
    // Canary for unwanted background job concurrency.
    private AtomicBoolean wasConcurrent;
    // Canary for thread interruption.
    private AtomicBoolean wasInterrupted;
    // Thread sleep "randomizer".
    private AtomicInteger rand;
    // Sleeper for timing-sensitive tests.
    private Sleeper sleeper;


    @Before
    public void setUp() throws Exception {
        executor = new PooledExecutor();
        ticker = new PrioritizedTicker(executor, 0);
        value = new AtomicInteger(0);
        wasConcurrent = new AtomicBoolean(false);
        wasInterrupted = new AtomicBoolean(false);
        rand = new AtomicInteger(0);
        sleeper = null;
        ticker.start();
    }

    /**
     * Asserts that our canaries for unwanted concurrency and interruption are not set after each
     * test. Tests that rely on interruption should reset the interruption canary themselves.
     */
    @After
    public void checkCanaries() {
        assertFalse(wasConcurrent.get());
        assertFalse(wasInterrupted.get());
    }

    /**
     * Creates a new, runnable that increments the {@link #value} by 1, then sleeps for the given
     * amount of time. It sets canary {@link #wasConcurrent} when there is more than one
     * concurrently running thread for the same instance, and sets canary {@link #wasInterrupted}
     * when receiving an InterruptedException during sleep.
     * @param sleepTime the sleep time in ms
     */
    private Runnable newValueIncrementer(final long sleepTime) {
        return new Runnable() {
            private AtomicBoolean isRunning = new AtomicBoolean(false);
            @Override
            public void run() {
                if (!isRunning.compareAndSet(false, true)) {
                    wasConcurrent.set(true);
                }
                value.incrementAndGet();
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    wasInterrupted.set(true);
                }
                isRunning.set(false);
            }
        };
    }

    /**
     * Creates a Runnable that invokes {@code job.triggerExecution()} 1000 times, sleeps for about
     * 1 ms, and repeats this for {@code duration} ms.
     * The created Runnable is stateless and can be used multiple times, even concurrently.
     */
    private Runnable newHammerDefault(final DelayedBackgroundJob job, final long duration) {
        return new Runnable() {
            @Override
            public void run() {
                long t = System.currentTimeMillis();
                while (System.currentTimeMillis() < t + duration) {
                    for (int i = 0; i < 1000; i++) {
                        job.triggerExecution();
                    }
                    try {
                        Thread.sleep(1, rand.addAndGet(500));
                    } catch (InterruptedException e) {
                        wasInterrupted.set(true);
                    }
                }
            }
        };
    }

    /**
     * Creates a Runnable that invokes {@code job.triggerExecution(long)} 1 time, sleeps for about
     * 1 ms, and repeats this until all delays are used (first to last).
     * The created Runnable is stateless and can be used multiple times, even concurrently.
     */
    private Runnable newHammerCustom(final DelayedBackgroundJob job, final long[] delays) {
        return new Runnable() {
            @Override
            public void run() {
                for (long delay : delays) {
                    job.triggerExecution(delay);
                    try {
                        Thread.sleep(1, rand.addAndGet(500));
                    } catch (InterruptedException e) {
                        wasInterrupted.set(true);
                    }
                }
            }
        };
    }

    /**
     * Warmup the job/ticker/executor by triggering its immediate execution and waiting for the
     * change to happen 10 times. Restores the {@link #value} after warmup.
     * @param job the job to warm up
     * @param jobDuration the expected duration of the job
     */
    private void warmup(TickerDelayedBackgroundJob job, long jobDuration) throws Exception {
        int val = value.getAndSet(0);
        assertEquals(JobState.IDLE, job.getState());
        for (int i = 1; i <= 10; i++) {
            job.triggerExecution(0);
            assertTrue(job.getState() == JobState.WAITING || job.getState() == JobState.RUNNING);
            Thread.sleep(jobDuration);
            // Wait for at most an additional 20ms for the job to finish.
            sleeper = new Sleeper();
            for (int j = 0; j < 20; j++) {
                if (job.getState() == JobState.IDLE) {
                    break;
                }
                sleeper.sleepUntil(j + 1);
            }
            assertEquals(JobState.IDLE, job.getState());
            assertEquals(i, value.get());
        }
        value.set(val);
    }
    
    /**
     * An ExecutorService which will keep the given amount of threads running and waiting for work
     * right at time of construction, i.e. before any Runnable has been submitted for execution.<br>
     * This greatly reduces the delay which is encountered when calling {@link #execute(Runnable)},
     * as compared to for example pre-creating Java {@link Thread}s and calling
     * {@link Thread#start()} upon them.<br><br>
     * 
     * The motivation behind writing this was that I had encountered {@link Thread#start()} taking
     * up to 10 milliseconds.<br><br>
     * 
     * Notice: This is not based upon {@link PooledExecutor} but rather uses the standard JRE
     * thread pools because {@link PooledExecutor} terminates idle threads upon certain conditions.
     * So pre-creating the threads would not have any effect because they would die soon after.
     * TODO: Code quality: Add this class' features to {@link PooledExecutor}.
     */
    private class FastExecutorService {
     
        private final ExecutorService pool;
        
        public FastExecutorService(int hotThreads) {
            pool = Executors.newFixedThreadPool(hotThreads);
            warmupPoolThreads(hotThreads);
        }
    
        /**
         * Ensures that the {@link #pool} has at least the given amount of living threads ready
         * waiting for work.<br>
         */
        private void warmupPoolThreads(final int threadsToCreate) {
            // Since Java's ExecutorService has no feature for forcing the desired amount of threads
            // to be pre-created, we have to emulate that feature. We do so by forcing it to
            // pre-create them by shoving Runnables into it which block until the desired amount of
            // hreads is alive.
            
            class Counter {
                // Anonymous classes can only access *final* variables. To get a non-final one
                // accessible in the anonymous Runnable below, we must wrap it in this class, and
                // put an instance of the class into a final variable.
                int threads = 0;
            }
            final Counter counter = new Counter();

            synchronized(counter) {
                for(int i=0; i < threadsToCreate; ++i) {
                    pool.execute(new Runnable() { @Override public void run() {
                        synchronized(counter) {
                            ++counter.threads;
                            while(counter.threads < threadsToCreate) {
                                try {
                                    // To ensure that the Executor pools N threads, N threads must
                                    // be blocked. So we must block here.
                                    counter.wait();
                                } catch (InterruptedException e) {
                                    wasInterrupted.set(true);
                                }
                                // Notice: We loop again now to guard against spurious wait() wakeup
                            }
                            counter.notifyAll();
                        }
                    }});
                }
            
                while(counter.threads < threadsToCreate) {
                    try {
                        counter.wait();
                    } catch (InterruptedException e) {
                        wasInterrupted.set(true);
                    }
                }
            }
        }
        
        public void execute(Runnable r) {
            pool.execute(r);
        }
    }
    
    @Test
    public void testFastExecutorService() {
        final AtomicInteger threadCount = new AtomicInteger();
        Runnable sleepingThread = new Runnable() { @Override public void run() {
            threadCount.incrementAndGet();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
            }
            threadCount.decrementAndGet();
        }};
        
        FastExecutorService exec = new FastExecutorService(100);
        Sleeper sleeper = new Sleeper();
        long begin = System.currentTimeMillis();
        for(int i = 0; i < 100 ; ++i)
            exec.execute(sleepingThread);
        long end = System.currentTimeMillis();
        assertTrue((end - begin) < 10);
        sleeper.sleepUntil(50);
        assertEquals(100, threadCount.get());
    }

    /**
     * Creates a new, warmed-up DelayedBackgroundJob that increments the {@link #value} by 1 and
     * waits for {@code jobDuration} ms on each execution, with given aggregation delay.
     * @param jobDuration the job duration
     * @param delay the trigger aggregation delay
     * @return
     */
    private TickerDelayedBackgroundJob newJob(long jobDuration, long delay, String name) throws
            Exception {
        Runnable test = newValueIncrementer(jobDuration);
        TickerDelayedBackgroundJob job = new TickerDelayedBackgroundJob(test, name, delay, ticker);
        warmup(job, jobDuration);
        return job;
    }

    @Test
    public void testTriggerDefault() throws Exception {
        // First test for a reasonable fast test (with execution time smaller than the delay).
        TickerDelayedBackgroundJob job = newJob(10, 50, "default1");

        sleeper = new Sleeper();
        assertEquals(0, value.get());
        assertEquals(JobState.IDLE, job.getState());

        // The value should remain stable if we don't trigger.
        sleeper.sleepUntil(100);
        assertEquals(0, value.get());

        // Timing of schedule (with safety margin): value should not change first 25 ms, and
        // certainly be changed after 75 ms, then remain stable.
        sleeper = new Sleeper();
        assertEquals(JobState.IDLE, job.getState());
        job.triggerExecution();
        sleeper.sleepUntil(25);
        assertEquals(0, value.get());
        assertEquals(JobState.WAITING, job.getState());
        sleeper.sleepUntil(75);
        assertEquals(1, value.get());
        assertEquals(JobState.IDLE, job.getState());
        sleeper.sleepUntil(175);
        assertEquals(1, value.get());
        assertEquals(JobState.IDLE, job.getState());

        // Same as before, but now with 10 threads hammering the trigger for 60 ms: we expect no
        // increase the first 25 ms, one increase after 75 ms, another increase after 125 ms, then
        // remain stable.
        Runnable trigger = newHammerDefault(job, 60);
        FastExecutorService fastExec = new FastExecutorService(10);
        sleeper = new Sleeper(); // Set "t = 0" to the point where we start the trigger threads
        for (int i = 0; i < 10; i++)
            fastExec.execute(trigger);
        assertEquals(1, value.get());
        sleeper.sleepUntil(25);
        assertEquals(1, value.get());
        assertEquals(JobState.WAITING, job.getState());
        sleeper.sleepUntil(75);
        assertEquals(2, value.get());
        assertEquals(JobState.WAITING, job.getState());
        sleeper.sleepUntil(125);
        assertEquals(3, value.get());
        assertEquals(JobState.IDLE, job.getState());
        sleeper.sleepUntil(225);
        assertEquals(3, value.get());
        assertEquals(JobState.IDLE, job.getState());

        // Now test whether a slow background task (with execution time longer than the delay) is
        // handled correctly.
        TickerDelayedBackgroundJob slowJob = newJob(80, 50, "default2");
        Runnable hammer = newHammerDefault(slowJob, 260);
        sleeper = new Sleeper();
        assertEquals(3, value.get());
        assertEquals(JobState.IDLE, slowJob.getState());
        fastExec.execute(hammer);
        sleeper.sleepUntil(50 - 25);
        assertEquals(3, value.get());
        assertEquals("Should be WAITING until t = 50", JobState.WAITING, job.getState());
        sleeper.sleepUntil(50 + 25);
        assertEquals(4, value.get());
        assertEquals("Should be RUNNING until t = 50 + 80", JobState.RUNNING, job.getState());
        sleeper.sleepUntil(50 + 80 + 25);
        assertEquals(5, value.get());
        assertEquals("Should be WAITING until t = 50 + 80 + 50", JobState.WAITING, job.getState());
        sleeper.sleepUntil(50 + 80 + 50 + 80 - 25);
        assertEquals(6, value.get());
        assertEquals("Should be RUNNING until t = 50 + 80 + 50 + 80",
            JobState.RUNNING, job.getState());
        sleeper.sleepUntil(50 + 80 + 50 + 80 + 50 + 5);
        assertEquals(7, value.get());
        assertEquals("Should be RUNNING until t = 50 + 80 + 50 + 80 + 50 + 80",
            JobState.RUNNING, slowJob.getState());
        // The hammer hammered up to t = 260ms, then the job slept for 50ms, and ran for 80ms
        // 260 + 50 + 80 = 390. So after 390, we should be IDLE for ever.
        sleeper.sleepUntil(260 + 50 + 80 + 5);
        assertEquals(7, value.get());
        assertEquals(JobState.IDLE, slowJob.getState());
        // Wait another cycle to be dead sure
        sleeper.sleepUntil(260 + (50 + 80 + 5) * 2);
        assertEquals(7, value.get());
        assertEquals(JobState.IDLE, slowJob.getState());
        assertFalse(wasConcurrent.get());
    }

    @Test
    public void testTriggerCustom() throws Exception {
        // Simple test
        TickerDelayedBackgroundJob job1 = newJob(10, 1000, "custom1");
        Thread hammer = new Thread(newHammerCustom(job1, new long[] {60, 50, 30, 20, 10}));
        sleeper = new Sleeper();
        assertEquals(0, value.get());
        assertEquals(JobState.IDLE, job1.getState());
        hammer.start();
        sleeper.sleepUntil(10);
        assertEquals(0, value.get());
        assertEquals(JobState.WAITING, job1.getState());
        sleeper.sleepUntil(20);
        assertEquals(1, value.get());
        sleeper.sleepUntil(30);
        assertEquals(JobState.IDLE, job1.getState());

        // Default delay plus immediate trigger
        TickerDelayedBackgroundJob job2 = newJob(30, 100, "custom1");
        sleeper = new Sleeper();
        assertEquals(1, value.get());
        assertEquals(JobState.IDLE, job2.getState());
        job2.triggerExecution();
        assertEquals(JobState.WAITING, job2.getState());
        job2.triggerExecution(0);
        sleeper.sleepUntil(10);
        assertEquals(2, value.get());
        assertEquals(JobState.RUNNING, job2.getState());
        job2.triggerExecution();
        sleeper.sleepUntil(50);
        assertEquals(2, value.get());
        assertEquals(JobState.WAITING, job2.getState());
        sleeper.sleepUntil(130);
        assertEquals(3, value.get());
        assertEquals(JobState.RUNNING, job2.getState());
        sleeper.sleepUntil(160);
        assertEquals(3, value.get());
        assertEquals(JobState.IDLE, job2.getState());
    }

    @Test
    public void testTerminate() throws Exception {
        // Test immediate termination on IDLE
        TickerDelayedBackgroundJob job1 = newJob(50, 20, "terminate1");
        assertEquals(JobState.IDLE, job1.getState());
        assertFalse(job1.isTerminated());
        job1.terminate();
        assertEquals(JobState.TERMINATED, job1.getState());
        assertTrue(job1.isTerminated());
        assertFalse(wasInterrupted.get());

        // Test immediate termination on WAITING
        TickerDelayedBackgroundJob job2 = newJob(50, 20, "terminate2");
        assertEquals(JobState.IDLE, job2.getState());
        assertFalse(job2.isTerminated());
        job2.triggerExecution();
        assertEquals(JobState.WAITING, job2.getState());
        assertFalse(job2.isTerminated());
        job2.terminate();
        assertEquals(JobState.TERMINATED, job2.getState());
        assertTrue(job2.isTerminated());
        assertFalse(wasInterrupted.get());

        // Test interrupting termination on RUNNING
        TickerDelayedBackgroundJob job3 = newJob(50, 20, "terminate3");
        assertEquals(JobState.IDLE, job3.getState());
        assertFalse(job3.isTerminated());
        job3.triggerExecution(0);
        Thread.sleep(20);
        assertEquals(JobState.RUNNING, job3.getState());
        // Synchronize here to avoid the race condition where the thread has terminated before we
        // have a chance to inspect its TERMINATING state.
        synchronized(job3) {
            job3.terminate();
            assertEquals(JobState.TERMINATING, job3.getState());
            assertFalse(job3.isTerminated());
        }
        Thread.sleep(20);
        assertEquals(JobState.TERMINATED, job3.getState());
        assertTrue(job3.isTerminated());
        assertTrue(wasInterrupted.get());
        // Reset interrupted flag, otherwise our @After {@link #checkCanaries()} will throw.
        wasInterrupted.set(false);
    }

    @Test
    public void testWaitForTermination() throws Exception {
        long begin, end;
        // Test that the timeout is obeyed within reasonable limits (at most 10 ms too much).
        DelayedBackgroundJob job1 = newJob(0, 50, "wait1");
        for (int i = 0; i < 10; i++) {
            long timeout = 10 * i;
            begin = System.currentTimeMillis();
            job1.waitForTermination(timeout);
            end = System.currentTimeMillis();
            long waited = end - begin;
            assertTrue(waited >= timeout);
            assertTrue(waited <= timeout + 10);
        }

        // Test that terminated jobs return reasonably immediately.
        DelayedBackgroundJob job2 = newJob(0, 50, "wait2");
        job2.terminate();
        begin = System.currentTimeMillis();
        job2.waitForTermination(1000);
        end = System.currentTimeMillis();
        assertTrue(end - begin < 2);

        // Test termination from job and notify
        // Circumvent Java referencing restrictions...
        final DelayedBackgroundJob[] jobs = new DelayedBackgroundJob[1];
        jobs[0] = new TickerDelayedBackgroundJob(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(50);
                    jobs[0].terminate();
                    Thread.sleep(20000);
                } catch (InterruptedException e) {
                    wasInterrupted.set(true);
                }
            }
        }, "wait3", 0, ticker);
        jobs[0].triggerExecution(0);
        assertFalse(jobs[0].isTerminated());
        begin = System.currentTimeMillis();
        jobs[0].waitForTermination(1000);
        end = System.currentTimeMillis();
        assertTrue(jobs[0].isTerminated());
        assertTrue(end - begin >= 40);
        assertTrue(end - begin <= 70);
        assertTrue(wasInterrupted.get());
        // Reset interrupted flag, otherwise our @After {@link #checkCanaries()} will throw.
        wasInterrupted.set(false);
    }

    /** Utility to allow for sustained sleeping until specified time after instantiation. */
    private class Sleeper {
        final long creation = System.currentTimeMillis();
        void sleepUntil(long msFromCreation) {
            try {
                long sleep = creation + msFromCreation - System.currentTimeMillis();
                if (sleep > 0) {
                    Thread.sleep(sleep);
                }
            } catch(InterruptedException e) {
                throw new RuntimeException("Got interrupted during sleep.", e);
            }
        }
    }
}
