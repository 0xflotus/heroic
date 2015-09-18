/*
 * Copyright (c) 2015 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.heroic.metric.datastax;

import lombok.RequiredArgsConstructor;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Session;

@RequiredArgsConstructor
public final class Connection {
    protected final Cluster cluster;
    protected final Session session;
    protected final PreparedStatement write;
    protected final PreparedStatement fetch;
    protected final PreparedStatement keysPaging;
    protected final PreparedStatement keysPagingLeft;
    protected final PreparedStatement keysPagingLimit;
    protected final PreparedStatement keysPagingLeftLimit;
}