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
package io.github.robwin.ratelimiter;

import io.github.robwin.ratelimiter.internal.InMemoryRateLimiterRegistry;

import java.util.function.Supplier;

/**
 * Manages all RateLimiter instances.
 */
public interface RateLimiterRegistry {

    /**
     * Returns a managed {@link RateLimiter} or creates a new one with the default RateLimiter configuration.
     *
     * @param name the name of the RateLimiter
     * @return The {@link RateLimiter}
     */
    RateLimiter rateLimiter(String name);

    /**
     * Returns a managed {@link RateLimiter} or creates a new one with a custom RateLimiter configuration.
     *
     * @param name              the name of the RateLimiter
     * @param rateLimiterConfig a custom RateLimiter configuration
     * @return The {@link RateLimiter}
     */
    RateLimiter rateLimiter(String name, RateLimiterConfig rateLimiterConfig);

    /**
     * Returns a managed {@link RateLimiterConfig} or creates a new one with a custom RateLimiterConfig configuration.
     *
     * @param name                      the name of the RateLimiterConfig
     * @param rateLimiterConfigSupplier a supplier of a custom RateLimiterConfig configuration
     * @return The {@link RateLimiterConfig}
     */
    RateLimiter rateLimiter(String name, Supplier<RateLimiterConfig> rateLimiterConfigSupplier);

    static RateLimiterRegistry of(RateLimiterConfig defaultRateLimiterConfig) {
        return new InMemoryRateLimiterRegistry(defaultRateLimiterConfig);
    }
}
