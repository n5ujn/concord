package com.walmartlabs.concord.server.console;

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

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.walmartlabs.concord.common.validation.ConcordKey;
import com.walmartlabs.concord.db.AbstractDao;
import com.walmartlabs.concord.sdk.EventType;
import com.walmartlabs.concord.server.ConcordApplicationException;
import com.walmartlabs.concord.server.jooq.tables.ProcessCheckpoints;
import com.walmartlabs.concord.server.jooq.tables.ProcessEvents;
import com.walmartlabs.concord.server.jooq.tables.VProcessQueue;
import com.walmartlabs.concord.server.metrics.WithTimer;
import com.walmartlabs.concord.server.org.OrganizationEntry;
import com.walmartlabs.concord.server.org.OrganizationManager;
import com.walmartlabs.concord.server.org.project.ProjectDao;
import com.walmartlabs.concord.server.org.project.RepositoryDao;
import com.walmartlabs.concord.server.org.secret.PasswordChecker;
import com.walmartlabs.concord.server.org.secret.SecretDao;
import com.walmartlabs.concord.server.org.team.TeamDao;
import com.walmartlabs.concord.server.process.ProcessStatus;
import com.walmartlabs.concord.server.repository.InvalidRepositoryPathException;
import com.walmartlabs.concord.server.repository.RepositoryManager;
import com.walmartlabs.concord.server.security.UserPrincipal;
import com.walmartlabs.concord.server.security.apikey.ApiKeyDao;
import com.walmartlabs.concord.server.security.ldap.LdapManager;
import com.walmartlabs.concord.server.security.ldap.LdapPrincipal;
import com.walmartlabs.concord.server.user.UserEntry;
import com.walmartlabs.concord.server.user.UserManager;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.UnauthorizedException;
import org.apache.shiro.subject.Subject;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.sonatype.siesta.Resource;
import org.sonatype.siesta.Validate;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.naming.NamingException;
import javax.validation.constraints.Size;
import javax.ws.rs.*;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.sql.Timestamp;
import java.util.*;

import static com.walmartlabs.concord.server.jooq.tables.ProcessCheckpoints.PROCESS_CHECKPOINTS;
import static com.walmartlabs.concord.server.jooq.tables.ProcessEvents.PROCESS_EVENTS;
import static com.walmartlabs.concord.server.jooq.tables.VProcessQueue.V_PROCESS_QUEUE;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.function;
import static org.jooq.impl.DSL.inline;

@Named
@Singleton
@Path("/api/service/console")
public class ConsoleService implements Resource {

    private final ProjectDao projectDao;
    private final RepositoryManager repositoryManager;
    private final UserManager userManager;
    private final SecretDao secretDao;
    private final OrganizationManager orgManager;
    private final RepositoryDao repositoryDao;
    private final TeamDao teamDao;
    private final LdapManager ldapManager;
    private final ProcessDao processDao;
    private final ApiKeyDao apiKeyDao;

    @Inject
    public ConsoleService(ProjectDao projectDao,
                          RepositoryManager repositoryManager,
                          UserManager userManager,
                          SecretDao secretDao,
                          OrganizationManager orgManager,
                          RepositoryDao repositoryDao,
                          TeamDao teamDao,
                          LdapManager ldapManager,
                          ProcessDao processDao,
                          ApiKeyDao apiKeyDao) {

        this.projectDao = projectDao;
        this.repositoryManager = repositoryManager;
        this.userManager = userManager;
        this.repositoryDao = repositoryDao;
        this.secretDao = secretDao;
        this.orgManager = orgManager;
        this.teamDao = teamDao;
        this.ldapManager = ldapManager;
        this.apiKeyDao = apiKeyDao;
        this.processDao = processDao;
    }

    @GET
    @Path("/whoami")
    @Produces(MediaType.APPLICATION_JSON)
    public UserResponse whoami() {
        UserPrincipal p = UserPrincipal.getCurrent();
        if (p == null) {
            throw new ConcordApplicationException("Can't determine current user: entry not found",
                    Status.INTERNAL_SERVER_ERROR);
        }

        String displayName = null;

        LdapPrincipal l = LdapPrincipal.getCurrent();
        if (l != null) {
            displayName = l.getDisplayName();
        }

        if (displayName == null) {
            displayName = p.getUsername();
        }

        UserEntry user = userManager.get(p.getId())
                .orElseThrow(() -> new ConcordApplicationException("Unknown user: " + p.getId()));

        return new UserResponse(p.getRealm(), user.getName(), displayName, user.getOrgs());
    }

    @POST
    @Path("/logout")
    public void logout() {
        Subject subject = SecurityUtils.getSubject();
        if (subject == null || !subject.isAuthenticated()) {
            return;
        }

        subject.logout();
    }

    @GET
    @Path("/org/{orgName}/project/{projectName}/exists")
    @Produces(MediaType.APPLICATION_JSON)
    @WithTimer
    public boolean isProjectExists(@PathParam("orgName") @ConcordKey String orgName,
                                   @PathParam("projectName") String projectName) {

        OrganizationEntry org = orgManager.assertAccess(orgName, true);
        return projectDao.getId(org.getId(), projectName) != null;
    }

    @GET
    @Path("/org/{orgName}/secret/{secretName}/exists")
    @Produces(MediaType.APPLICATION_JSON)
    @WithTimer
    public boolean isSecretExists(@PathParam("orgName") @ConcordKey String orgName,
                                  @PathParam("secretName") String secretName) {
        try {
            OrganizationEntry org = orgManager.assertAccess(orgName, true);
            return secretDao.getId(org.getId(), secretName) != null;
        } catch (UnauthorizedException e) {
            return false;
        }
    }

    @GET
    @Path("/org/{orgName}/project/{projectName}/repo/{repoName}/exists")
    @Produces(MediaType.APPLICATION_JSON)
    @WithTimer
    public boolean isRepositoryExists(@PathParam("orgName") @ConcordKey String orgName,
                                      @PathParam("projectName") @ConcordKey String projectName,
                                      @PathParam("repoName") String repoName) {
        try {
            OrganizationEntry org = orgManager.assertAccess(orgName, true);
            UUID projectId = projectDao.getId(org.getId(), projectName);
            if (projectId == null) {
                throw new ConcordApplicationException("Project not found: " + projectName, Status.BAD_REQUEST);
            }
            return repositoryDao.getId(projectId, repoName) != null;
        } catch (UnauthorizedException e) {
            return false;
        }
    }

    @GET
    @Path("/org/{orgName}/team/{teamName}/exists")
    @Produces(MediaType.APPLICATION_JSON)
    @WithTimer
    public boolean isTeamExists(@PathParam("orgName") @ConcordKey String orgName,
                                @PathParam("teamName") @ConcordKey String teamName) {
        try {
            OrganizationEntry org = orgManager.assertAccess(orgName, true);
            return teamDao.getId(org.getId(), teamName) != null;
        } catch (UnauthorizedException e) {
            return false;
        }
    }

    @GET
    @Path("/apikey/{name}/exists")
    @Produces(MediaType.APPLICATION_JSON)
    @WithTimer
    public boolean isApiTokenExists(@PathParam("name") @ConcordKey String tokenName) {
        UserPrincipal currentUser = UserPrincipal.getCurrent();
        if (currentUser == null) {
            return false;
        }

        UUID userId = currentUser.getId();
        return apiKeyDao.getId(userId, tokenName) != null;
    }

    @POST
    @Path("/repository/test")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @WithTimer
    public boolean testRepository(RepositoryTestRequest req) {
        OrganizationEntry org = orgManager.assertAccess(req.getOrgId(), req.getOrgName(), false);

        try {
            String secretName = secretDao.getName(req.getSecretId());
            repositoryManager.testConnection(org.getId(), req.getUrl(), req.getBranch(), req.getCommitId(), req.getPath(), secretName);
            return true;
        } catch (InvalidRepositoryPathException e) {
            Map<String, String> m = new HashMap<>();
            m.put("message", "Repository validation error");
            m.put("level", "WARN");
            m.put("details", e.getMessage());

            throw new ConcordApplicationException(Response.status(Status.INTERNAL_SERVER_ERROR)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                    .entity(m)
                    .build());
        } catch (Exception e) {
            String msg;
            Throwable t = e;
            while (true) {
                msg = t.getMessage();
                t = t.getCause();
                if (t == null) {
                    break;
                }
            }

            if (msg == null) {
                msg = "Repository test error";
            }

            throw new ConcordApplicationException(Response.status(Status.INTERNAL_SERVER_ERROR)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN)
                    .entity(msg)
                    .build());
        }
    }

    @GET
    @Path("/search/users")
    @Produces(MediaType.APPLICATION_JSON)
    @Validate
    @WithTimer
    public List<UserSearchResult> searchUsers(@QueryParam("filter") @Size(min = 5, max = 128) String filter) {
        if (filter == null) {
            return Collections.emptyList();
        }

        filter = filter.trim();
        if (filter.startsWith("*")) {
            // disallow "starts-with" filters, they can be too slow
            return Collections.emptyList();
        }

        try {
            return ldapManager.search(filter);
        } catch (NamingException e) {
            throw new ConcordApplicationException("LDAP search error: " + e.getMessage(), e);
        }
    }

    @POST
    @Path("/validate-password")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    public boolean validatePassword(String pwd) {
        try {
            PasswordChecker.check(pwd);
        } catch (PasswordChecker.CheckerException e) {
            return false;
        }

        return true;
    }

    @GET
    @Path("/process")
    @Produces(MediaType.APPLICATION_JSON)
    @WithTimer
    public List<ProcessEntry> listProcesses(
            @QueryParam("orgId") UUID orgId,
            @QueryParam("projectId") UUID projectId,
            @QueryParam("limit") @DefaultValue("30") int limit) {
        if (limit <= 0) {
            throw new ConcordApplicationException("'limit' must be a positive number", Status.BAD_REQUEST);
        }

        return processDao.list(orgId, projectId, limit);
    }

    @Named
    public static class ProcessDao extends AbstractDao {

        @Inject
        protected ProcessDao(@Named("app") Configuration cfg) {
            super(cfg);
        }

        public List<ProcessEntry> list(UUID orgId, UUID projectId, int limit) {
            VProcessQueue pq = V_PROCESS_QUEUE.as("pq");
            ProcessCheckpoints pc = PROCESS_CHECKPOINTS.as("pc");
            ProcessEvents pe = PROCESS_EVENTS.as("pe");

            try (DSLContext tx = DSL.using(cfg)) {
                Field<Object> checkpoints = tx.select(
                        function("array_to_json", Object.class,
                            function("array_agg", Object.class,
                                function("json_strip_nulls", Object.class,
                                        function("json_build_object", Object.class,
                                                inline("id"), pc.CHECKPOINT_ID,
                                                inline("name"), pc.CHECKPOINT_NAME,
                                                inline("createdAt"), pc.CHECKPOINT_DATE)))))
                        .from(pc)
                        .where(pc.INSTANCE_ID.eq(pq.INSTANCE_ID)).asField();

                Field<Object> status = tx.select(
                        function("array_to_json", Object.class,
                                function("array_agg", Object.class,
                                        function("json_strip_nulls", Object.class,
                                                function("json_build_object", Object.class,
                                                        inline("changeDate"), pe.EVENT_DATE,
                                                        inline("status"), field("{0}->'status'", Object.class, pe.EVENT_DATA),
                                                        inline("checkpointId"), field("{0}->'checkpointId'", Object.class, pe.EVENT_DATA))))))
                        .from(pe)
                        .where(pq.INSTANCE_ID.eq(pe.INSTANCE_ID).and(pe.EVENT_TYPE.eq(EventType.PROCESS_STATUS.name()).and(pe.EVENT_DATE.greaterOrEqual(pq.CREATED_AT))))
                        .asField();

                SelectJoinStep<Record11<UUID, UUID, String, UUID, String, String, String, Timestamp, Timestamp, Object, Object>> s = tx
                        .select(pq.INSTANCE_ID,
                                pq.ORG_ID, pq.ORG_NAME,
                                pq.PROJECT_ID, pq.PROJECT_NAME,
                                pq.INITIATOR,
                                pq.CURRENT_STATUS,
                                pq.CREATED_AT,
                                pq.LAST_UPDATED_AT,
                                checkpoints,
                                status)
                        .from(pq);

                if (orgId != null) {
                    s.where(pq.ORG_ID.eq(orgId));
                }

                if (projectId != null) {
                    s.where(pq.PROJECT_ID.eq(projectId));
                }

                return s.orderBy(pq.CREATED_AT.desc())
                        .limit(limit)
                        .fetch(this::toEntry);
            }
        }

        private ProcessEntry toEntry(Record11<UUID, UUID, String, UUID, String, String, String, Timestamp, Timestamp, Object, Object> r) {
            return new ProcessEntry(r.value1(),
                    r.value2(), r.value3(),
                    r.value4(), r.value5(),
                    r.value6(),
                    ProcessStatus.valueOf(r.value7()),
                    r.value8(),
                    r.value9(),
                    r.value10(),
                    r.value11());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class ProcessEntry {
        private final UUID instanceId;
        private final UUID orgId;
        private final String orgName;
        private final UUID projectId;
        private final String projectName;
        private final String initiator;
        private final ProcessStatus status;

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX")
        private final Date createdAt;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX")
        private final Date lastUpdatedAt;

        private final Object checkpoints;
        private final Object statusHistory;

        public ProcessEntry(@JsonProperty("instanceId") UUID instanceId,
                            @JsonProperty("orgId") UUID orgId,
                            @JsonProperty("orgName") String orgName,
                            @JsonProperty("projectId") UUID projectId,
                            @JsonProperty("projectName") String projectName,
                            @JsonProperty("initiator") String initiator,
                            @JsonProperty("status") ProcessStatus status,
                            @JsonProperty("createdAt") Date createdAt,
                            @JsonProperty("lastUpdatedAt") Date lastUpdatedAt,
                            @JsonProperty("checkpoints") Object checkpoints,
                            @JsonProperty("history") Object statusHistory) {
            this.instanceId = instanceId;
            this.orgId = orgId;
            this.orgName = orgName;
            this.projectId = projectId;
            this.projectName = projectName;
            this.initiator = initiator;
            this.status = status;
            this.createdAt = createdAt;
            this.lastUpdatedAt = lastUpdatedAt;
            this.checkpoints = checkpoints;
            this.statusHistory = statusHistory;
        }

        public UUID getInstanceId() {
            return instanceId;
        }

        public UUID getOrgId() {
            return orgId;
        }

        public String getOrgName() {
            return orgName;
        }

        public UUID getProjectId() {
            return projectId;
        }

        public String getProjectName() {
            return projectName;
        }

        public String getInitiator() {
            return initiator;
        }

        public ProcessStatus getStatus() {
            return status;
        }

        public Date getCreatedAt() {
            return createdAt;
        }

        public Date getLastUpdatedAt() {
            return lastUpdatedAt;
        }

        @JsonRawValue
        public Object getCheckpoints() {
            return checkpoints;
        }

        @JsonRawValue
        public Object getStatusHistory() {
            return statusHistory;
        }
    }
}
