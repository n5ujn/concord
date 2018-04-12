package com.walmartlabs.concord.sdk;

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

import java.util.Map;

public interface SecretReader {

    String exportAsString(String instanceId,
                          String name,
                          String password) throws Exception;

    String exportAsString(String instanceId,
                          String orgName,
                          String name,
                          String password) throws Exception;

    Map<String, String> exportKeyAsFile(String instanceId,
                                        String workDir,
                                        String name,
                                        String password) throws Exception;

    Map<String, String> exportKeyAsFile(String instanceId,
                                        String workDir,
                                        String orgName,
                                        String name,
                                        String password) throws Exception;

    Map<String, String> exportCredentials(String instanceId,
                                          String workDir,
                                          String name,
                                          String password) throws Exception;

    Map<String, String> exportCredentials(String instanceId,
                                          String workDir,
                                          String orgName,
                                          String name,
                                          String password) throws Exception;

    String exportAsFile(String instanceId,
                        String workDir,
                        String name,
                        String password) throws Exception;

    String exportAsFile(String instanceId,
                        String workDir,
                        String orgName,
                        String name,
                        String password) throws Exception;

    String decryptString(String instanceId, String s) throws Exception;
}