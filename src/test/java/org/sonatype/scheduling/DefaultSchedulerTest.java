/**
 * Copyright (c) 2008 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.sonatype.scheduling;

import java.util.Date;
import java.util.concurrent.Callable;

import junit.framework.Assert;

import org.codehaus.plexus.PlexusTestCase;
import org.sonatype.scheduling.schedules.HourlySchedule;
import org.sonatype.scheduling.schedules.ManualRunSchedule;
import org.sonatype.scheduling.schedules.Schedule;

public class DefaultSchedulerTest
    extends PlexusTestCase
{
    protected DefaultScheduler defaultScheduler;

    @Override
    public void setUp()
        throws Exception
    {
        super.setUp();

        defaultScheduler = (DefaultScheduler) lookup( Scheduler.class.getName() );
    }

    public void testSimpleRunnable()
        throws Exception
    {
        TestRunnable tr = null;

        tr = new TestRunnable();

        ScheduledTask<Object> st = defaultScheduler.submit( "default", tr );

        assertEquals( 1, defaultScheduler.getActiveTasks().size() );

        while ( !st.getTaskState().isEndingState() )
        {
            Thread.sleep( 300 );
        }

        assertEquals( 1, tr.getRunCount() );

        assertEquals( TaskState.FINISHED, st.getTaskState() );

        assertEquals( 0, defaultScheduler.getActiveTasks().size() );
    }

    public void testSimpleCallable()
        throws Exception
    {
        TestCallable tr = null;

        tr = new TestCallable();

        ScheduledTask<Integer> st = defaultScheduler.submit( "default", tr );

        assertEquals( 1, defaultScheduler.getActiveTasks().size() );

        while ( !st.getTaskState().isEndingState() )
        {
            Thread.sleep( 300 );
        }

        assertEquals( 1, tr.getRunCount() );

        assertEquals( Integer.valueOf( 0 ), st.getIfDone() );

        assertEquals( TaskState.FINISHED, st.getTaskState() );

        assertEquals( 0, defaultScheduler.getActiveTasks().size() );
    }

    public void testManual()
        throws Exception
    {
        TestCallable tr = new TestCallable();

        ScheduledTask<Integer> st = defaultScheduler.schedule( "default", tr, new ManualRunSchedule() );

        assertEquals( 1, defaultScheduler.getActiveTasks().size() );

        // Give the scheduler a chance to start if it would (it shouldn't that's the test)
        Thread.sleep( 100 );

        assertEquals( TaskState.SUBMITTED, st.getTaskState() );

        st.runNow();

        // Give the task a chance to start
        Thread.sleep( 100 );

        // Now wait for it to finish
        while ( !st.getTaskState().equals( TaskState.SUBMITTED ) )
        {
            Thread.sleep( 100 );
        }

        assertEquals( 1, tr.getRunCount() );

        assertEquals( TaskState.SUBMITTED, st.getTaskState() );

        assertEquals( 1, defaultScheduler.getActiveTasks().size() );

        st.cancel();

        while ( defaultScheduler.getActiveTasks().size() > 0 )
        {
            Thread.sleep( 100 );
        }
    }

    public void testSecondsRunnable()
        throws Exception
    {
        TestRunnable tr = null;

        tr = new TestRunnable();

        long nearFuture = System.currentTimeMillis() + 500;

        Schedule schedule = getEverySecondSchedule( new Date( nearFuture ), new Date( nearFuture + 4900 ) );

        ScheduledTask<Object> st = defaultScheduler.schedule( "default", tr, schedule );

        assertEquals( 1, defaultScheduler.getActiveTasks().size() );

        while ( !st.getTaskState().isEndingState() )
        {
            Thread.sleep( 300 );
        }

        assertEquals( 5, tr.getRunCount() );

        assertEquals( TaskState.FINISHED, st.getTaskState() );

        assertEquals( 0, defaultScheduler.getActiveTasks().size() );
    }

    public void testSecondsCallable()
        throws Exception
    {
        TestCallable tr = null;

        tr = new TestCallable();

        long nearFuture = System.currentTimeMillis() + 500;

        Schedule schedule = getEverySecondSchedule( new Date( nearFuture ), new Date( nearFuture + 4900 ) );

        ScheduledTask<Integer> st = defaultScheduler.schedule( "default", tr, schedule );

        assertEquals( 1, defaultScheduler.getActiveTasks().size() );

        while ( !st.getTaskState().isEndingState() )
        {
            Thread.sleep( 300 );
        }

        assertEquals( 5, tr.getRunCount() );

        assertEquals( 5, st.getResults().size() );

        assertEquals( Integer.valueOf( 0 ), st.getResults().get( 0 ) );

        assertEquals( Integer.valueOf( 1 ), st.getResults().get( 1 ) );

        assertEquals( Integer.valueOf( 2 ), st.getResults().get( 2 ) );

        assertEquals( Integer.valueOf( 3 ), st.getResults().get( 3 ) );

        assertEquals( Integer.valueOf( 4 ), st.getResults().get( 4 ) );

        assertEquals( TaskState.FINISHED, st.getTaskState() );

        assertEquals( 0, defaultScheduler.getActiveTasks().size() );
    }

    public void testCancelRunnable()
        throws Exception
    {
        TestRunnable tr = null;

        tr = new TestRunnable();

        long nearFuture = System.currentTimeMillis() + 500;

        Schedule schedule = getEverySecondSchedule( new Date( nearFuture ), new Date( nearFuture + 4900 ) );

        ScheduledTask<Object> st = defaultScheduler.schedule( "default", tr, schedule );

        assertEquals( 1, defaultScheduler.getActiveTasks().size() );

        st.cancel();

        assertEquals( 0, tr.getRunCount() );

        assertTrue( st.getTaskState().isEndingState() );

        assertEquals( TaskState.CANCELLED, st.getTaskState() );

        assertEquals( 0, defaultScheduler.getActiveTasks().size() );
    }

    public void testCancelCallable()
        throws Exception
    {
        TestCallable tr = null;

        tr = new TestCallable();

        long nearFuture = System.currentTimeMillis() + 500;

        Schedule schedule = getEverySecondSchedule( new Date( nearFuture ), new Date( nearFuture + 4900 ) );

        ScheduledTask<Integer> st = defaultScheduler.schedule( "default", tr, schedule );

        assertEquals( 1, defaultScheduler.getActiveTasks().size() );

        st.cancel();

        assertEquals( 0, tr.getRunCount() );

        assertTrue( st.getTaskState().isEndingState() );

        assertEquals( TaskState.CANCELLED, st.getTaskState() );

        assertEquals( 0, defaultScheduler.getActiveTasks().size() );
    }

    public void testBrokenCallable()
        throws Exception
    {
        BrokenTestCallable callable = new BrokenTestCallable();

        long nearFuture = System.currentTimeMillis() + 500;

        Schedule schedule = getEverySecondSchedule( new Date( nearFuture ), new Date( nearFuture + 1200 ) );

        ScheduledTask<Integer> task = defaultScheduler.schedule( "default", callable, schedule );

        Thread.sleep( 700 );

        assertEquals( TaskState.BROKEN, task.getTaskState() );

        Thread.sleep( 1000 );

        assertEquals( 0, defaultScheduler.getAllTasks().size() );

        // assertEquals( TaskState.BROKEN, task.getTaskState() );
    }

    /**
     * Validate that setting schedule during run properly sets next schedule time
     * 
     * @throws Exception
     */
    public void testChangeScheduleDuringRunCallable()
        throws Exception
    {
        TestChangeScheduleDuringRunCallable callable = new TestChangeScheduleDuringRunCallable();

        long nearFuture = System.currentTimeMillis() + 500;

        Schedule schedule = getEverySecondSchedule( new Date( nearFuture ), new Date( nearFuture + 2400 ) );

        ScheduledTask<Integer> task = defaultScheduler.schedule( "default", callable, schedule );

        callable.setTask( task );

        // save some time and loop until we see time is set properly
        for ( int i = 0; i < 11 && callable.getRunCount() < 1; i++ )
        {
            if ( i == 11 )
            {
                Assert.fail( "Waited too long for callable to have run count greater than 2 it is "
                    + callable.getRunCount() );
            }
            Thread.sleep( 500 );
        }

        // if the next run we set, and the next run of task are the same, its proof that we
        // have broken the cycle and introduced new schedule
        Assert.assertEquals( callable.getNextRun(), task.getNextRun() );

        task.cancel( true );
    }

    protected Schedule getEverySecondSchedule( Date start, Date stop )
    {
        return new FewSecondSchedule( start, stop, 1 );
    }

    // Helper classes

    public class TestRunnable
        implements Runnable
    {
        private int runCount = 0;

        public void run()
        {
            runCount++;
        }

        public int getRunCount()
        {
            return runCount;
        }
    }

    public class TestCallable
        implements Callable<Integer>
    {
        private int runCount = 0;

        public Integer call()
            throws Exception
        {
            return runCount++;
        }

        public int getRunCount()
        {
            return runCount;
        }
    }

    public class BrokenTestCallable
        implements Callable<Integer>
    {
        public Integer call()
            throws Exception
        {
            throw new Exception( "Test task failed to run" );
        }
    }

    public class TestChangeScheduleDuringRunCallable
        implements Callable<Integer>
    {
        private int runCount = 0;

        private ScheduledTask<?> task;

        private Date futureRun;

        public Integer call()
            throws Exception
        {
            futureRun = new Date( System.currentTimeMillis() + 200000 );

            // by doing this, we should see the next scheduled time 200 seconds in future
            task.setSchedule( new HourlySchedule( futureRun, null ) );

            return runCount++;
        }

        public int getRunCount()
        {
            return runCount;
        }

        public void setTask( ScheduledTask<?> task )
        {
            this.task = task;
        }

        public Date getNextRun()
        {
            return futureRun;
        }
    }
}
