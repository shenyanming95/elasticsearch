/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingAction;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.AliasOrIndex;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.CheckedSupplier;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.Index;
import org.elasticsearch.license.LicenseUtils;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.persistent.AllocatedPersistentTask;
import org.elasticsearch.persistent.PersistentTaskState;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.persistent.PersistentTasksExecutor;
import org.elasticsearch.persistent.PersistentTasksService;
import org.elasticsearch.plugins.MapperPlugin;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.XPackField;
import org.elasticsearch.xpack.core.ml.MlMetaIndex;
import org.elasticsearch.xpack.core.ml.MlMetadata;
import org.elasticsearch.xpack.core.ml.MlTasks;
import org.elasticsearch.xpack.core.ml.action.FinalizeJobExecutionAction;
import org.elasticsearch.xpack.core.ml.action.OpenJobAction;
import org.elasticsearch.xpack.core.ml.action.PutJobAction;
import org.elasticsearch.xpack.core.ml.action.UpdateJobAction;
import org.elasticsearch.xpack.core.ml.job.config.AnalysisLimits;
import org.elasticsearch.xpack.core.ml.job.config.DetectionRule;
import org.elasticsearch.xpack.core.ml.job.config.Job;
import org.elasticsearch.xpack.core.ml.job.config.JobState;
import org.elasticsearch.xpack.core.ml.job.config.JobTaskState;
import org.elasticsearch.xpack.core.ml.job.config.JobUpdate;
import org.elasticsearch.xpack.core.ml.job.persistence.AnomalyDetectorsIndex;
import org.elasticsearch.xpack.core.ml.job.persistence.ElasticsearchMappings;
import org.elasticsearch.xpack.core.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.ml.MachineLearning;
import org.elasticsearch.xpack.ml.MlConfigMigrationEligibilityCheck;
import org.elasticsearch.xpack.ml.job.ClusterStateJobUpdate;
import org.elasticsearch.xpack.ml.job.JobManager;
import org.elasticsearch.xpack.ml.job.persistence.JobConfigProvider;
import org.elasticsearch.xpack.ml.job.persistence.JobResultsProvider;
import org.elasticsearch.xpack.ml.job.process.autodetect.AutodetectProcessManager;
import org.elasticsearch.xpack.ml.process.MlMemoryTracker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.elasticsearch.xpack.core.ClientHelper.ML_ORIGIN;
import static org.elasticsearch.xpack.core.ClientHelper.executeAsyncWithOrigin;
import static org.elasticsearch.xpack.ml.job.process.autodetect.AutodetectProcessManager.MAX_OPEN_JOBS_PER_NODE;

/*
 This class extends from TransportMasterNodeAction for cluster state observing purposes.
 The close job api also redirect the elected master node.
 The master node will wait for the job to be opened by checking the persistent task's status and then return.
 To ensure that a subsequent close job call will see that same task status (and sanity validation doesn't fail)
 both open and close job apis redirect to the elected master node.
 In case of instability persistent tasks checks may fail and that is ok, in that case all bets are off.
 The open job api is a low through put api, so the fact that we redirect to elected master node shouldn't be an issue.
*/
public class TransportOpenJobAction extends TransportMasterNodeAction<OpenJobAction.Request, AcknowledgedResponse> {

    private static final PersistentTasksCustomMetaData.Assignment AWAITING_LAZY_ASSIGNMENT =
        new PersistentTasksCustomMetaData.Assignment(null, "persistent task is awaiting node assignment.");

    private final XPackLicenseState licenseState;
    private final PersistentTasksService persistentTasksService;
    private final Client client;
    private final JobConfigProvider jobConfigProvider;
    private final JobResultsProvider jobResultsProvider;
    private final JobManager jobManager;
    private final MlMemoryTracker memoryTracker;
    private final MlConfigMigrationEligibilityCheck migrationEligibilityCheck;

    @Inject
    public TransportOpenJobAction(Settings settings, TransportService transportService, ThreadPool threadPool,
                                  XPackLicenseState licenseState, ClusterService clusterService,
                                  PersistentTasksService persistentTasksService, ActionFilters actionFilters,
                                  IndexNameExpressionResolver indexNameExpressionResolver, Client client,
                                  JobResultsProvider jobResultsProvider, JobManager jobManager,
                                  JobConfigProvider jobConfigProvider, MlMemoryTracker memoryTracker) {
        super(settings, OpenJobAction.NAME, transportService, clusterService, threadPool, actionFilters,
                indexNameExpressionResolver, OpenJobAction.Request::new);
        this.licenseState = licenseState;
        this.persistentTasksService = persistentTasksService;
        this.client = client;
        this.jobResultsProvider = jobResultsProvider;
        this.jobConfigProvider = jobConfigProvider;
        this.jobManager = jobManager;
        this.memoryTracker = memoryTracker;
        this.migrationEligibilityCheck = new MlConfigMigrationEligibilityCheck(settings, clusterService);
    }

    /**
     * Validations to fail fast before trying to update the job state on master node:
     * <ul>
     *     <li>check job exists</li>
     *     <li>check job is not marked as deleted</li>
     *     <li>check job's version is supported</li>
     * </ul>
     */
    static void validate(String jobId, Job job) {
        if (job == null) {
            throw ExceptionsHelper.missingJobException(jobId);
        }
        if (job.isDeleting()) {
            throw ExceptionsHelper.conflictStatusException("Cannot open job [" + jobId + "] because it is being deleted");
        }
        if (job.getJobVersion() == null) {
            throw ExceptionsHelper.badRequestException("Cannot open job [" + jobId
                    + "] because jobs created prior to version 5.5 are not supported");
        }
    }

    static PersistentTasksCustomMetaData.Assignment selectLeastLoadedMlNode(String jobId, @Nullable Job job,
                                                                            ClusterState clusterState,
                                                                            int maxConcurrentJobAllocations,
                                                                            int fallbackMaxNumberOfOpenJobs,
                                                                            int maxMachineMemoryPercent,
                                                                            MlMemoryTracker memoryTracker,
                                                                            Logger logger) {
        if (job == null) {
            logger.debug("[{}] select node job is null", jobId);
        }

        String resultsIndexName = job != null ? job.getResultsIndexName() : null;
        List<String> unavailableIndices = verifyIndicesPrimaryShardsAreActive(resultsIndexName, clusterState);
        if (unavailableIndices.size() != 0) {
            String reason = "Not opening job [" + jobId + "], because not all primary shards are active for the following indices [" +
                    String.join(",", unavailableIndices) + "]";
            logger.debug(reason);
            return new PersistentTasksCustomMetaData.Assignment(null, reason);
        }

        // Try to allocate jobs according to memory usage, but if that's not possible (maybe due to a mixed version cluster or maybe
        // because of some weird OS problem) then fall back to the old mechanism of only considering numbers of assigned jobs
        boolean allocateByMemory = true;

        if (memoryTracker.isRecentlyRefreshed() == false) {

            boolean scheduledRefresh = memoryTracker.asyncRefresh();
            if (scheduledRefresh) {
                String reason = "Not opening job [" + jobId + "] because job memory requirements are stale - refresh requested";
                logger.debug(reason);
                return new PersistentTasksCustomMetaData.Assignment(null, reason);
            } else {
                allocateByMemory = false;
                logger.warn("Falling back to allocating job [{}] by job counts because a memory requirement refresh could not be scheduled",
                    jobId);
            }
        }

        List<String> reasons = new LinkedList<>();
        long maxAvailableCount = Long.MIN_VALUE;
        long maxAvailableMemory = Long.MIN_VALUE;
        DiscoveryNode minLoadedNodeByCount = null;
        DiscoveryNode minLoadedNodeByMemory = null;
        PersistentTasksCustomMetaData persistentTasks = clusterState.getMetaData().custom(PersistentTasksCustomMetaData.TYPE);
        for (DiscoveryNode node : clusterState.getNodes()) {
            Map<String, String> nodeAttributes = node.getAttributes();
            String enabled = nodeAttributes.get(MachineLearning.ML_ENABLED_NODE_ATTR);
            if (Boolean.valueOf(enabled) == false) {
                String reason = "Not opening job [" + jobId + "] on node [" + nodeNameOrId(node)
                        + "], because this node isn't a ml node.";
                logger.trace(reason);
                reasons.add(reason);
                continue;
            }

            if (nodeSupportsMlJobs(node.getVersion()) == false) {
                String reason = "Not opening job [" + jobId + "] on node [" + nodeNameAndVersion(node)
                        + "], because this node does not support machine learning jobs";
                logger.trace(reason);
                reasons.add(reason);
                continue;
            }

            if (job != null) {
                Set<String> compatibleJobTypes = Job.getCompatibleJobTypes(node.getVersion());
                if (compatibleJobTypes.contains(job.getJobType()) == false) {
                    String reason = "Not opening job [" + jobId + "] on node [" + nodeNameAndVersion(node) +
                            "], because this node does not support jobs of type [" + job.getJobType() + "]";
                    logger.trace(reason);
                    reasons.add(reason);
                    continue;
                }

                if (jobHasRules(job) && node.getVersion().before(DetectionRule.VERSION_INTRODUCED)) {
                    String reason = "Not opening job [" + jobId + "] on node [" + nodeNameAndVersion(node) + "], because jobs using " +
                            "custom_rules require a node of version [" + DetectionRule.VERSION_INTRODUCED + "] or higher";
                    logger.trace(reason);
                    reasons.add(reason);
                    continue;
                }

                boolean jobConfigIsStoredInIndex = job.getJobVersion().onOrAfter(Version.V_6_6_0);
                if (jobConfigIsStoredInIndex && node.getVersion().before(Version.V_6_6_0)) {
                    String reason = "Not opening job [" + jobId + "] on node [" + nodeNameOrId(node)
                            + "] version [" + node.getVersion() + "], because this node does not support " +
                            "jobs of version [" + job.getJobVersion() + "]";
                    logger.trace(reason);
                    reasons.add(reason);
                    continue;
                }
            }

            long numberOfAssignedJobs = 0;
            int numberOfAllocatingJobs = 0;
            long assignedJobMemory = 0;
            if (persistentTasks != null) {
                // find all the job tasks assigned to this node
                Collection<PersistentTasksCustomMetaData.PersistentTask<?>> assignedTasks = persistentTasks.findTasks(
                        MlTasks.JOB_TASK_NAME, task -> node.getId().equals(task.getExecutorNode()));
                for (PersistentTasksCustomMetaData.PersistentTask<?> assignedTask : assignedTasks) {
                    JobState jobState = MlTasks.getJobStateModifiedForReassignments(assignedTask);
                    if (jobState.isAnyOf(JobState.CLOSED, JobState.FAILED) == false) {
                        // Don't count CLOSED or FAILED jobs, as they don't consume native memory
                        ++numberOfAssignedJobs;
                        if (jobState == JobState.OPENING) {
                            ++numberOfAllocatingJobs;
                        }
                        OpenJobAction.JobParams params = (OpenJobAction.JobParams) assignedTask.getParams();
                        Long jobMemoryRequirement = memoryTracker.getJobMemoryRequirement(params.getJobId());
                        if (jobMemoryRequirement == null) {
                            allocateByMemory = false;
                            logger.debug("Falling back to allocating job [{}] by job counts because " +
                                    "the memory requirement for job [{}] was not available", jobId, params.getJobId());
                        } else {
                            assignedJobMemory += jobMemoryRequirement;
                        }
                    }
                }
            }
            if (numberOfAllocatingJobs >= maxConcurrentJobAllocations) {
                String reason = "Not opening job [" + jobId + "] on node [" + nodeNameAndMlAttributes(node)
                        + "], because node exceeds [" + numberOfAllocatingJobs +
                        "] the maximum number of jobs [" + maxConcurrentJobAllocations + "] in opening state";
                logger.trace(reason);
                reasons.add(reason);
                continue;
            }

            String maxNumberOfOpenJobsStr = nodeAttributes.get(MachineLearning.MAX_OPEN_JOBS_NODE_ATTR);
            int maxNumberOfOpenJobs = fallbackMaxNumberOfOpenJobs;
            // TODO: remove leniency and reject the node if the attribute is null in 7.0
            if (maxNumberOfOpenJobsStr != null) {
                try {
                    maxNumberOfOpenJobs = Integer.parseInt(maxNumberOfOpenJobsStr);
                } catch (NumberFormatException e) {
                    String reason = "Not opening job [" + jobId + "] on node [" + nodeNameAndMlAttributes(node) + "], because " +
                            MachineLearning.MAX_OPEN_JOBS_NODE_ATTR + " attribute [" + maxNumberOfOpenJobsStr + "] is not an integer";
                    logger.trace(reason);
                    reasons.add(reason);
                    continue;
                }
            }
            long availableCount = maxNumberOfOpenJobs - numberOfAssignedJobs;
            if (availableCount == 0) {
                String reason = "Not opening job [" + jobId + "] on node [" + nodeNameAndMlAttributes(node)
                        + "], because this node is full. Number of opened jobs [" + numberOfAssignedJobs
                        + "], " + MAX_OPEN_JOBS_PER_NODE.getKey() + " [" + maxNumberOfOpenJobs + "]";
                logger.trace(reason);
                reasons.add(reason);
                continue;
            }

            if (maxAvailableCount < availableCount) {
                maxAvailableCount = availableCount;
                minLoadedNodeByCount = node;
            }

            String machineMemoryStr = nodeAttributes.get(MachineLearning.MACHINE_MEMORY_NODE_ATTR);
            long machineMemory = -1;
            // TODO: remove leniency and reject the node if the attribute is null in 7.0
            if (machineMemoryStr != null) {
                try {
                    machineMemory = Long.parseLong(machineMemoryStr);
                } catch (NumberFormatException e) {
                    String reason = "Not opening job [" + jobId + "] on node [" + nodeNameAndMlAttributes(node) + "], because " +
                        MachineLearning.MACHINE_MEMORY_NODE_ATTR + " attribute [" + machineMemoryStr + "] is not a long";
                    logger.trace(reason);
                    reasons.add(reason);
                    continue;
                }
            }

            if (allocateByMemory) {
                if (machineMemory > 0) {
                    long maxMlMemory = machineMemory * maxMachineMemoryPercent / 100;
                    Long estimatedMemoryFootprint = memoryTracker.getJobMemoryRequirement(jobId);
                    if (estimatedMemoryFootprint != null) {
                        long availableMemory = maxMlMemory - assignedJobMemory;
                        if (estimatedMemoryFootprint > availableMemory) {
                            String reason = "Not opening job [" + jobId + "] on node [" + nodeNameAndMlAttributes(node) +
                                "], because this node has insufficient available memory. Available memory for ML [" + maxMlMemory +
                                "], memory required by existing jobs [" + assignedJobMemory +
                                "], estimated memory required for this job [" + estimatedMemoryFootprint + "]";
                            logger.trace(reason);
                            reasons.add(reason);
                            continue;
                        }

                        if (maxAvailableMemory < availableMemory) {
                            maxAvailableMemory = availableMemory;
                            minLoadedNodeByMemory = node;
                        }
                    } else {
                        // If we cannot get the job memory requirement,
                        // fall back to simply allocating by job count
                        allocateByMemory = false;
                        logger.debug("Falling back to allocating job [{}] by job counts because its memory requirement was not available",
                            jobId);
                    }
                } else {
                    // If we cannot get the available memory on any machine in
                    // the cluster, fall back to simply allocating by job count
                    allocateByMemory = false;
                    logger.debug("Falling back to allocating job [{}] by job counts because machine memory was not available for node [{}]",
                        jobId, nodeNameAndMlAttributes(node));
                }
            }
        }
        DiscoveryNode minLoadedNode = allocateByMemory ? minLoadedNodeByMemory : minLoadedNodeByCount;
        if (minLoadedNode != null) {
            logger.debug("selected node [{}] for job [{}]", minLoadedNode, jobId);
            return new PersistentTasksCustomMetaData.Assignment(minLoadedNode.getId(), "");
        } else {
            String explanation = String.join("|", reasons);
            logger.debug("no node selected for job [{}], reasons [{}]", jobId, explanation);
            return new PersistentTasksCustomMetaData.Assignment(null, explanation);
        }
    }

    static String nodeNameOrId(DiscoveryNode node) {
        String nodeNameOrID = node.getName();
        if (Strings.isNullOrEmpty(nodeNameOrID)) {
            nodeNameOrID = node.getId();
        }
        return nodeNameOrID;
    }

    static String nodeNameAndVersion(DiscoveryNode node) {
        String nodeNameOrID = nodeNameOrId(node);
        StringBuilder builder = new StringBuilder("{").append(nodeNameOrID).append('}');
        builder.append('{').append("version=").append(node.getVersion()).append('}');
        return builder.toString();
    }

    static String nodeNameAndMlAttributes(DiscoveryNode node) {
        String nodeNameOrID = nodeNameOrId(node);

        StringBuilder builder = new StringBuilder("{").append(nodeNameOrID).append('}');
        for (Map.Entry<String, String> entry : node.getAttributes().entrySet()) {
            if (entry.getKey().startsWith("ml.") || entry.getKey().equals("node.ml")) {
                builder.append('{').append(entry).append('}');
            }
        }
        return builder.toString();
    }

    static String[] indicesOfInterest(String resultsIndex) {
        if (resultsIndex == null) {
            return new String[]{AnomalyDetectorsIndex.jobStateIndexName(), MlMetaIndex.INDEX_NAME};
        }
        return new String[]{AnomalyDetectorsIndex.jobStateIndexName(), resultsIndex, MlMetaIndex.INDEX_NAME};
    }

    static List<String> verifyIndicesPrimaryShardsAreActive(String resultsIndex, ClusterState clusterState) {
        String[] indices = indicesOfInterest(resultsIndex);
        List<String> unavailableIndices = new ArrayList<>(indices.length);
        for (String index : indices) {
            // Indices are created on demand from templates.
            // It is not an error if the index doesn't exist yet
            if (clusterState.metaData().hasIndex(index) == false) {
                continue;
            }
            IndexRoutingTable routingTable = clusterState.getRoutingTable().index(index);
            if (routingTable == null || routingTable.allPrimaryShardsActive() == false) {
                unavailableIndices.add(index);
            }
        }
        return unavailableIndices;
    }

    private static boolean nodeSupportsMlJobs(Version nodeVersion) {
        return nodeVersion.onOrAfter(Version.V_5_5_0);
    }

    private static boolean jobHasRules(Job job) {
        return job.getAnalysisConfig().getDetectors().stream().anyMatch(d -> d.getRules().isEmpty() == false);
    }

    public static String[] mappingRequiresUpdate(ClusterState state, String[] concreteIndices, Version minVersion, Logger logger)
            throws IOException {

        List<String> indicesToUpdate = new ArrayList<>();

        ImmutableOpenMap<String, ImmutableOpenMap<String, MappingMetaData>> currentMapping = state.metaData().findMappings(concreteIndices,
                new String[] { ElasticsearchMappings.DOC_TYPE }, MapperPlugin.NOOP_FIELD_FILTER);

        for (String index : concreteIndices) {
            ImmutableOpenMap<String, MappingMetaData> innerMap = currentMapping.get(index);
            if (innerMap != null) {
                MappingMetaData metaData = innerMap.get(ElasticsearchMappings.DOC_TYPE);
                try {
                    Map<String, Object> meta = (Map<String, Object>) metaData.sourceAsMap().get("_meta");
                    if (meta != null) {
                        String versionString = (String) meta.get("version");
                        if (versionString == null) {
                            logger.info("Version of mappings for [{}] not found, recreating", index);
                            indicesToUpdate.add(index);
                            continue;
                        }

                        Version mappingVersion = Version.fromString(versionString);

                        if (mappingVersion.onOrAfter(minVersion)) {
                            continue;
                        } else {
                            logger.info("Mappings for [{}] are outdated [{}], updating it[{}].", index, mappingVersion, Version.CURRENT);
                            indicesToUpdate.add(index);
                            continue;
                        }
                    } else {
                        logger.info("Version of mappings for [{}] not found, recreating", index);
                        indicesToUpdate.add(index);
                        continue;
                    }
                } catch (Exception e) {
                    logger.error(new ParameterizedMessage("Failed to retrieve mapping version for [{}], recreating", index), e);
                    indicesToUpdate.add(index);
                    continue;
                }
            } else {
                logger.info("No mappings found for [{}], recreating", index);
                indicesToUpdate.add(index);
            }
        }
        return indicesToUpdate.toArray(new String[indicesToUpdate.size()]);
    }

    @Override
    protected String executor() {
        // This api doesn't do heavy or blocking operations (just delegates PersistentTasksService),
        // so we can do this on the network thread
        return ThreadPool.Names.SAME;
    }

    @Override
    protected AcknowledgedResponse newResponse() {
        return new AcknowledgedResponse();
    }

    @Override
    protected ClusterBlockException checkBlock(OpenJobAction.Request request, ClusterState state) {
        // We only delegate here to PersistentTasksService, but if there is a metadata writeblock,
        // then delegating to PersistentTasksService doesn't make a whole lot of sense,
        // because PersistentTasksService will then fail.
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }

    @Override
    protected void masterOperation(OpenJobAction.Request request, ClusterState state, ActionListener<AcknowledgedResponse> listener) {
        if (migrationEligibilityCheck.jobIsEligibleForMigration(request.getJobParams().getJobId(), state)) {
            listener.onFailure(ExceptionsHelper.configHasNotBeenMigrated("open job", request.getJobParams().getJobId()));
            return;
        }

        OpenJobAction.JobParams jobParams = request.getJobParams();
        if (licenseState.isMachineLearningAllowed()) {

            // If the whole cluster supports the ML memory tracker then we don't need
            // to worry about updating established model memory on the job objects
            // TODO: remove in 7.0 as it will always be true
            boolean clusterSupportsMlMemoryTracker = state.getNodes().getMinNodeVersion().onOrAfter(Version.V_6_6_0);

            // Clear job finished time once the job is started and respond
            ActionListener<AcknowledgedResponse> clearJobFinishTime = ActionListener.wrap(
                response -> {
                    if (response.isAcknowledged()) {
                        clearJobFinishedTime(jobParams.getJobId(), listener);
                    } else {
                        listener.onResponse(response);
                    }
                },
                listener::onFailure
            );

            // Wait for job to be started
            ActionListener<PersistentTasksCustomMetaData.PersistentTask<OpenJobAction.JobParams>> waitForJobToStart =
                    new ActionListener<PersistentTasksCustomMetaData.PersistentTask<OpenJobAction.JobParams>>() {
                @Override
                public void onResponse(PersistentTasksCustomMetaData.PersistentTask<OpenJobAction.JobParams> task) {
                    waitForJobStarted(task.getId(), jobParams, clearJobFinishTime);
                }

                @Override
                public void onFailure(Exception e) {
                    if (e instanceof ResourceAlreadyExistsException) {
                        e = new ElasticsearchStatusException("Cannot open job [" + jobParams.getJobId() +
                                "] because it has already been opened", RestStatus.CONFLICT, e);
                    }
                    listener.onFailure(e);
                }
            };

            // Start job task
            ActionListener<Long> memoryRequirementRefreshListener = ActionListener.wrap(
                mem -> persistentTasksService.sendStartRequest(MlTasks.jobTaskId(jobParams.getJobId()), MlTasks.JOB_TASK_NAME, jobParams,
                    waitForJobToStart),
                listener::onFailure
            );

            // Tell the job tracker to refresh the memory requirement for this job and all other jobs that have persistent tasks
            ActionListener<PutJobAction.Response> jobUpdateListener = ActionListener.wrap(
                response -> memoryTracker.refreshJobMemoryAndAllOthers(jobParams.getJobId(), memoryRequirementRefreshListener),
                listener::onFailure
            );

            // Update established model memory for pre-6.1 jobs that haven't had it set (TODO: remove in 7.0)
            // and increase the model memory limit for 6.1 - 6.3 jobs
            ActionListener<Boolean> missingMappingsListener = ActionListener.wrap(
                    response -> {
                        Job job = jobParams.getJob();
                        if (job != null) {
                            Version jobVersion = job.getJobVersion();
                            Long jobEstablishedModelMemory = job.getEstablishedModelMemory();
                            if (clusterSupportsMlMemoryTracker == false && (jobVersion == null || jobVersion.before(Version.V_6_1_0))
                                    && (jobEstablishedModelMemory == null || jobEstablishedModelMemory == 0)) {
                                // TODO: remove in 7.0 - established model memory no longer needs to be set on the job object
                                // Set the established memory usage for pre 6.1 jobs
                                jobResultsProvider.getEstablishedMemoryUsage(job.getId(), null, null, establishedModelMemory -> {
                                    if (establishedModelMemory != null && establishedModelMemory > 0) {
                                        JobUpdate update = new JobUpdate.Builder(job.getId())
                                                .setEstablishedModelMemory(establishedModelMemory).build();
                                        UpdateJobAction.Request updateRequest = UpdateJobAction.Request.internal(job.getId(), update);

                                        executeAsyncWithOrigin(client, ML_ORIGIN, UpdateJobAction.INSTANCE, updateRequest,
                                                jobUpdateListener);
                                    } else {
                                        jobUpdateListener.onResponse(null);
                                    }
                                }, listener::onFailure);
                            } else if (jobVersion != null &&
                                    (jobVersion.onOrAfter(Version.V_6_1_0) && jobVersion.before(Version.V_6_3_0))) {
                                // Increase model memory limit if < 512MB
                                if (job.getAnalysisLimits() != null && job.getAnalysisLimits().getModelMemoryLimit() != null &&
                                        job.getAnalysisLimits().getModelMemoryLimit() < 512L) {

                                    long updatedModelMemoryLimit = (long) (job.getAnalysisLimits().getModelMemoryLimit() * 1.3);
                                    AnalysisLimits limits = new AnalysisLimits(updatedModelMemoryLimit,
                                            job.getAnalysisLimits().getCategorizationExamplesLimit());

                                    JobUpdate update = new JobUpdate.Builder(job.getId()).setJobVersion(Version.CURRENT)
                                            .setAnalysisLimits(limits).build();
                                    UpdateJobAction.Request updateRequest = UpdateJobAction.Request.internal(job.getId(), update);
                                    executeAsyncWithOrigin(client, ML_ORIGIN, UpdateJobAction.INSTANCE, updateRequest,
                                            jobUpdateListener);
                                } else {
                                    jobUpdateListener.onResponse(null);
                                }
                            }
                            else {
                                jobUpdateListener.onResponse(null);
                            }
                        } else {
                            jobUpdateListener.onResponse(null);
                        }
                    }, listener::onFailure
            );

            // Try adding state doc mapping
            ActionListener<Boolean> resultsPutMappingHandler = ActionListener.wrap(
                    response -> {
                        addDocMappingIfMissing(AnomalyDetectorsIndex.jobStateIndexName(), ElasticsearchMappings::stateMapping,
                                state, missingMappingsListener);
                    }, listener::onFailure
            );

            // Get the job config
            jobManager.getJob(jobParams.getJobId(), ActionListener.wrap(
                    job -> {
                        try {
                            jobParams.setJob(job);

                            // Try adding results doc mapping
                            addDocMappingIfMissing(AnomalyDetectorsIndex.jobResultsAliasedName(jobParams.getJobId()),
                                    ElasticsearchMappings::resultsMapping, state, resultsPutMappingHandler);
                        } catch (Exception e) {
                            listener.onFailure(e);
                        }
                    },
                    listener::onFailure
            ));
        } else {
            listener.onFailure(LicenseUtils.newComplianceException(XPackField.MACHINE_LEARNING));
        }
    }

    private void waitForJobStarted(String taskId, OpenJobAction.JobParams jobParams, ActionListener<AcknowledgedResponse> listener) {
        JobPredicate predicate = new JobPredicate();
        persistentTasksService.waitForPersistentTaskCondition(taskId, predicate, jobParams.getTimeout(),
                new PersistentTasksService.WaitForPersistentTaskListener<OpenJobAction.JobParams>() {
            @Override
            public void onResponse(PersistentTasksCustomMetaData.PersistentTask<OpenJobAction.JobParams> persistentTask) {
                if (predicate.exception != null) {
                    if (predicate.shouldCancel) {
                        // We want to return to the caller without leaving an unassigned persistent task, to match
                        // what would have happened if the error had been detected in the "fast fail" validation
                        cancelJobStart(persistentTask, predicate.exception, listener);
                    } else {
                        listener.onFailure(predicate.exception);
                    }
                } else {
                    listener.onResponse(new AcknowledgedResponse(predicate.opened));
                }
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }

            @Override
            public void onTimeout(TimeValue timeout) {
                listener.onFailure(new ElasticsearchException("Opening job ["
                        + jobParams.getJobId() + "] timed out after [" + timeout + "]"));
            }
        });
    }

    private void clearJobFinishedTime(String jobId, ActionListener<AcknowledgedResponse> listener) {

        boolean jobIsInClusterState = ClusterStateJobUpdate.jobIsInClusterState(clusterService.state(), jobId);
        if (jobIsInClusterState) {
            clusterService.submitStateUpdateTask("clearing-job-finish-time-for-" + jobId, new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    MlMetadata mlMetadata = MlMetadata.getMlMetadata(currentState);
                    MlMetadata.Builder mlMetadataBuilder = new MlMetadata.Builder(mlMetadata);
                    Job.Builder jobBuilder = new Job.Builder(mlMetadata.getJobs().get(jobId));
                    jobBuilder.setFinishedTime(null);

                    mlMetadataBuilder.putJob(jobBuilder.build(), true);
                    ClusterState.Builder builder = ClusterState.builder(currentState);
                    return builder.metaData(new MetaData.Builder(currentState.metaData())
                            .putCustom(MlMetadata.TYPE, mlMetadataBuilder.build()))
                            .build();
                }

                @Override
                public void onFailure(String source, Exception e) {
                    logger.error("[" + jobId + "] Failed to clear finished_time; source [" + source + "]", e);
                    listener.onResponse(new AcknowledgedResponse(true));
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState,
                                                  ClusterState newState) {
                    listener.onResponse(new AcknowledgedResponse(true));
                }
            });
        } else {
            JobUpdate update = new JobUpdate.Builder(jobId).setClearFinishTime(true).build();

            jobConfigProvider.updateJob(jobId, update, null, ActionListener.wrap(
                    job -> listener.onResponse(new AcknowledgedResponse(true)),
                    e -> {
                        logger.error("[" + jobId + "] Failed to clear finished_time", e);
                        // Not a critical error so continue
                        listener.onResponse(new AcknowledgedResponse(true));
                    }
            ));
        }
    }

    private void cancelJobStart(PersistentTasksCustomMetaData.PersistentTask<OpenJobAction.JobParams> persistentTask, Exception exception,
                                ActionListener<AcknowledgedResponse> listener) {
        persistentTasksService.sendRemoveRequest(persistentTask.getId(),
                new ActionListener<PersistentTasksCustomMetaData.PersistentTask<?>>() {
                    @Override
                    public void onResponse(PersistentTasksCustomMetaData.PersistentTask<?> task) {
                        // We succeeded in cancelling the persistent task, but the
                        // problem that caused us to cancel it is the overall result
                        listener.onFailure(exception);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        logger.error("[" + persistentTask.getParams().getJobId() + "] Failed to cancel persistent task that could " +
                                "not be assigned due to [" + exception.getMessage() + "]", e);
                        listener.onFailure(exception);
                    }
                }
        );
    }

    private void addDocMappingIfMissing(String alias, CheckedSupplier<XContentBuilder, IOException> mappingSupplier, ClusterState state,
                                        ActionListener<Boolean> listener) {
        AliasOrIndex aliasOrIndex = state.metaData().getAliasAndIndexLookup().get(alias);
        if (aliasOrIndex == null) {
            // The index has never been created yet
            listener.onResponse(true);
            return;
        }
        String[] concreteIndices = aliasOrIndex.getIndices().stream().map(IndexMetaData::getIndex).map(Index::getName)
                .toArray(String[]::new);

        String[] indicesThatRequireAnUpdate;
        try {
            indicesThatRequireAnUpdate = mappingRequiresUpdate(state, concreteIndices, Version.CURRENT, logger);
        } catch (IOException e) {
            listener.onFailure(e);
            return;
        }

        if (indicesThatRequireAnUpdate.length > 0) {
            try (XContentBuilder mapping = mappingSupplier.get()) {
                PutMappingRequest putMappingRequest = new PutMappingRequest(indicesThatRequireAnUpdate);
                putMappingRequest.type(ElasticsearchMappings.DOC_TYPE);
                putMappingRequest.source(mapping);
                executeAsyncWithOrigin(client, ML_ORIGIN, PutMappingAction.INSTANCE, putMappingRequest,
                        ActionListener.wrap(response -> {
                            if (response.isAcknowledged()) {
                                listener.onResponse(true);
                            } else {
                                listener.onFailure(new ElasticsearchException("Attempt to put missing mapping in indices "
                                        + Arrays.toString(indicesThatRequireAnUpdate) + " was not acknowledged"));
                            }
                        }, listener::onFailure));
            } catch (IOException e) {
                listener.onFailure(e);
            }
        } else {
            logger.trace("Mappings are uptodate.");
            listener.onResponse(true);
        }
    }

    public static class OpenJobPersistentTasksExecutor extends PersistentTasksExecutor<OpenJobAction.JobParams> {

        private static final Logger logger = LogManager.getLogger(OpenJobPersistentTasksExecutor.class);

        private final AutodetectProcessManager autodetectProcessManager;
        private final MlMemoryTracker memoryTracker;
        private final Client client;

        /**
         * The maximum number of open jobs can be different on each node.  However, nodes on older versions
         * won't add their setting to the cluster state, so for backwards compatibility with these nodes we
         * assume the older node's setting is the same as that of the node running this code.
         * TODO: remove this member in 7.0
         */
        private final int fallbackMaxNumberOfOpenJobs;
        private volatile int maxConcurrentJobAllocations;
        private volatile int maxMachineMemoryPercent;
        private volatile int maxLazyMLNodes;

        public OpenJobPersistentTasksExecutor(Settings settings, ClusterService clusterService,
                                              AutodetectProcessManager autodetectProcessManager, MlMemoryTracker memoryTracker,
                                              Client client) {
            super(MlTasks.JOB_TASK_NAME, MachineLearning.UTILITY_THREAD_POOL_NAME);
            this.autodetectProcessManager = autodetectProcessManager;
            this.memoryTracker = memoryTracker;
            this.client = client;
            this.fallbackMaxNumberOfOpenJobs = AutodetectProcessManager.MAX_OPEN_JOBS_PER_NODE.get(settings);
            this.maxConcurrentJobAllocations = MachineLearning.CONCURRENT_JOB_ALLOCATIONS.get(settings);
            this.maxMachineMemoryPercent = MachineLearning.MAX_MACHINE_MEMORY_PERCENT.get(settings);
            this.maxLazyMLNodes = MachineLearning.MAX_LAZY_ML_NODES.get(settings);
            clusterService.getClusterSettings()
                    .addSettingsUpdateConsumer(MachineLearning.CONCURRENT_JOB_ALLOCATIONS, this::setMaxConcurrentJobAllocations);
            clusterService.getClusterSettings()
                    .addSettingsUpdateConsumer(MachineLearning.MAX_MACHINE_MEMORY_PERCENT, this::setMaxMachineMemoryPercent);
            clusterService.getClusterSettings().addSettingsUpdateConsumer(MachineLearning.MAX_LAZY_ML_NODES, this::setMaxLazyMLNodes);
        }

        @Override
        public PersistentTasksCustomMetaData.Assignment getAssignment(OpenJobAction.JobParams params, ClusterState clusterState) {
            Job foundJob = params.getJob();
            if (foundJob == null) {
                // The job was added to the persistent task parameters in 6.6.0
                // if the field is not present the task was created before 6.6.0.
                // In which case the job should still be in the clusterstate
                foundJob = MlMetadata.getMlMetadata(clusterState).getJobs().get(params.getJobId());
            }

            PersistentTasksCustomMetaData.Assignment assignment = selectLeastLoadedMlNode(params.getJobId(),
                foundJob,
                clusterState,
                maxConcurrentJobAllocations,
                fallbackMaxNumberOfOpenJobs,
                maxMachineMemoryPercent,
                memoryTracker,
                logger);
            if (assignment.getExecutorNode() == null) {
                int numMlNodes = 0;
                for (DiscoveryNode node : clusterState.getNodes()) {
                    if (Boolean.valueOf(node.getAttributes().get(MachineLearning.ML_ENABLED_NODE_ATTR))) {
                        numMlNodes++;
                    }
                }

                if (numMlNodes < maxLazyMLNodes) { // Means we have lazy nodes left to allocate
                    assignment = AWAITING_LAZY_ASSIGNMENT;
                }
            }
            return assignment;
        }

        @Override
        public void validate(OpenJobAction.JobParams params, ClusterState clusterState) {

            TransportOpenJobAction.validate(params.getJobId(), params.getJob());

            // If we already know that we can't find an ml node because all ml nodes are running at capacity or
            // simply because there are no ml nodes in the cluster then we fail quickly here:
            PersistentTasksCustomMetaData.Assignment assignment = getAssignment(params, clusterState);
            if (assignment.getExecutorNode() == null && assignment.equals(AWAITING_LAZY_ASSIGNMENT) == false) {
                throw makeNoSuitableNodesException(logger, params.getJobId(), assignment.getExplanation());
            }
        }

        @Override
        protected void nodeOperation(AllocatedPersistentTask task, OpenJobAction.JobParams params, PersistentTaskState state) {
            JobTask jobTask = (JobTask) task;
            jobTask.autodetectProcessManager = autodetectProcessManager;
            JobTaskState jobTaskState = (JobTaskState) state;
            // If the job is failed then the Persistent Task Service will
            // try to restart it on a node restart. Exiting here leaves the
            // job in the failed state and it must be force closed.
            if (jobTaskState != null && jobTaskState.getState().isAnyOf(JobState.FAILED, JobState.CLOSING)) {
                return;
            }

            String jobId = jobTask.getJobId();
            autodetectProcessManager.openJob(jobTask, e2 -> {
                if (e2 == null) {
                    FinalizeJobExecutionAction.Request finalizeRequest = new FinalizeJobExecutionAction.Request(new String[]{jobId});
                    executeAsyncWithOrigin(client, ML_ORIGIN, FinalizeJobExecutionAction.INSTANCE, finalizeRequest,
                            ActionListener.wrap(
                                    response -> task.markAsCompleted(),
                                    e -> logger.error("error finalizing job [" + jobId + "]", e)
                            ));
                } else {
                    task.markAsFailed(e2);
                }
            });
        }

        @Override
        protected AllocatedPersistentTask createTask(long id, String type, String action, TaskId parentTaskId,
                                                     PersistentTasksCustomMetaData.PersistentTask<OpenJobAction.JobParams> persistentTask,
                                                     Map<String, String> headers) {
             return new JobTask(persistentTask.getParams().getJobId(), id, type, action, parentTaskId, headers);
        }

        void setMaxConcurrentJobAllocations(int maxConcurrentJobAllocations) {
            logger.info("Changing [{}] from [{}] to [{}]", MachineLearning.CONCURRENT_JOB_ALLOCATIONS.getKey(),
                    this.maxConcurrentJobAllocations, maxConcurrentJobAllocations);
            this.maxConcurrentJobAllocations = maxConcurrentJobAllocations;
        }

        void setMaxMachineMemoryPercent(int maxMachineMemoryPercent) {
            logger.info("Changing [{}] from [{}] to [{}]", MachineLearning.MAX_MACHINE_MEMORY_PERCENT.getKey(),
                    this.maxMachineMemoryPercent, maxMachineMemoryPercent);
            this.maxMachineMemoryPercent = maxMachineMemoryPercent;
        }

        void setMaxLazyMLNodes(int maxLazyMLNodes) {
            logger.info("Changing [{}] from [{}] to [{}]", MachineLearning.MAX_LAZY_ML_NODES.getKey(),
                    this.maxLazyMLNodes, maxLazyMLNodes);
            this.maxLazyMLNodes = maxLazyMLNodes;
        }
    }

    public static class JobTask extends AllocatedPersistentTask implements OpenJobAction.JobTaskMatcher {

        private static final Logger LOGGER = LogManager.getLogger(JobTask.class);

        private final String jobId;
        private volatile AutodetectProcessManager autodetectProcessManager;

        JobTask(String jobId, long id, String type, String action, TaskId parentTask, Map<String, String> headers) {
            super(id, type, action, "job-" + jobId, parentTask, headers);
            this.jobId = jobId;
        }

        public String getJobId() {
            return jobId;
        }

        @Override
        protected void onCancelled() {
            String reason = getReasonCancelled();
            LOGGER.trace("[{}] Cancelling job task because: {}", jobId, reason);
            killJob(reason);
        }

        void killJob(String reason) {
            autodetectProcessManager.killProcess(this, false, reason);
        }

        void closeJob(String reason) {
            autodetectProcessManager.closeJob(this, false, reason);
        }

    }

    /**
     * This class contains the wait logic for waiting for a job's persistent task to be allocated on
     * job opening.  It should only be used in the open job action, and never at other times the job's
     * persistent task may be assigned to a node, for example on recovery from node failures.
     *
     * Important: the methods of this class must NOT throw exceptions.  If they did then the callers
     * of endpoints waiting for a condition tested by this predicate would never get a response.
     */
    private class JobPredicate implements Predicate<PersistentTasksCustomMetaData.PersistentTask<?>> {

        private volatile boolean opened;
        private volatile Exception exception;
        private volatile boolean shouldCancel;

        @Override
        public boolean test(PersistentTasksCustomMetaData.PersistentTask<?> persistentTask) {
            JobState jobState = JobState.CLOSED;
            if (persistentTask != null) {
                JobTaskState jobTaskState = (JobTaskState) persistentTask.getState();
                jobState = jobTaskState == null ? JobState.OPENING : jobTaskState.getState();

                PersistentTasksCustomMetaData.Assignment assignment = persistentTask.getAssignment();

                // This means we are awaiting a new node to be spun up, ok to return back to the user to await node creation
                if (assignment != null && assignment.equals(AWAITING_LAZY_ASSIGNMENT)) {
                    return true;
                }

                // This logic is only appropriate when opening a job, not when reallocating following a failure,
                // and this is why this class must only be used when opening a job
                if (assignment != null && assignment.equals(PersistentTasksCustomMetaData.INITIAL_ASSIGNMENT) == false &&
                        assignment.isAssigned() == false) {
                    OpenJobAction.JobParams params = (OpenJobAction.JobParams) persistentTask.getParams();
                    // Assignment has failed on the master node despite passing our "fast fail" validation
                    exception = makeNoSuitableNodesException(logger, params.getJobId(), assignment.getExplanation());
                    // The persistent task should be cancelled so that the observed outcome is the
                    // same as if the "fast fail" validation on the coordinating node had failed
                    shouldCancel = true;
                    return true;
                }
            }
            switch (jobState) {
                case OPENING:
                case CLOSED:
                    return false;
                case OPENED:
                    opened = true;
                    return true;
                case CLOSING:
                    exception = ExceptionsHelper.conflictStatusException("The job has been " + JobState.CLOSED + " while waiting to be "
                            + JobState.OPENED);
                    return true;
                case FAILED:
                default:
                    exception = ExceptionsHelper.serverError("Unexpected job state [" + jobState
                            + "] while waiting for job to be " + JobState.OPENED);
                    return true;
            }
        }
    }

    static ElasticsearchException makeNoSuitableNodesException(Logger logger, String jobId, String explanation) {
        String msg = "Could not open job because no suitable nodes were found, allocation explanation [" + explanation + "]";
        logger.warn("[{}] {}", jobId, msg);
        Exception detail = new IllegalStateException(msg);
        return new ElasticsearchStatusException("Could not open job because no ML nodes with sufficient capacity were found",
            RestStatus.TOO_MANY_REQUESTS, detail);
    }
}
