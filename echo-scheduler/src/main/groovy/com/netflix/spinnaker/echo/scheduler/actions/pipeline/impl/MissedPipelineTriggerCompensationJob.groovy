/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.echo.scheduler.actions.pipeline.impl

import com.google.common.collect.Lists
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.echo.model.Pipeline
import com.netflix.spinnaker.echo.model.Trigger
import com.netflix.spinnaker.echo.pipelinetriggers.PipelineCache
import com.netflix.spinnaker.echo.pipelinetriggers.orca.OrcaService
import com.netflix.spinnaker.echo.pipelinetriggers.orca.OrcaService.PipelineResponse
import com.netflix.spinnaker.echo.pipelinetriggers.orca.PipelineInitiator
import groovy.util.logging.Slf4j
import org.quartz.CronExpression
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.stereotype.Component
import rx.Observable
import rx.Scheduler
import rx.Subscription

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

import static com.netflix.spinnaker.echo.model.Trigger.Type.CRON
import static net.logstash.logback.argument.StructuredArguments.kv
/**
 * Finds and executes all pipeline triggers that should have run in the last configured time window during startup.
 * This job will wait until the {@link com.netflix.spinnaker.echo.pipelinetriggers.PipelineCache} has run prior to
 * finding any missed triggers.
 *
 * If enabled (`scheduler.compensationJob.enableRecurring`, default on), after the initial startup compensation job
 * has been performed, a recurring job will be started at a less aggressive poll cycle to ensure lost triggers are
 * re-scheduled.
 */
@ConditionalOnExpression('${scheduler.enabled:false} && ${scheduler.compensationJob.enabled:false}')
@Component
@Slf4j
class MissedPipelineTriggerCompensationJob implements ApplicationListener<ContextRefreshedEvent> {

  final static Duration STARTUP_POLL_INTERVAL = Duration.ofSeconds(60)

  final Scheduler scheduler
  final PipelineCache pipelineCache
  final OrcaService orcaService
  final PipelineInitiator pipelineInitiator
  final Registry registry
  final boolean enableRecurring
  final Duration recurringPollInterval
  final int pipelineFetchSize
  final DateContext dateContext

  Subscription startupSubscription
  Subscription recurringSubscription

  @Autowired
  MissedPipelineTriggerCompensationJob(Scheduler scheduler,
                                       PipelineCache pipelineCache,
                                       OrcaService orcaService,
                                       PipelineInitiator pipelineInitiator,
                                       Registry registry,
                                       @Value('${scheduler.compensationJob.windowMs:600000}') long compensationWindowMs, // 10 min
                                       @Value('${scheduler.cron.timezone:America/Los_Angeles}') String timeZoneId,
                                       @Value('${scheduler.compensationJob.enableRecurring:true}') boolean enableRecurring,
                                       @Value('${scheduler.compensationJob.recurringPollIntervalMs:300000}') long recurringPollIntervalMs, // 5 min
                                       @Value('${scheduler.compensationJob.pipelineFetchSize:20}') int pipelineFetchSize) {

    this(scheduler, pipelineCache, orcaService, pipelineInitiator, registry, compensationWindowMs, timeZoneId,
      enableRecurring, recurringPollIntervalMs, pipelineFetchSize, null)
  }

  MissedPipelineTriggerCompensationJob(Scheduler scheduler,
                                       PipelineCache pipelineCache,
                                       OrcaService orcaService,
                                       PipelineInitiator pipelineInitiator,
                                       Registry registry,
                                       @Value('${scheduler.compensationJob.windowMs:600000}') long compensationWindowMs, // 10 min
                                       @Value('${scheduler.cron.timezone:America/Los_Angeles}') String timeZoneId,
                                       @Value('${scheduler.compensationJob.enableRecurring:true}') boolean enableRecurring,
                                       @Value('${scheduler.compensationJob.recurringPollIntervalMs:300000}') long recurringPollIntervalMs, // 5 min
                                       @Value('${scheduler.compensationJob.pipelineFetchSize:20}') int pipelineFetchSize,
                                       DateContext dateContext) {
    this.scheduler = scheduler
    this.pipelineCache = pipelineCache
    this.orcaService = orcaService
    this.pipelineInitiator = pipelineInitiator
    this.registry = registry
    this.enableRecurring = enableRecurring
    this.recurringPollInterval = Duration.ofMillis(recurringPollIntervalMs)
    this.pipelineFetchSize = pipelineFetchSize
    this.dateContext = dateContext ?: DateContext.fromCompensationWindow(timeZoneId, compensationWindowMs)
  }

  @Override
  void onApplicationEvent(ContextRefreshedEvent event) {
    if (startupSubscription == null) {
      startupSubscription = Observable.interval(STARTUP_POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS, scheduler)
        .doOnNext { onPipelineCacheAwait(it) }
        .flatMap { tick -> Observable.just(pipelineCache.pipelines) }
        .doOnError { onPipelineCacheError(it) }
        .retry()
        .subscribe { List<Pipeline> pipelines ->
          if (pipelines.isEmpty()) {
            return
          }
          triggerMissedExecutions(pipelines)
          startupSubscription.unsubscribe()

          scheduleRecurringCompensation()
        }
    }
  }

  void scheduleRecurringCompensation() {
    if (enableRecurring && recurringSubscription == null) {
      recurringSubscription = Observable.interval(recurringPollInterval.toMinutes(), TimeUnit.MINUTES, scheduler)
        .doOnNext { onPipelineCacheAwait(it) }
        .flatMap { tick -> Observable.just(pipelineCache.pipelines) }
        .doOnError { onPipelineCacheError(it) }
        .retry()
        .subscribe { List<Pipeline> pipelines ->
          if (pipelines.isEmpty()) {
            return
          }
          triggerMissedExecutions(pipelines)
        }
    }
  }

  void onPipelineCacheAwait(long tick) {
    log.info('Waiting for pipeline cache to fill')
  }

  void onPipelineCacheError(Throwable t) {
    log.error("Error waiting for pipeline cache", t)
  }

  void triggerMissedExecutions(List<Pipeline> pipelines) {
    pipelines = pipelines.findAll { !it.disabled }
    List<Trigger> triggers = getEnabledCronTriggers(pipelines)
    List<String> ids = getPipelineConfigIds(pipelines, triggers)

    log.info("Checking ${ids.size()} pipelines with cron triggers (out of ${pipelines.size()})")
    long startTime = System.currentTimeMillis()
    Lists.partition(ids, pipelineFetchSize).forEach { idsPartition ->
      try {
        onOrcaResponse(orcaService.getLatestPipelineExecutions(idsPartition, 1), pipelines, triggers)
      } catch (Exception e) {
        onOrcaError(e)
      }
    }
    log.info("Done searching for cron trigger misfires in ${(System.currentTimeMillis() - startTime)/1000}s")
  }

  private Date getLastExecutionOrNull(List<Date> executions) {
    if (executions.isEmpty()) {
      return null
    }

    return executions.first()
  }

  void onOrcaResponse(Collection<PipelineResponse> response, List<Pipeline> pipelines, List<Trigger> triggers) {
    triggers.each { trigger ->
      Pipeline pipeline = pipelines.find { it.triggers && it.triggers*.id.contains(trigger.id) }
      List<Date> executions = response.findAll { it.pipelineConfigId == pipeline.id }.collect {
        // A null value is valid; a pipeline that hasn't started won't get re-triggered.
        it.startTime != null ? new Date(it.startTime) : null
      }

      def lastExecution = getLastExecutionOrNull(executions)
      if (lastExecution == null) {
        // a pipeline that has no executions could technically get retriggered (e.g. it missed its very first trigger)
        // but this should be a very rare occurrence, so as a safety measure let's bail
        // for instance, if orca returns no executions, we don't want to cause a retrigger storm!
        return
      }

      def expr = new CronExpression(trigger.cronExpression)
      expr.timeZone = dateContext.timeZone

      if (missedExecution(expr, lastExecution, dateContext.triggerWindowFloor, dateContext.now, pipeline)) {
        pipelineInitiator.call(pipeline)
      }
    }
  }

  void onOrcaError(Throwable error) {
    log.error("Error retrieving latest pipeline executions", error)
    registry.counter('orca.errors', "exception", error.getClass().getName()).increment()
  }

  static List<Trigger> getEnabledCronTriggers(List<Pipeline> pipelines) {
    (List<Trigger>) pipelines
      .collect { it.triggers }
      .flatten()
      .findAll { Trigger it -> it && it.enabled && it.type == CRON.toString() }
  }

  private static Date getLastValidTimeInWindow(CronExpression expr, Date from, Date to) {
    Date valid = expr.getNextValidTimeAfter(from)
    if (valid.after(to)) {
      return null
    }

    while (true) {
      def truncatedAtMinute = valid.toInstant().truncatedTo(ChronoUnit.MINUTES).plusSeconds(60)
      Date candidate = expr.getNextValidTimeAfter(Date.from(truncatedAtMinute))
      if (candidate.after(to)) {
        return valid
      }

      valid = candidate
    }
  }

  /**
   * We have a missed execution if there is a valid date D in [windowFloor..now] such that:
   *   D satisfies the cron expression expr
   *   there is no execution E in lastExecutions such that E is in [D..now]
   */
  boolean missedExecution(CronExpression expr, Date lastExecution, Date windowFloor, Date now,
                                 Pipeline pipeline = null) {
    def validTriggerDate = getLastValidTimeInWindow(expr, windowFloor, now)

    // there is no date in [windowFloor..now] that satisfies the cron expression, so no trigger
    if (validTriggerDate == null) {
      return false
    }

    // we have a valid trigger date, and no execution after it: trigger!
    def missed = lastExecution == null || lastExecution.before(validTriggerDate)
    if (missed) {
      log.info('Triggering missed cron trigger on pipeline {} {} {} {} {}',
        kv('application', pipeline?.application), kv('pipelineName', pipeline?.name),
        kv('pipelineConfigId', pipeline?.id), kv('lastExecution', lastExecution),
        kv('validTriggerDate', validTriggerDate))

      def delayMillis = now.getTime() - validTriggerDate.getTime()
      registry.timer("triggers.cronMisfires").record(delayMillis, TimeUnit.MILLISECONDS)

    }

    return missed
  }

  static List<String> getPipelineConfigIds(List<Pipeline> pipelines, List<Trigger> cronTriggers) {
    pipelines.findAll { pipeline ->
      !pipeline.disabled && cronTriggers.find { pipeline.triggers?.contains(it) }
    }.collect { it.id }
  }

  static class DateContext {
    TimeZone timeZone
    Date triggerWindowFloor
    Date now

    static DateContext fromCompensationWindow(String timeZoneId, long compensationWindowMs) {
      Clock clock = Clock.system(ZoneId.of(timeZoneId))
      TimeZone tz = TimeZone.getTimeZone(clock.zone)
      Date triggerWindowFloor = Date.from(Instant.now(clock).minus(compensationWindowMs, ChronoUnit.MILLIS))
      Date now = Date.from(Instant.now(clock))
      return new DateContext(timeZone: tz, triggerWindowFloor: triggerWindowFloor, now: now)
    }
  }
}
