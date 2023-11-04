/*
 * Copyright (c) 2023 Contributors to the Eclipse Foundation
 * Copyright (c) 2010, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */
package org.glassfish.enterprise.concurrent.test.virtualthreads;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

import org.glassfish.enterprise.concurrent.test.AwaitableManagedTaskListenerImpl;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import org.glassfish.enterprise.concurrent.AbstractManagedExecutorService.RejectPolicy;
import org.glassfish.enterprise.concurrent.spi.ContextSetupProvider;
import org.glassfish.enterprise.concurrent.test.BlockingRunnableImpl;
import org.glassfish.enterprise.concurrent.test.ManagedBlockingRunnableTask;
import org.glassfish.enterprise.concurrent.test.ManagedTaskListenerImpl;
import org.glassfish.enterprise.concurrent.test.RunnableImpl;
import org.glassfish.enterprise.concurrent.test.TestContextService;
import org.glassfish.enterprise.concurrent.test.Util;
import org.glassfish.enterprise.concurrent.test.Util.BooleanValueProducer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import org.glassfish.enterprise.concurrent.AbstractManagedExecutorService;
import org.glassfish.enterprise.concurrent.ContextServiceImpl;
import org.glassfish.enterprise.concurrent.ManagedExecutorServiceAdapterTest;
import org.glassfish.enterprise.concurrent.test.ManagedRunnableTask;
import org.glassfish.enterprise.concurrent.virtualthreads.VirtualThreadsManagedExecutorService;
import org.glassfish.enterprise.concurrent.virtualthreads.VirtualThreadsManagedThreadFactory;
import org.junit.Test;

/**
 * Tests for Life cycle APIs in VirtualThreadsManagedExecutorService
 *
 */
public class VirtualThreadsManagedExecutorServiceTest {

    System.Logger logger = System.getLogger(this.getClass().getName());

    /**
     * Test for shutdown and isShutdown to verify that we do not regress Java SE ExecutorService functionality
     */
    @Test
    public void testShutdown() {
        ManagedExecutorService mes
                = createManagedExecutorWithMaxOneParallelTask("testShutdown", null);
        assertFalse(mes.isShutdown());
        mes.shutdown();
        assertTrue(mes.isShutdown());
    }

    /**
     * Verifies that when the executor is shut down using shutdownNow() - all submitted tasks are cancelled if not
     * running, and - all running task threads are interrupted, and - all registered ManagedTaskListeners are invoked
     *
     *
     */
    @Test
    public void testShutdownNow_tasks_behavior() throws InterruptedException, InterruptedException {
        ManagedExecutorService mes
                = createManagedExecutor("testShutdown_tasks_behavior", 2, 2); // max tasks=2, queue=2
        ManagedTaskListenerImpl listener1 = new ManagedTaskListenerImpl();
        final BlockingRunnableImpl task1 = new ManagedBlockingRunnableTask(listener1, 0L);
        logger.log(System.Logger.Level.INFO, "task1: " + task1);
        Future f1 = mes.submit(task1); // this task should be run

        ManagedTaskListenerImpl listener2 = new ManagedTaskListenerImpl();
        BlockingRunnableImpl task2 = new ManagedBlockingRunnableTask(listener2, 0L);
        Future f2 = mes.submit(task2); // this task should be queued

        ManagedTaskListenerImpl listener3 = new ManagedTaskListenerImpl();
        BlockingRunnableImpl task3 = new ManagedBlockingRunnableTask(listener3, 0L);
        Future f3 = mes.submit(task3); // this task should be queued
        // waits for task1 to start
        Util.waitForTaskStarted(f1, listener1, getLoggerName());

        mes.shutdownNow();

        // task2 and task3 should be cancelled
        Util.waitForTaskAborted(f2, listener2, getLoggerName());
        assertTrue(f2.isCancelled());
        assertTrue(listener2.eventCalled(f2, ManagedTaskListenerImpl.ABORTED));

        Util.waitForTaskAborted(f3, listener3, getLoggerName());
        assertTrue(f3.isCancelled());
        assertTrue(listener3.eventCalled(f3, ManagedTaskListenerImpl.ABORTED));

        // task1 should be interrupted
        Util.waitForBoolean(task1::isInterrupted, true, getLoggerName());
        assertTrue(task1.isInterrupted());
    }

    @Test
    public void testMaxParallelTasks_limitation() throws InterruptedException {
        ManagedExecutorService mes
                = createManagedExecutor("testShutdown_tasks_behavior", 2, 2); // max tasks=2, queue=2
        ManagedTaskListenerImpl listener1 = new ManagedTaskListenerImpl();
        final BlockingRunnableImpl task1 = new ManagedBlockingRunnableTask(listener1, 0);
        Future f1 = mes.submit(task1); // this task should be run

        ManagedTaskListenerImpl listener2 = new ManagedTaskListenerImpl();
        BlockingRunnableImpl task2 = new ManagedBlockingRunnableTask(listener2, 0);
        Future f2 = mes.submit(task2); // this task should be queued

        // waits for task1 to start
        assertTrue(Util.waitForTaskStarted(f1, listener1, getLoggerName()));
        assertTrue(Util.waitForTaskStarted(f2, listener2, getLoggerName()));

        ManagedTaskListenerImpl listener3 = new ManagedTaskListenerImpl();
        RunnableImpl task3 = new ManagedRunnableTask(listener3);
        Future f3 = mes.submit(task3); // this task should be queued

        // wait for some time so tasks have some chance to start before assertions are made
        Thread.sleep(Duration.ofSeconds(1));

        // task3 should wait with starting while the other 2 tasks are running
        assertFalse(listener3.eventCalled(f3, ManagedTaskListenerImpl.STARTING));
        assertFalse(f1.isDone());
        assertFalse(f2.isDone());

        task1.stopBlocking();
        task2.stopBlocking();

        // tasks should complete successfully
        assertTrue(Util.waitForTaskComplete(task1, getLoggerName()));
        assertTrue(Util.waitForTaskComplete(task2, getLoggerName()));
        assertTrue(Util.waitForTaskComplete(task3, getLoggerName()));

        assertTrue(f1.isDone());
        assertTrue(f2.isDone());
        assertTrue(f3.isDone());
    }

    @Test
    public void testMaxQueueSize_limitation() {
        fail("To be implemented...test that tasks are thrown away if max queue size reached");
        // also test that tasks can be created it queue size is -1 (unlimited)
    }

    /**
     * Test for shutdownNow to verify that we do not regress Java SE ExecutorService functionality
     */
    @Test
    public void testShutdownNow() {
        ManagedExecutorService mes
                = createManagedExecutorWithMaxOneParallelTask("testShutdownNow", null);
        assertFalse(mes.isShutdown());
        List<Runnable> tasks = mes.shutdownNow();
        assertTrue(tasks.isEmpty());
        assertTrue(mes.isShutdown());
        assertTrue(mes.isTerminated());
    }

    /**
     * Test for shutdownNow with unfinished task to verify that we do not regress Java SE ExecutorService functionality
     */
    @Test
    public void testShutdownNow_unfinishedTask() {
        ManagedExecutorService mes
                = createManagedExecutorWithMaxOneParallelTask("testShutdown_unfinishedTask", null);
        assertFalse(mes.isShutdown());
        ManagedTaskListenerImpl listener = new ManagedTaskListenerImpl();
        BlockingRunnableImpl task1 = new ManagedBlockingRunnableTask(listener, 0L);
        logger.log(System.Logger.Level.DEBUG, "Submitting task1 = " + task1);
        Future f = mes.submit(task1);
        // waits for task to start
        Util.waitForTaskStarted(f, listener, getLoggerName());
        RunnableImpl task2 = new RunnableImpl(null);
        mes.submit(task2); // this task cannot start until task1 has finished
        List<Runnable> tasks = mes.shutdownNow();
        assertFalse(mes.isTerminated());

        assertThat(tasks.size(), is(greaterThan(0)));
        task1.stopBlocking();
        assertTrue(mes.isShutdown());
    }

    /**
     * Test for awaitsTermination with unfinished task to verify that we do not regress Java SE ExecutorService
     * functionality
     */
    @Test
    public void testAwaitsTermination() throws Exception {
        ManagedExecutorService mes
                = createManagedExecutorWithMaxOneParallelTask("testAwaitsTermination", null);
        assertFalse(mes.isShutdown());
        ManagedTaskListenerImpl listener = new ManagedTaskListenerImpl();
        BlockingRunnableImpl task = new ManagedBlockingRunnableTask(listener, 0L);
        Future f = mes.submit(task);
        // waits for task to start
        Util.waitForTaskStarted(f, listener, getLoggerName());
        mes.shutdown();
        try {
            assertFalse(mes.awaitTermination(1, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Logger.getLogger(VirtualThreadsManagedExecutorServiceTest.class.getName()).log(Level.SEVERE, null, ex);
        }
        task.stopBlocking();
        try {
            assertTrue(mes.awaitTermination(10, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Logger.getLogger(VirtualThreadsManagedExecutorServiceTest.class.getName()).log(Level.SEVERE, null, ex);
        }
        assertTrue(mes.isTerminated());
    }

    @Test
    public void testTaskCounters() {
        final AbstractManagedExecutorService mes
                = (AbstractManagedExecutorService) createManagedExecutorWithMaxOneParallelTask("testTaskCounters", null);
        assertEquals(0, mes.getTaskCount());
        assertEquals(0, mes.getCompletedTaskCount());
        RunnableImpl task = new RunnableImpl(null);
        Future future = mes.submit(task);
        try {
            future.get();
        } catch (InterruptedException | ExecutionException ex) {
            Logger.getLogger(ManagedExecutorServiceAdapterTest.class.getName()).log(Level.SEVERE, null, ex);
        }
        assertTrue(future.isDone());
        Util.waitForBoolean(new BooleanValueProducer() {
            @Override
            public boolean getValue() {
                return (mes.getTaskCount() > 0) && (mes.getCompletedTaskCount() > 0);
            }
        },
                true, getLoggerName()
        );

        assertEquals(1, mes.getTaskCount());
        assertEquals(1, mes.getCompletedTaskCount());
    }

    @Test
    public void testThreadLifeTime() throws InterruptedException, ExecutionException, TimeoutException {
        final AbstractManagedExecutorService mes
                = createManagedExecutor("testThreadLifeTime",
                        2, 0, 3L, 0L, false);

        Collection<Thread> threads = mes.getThreads();
        assertNull(threads);

        AwaitableManagedTaskListenerImpl taskListener = new AwaitableManagedTaskListenerImpl();
        RunnableImpl runnable = new ManagedRunnableTask(taskListener);

        Future f = mes.submit(runnable);
        threads = mes.getThreads();
        assertEquals(1, threads.size());
        try {
            f.get();
        } catch (Exception ex) {
            Logger.getLogger(VirtualThreadsManagedExecutorServiceTest.class.getName()).log(Level.SEVERE, null, ex);
        }

        taskListener.whenDone().get(5, TimeUnit.SECONDS);
        assertNull("All virtual threads should be discarded after tasks are done", mes.getThreads());

    }

    @Test
    public void testHungThreads() {
        final AbstractManagedExecutorService mes
                = createManagedExecutor("testThreadLifeTime",
                        2, 0, 0L, 1L, false);

        Collection<Thread> threads = mes.getHungThreads();
        assertNull(threads);

        BlockingRunnableImpl runnable = new BlockingRunnableImpl(null, 0L);
        Future f = mes.submit(runnable);
        try {
            Thread.sleep(1000); // sleep for 1 second
        } catch (InterruptedException ex) {
        }

        // should get one hung thread
        threads = mes.getHungThreads();
        assertEquals(1, threads.size());

        // tell task to stop waiting
        runnable.stopBlocking();
        Util.waitForTaskComplete(runnable, getLoggerName());

        // should not have any more hung threads
        threads = mes.getHungThreads();
        assertNull(threads);
    }

    @Test
    public void testHungThreads_LongRunningTasks() {
        final AbstractManagedExecutorService mes
                = createManagedExecutor("testThreadLifeTime",
                        2, 0, 0L, 1L, true);

        Collection<Thread> threads = mes.getHungThreads();
        assertNull(threads);

        BlockingRunnableImpl runnable = new BlockingRunnableImpl(null, 0L);
        Future f = mes.submit(runnable);
        try {
            Thread.sleep(1000); // sleep for 1 second
        } catch (InterruptedException ex) {
        }

        // should not get any hung thread because longRunningTasks is true
        threads = mes.getHungThreads();
        assertNull(threads);

        // tell task to stop waiting
        runnable.stopBlocking();
        Util.waitForTaskComplete(runnable, getLoggerName());

        // should not have any more hung threads
        threads = mes.getHungThreads();
        assertNull(threads);
    }

    protected VirtualThreadsManagedExecutorServiceExt createManagedExecutorWithMaxOneParallelTask(String name,
            ContextSetupProvider contextCallback) {
        return new VirtualThreadsManagedExecutorServiceExt(name, new VirtualThreadsManagedThreadFactory(name),
                0, false,
                1,
                Integer.MAX_VALUE,
                new TestContextService(contextCallback),
                RejectPolicy.ABORT);
    }

    protected VirtualThreadsManagedExecutorServiceExt createManagedExecutor(String name, int maxParallelTasks, int queueSize) {
        return createManagedExecutor(name, maxParallelTasks,
                queueSize, 0L, 0L, false);
    }

    protected VirtualThreadsManagedExecutorServiceExt createManagedExecutor(String name,
            int maxParallelTasks, int queueSize, long threadLifeTime,
            long hungTask, boolean longRunningTasks) {
        return new VirtualThreadsManagedExecutorServiceExt(name, new VirtualThreadsManagedThreadFactory(name),
                hungTask, longRunningTasks,
                maxParallelTasks,
                queueSize,
                new TestContextService(null),
                RejectPolicy.ABORT);
    }

    public String getLoggerName() {
        return VirtualThreadsManagedExecutorServiceTest.class.getName();
    }

    public static class VirtualThreadsManagedExecutorServiceExt extends VirtualThreadsManagedExecutorService {

        public VirtualThreadsManagedExecutorServiceExt(String name, VirtualThreadsManagedThreadFactory managedThreadFactory, long hungTaskThreshold, boolean longRunningTasks, int maxParallelTasks, int queueCapacity, ContextServiceImpl contextService, RejectPolicy rejectPolicy) {
            super(name, managedThreadFactory, hungTaskThreshold, longRunningTasks, maxParallelTasks, queueCapacity, contextService, rejectPolicy);
        }

        @Override
        public ExecutorService getThreadPoolExecutor() {
            return super.getThreadPoolExecutor();
        }

    }

}
