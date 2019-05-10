package com.walmartlabs.concord.client;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2018 Walmart Inc.
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmartlabs.concord.ApiException;
import com.walmartlabs.concord.common.IOUtils;
import com.walmartlabs.concord.sdk.*;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static com.walmartlabs.concord.client.Keys.ACTION_KEY;
import static com.walmartlabs.concord.sdk.MapUtils.*;

@Named("concord")
public class ConcordTask extends AbstractConcordTask {

    private static final Logger log = LoggerFactory.getLogger(ConcordTask.class);

    private static final long DEFAULT_KILL_TIMEOUT = 10000;
    private static final long DEFAULT_POLL_DELAY = 5000;

    /**
     * @deprecated use {@link #PAYLOAD_KEY}
     */
    @Deprecated
    private static final String ARCHIVE_KEY = "archive";

    /**
     * @deprecated use {@link #REPO_KEY}
     */
    @Deprecated
    private static final String REPOSITORY_KEY = "repository";

    private static final String ACTIVE_PROFILES_KEY = "activeProfiles";
    private static final String ARGUMENTS_KEY = "arguments";
    private static final String BASE_URL_KEY = "baseUrl";
    private static final String DISABLE_ON_CANCEL_KEY = "disableOnCancel";
    private static final String DISABLE_ON_FAILURE_KEY = "disableOnFailure";
    private static final String ENTRY_POINT_KEY = "entryPoint";
    private static final String EXCLUSIVE_EXEC_KEY = "exclusiveExec";
    private static final String FORKS_KEY = "forks";
    private static final String IGNORE_FAILURES_KEY = "ignoreFailures";
    private static final String INSTANCE_ID_KEY = "instanceId";
    private static final String INSTANCES_KEY = "instances";
    private static final String JOB_OUT_KEY = "jobOut";
    private static final String JOBS_KEY = "jobs";
    private static final String ORG_KEY = "org";
    private static final String OUT_VARS_KEY = "outVars";
    private static final String PAYLOAD_KEY = "payload";
    private static final String PROJECT_KEY = "project";
    private static final String REPO_BRANCH_OR_TAG_KEY = "repoBranchOrTag";
    private static final String REPO_COMMIT_ID_KEY = "repoCommitId";
    private static final String REPO_KEY = "repo";
    private static final String START_AT_KEY = "startAt";
    private static final String SYNC_KEY = "sync";
    private static final String TAGS_KEY = "tags";
    private static final String SUSPEND_KEY = "suspend";

    private static final String SUSPEND_MARKER = "__concordTaskSuspend";
    private static final String RESUME_EVENT_NAME = "concordTask";

    private static final Set<String> FAILED_STATUSES;

    static {
        FAILED_STATUSES = new HashSet<>();
        FAILED_STATUSES.add(ProcessEntry.StatusEnum.FAILED.toString());
        FAILED_STATUSES.add(ProcessEntry.StatusEnum.CANCELLED.toString());
        FAILED_STATUSES.add(ProcessEntry.StatusEnum.TIMED_OUT.toString());
    }

    @InjectVariable("concord")
    Map<String, Object> defaults;

    @Override
    public void execute(Context ctx) throws Exception {
        Action action = getAction(ctx);
        switch (action) {
            case START: {
                if (ContextUtils.getBoolean(ctx, SUSPEND_MARKER, false)) {
                    continueAfterSuspend(ctx);
                } else {
                    startChildProcess(ctx);
                }
                break;
            }
            case STARTEXTERNAL: {
                startExternalProcess(ctx);
                break;
            }
            case FORK: {
                fork(ctx);
                break;
            }
            case KILL: {
                kill(ctx);
                break;
            }
            default:
                throw new IllegalArgumentException("Unsupported action type: " + action);
        }
    }

    public List<String> listSubprocesses(@InjectVariable("context") Context ctx, String instanceId, String... tags) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put(INSTANCE_ID_KEY, instanceId);
        if (tags != null) {
            m.put(TAGS_KEY, new HashSet<>(Arrays.asList(tags)));
        }
        return listSubprocesses(ctx, createJobCfg(ctx, m));
    }

    public List<String> listSubprocesses(@InjectVariable("context") Context ctx, Map<String, Object> cfg) throws Exception {
        UUID instanceId = getUUID(cfg, INSTANCE_ID_KEY);
        Set<String> tags = getSet(cfg, TAGS_KEY);

        return withClient(ctx, client -> {
            ProcessApi api = new ProcessApi(client);

            List<String> tl = tags != null ? new ArrayList<>(tags) : null;
            List<ProcessEntry> result = api.listSubprocesses(instanceId, tl);

            return result.stream()
                    .map(ProcessEntry::getInstanceId)
                    .map(UUID::toString)
                    .collect(Collectors.toList());
        });
    }

    public Map<String, ProcessEntry> waitForCompletion(@InjectVariable("context") Context ctx, List<String> ids) throws Exception {
        return waitForCompletion(ctx, ids, -1);
    }

    public Map<String, ProcessEntry> waitForCompletion(@InjectVariable("context") Context ctx, List<String> ids, long timeout) {
        Map<String, ProcessEntry> result = new HashMap<>();

        ids.parallelStream().map(UUID::fromString).forEach(id -> {
            log.info("Waiting for {}...", id);

            long t1 = System.currentTimeMillis();
            while (true) {
                try {
                    ProcessEntry e = ClientUtils.withRetry(3, 1000, () -> withClient(ctx, client -> {
                        ProcessApi api = new ProcessApi(client);
                        return api.get(id);
                    }));

                    ProcessEntry.StatusEnum s = e.getStatus();

                    if (s == ProcessEntry.StatusEnum.FAILED || s == ProcessEntry.StatusEnum.FINISHED || s == ProcessEntry.StatusEnum.CANCELLED) {
                        result.put(id.toString(), e);
                        break;
                    } else {
                        long t2 = System.currentTimeMillis();
                        if (timeout > 0) {
                            long dt = t2 - t1;
                            if (dt >= timeout) {
                                throw new TimeoutException("Timeout waiting for " + id + ": " + dt);
                            }
                        }

                        Thread.sleep(DEFAULT_POLL_DELAY);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        return result;
    }

    public void kill(@InjectVariable("context") Context ctx, Map<String, Object> cfg) throws Exception {
        List<String> ids = new ArrayList<>();

        Object v = cfg.get(INSTANCE_ID_KEY);
        if (v instanceof String || v instanceof UUID) {
            ids.add(v.toString());
        } else if (v instanceof String[] || v instanceof UUID[]) {
            Object[] os = (Object[]) v;
            for (Object o : os) {
                ids.add(o.toString());
            }
        } else if (v instanceof Collection) {
            for (Object o : (Collection) v) {
                if (o instanceof String || o instanceof UUID) {
                    ids.add(o.toString());
                } else {
                    throw new IllegalArgumentException("'" + INSTANCE_ID_KEY + "' value should be a string or an UUID: " + o);
                }
            }
        } else {
            throw new IllegalArgumentException("'" + INSTANCE_ID_KEY + "' should be a single string, an UUID value or an array of strings or UUIDs: " + v);
        }

        killMany(ctx, cfg, ids);
    }

    private void startExternalProcess(Context ctx) throws Exception {
        // just the validation. AbstractConcordTask#withClient will take care of passing the API key into the client
        ContextUtils.assertString("'" + API_KEY + "' is required to start a process on an external Concord instance",
                ctx, API_KEY);

        start(ctx, null);
    }

    private void startChildProcess(Context ctx) throws Exception {
        UUID parentInstanceId = ContextUtils.getTxId(ctx);
        start(ctx, parentInstanceId);
    }

    private void start(Context ctx, UUID parentInstanceId) throws Exception {
        Map<String, Object> cfg = createJobCfg(ctx, defaults);

        Map<String, Object> req = createRequest(cfg);

        Path workDir = ContextUtils.getWorkDir(ctx);

        Path archive = archivePayload(workDir, cfg);
        String project = getString(cfg, PROJECT_KEY);
        if (project == null && archive == null) {
            throw new IllegalArgumentException("'" + PAYLOAD_KEY + "' and/or '" + PROJECT_KEY + "' are required");
        }

        String targetUri = "/api/v1/process";

        Map<String, Object> input = new HashMap<>();

        if (archive != null) {
            input.put("archive", Files.readAllBytes(archive));
        }

        ObjectMapper om = new ObjectMapper();
        input.put("request", om.writeValueAsBytes(req));

        String org = getOrg(ctx, cfg);
        addIfNotNull(input, "org", org);
        addIfNotNull(input, "project", project);

        String repo = getRepo(cfg);
        addIfNotNull(input, "repo", repo);

        String repoBranchOrTag = getString(cfg, REPO_BRANCH_OR_TAG_KEY);
        addIfNotNull(input, "repoBranchOrTag", repoBranchOrTag);

        String repoCommitId = getString(cfg, REPO_COMMIT_ID_KEY);
        addIfNotNull(input, "repoCommitId", repoCommitId);

        String startAt = getStartAt(cfg);
        addIfNotNull(input, "startAt", startAt);

        addIfNotNull(input, "parentInstanceId", parentInstanceId);

        boolean sync = getBoolean(cfg, SYNC_KEY, false);
        if (parentInstanceId != null) {
            log.info("Starting a child process (project={}, repository={}, archive={}, sync={}, req={})",
                    project, repo, archive, sync, req);
        } else {
            log.info("Starting a new process (project={}, repository={}, archive={}, sync={}, req={}), on {}",
                    project, repo, archive, sync, req, ctx.getVariable(BASE_URL_KEY));
        }

        StartProcessResponse resp = request(ctx, targetUri, input, StartProcessResponse.class);

        String processInstanceId = resp.getInstanceId().toString();

        if (parentInstanceId != null) {
            log.info("Started a child process: {}", processInstanceId);
        } else {
            log.info("Started a process: {} on {}", processInstanceId, ctx.getVariable(BASE_URL_KEY));
        }

        List<String> jobs = Collections.singletonList(processInstanceId);
        ctx.setVariable(JOBS_KEY, jobs);

        if (sync) {
            boolean suspend = getBoolean(cfg, SUSPEND_KEY, false);
            if (suspend) {
                log.info("Suspending the process until the child process ({}) is completed...", processInstanceId);
                suspend(ctx, jobs);
                return;
            }

            Map<String, ProcessEntry> result = waitForCompletion(ctx, jobs);
            handleResults(cfg, result);

            Object out = null;
            if (cfg.containsKey(OUT_VARS_KEY)) {
                out = getOutVars(ctx, processInstanceId);
            }
            ctx.setVariable(JOB_OUT_KEY, out != null ? out : Collections.emptyMap());
        }
    }

    @SuppressWarnings("unchecked")
    private void continueAfterSuspend(Context ctx) throws Exception {
        Map<String, Object> cfg = createJobCfg(ctx, defaults);
        List<String> jobs = (List<String>) ctx.getVariable(JOBS_KEY);
        String childId = jobs.get(0);

        Map<String, ProcessEntry> result = waitForCompletion(ctx, jobs);
        handleResults(cfg, result);

        Object out = null;
        if (cfg.containsKey(OUT_VARS_KEY)) {
            out = getOutVars(ctx, childId);
        }
        ctx.setVariable(JOB_OUT_KEY, out != null ? out : Collections.emptyMap());
        ctx.removeVariable(SUSPEND_MARKER);
    }

    private void suspend(Context ctx, List<String> jobs) throws ApiException {
        Map<String, Object> condition = new HashMap<>();
        condition.put("type", "PROCESS_COMPLETION");
        condition.put("reason", "Waiting for a child process to end");
        condition.put("processes", jobs);
        condition.put("resumeEvent", RESUME_EVENT_NAME);

        ClientUtils.withRetry(3, 1000, () -> withClient(ctx, client -> {
            ProcessApi api = new ProcessApi(client);
            api.setWaitCondition(ContextUtils.getTxId(ctx), condition);
            return null;
        }));

        ctx.setVariable(SUSPEND_MARKER, true);

        ctx.suspend(RESUME_EVENT_NAME, null, true);
    }

    private static void handleResults(Map<String, Object> cfg, Map<String, ProcessEntry> m) {
        StringBuilder errors = new StringBuilder();
        boolean hasErrors = false;
        boolean ignoreFailures = getBoolean(cfg, IGNORE_FAILURES_KEY, false);
        for (Map.Entry<String, ProcessEntry> e : m.entrySet()) {
            String id = e.getKey();
            String status = e.getValue().getStatus().getValue();
            if (FAILED_STATUSES.contains(status)) {
                Map<String, Object> error = getError(e.getValue());
                String errorMessage = "";
                if (!error.isEmpty()) {
                    errorMessage = "(error: " + error + ")";
                }

                if (ignoreFailures) {
                    log.warn("Child process {} {} {}, ignoring...", id, status, errorMessage);
                    continue;
                }

                errors.append("Child process ").append(id).append(" ").append(status).append(" ").append(errorMessage);
                errors.append("\n");
                hasErrors = true;
            }
        }

        if (hasErrors) {
            throw new IllegalStateException(errors.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getError(ProcessEntry p) {
        Map<String, Object> meta = p.getMeta();
        if (meta == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> out = (Map<String, Object>) meta.get("out");
        if (out == null) {
            return Collections.emptyMap();
        }

        return (Map<String, Object>) out.getOrDefault(Constants.Context.LAST_ERROR_KEY, Collections.emptyMap());
    }

    private Object getOutVars(Context ctx, String childId) throws Exception {
        return withClient(ctx, client -> {
            ProcessApi api = new ProcessApi(client);

            File f = null;
            try {
                f = api.downloadAttachment(UUID.fromString(childId), "out.json");
                ObjectMapper om = new ObjectMapper();
                return om.readValue(f, Map.class);
            } catch (ApiException e) {
                if (e.getCode() == 404) {
                    return null;
                }
                log.error("Error while reading the out variables", e);
                throw e;
            } finally {
                IOUtils.delete(f);
            }
        });
    }

    private void fork(Context ctx) throws Exception {
        List<Map<String, Object>> jobs = ContextUtils.getList(ctx, FORKS_KEY, null);
        if (jobs == null) {
            jobs = Collections.singletonList(createJobCfg(ctx, null));
        }

        if (jobs.isEmpty()) {
            throw new IllegalArgumentException("'" + FORKS_KEY + "' can't be an empty list");
        }

        List<String> jobIds = forkMany(ctx, jobs);
        ctx.setVariable(JOBS_KEY, jobIds);
    }

    private List<String> forkMany(Context ctx, List<Map<String, Object>> jobs) throws Exception {
        List<String> ids = new ArrayList<>();

        for (Map<String, Object> job : jobs) {
            Map<String, Object> cfg = createJobCfg(ctx, job);
            cfg.put(INSTANCE_ID_KEY, ctx.getVariable(Constants.Context.TX_ID_KEY));

            int n = getInstances(cfg);
            for (int i = 0; i < n; i++) {
                UUID id = forkOne(ctx, cfg);
                ids.add(id.toString());
            }
        }

        return ids;
    }

    private UUID forkOne(Context ctx, Map<String, Object> cfg) throws Exception {
        if (cfg.containsKey(ARCHIVE_KEY)) {
            log.warn("'" + ARCHIVE_KEY + "' parameter is not supported for fork action and will be ignored");
        }

        if (!cfg.containsKey(ENTRY_POINT_KEY)) {
            throw new IllegalArgumentException("'" + ENTRY_POINT_KEY + "' is required");
        }

        Map<String, Object> req = createRequest(cfg);

        UUID instanceId = assertUUID(cfg, INSTANCE_ID_KEY);
        boolean sync = getBoolean(cfg, SYNC_KEY, false);

        log.info("Forking the current instance (sync={}, req={})...", sync, req);

        return withClient(ctx, client -> {
            ProcessApi api = new ProcessApi(client);
            StartProcessResponse resp = api.fork(instanceId, req, sync, null);
            log.info("Forked a child process: {}", resp.getInstanceId());
            return resp.getInstanceId();
        });
    }

    private void kill(Context ctx) throws Exception {
        Map<String, Object> cfg = createCfg(ctx);
        kill(ctx, cfg);
    }

    private void killMany(Context ctx, Map<String, Object> cfg, List<String> instanceIds) throws Exception {
        if (instanceIds == null || instanceIds.isEmpty()) {
            throw new IllegalArgumentException("'" + INSTANCE_ID_KEY + "' should be a single value or an array of values: " + instanceIds);
        }

        for (String id : instanceIds) {
            killOne(ctx, cfg, id);
        }
    }

    private void killOne(Context ctx, Map<String, Object> cfg, String instanceId) throws Exception {
        withClient(ctx, client -> {
            ProcessApi api = new ProcessApi(client);
            api.kill(UUID.fromString(instanceId));
            return null;
        });

        boolean sync = getBoolean(cfg, SYNC_KEY, false);
        if (sync) {
            waitForCompletion(ctx, Collections.singletonList(instanceId), DEFAULT_KILL_TIMEOUT);
        }
    }

    private Map<String, Object> createJobCfg(Context ctx, Map<String, Object> job) {
        Map<String, Object> m = createCfg(ctx,
                ACTIVE_PROFILES_KEY,
                ARCHIVE_KEY,
                ARGUMENTS_KEY,
                DISABLE_ON_CANCEL_KEY,
                DISABLE_ON_FAILURE_KEY,
                ENTRY_POINT_KEY,
                EXCLUSIVE_EXEC_KEY,
                IGNORE_FAILURES_KEY,
                INSTANCE_ID_KEY,
                ORG_KEY,
                OUT_VARS_KEY,
                PAYLOAD_KEY,
                PROJECT_KEY,
                REPO_BRANCH_OR_TAG_KEY,
                REPO_COMMIT_ID_KEY,
                REPO_KEY,
                REPOSITORY_KEY,
                START_AT_KEY,
                SYNC_KEY,
                TAGS_KEY,
                SUSPEND_KEY);

        if (job != null) {
            m.putAll(job);
        }

        return m;
    }

    private static void addIfNotNull(Map<String, Object> m, String k, Object v) {
        if (v == null) {
            return;
        }
        m.put(k, v);
    }

    private static Path archivePayload(Path workDir, Map<String, Object> cfg) throws IOException {
        String s = getString(cfg, PAYLOAD_KEY);
        if (s == null) {
            s = getString(cfg, ARCHIVE_KEY);
            if (s != null) {
                log.warn("'{}' is deprecated, please use '{}' parameter", ARCHIVE_KEY, PAYLOAD_KEY);
            }
        }

        if (s == null) {
            return null;
        }

        Path path = workDir.resolve(s);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File or directory not found: " + path);
        }

        if (Files.isDirectory(path)) {
            Path tmp = IOUtils.createTempFile("payload", ".zip");
            try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(Files.newOutputStream(tmp))) {
                IOUtils.zip(out, path);
            }
            return tmp;
        }

        return path;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> createRequest(Map<String, Object> cfg) {
        Map<String, Object> req = new HashMap<>();

        Set<String> activeProfiles = getSet(cfg, ACTIVE_PROFILES_KEY);
        if (activeProfiles != null) {
            req.put(Constants.Request.ACTIVE_PROFILES_KEY, activeProfiles);
        }

        String entryPoint = getString(cfg, ENTRY_POINT_KEY);
        if (entryPoint != null) {
            req.put(Constants.Request.ENTRY_POINT_KEY, entryPoint);
        }

        if (cfg.get(EXCLUSIVE_EXEC_KEY) != null) {
            boolean exclusiveExec = getBoolean(cfg, EXCLUSIVE_EXEC_KEY, false);
            req.put(Constants.Request.EXCLUSIVE_EXEC, exclusiveExec);
        }

        Set<String> tags = getSet(cfg, TAGS_KEY);
        if (tags != null) {
            req.put(Constants.Request.TAGS_KEY, tags);
        }

        Map<String, Object> args = getMap(cfg, ARGUMENTS_KEY, Collections.emptyMap());
        if (!args.isEmpty()) {
            req.put(Constants.Request.ARGUMENTS_KEY, new HashMap<>(args));
        }

        if (getBoolean(cfg, DISABLE_ON_CANCEL_KEY, false)) {
            req.put(Constants.Request.DISABLE_ON_CANCEL_KEY, true);
        }

        if (getBoolean(cfg, DISABLE_ON_FAILURE_KEY, false)) {
            req.put(Constants.Request.DISABLE_ON_FAILURE_KEY, true);
        }

        Collection<String> outVars = (Collection<String>) cfg.get(OUT_VARS_KEY);
        if (outVars != null && !outVars.isEmpty()) {
            req.put(Constants.Request.OUT_EXPRESSIONS_KEY, outVars);
        }

        return req;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> getSet(Map<String, Object> cfg, String k) {
        if (cfg == null) {
            return null;
        }

        Object v = cfg.get(k);
        if (v == null) {
            return null;
        }

        if (v instanceof String) {
            return Arrays.stream(((String) v)
                    .split(","))
                    .map(String::trim)
                    .collect(Collectors.toSet());
        } else if (v instanceof String[]) {
            return new HashSet<>(Arrays.asList((String[]) v));
        } else if (v instanceof Collection) {
            return new HashSet<>((Collection) v);
        } else {
            throw new IllegalArgumentException("'" + k + "' must a single string value or an array of strings: " + v);
        }
    }

    private static String getOrg(Context ctx, Map<String, Object> cfg) {
        String org = getString(cfg, ORG_KEY);
        if (org != null) {
            return org;
        }

        ProjectInfo pi = ContextUtils.getProjectInfo(ctx);
        if (pi != null) {
            return pi.orgName();
        }
        return null;
    }

    private static String getRepo(Map<String, Object> cfg) {
        String repo = getString(cfg, REPO_KEY);
        if (repo != null) {
            return repo;
        }
        repo = getString(cfg, REPOSITORY_KEY);
        if (repo != null) {
            log.warn("'{}' is deprecated, please use '{}' parameter", REPOSITORY_KEY, REPO_KEY);
        }
        return repo;
    }

    private static Action getAction(Context ctx) {
        String action = ContextUtils.assertString(ctx, ACTION_KEY);
        return Action.valueOf(action.trim().toUpperCase());
    }

    private static int getInstances(Map<String, Object> cfg) {
        int i;

        Object v = cfg.getOrDefault(INSTANCES_KEY, 1);
        if (v instanceof Integer) {
            i = (Integer) v;
        } else if (v instanceof Long) {
            i = ((Long) v).intValue();
        } else {
            throw new IllegalArgumentException("'" + INSTANCES_KEY + "' must be a number");
        }

        if (i <= 0) {
            throw new IllegalArgumentException("'" + INSTANCES_KEY + "' must be a positive number");
        }

        return i;
    }

    private static String getStartAt(Map<String, Object> cfg) {
        Object v = cfg.get(START_AT_KEY);
        if (v == null) {
            return null;
        }

        if (v instanceof String) {
            return (String) v;
        } else if (v instanceof Date) {
            Calendar c = Calendar.getInstance();
            c.setTime((Date) v);
            return DatatypeConverter.printDateTime(c);
        } else if (v instanceof Calendar) {
            return DatatypeConverter.printDateTime((Calendar) v);
        } else {
            throw new IllegalArgumentException("'" + START_AT_KEY + "' must be a string, java.util.Date or java.util.Calendar value. Got: " + v);
        }
    }

    private enum Action {

        START,
        STARTEXTERNAL,
        FORK,
        KILL
    }
}
