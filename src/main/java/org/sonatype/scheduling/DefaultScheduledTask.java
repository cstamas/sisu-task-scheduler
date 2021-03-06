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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.sonatype.scheduling.iterators.NoopSchedulerIterator;
import org.sonatype.scheduling.iterators.SchedulerIterator;
import org.sonatype.scheduling.schedules.ManualRunSchedule;
import org.sonatype.scheduling.schedules.Schedule;

public class DefaultScheduledTask<T>
    implements ScheduledTask<T>, Callable<T>
{
    private final String id;

    private String name;

    private final String type;

    private final DefaultScheduler scheduler;

    private final Callable<T> callable;

    private TaskState taskState;

    private Date scheduledAt;

    private Future<T> future;

    private Throwable throwable;

    private boolean enabled;

    private Date lastRun;

    private Date nextRun;

    private List<T> results;

    private Schedule schedule;

    private SchedulerIterator scheduleIterator;

    private volatile ProgressListener progressListener;

    boolean manualRun;

    private long duration;

    private TaskState lastStatus;

    private boolean toBeRemoved = false;

    public DefaultScheduledTask( String id, String name, String type, DefaultScheduler scheduler, Callable<T> callable,
                                 Schedule schedule )
    {
        super();

        this.id = id;

        this.name = name;

        this.type = type;

        this.scheduler = scheduler;

        this.callable = callable;

        this.taskState = TaskState.SUBMITTED;

        this.enabled = true;

        this.results = new ArrayList<T>();

        this.schedule = schedule;

        this.scheduleIterator = null;

        this.nextRun = null;

        this.manualRun = false;
    }

    public SchedulerTask<T> getSchedulerTask()
    {
        final Callable<T> task = getTask();

        if ( task != null && task instanceof SchedulerTask<?> )
        {
            return (SchedulerTask<T>) task;
        }
        else
        {
            return null;
        }
    }

    public ProgressListener getProgressListener()
    {
        return progressListener;
    }

    public Callable<T> getTask()
    {
        return callable;
    }

    protected void start()
    {
        this.scheduledAt = new Date();

        setFuture( reschedule() );
    }

    protected Future<T> getFuture()
    {
        return future;
    }

    protected void setFuture( Future<T> future )
    {
        this.future = future;
    }

    protected DefaultScheduler getScheduler()
    {
        return scheduler;
    }

    protected void setTaskState( TaskState state )
    {
        if ( !getTaskState().isEndingState() )
        {
            this.taskState = state;
        }
    }

    protected void setBrokenCause( Throwable e )
    {
        this.throwable = e;
    }

    protected Callable<T> getCallable()
    {
        return callable;
    }

    protected boolean isManualRunScheduled()
    {
        return ManualRunSchedule.class.isAssignableFrom( getSchedule().getClass() );
    }

    public String getId()
    {
        return id;
    }

    public String getType()
    {
        return type;
    }

    public TaskState getTaskState()
    {
        return taskState;
    }

    public Date getScheduledAt()
    {
        return scheduledAt;
    }

    public void cancelOnly()
    {
        cancel( false, getScheduleIterator().isFinished() );
    }

    public void cancel()
    {
        cancel( false );
    }

    public void cancel( boolean interrupt )
    {
        cancel( interrupt, true );
    }

    public void cancel( boolean interrupt, boolean removeTask )
    {
        final ProgressListener progressListener = getProgressListener();
        TaskState originalState = getTaskState();

        // only go into cancelling state if task is actually doing something
        if ( originalState.isExecuting() || originalState.equals( TaskState.SLEEPING ) )
        {
            setTaskState( TaskState.CANCELLING );

            if ( progressListener != null )
            {
                progressListener.cancel();
            }

            // to prevent starting it if not yet started
            if ( getFuture() != null )
            {
                getFuture().cancel( interrupt );
            }

            if ( originalState.equals( TaskState.SLEEPING ) )
            {
                // manualRun would be reset on transition to RUNNING, so we need to do that here as well
                manualRun = false;
                // NEXUS-4681 set last run to identify we tried to run this
                setLastRun( new Date() );

                // remove one-shots
                if ( getScheduleIterator().isFinished() )
                {
                    removeTask = true;
                }
                else if ( !removeTask )
                {
                    // only reschedule/set new state if task is not to be removed
                    // (maintain correct state transitions, e.g. SUBMITTED -> CANCELLED not allowed)
                    reschedule();
                    TaskState newState = isManualRunScheduled() ? TaskState.SUBMITTED : TaskState.WAITING;
                    setTaskState( newState );
                }
            }
            setToBeRemoved( removeTask );
        }

        // if this task is not executing, it can be immediately removed from task map
        if ( removeTask && !originalState.isExecuting() )
        {
            setTaskState( TaskState.CANCELLED );
            getScheduler().removeFromTasksMap( this );
        }
    }

    public void reset()
    {
        final ProgressListener progressListener = getProgressListener();

        if ( progressListener != null )
        {
            progressListener.cancel();
        }

        // to prevent starting it if not yet started
        if ( getFuture() != null )
        {
            getFuture().cancel( false );
        }

        setTaskState( TaskState.SUBMITTED );

        setFuture( reschedule() );
    }

    public Throwable getBrokenCause()
    {
        return throwable;
    }

    public T get()
        throws ExecutionException, InterruptedException
    {
        return getFuture().get();
    }

    public T getIfDone()
    {
        if ( TaskState.FINISHED.equals( getTaskState() ) )
        {
            try
            {
                return getFuture().get();
            }
            catch ( ExecutionException e )
            {
                return null;
            }
            catch ( InterruptedException e )
            {
                return null;
            }
        }
        else
        {
            return null;
        }
    }

    protected void setLastRun( Date lastRun )
    {
        this.lastRun = new Date( lastRun.getTime() + 20 );
    }

    protected void setLastStatus( TaskState lastStatus )
    {
        this.lastStatus = lastStatus;
    }

    protected void setDuration( long duration )
    {
        this.duration = duration;
    }

    protected Future<T> reschedule()
    {
        if ( !isManualRunScheduled() )
        {
            SchedulerIterator iter = getScheduleIterator();

            if ( iter != null && !iter.isFinished() )
            {
                nextRun = iter.next();

                long nextTime = 0;

                if ( nextRun != null )
                {
                    nextTime = nextRun.getTime();
                }
                else
                {
                    nextTime = System.currentTimeMillis();
                }

                getScheduler().taskRescheduled( this );

                return getScheduler().getScheduledExecutorService().schedule( this,
                                                                              nextTime - System.currentTimeMillis(),
                                                                              TimeUnit.MILLISECONDS );
            }
            else
            {
                nextRun = null;

                return null;
            }
        }
        else
        {
            nextRun = null;

            return null;
        }
    }

    public void runNow()
    {
        // if we are not RUNNING
        if ( !TaskState.RUNNING.equals( getTaskState() ) && !manualRun )
        {
            manualRun = true;

            getScheduler().getScheduledExecutorService().schedule( this, 0, TimeUnit.MILLISECONDS );
        }
    }

    public T call()
        throws Exception
    {
        try
        {
            this.progressListener = new LoggingProgressListener( getCallable().getClass().getSimpleName() );

            TaskUtil.setCurrent( this.progressListener );

            T result = null;

            if ( getCallable() instanceof SchedulerTask )
            {
                // check for execution
                if ( !( (SchedulerTask<?>) getCallable() ).allowConcurrentExecution( getScheduler().getActiveTasks() ) )
                {
                    if ( nextRun != null )
                    {
                        // simply reschedule itself for 10sec
                        nextRun = new Date( nextRun.getTime() + 10000 );
                    }

                    setFuture( getScheduler().getScheduledExecutorService().schedule( this, 10000,
                                                                                      TimeUnit.MILLISECONDS ) );

                    setTaskState( TaskState.SLEEPING );

                    return result;
                }
            }

            Future<T> nextFuture = null;
            Date peekBefore = null;
            Date peekAfter = null;

            if ( ( isEnabled() || manualRun ) && getTaskState().isRunnable() )
            {
                setTaskState( TaskState.RUNNING );

                Date startDate = new Date();

                try
                {
                    // Note that we need to do this prior to starting, so that the next run time will be updated
                    // properly
                    // Rather than having to wait for the task to finish

                    // If manually running, just grab the previous future and use that or create a new one
                    if ( manualRun )
                    {
                        nextFuture = getFuture();

                        manualRun = false;
                    }
                    // Otherwise, grab the next one
                    else
                    {
                        nextFuture = reschedule();
                    }

                    setLastRun( startDate );

                    // keep track of the next run times so that if they change during schedule run, will get proper one
                    peekBefore = getScheduleIterator().peekNext();
                    result = getCallable().call();
                    peekAfter = getScheduleIterator().peekNext();

                    if ( result != null )
                    {
                        results.add( result );
                    }
                    setLastStatus( TaskState.FINISHED );
                }
                catch ( Throwable e )
                {
                    if ( peekAfter == null )
                    {
                        peekAfter = getScheduleIterator().peekNext();
                    }
                    
                    manualRun = false;

                    setBrokenCause( e );

                    setLastStatus( TaskState.BROKEN );
                    if ( isToBeRemoved() )
                    {
                        setTaskState( TaskState.CANCELLED );
                    }
                    else
                    {
                        setTaskState( TaskState.BROKEN );
                    }

                    if ( ( !isManualRunScheduled() && nextFuture == null && isEnabled() ) || isToBeRemoved() )
                    {
                        getScheduler().removeFromTasksMap( this );
                    }

                    if ( Exception.class.isAssignableFrom( e.getClass() ) )
                    {
                        // this is an exception, pass it further
                        throw (Exception) e;
                    }
                    else
                    {
                        // this is a Throwable or Error instance, pack it into an exception and rethrow
                        throw new TaskExecutionException( e );
                    }
                }
                finally
                {
                    // next run is set, but has changed from before run
                    if ( ( peekBefore == null && peekAfter != null )
                        || ( peekBefore != null && !peekBefore.equals( peekAfter ) ) )
                    {
                        if ( nextFuture != null )
                        {
                            nextFuture.cancel( true );
                        }

                        nextFuture = reschedule();
                    }
                    
                    setDuration( System.currentTimeMillis() - startDate.getTime() );
                }
            }

            if ( TaskState.BROKEN == getTaskState() )
            {
                // do nothing, let user fix or delete it
            }
            else if ( isToBeRemoved() )
            {
                setTaskState( TaskState.CANCELLED );
            }
            // If manually running or having future, park this task to submitted
            else if ( isManualRunScheduled() )
            {
                setTaskState( TaskState.SUBMITTED );
            }
            else if ( nextFuture != null )
            {
                setTaskState( TaskState.WAITING );

                setFuture( nextFuture );
            }
            // If disabled (and not manually run),
            // put to waiting and reschedule for next time
            // user may want to enable at some point,
            // so still seeing the next run time may be handy
            else if ( !isEnabled() )
            {
                setTaskState( TaskState.WAITING );

                nextFuture = reschedule();

                setFuture( nextFuture );
            }
            // this execution was the last execution (no other if-clause triggered)
            else if ( TaskState.CANCELLING.equals( getTaskState() ) )
            {
                setTaskState( TaskState.CANCELLED );
            }
            else
            {
                setTaskState( TaskState.FINISHED );
            }

            if ( getTaskState().isEndingState() /* FINISHED or CANCELLED */)
            {
                getScheduler().removeFromTasksMap( this );
            }

            return result;
        }
        finally
        {
            this.progressListener = null;

            TaskUtil.setCurrent( null );
        }
    }

    // IteratingTask

    public Date getLastRun()
    {
        return lastRun;
    }

    public TaskState getLastStatus()
    {
        return lastStatus;
    }

    public Long getDuration()
    {
        return duration;
    }

    public Date getNextRun()
    {
        return nextRun;
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled( boolean enabled )
    {
        this.enabled = enabled;
    }

    public List<T> getResults()
    {
        return results;
    }

    // ScheduledTask

    public Schedule getSchedule()
    {
        return schedule;
    }

    public void setSchedule( Schedule schedule )
    {
        this.schedule = schedule;

        this.scheduleIterator = null;
    }

    public SchedulerIterator getScheduleIterator()
    {
        if ( scheduleIterator == null && getSchedule() != null )
        {
            scheduleIterator = getSchedule().getIterator();
        }
        
        //this noop iterator will never return a next run time, but will cover users from having to check for null
        if ( scheduleIterator == null )
        {
            return new NoopSchedulerIterator();
        }

        return scheduleIterator;
    }

    public void setName( String name )
    {
        this.name = name;
    }

    public String getName()
    {
        return name;
    }

    public Map<String, String> getTaskParams()
    {
        if ( SchedulerTask.class.isAssignableFrom( getCallable().getClass() ) )
        {
            return ( (SchedulerTask<?>) getCallable() ).getParameters();
        }

        return Collections.emptyMap();
    }

    public boolean isToBeRemoved()
    {
        return toBeRemoved;
    }

    public void setToBeRemoved( boolean toBeRemoved )
    {
        this.toBeRemoved = toBeRemoved;
    }
}
