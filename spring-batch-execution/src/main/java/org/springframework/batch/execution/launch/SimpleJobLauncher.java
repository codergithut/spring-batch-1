/*
 * Copyright 2006-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.batch.execution.launch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.batch.core.domain.Job;
import org.springframework.batch.core.domain.JobExecution;
import org.springframework.batch.core.domain.JobIdentifier;
import org.springframework.batch.core.domain.JobLocator;
import org.springframework.batch.core.domain.NoSuchJobException;
import org.springframework.batch.core.executor.JobExecutor;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.runtime.JobIdentifierFactory;
import org.springframework.batch.execution.job.DefaultJobExecutor;
import org.springframework.batch.execution.runtime.ScheduledJobIdentifierFactory;
import org.springframework.batch.repeat.interceptor.RepeatOperationsApplicationEvent;
import org.springframework.batch.statistics.StatisticsProvider;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.util.Assert;

/**
 * Generic {@link JobLauncher} allowing choice of strategy for concurrent
 * execution and .
 * 
 * @see JobLauncher
 * @author Dave Syer
 */
public class SimpleJobLauncher implements JobLauncher, InitializingBean, ApplicationEventPublisherAware,
		StatisticsProvider {

	protected static final Log logger = LogFactory.getLog(SimpleJobLauncher.class);

	private JobExecutor jobExecutor = new DefaultJobExecutor();

	// there is no sensible default for this
	private JobRepository jobRepository;

	// there is no sensible default for this
	private JobLocator jobLocator;

	// this can be defaulted from some other properties (see
	// afterPropertiesSet())
	private JobExecutorFacade jobExecutorFacade;

	private TaskExecutor taskExecutor = new SyncTaskExecutor();

	private List listeners = new ArrayList();

	private JobIdentifierFactory jobIdentifierFactory = new ScheduledJobIdentifierFactory();

	private final Object monitor = new Object();

	// A private registry for keeping track of running jobs.
	private volatile Map registry = new HashMap();

	private ApplicationEventPublisher applicationEventPublisher;

	/**
	 * Setter for {@link JobIdentifierFactory}.
	 * 
	 * @param jobIdentifierFactory the {@link JobIdentifierFactory} to set
	 */
	public void setJobIdentifierFactory(JobIdentifierFactory jobIdentifierFactory) {
		this.jobIdentifierFactory = jobIdentifierFactory;
	}

	/**
	 * Public setter for the listeners property.
	 * 
	 * @param listeners the listeners to set - a list of
	 * {@link JobExecutionListener}.
	 */
	public void setJobExecutionListeners(List listeners) {
		this.listeners = listeners;
	}

	/**
	 * Setter for injection of {@link JobLocator}. Mandatory with no default.
	 * 
	 * @param jobLocator the jobLocator to set
	 */
	public void setJobLocator(JobLocator jobLocator) {
		this.jobLocator = jobLocator;
	}

	/**
	 * Setter for {@link JobExecutor}. Defaults to a {@link DefaultJobExecutor}.
	 * 
	 * @param jobExecutor
	 */
	public void setJobExecutor(JobExecutor jobExecutor) {
		this.jobExecutor = jobExecutor;
	}

	/**
	 * Setter for {@link JobRepository}. Mandatory with no default.
	 * 
	 * @param jobRepository
	 */
	public void setJobRepository(JobRepository jobRepository) {
		this.jobRepository = jobRepository;
	}

	/**
	 * Setter for {@link JobExecutorFacade}. Package private because it is only
	 * used for testing purposes.
	 */
	void setJobExecutorFacade(JobExecutorFacade jobExecutorFacade) {
		this.jobExecutorFacade = jobExecutorFacade;
	}

	/**
	 * Check that mandatory properties are set and create a {@link JobExecutor}
	 * if one wasn't provided.
	 * 
	 * @see #setJobExecutorFacade(JobExecutorFacade)
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
	 */
	public void afterPropertiesSet() throws Exception {
		if (jobExecutorFacade == null) {
			logger.debug("Using SimpleJobExecutorFacade");
			Assert.notNull(jobLocator);
			Assert.notNull(jobExecutor);
			Assert.notNull(jobRepository);
			SimpleJobExecutorFacade jobExecutorFacade = new SimpleJobExecutorFacade();
			jobExecutorFacade.setJobLocator(jobLocator);
			jobExecutorFacade.setJobExecutionListeners(listeners);
			jobExecutorFacade.setJobExecutor(jobExecutor);
			jobExecutorFacade.setJobRepository(jobRepository);
			this.jobExecutorFacade = jobExecutorFacade;
		}
	}

	/**
	 * This method is wrapped in a Runnable by {@link #run(JobIdentifier)}, so
	 * that the internal housekeeping is done consistently. Subclasses should be
	 * careful to do the same.
	 * 
	 * @param jobIdentifier
	 * @return
	 * @throws NoSuchJobException
	 */
	protected final void runInternal(JobExecution execution) throws NoSuchJobException {

		JobIdentifier jobIdentifier = execution.getJobInstance().getIdentifier();

		if (getJobExecution(jobIdentifier) == null) {
			logger.info("Job already stopped (not launching): " + jobIdentifier);
			return;
		}

		try {
			logger.info("Launching: " + jobIdentifier);
			jobExecutorFacade.start(execution);
			logger.info("Completed successfully: " + jobIdentifier);
		}
		finally {
			unregister(jobIdentifier);
		}

	}

	/**
	 * Start the job using the task executor provided.
	 * 
	 * @throws NoSuchJobException if the identifier cannot be used to locate a
	 * {@link Job}.
	 * 
	 * @see org.springframework.batch.execution.launch.SimpleJobLauncher#run(org.springframework.batch.core.domain.JobIdentifier)
	 */
	public JobExecution run(final JobIdentifier jobIdentifier) throws NoSuchJobException,
			JobExecutionAlreadyRunningException {

		if (getJobExecution(jobIdentifier) != null) {
			throw new JobExecutionAlreadyRunningException("A job is already executing with this identifier: ["
					+ jobIdentifier + "]");
		}
		final JobExecution execution = jobExecutorFacade.createExecutionFrom(jobIdentifier);
		// TODO: throw JobExecutionAlreadyRunningException if it is in a running
		// state (someone else launched it)
		final JobExecutionHolder holder = register(execution);

		taskExecutor.execute(new Runnable() {
			public void run() {
				try {

					synchronized (monitor) {
						if (isInternalRunning(jobIdentifier)) {
							logger.info("This job is already running, so not re-launched: " + jobIdentifier);
							return;
						}
					}

					holder.start();
					runInternal(execution);

				}
				catch (NoSuchJobException e) {
					applicationEventPublisher.publishEvent(new RepeatOperationsApplicationEvent(jobIdentifier,
							"No such job", RepeatOperationsApplicationEvent.ERROR));
					logger.error("Job could not be located inside Runnable for identifier: [" + jobIdentifier + "]", e);
				}
				finally {
					holder.stop();
				}
			}
		});

		return execution;

	}

	/**
	 * Extension point for subclasses to stop a specific job.
	 * 
	 * @throws NoSuchJobExecutionException
	 */
	protected void doStop(JobIdentifier jobIdentifier) throws NoSuchJobExecutionException {
		JobExecution execution = getJobExecution(jobIdentifier);
		logger.info("Stopping job: " + jobIdentifier);
		if (execution != null) {
			jobExecutorFacade.stop(execution);
		}
		unregister(jobIdentifier);
	}

	/**
	 * Stop all jobs if any are running. If not, no action will be taken.
	 * Delegates to the {@link #doStop()} method.
	 * 
	 * @throws NoSuchJobExecutionException
	 * @see org.springframework.context.Lifecycle#stop()
	 * @see org.springframework.batch.execution.launch.JobLauncher#stop()
	 */
	final public void stop() {
		for (Iterator iter = new HashSet(registry.keySet()).iterator(); iter.hasNext();) {
			JobIdentifier context = (JobIdentifier) iter.next();
			try {
				stop(context);
			}
			catch (NoSuchJobExecutionException e) {
				logger.error(e);
			}
		}
	}

	/**
	 * Stop a job with this {@link JobIdentifier}. Delegates to the
	 * {@link #doStop(JobIdentifier)} method.
	 * 
	 * @throws NoSuchJobExecutionException
	 * 
	 * @see org.springframework.batch.execution.launch.JobLauncher#stop(org.springframework.batch.core.domain.JobIdentifier)
	 * @see BatchContainer#stop(JobRuntimeInformation))
	 */
	final public void stop(JobIdentifier runtimeInformation) throws NoSuchJobExecutionException {
		synchronized (monitor) {
			doStop(runtimeInformation);
		}
	}

	/**
	 * Stop all jobs with {@link JobIdentifier} having this name. Delegates to
	 * the {@link #stop(JobIdentifier)}.
	 * 
	 * @throws NoSuchJobExecutionException
	 * 
	 * @see org.springframework.batch.execution.launch.JobLauncher#stop(java.lang.String)
	 */
	final public void stop(String name) throws NoSuchJobExecutionException {
		this.stop(jobIdentifierFactory.getJobIdentifier(name));
	}

	/**
	 * Check each registered {@link JobIdentifier} to see if it is running (@see
	 * {@link #isRunning(JobIdentifier)}), and if any are, then return true.
	 * 
	 * @see org.springframework.batch.container.bootstrap.BatchContainerLauncher#isRunning()
	 */
	final public boolean isRunning() {
		Collection jobs = new HashSet(registry.keySet());
		for (Iterator iter = jobs.iterator(); iter.hasNext();) {
			JobIdentifier jobIdentifier = (JobIdentifier) iter.next();
			if (isInternalRunning(jobIdentifier)) {
				return true;
			}
		}
		return !jobs.isEmpty();
	}

	private boolean isInternalRunning(JobIdentifier jobIdentifier) {
		synchronized (registry) {
			JobExecutionHolder jobExecutionHolder = getJobExecutionHolder(jobIdentifier);
			return isRunning(jobIdentifier) && jobExecutionHolder != null && jobExecutionHolder.isRunning();
		}
	}

	/**
	 * Extension point for subclasses to check an individual
	 * {@link JobIdentifier} to see if it is running. As long as at least one
	 * job is running the launcher is deemed to be running.
	 * 
	 * @param jobIdentifier a {@link JobIdentifier}
	 * @return always true. Subclasses can override and provide more accurate
	 * information.
	 */
	protected boolean isRunning(JobIdentifier jobIdentifier) {
		return true;
	}

	/**
	 * Convenient synchronized accessor for the registry.
	 * 
	 * @param jobIdentifier
	 * @return TODO
	 */
	private JobExecutionHolder register(JobExecution execution) {
		JobExecutionHolder jobExecutionHolder = new JobExecutionHolder(execution);
		synchronized (registry) {
			registry.put(execution.getJobInstance().getIdentifier(), jobExecutionHolder);
		}
		return jobExecutionHolder;
	}

	/**
	 * Convenient synchronized accessor for the registry.
	 * 
	 * @param jobIdentifier
	 */
	private JobExecution getJobExecution(JobIdentifier jobIdentifier) {
		synchronized (registry) {
			if (registry.containsKey(jobIdentifier)) {
				return ((JobExecutionHolder) registry.get(jobIdentifier)).getExecution();
			}
		}
		return null;
	}

	/**
	 * Convenient synchronized accessor for the registry.
	 * 
	 * @param jobIdentifier
	 */
	private JobExecutionHolder getJobExecutionHolder(JobIdentifier jobIdentifier) {
		synchronized (registry) {
			if (registry.containsKey(jobIdentifier)) {
				return (JobExecutionHolder) registry.get(jobIdentifier);
			}
		}
		return null;
	}

	/**
	 * Convenient synchronized accessor for the registry. Must be used by
	 * subclasses to release the {@link JobIdentifier} when a job is finished
	 * (or stopped).
	 * 
	 * @param jobIdentifier
	 */
	private void unregister(JobIdentifier jobIdentifier) {
		synchronized (registry) {
			registry.remove(jobIdentifier);
		}
	}

	/**
	 * Setter for the {@link TaskExecutor}. Defaults to a
	 * {@link SyncTaskExecutor}.
	 * 
	 * @param taskExecutor the taskExecutor to set
	 */
	public void setTaskExecutor(TaskExecutor taskExecutor) {
		this.taskExecutor = taskExecutor;
	}

	/**
	 * Accessor for the job executions currently in progress (and having been
	 * started from this launcher). If you launch a job synchronously then it
	 * will have finished when the {@link #run()} method returns, so there will
	 * be no statistics. Because the request is potentially fulfilled
	 * asynchronously, and only on demand, the data might be out of date by the
	 * time this method is called, so it should be used for information purposes
	 * only.
	 * 
	 * @return Properties representing the {@link JobExecution} objects passed
	 * up from the underlying execution. If there are no jobs running it will be
	 * empty.
	 */
	public Properties getStatistics() {
		if (jobExecutorFacade instanceof StatisticsProvider) {
			return ((StatisticsProvider) jobExecutorFacade).getStatistics();
		}
		else {
			return new Properties();
		}
	}

	public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
		this.applicationEventPublisher = applicationEventPublisher;
	}

	private class JobExecutionHolder {

		private static final int NEW = 0;

		private static final int STARTED = 1;

		private static final int STOPPED = 2;

		private JobExecution execution;

		private int status = NEW;

		public JobExecutionHolder(JobExecution execution) {
			this.execution = execution;
		}

		JobExecution getExecution() {
			return execution;
		}

		boolean isRunning() {
			return status == STARTED;
		}

		void start() {
			status = STARTED;
		}

		void stop() {
			status = STOPPED;
		}

	}

}
