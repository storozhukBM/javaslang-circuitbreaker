/*
 *
 *  Copyright 2016 Robert Winkler and Bohdan Storozhuk
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */
package io.github.robwin.ratelimiter.internal;

import static com.jayway.awaitility.Awaitility.await;
import static com.jayway.awaitility.Duration.FIVE_HUNDRED_MILLISECONDS;
import static java.lang.Thread.State.RUNNABLE;
import static java.lang.Thread.State.TERMINATED;
import static java.lang.Thread.State.TIMED_WAITING;
import static java.time.Duration.ZERO;
import static javaslang.control.Try.run;
import static org.assertj.core.api.BDDAssertions.then;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.jayway.awaitility.core.ConditionFactory;
import io.github.robwin.ratelimiter.RateLimiter;
import io.github.robwin.ratelimiter.RateLimiterConfig;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;


public class SemaphoreBasedRateLimiterImplTest {

    private static final int LIMIT = 2;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REFRESH_PERIOD = Duration.ofMillis(100);
    private static final String CONFIG_MUST_NOT_BE_NULL = "RateLimiterConfig must not be null";
    private static final String NAME_MUST_NOT_BE_NULL = "Name must not be null";
    private static final Object O = new Object();
    @Rule
    public ExpectedException exception = ExpectedException.none();
    private RateLimiterConfig config;

    private static ConditionFactory awaitImpatiently() {
        return await()
            .pollDelay(1, TimeUnit.MICROSECONDS)
            .pollInterval(2, TimeUnit.MILLISECONDS);
    }

    @Before
    public void init() {
        config = RateLimiterConfig.builder()
            .timeoutDuration(TIMEOUT)
            .limitRefreshPeriod(REFRESH_PERIOD)
            .limitForPeriod(LIMIT)
            .build();
    }

    @Test
    public void rateLimiterCreationWithProvidedScheduler() throws Exception {
        ScheduledExecutorService scheduledExecutorService = mock(ScheduledExecutorService.class);
        RateLimiterConfig configSpy = spy(config);
        SemaphoreBasedRateLimiter limit = new SemaphoreBasedRateLimiter("test", configSpy, scheduledExecutorService);

        ArgumentCaptor<Runnable> refreshLimitRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduledExecutorService)
            .scheduleAtFixedRate(
                refreshLimitRunnableCaptor.capture(),
                eq(config.getLimitRefreshPeriod().toNanos()),
                eq(config.getLimitRefreshPeriod().toNanos()),
                eq(TimeUnit.NANOSECONDS)
            );

        Runnable refreshLimitRunnable = refreshLimitRunnableCaptor.getValue();

        then(limit.getPermission(ZERO)).isTrue();
        then(limit.getPermission(ZERO)).isTrue();
        then(limit.getPermission(ZERO)).isFalse();

        Thread.sleep(REFRESH_PERIOD.toMillis() * 2);
        verify(configSpy, times(1)).getLimitForPeriod();

        refreshLimitRunnable.run();

        verify(configSpy, times(2)).getLimitForPeriod();

        then(limit.getPermission(ZERO)).isTrue();
        then(limit.getPermission(ZERO)).isTrue();
        then(limit.getPermission(ZERO)).isFalse();
    }

    @Test
    public void rateLimiterCreationWithDefaultScheduler() throws Exception {
        SemaphoreBasedRateLimiter limit = new SemaphoreBasedRateLimiter("test", config);
        awaitImpatiently().atMost(FIVE_HUNDRED_MILLISECONDS)
            .until(() -> limit.getPermission(ZERO), equalTo(false));
        awaitImpatiently().atMost(110, TimeUnit.MILLISECONDS)
            .until(() -> limit.getPermission(ZERO), equalTo(true));
    }

    @Test
    public void getPermissionAndMetrics() throws Exception {
        ScheduledExecutorService scheduledExecutorService = mock(ScheduledExecutorService.class);
        RateLimiterConfig configSpy = spy(config);
        SemaphoreBasedRateLimiter limit = new SemaphoreBasedRateLimiter("test", configSpy, scheduledExecutorService);
        RateLimiter.Metrics detailedMetrics = limit.getMetrics();

        SynchronousQueue<Object> synchronousQueue = new SynchronousQueue<>();
        Thread thread = new Thread(() -> {
            run(() -> {
                for (int i = 0; i < LIMIT; i++) {
                    System.out.println("SLAVE -> WAITING FOR MASTER");
                    synchronousQueue.put(O);
                    System.out.println("SLAVE -> HAVE COMMAND FROM MASTER");
                    limit.getPermission(TIMEOUT);
                    System.out.println("SLAVE -> ACQUIRED PERMISSION");
                }
                System.out.println("SLAVE -> LAST PERMISSION ACQUIRE");
                limit.getPermission(TIMEOUT);
                System.out.println("SLAVE -> I'M DONE");
            });
        });
        thread.setDaemon(true);
        thread.start();

        for (int i = 0; i < LIMIT; i++) {
            System.out.println("MASTER -> TAKE PERMISSION");
            synchronousQueue.take();
        }

        System.out.println("MASTER -> CHECK IF SLAVE IS WAITING FOR PERMISSION");
        awaitImpatiently()
            .atMost(100, TimeUnit.MILLISECONDS).until(detailedMetrics::getAvailablePermissions, equalTo(0));
        System.out.println("MASTER -> SLAVE CONSUMED ALL PERMISSIONS");
        awaitImpatiently()
            .atMost(2, TimeUnit.SECONDS).until(thread::getState, equalTo(TIMED_WAITING));
        then(detailedMetrics.getAvailablePermissions()).isEqualTo(0);
        System.out.println("MASTER -> SLAVE WAS WAITING");

        limit.refreshLimit();
        awaitImpatiently()
            .atMost(100, TimeUnit.MILLISECONDS).until(detailedMetrics::getAvailablePermissions, equalTo(1));
        awaitImpatiently()
            .atMost(2, TimeUnit.SECONDS).until(thread::getState, equalTo(TERMINATED));
        then(detailedMetrics.getAvailablePermissions()).isEqualTo(1);
    }

    @Test
    public void getPermissionInterruption() throws Exception {
        ScheduledExecutorService scheduledExecutorService = mock(ScheduledExecutorService.class);
        RateLimiterConfig configSpy = spy(config);
        SemaphoreBasedRateLimiter limit = new SemaphoreBasedRateLimiter("test", configSpy, scheduledExecutorService);
        limit.getPermission(ZERO);
        limit.getPermission(ZERO);

        Thread thread = new Thread(() -> {
            limit.getPermission(TIMEOUT);
            while (true) {
                Function.identity().apply(1);
            }
        });
        thread.setDaemon(true);
        thread.start();

        awaitImpatiently()
            .atMost(2, TimeUnit.SECONDS).until(thread::getState, equalTo(TIMED_WAITING));

        thread.interrupt();

        awaitImpatiently()
            .atMost(2, TimeUnit.SECONDS).until(thread::getState, equalTo(RUNNABLE));
        then(thread.isInterrupted()).isTrue();
    }

    @Test
    public void getName() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        SemaphoreBasedRateLimiter limit = new SemaphoreBasedRateLimiter("test", config, scheduler);
        then(limit.getName()).isEqualTo("test");
    }

    @Test
    public void getMetrics() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        SemaphoreBasedRateLimiter limit = new SemaphoreBasedRateLimiter("test", config, scheduler);
        RateLimiter.Metrics metrics = limit.getMetrics();
        then(metrics.getNumberOfWaitingThreads()).isEqualTo(0);
    }

    @Test
    public void getRateLimiterConfig() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        SemaphoreBasedRateLimiter limit = new SemaphoreBasedRateLimiter("test", config, scheduler);
        then(limit.getRateLimiterConfig()).isEqualTo(config);
    }

    @Test
    public void getDetailedMetrics() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        SemaphoreBasedRateLimiter limit = new SemaphoreBasedRateLimiter("test", config, scheduler);
        RateLimiter.Metrics metrics = limit.getMetrics();
        then(metrics.getNumberOfWaitingThreads()).isEqualTo(0);
        then(metrics.getAvailablePermissions()).isEqualTo(2);
    }

    @Test
    public void constructionWithNullName() throws Exception {
        exception.expect(NullPointerException.class);
        exception.expectMessage(NAME_MUST_NOT_BE_NULL);
        new SemaphoreBasedRateLimiter(null, config, null);
    }

    @Test
    public void constructionWithNullConfig() throws Exception {
        exception.expect(NullPointerException.class);
        exception.expectMessage(CONFIG_MUST_NOT_BE_NULL);
        new SemaphoreBasedRateLimiter("test", null, null);
    }
}