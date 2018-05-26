package com.walmartlabs.concord.it.server;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 Wal-Mart Store, Inc.
 * -----
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
 * =====
 */

import com.google.common.collect.ImmutableMap;
import com.walmartlabs.concord.server.events.GithubEventResource;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class GithubCallbackIT extends AbstractServerIT {

    // for empty event and '123qwe' secret
    private static final String AUTH = "sha1=047cfb383db684bdfccc2c333698b70ee98e65d2";

    private static final String concordTriggersRepoName = "triggers";

    @Test(timeout = 60000)
    public void test() throws Exception {
        setGithubKey(AUTH);

        GithubEventResource githubResource = proxy(GithubEventResource.class);
        Map<String, Object> event = new HashMap<>();
        event.put("repository", ImmutableMap.of("full_name", concordTriggersRepoName));
        String result = githubResource.push(event);
        assertNotNull(result);
        assertEquals("ok", result);
    }
}
