package de.tum.in.www1.bamboo.server;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import model.TestResultsWithSuccessInfo;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.atlassian.bamboo.artifact.MutableArtifact;
import com.atlassian.bamboo.build.BuildLoggerManager;
import com.atlassian.bamboo.build.BuildOutputLogEntry;
import com.atlassian.bamboo.build.ErrorLogEntry;
import com.atlassian.bamboo.build.LogEntry;
import com.atlassian.bamboo.build.artifact.ArtifactLink;
import com.atlassian.bamboo.build.artifact.ArtifactLinkDataProvider;
import com.atlassian.bamboo.build.artifact.ArtifactLinkManager;
import com.atlassian.bamboo.build.artifact.FileSystemArtifactLinkDataProvider;
import com.atlassian.bamboo.build.logger.BuildLogFileAccessor;
import com.atlassian.bamboo.build.logger.BuildLogFileAccessorFactory;
import com.atlassian.bamboo.build.logger.BuildLogger;
import com.atlassian.bamboo.chains.ChainResultsSummary;
import com.atlassian.bamboo.chains.ChainStageResult;
import com.atlassian.bamboo.commit.Commit;
import com.atlassian.bamboo.deployments.results.DeploymentResult;
import com.atlassian.bamboo.notification.Notification;
import com.atlassian.bamboo.notification.NotificationTransport;
import com.atlassian.bamboo.plan.cache.ImmutablePlan;
import com.atlassian.bamboo.results.tests.TestResults;
import com.atlassian.bamboo.resultsummary.BuildResultsSummary;
import com.atlassian.bamboo.resultsummary.ResultsSummary;
import com.atlassian.bamboo.resultsummary.tests.TestCaseResultError;
import com.atlassian.bamboo.resultsummary.tests.TestResultsSummary;
import com.atlassian.bamboo.resultsummary.vcs.RepositoryChangeset;
import com.atlassian.bamboo.task.TaskResult;
import com.atlassian.bamboo.utils.HttpUtils;
import com.atlassian.bamboo.variable.CustomVariableContext;
import com.atlassian.bamboo.variable.VariableDefinition;
import com.atlassian.bamboo.variable.VariableDefinitionManager;
import com.atlassian.spring.container.ContainerManager;
import com.google.common.collect.ImmutableList;

import de.tum.in.ase.parser.ReportParser;
import de.tum.in.ase.parser.exception.ParserException;

public class ServerNotificationTransport implements NotificationTransport {

    private static final Logger log = Logger.getLogger(ServerNotificationTransport.class);

    private final String webhookUrl;

    private CloseableHttpClient client;

    @Nullable
    private final ImmutablePlan plan;

    @Nullable
    private final ResultsSummary resultsSummary;

    @Nullable
    private final DeploymentResult deploymentResult;

    @Nullable
    private final BuildLoggerManager buildLoggerManager;

    @Nullable
    private final BuildLogFileAccessorFactory buildLogFileAccessorFactory;

    // Will be injected by Bamboo
    private VariableDefinitionManager variableDefinitionManager = (VariableDefinitionManager) ContainerManager.getComponent("variableDefinitionManager");

    private ArtifactLinkManager artifactLinkManager = (ArtifactLinkManager) ContainerManager.getComponent("artifactLinkManager");

    // Maximum length for the feedback text. The feedback will be truncated afterwards
    private static int FEEDBACK_DETAIL_TEXT_MAX_CHARACTERS = 5000;

    // Maximum number of lines of log per job. The last lines will be taken.
    private static int JOB_LOG_MAX_LINES = 5000;

    // We are only interested in logs coming from the build, not in logs from Bamboo
    final List<Class<?>> logEntryTypes = ImmutableList.of(BuildOutputLogEntry.class, ErrorLogEntry.class);

    private final CustomFeedbackParser customFeedbackParser;

    public ServerNotificationTransport(String webhookUrl,
            @Nullable ImmutablePlan plan,
            @Nullable ResultsSummary resultsSummary,
            @Nullable DeploymentResult deploymentResult,
            CustomVariableContext customVariableContext,
            BuildLoggerManager buildLoggerManager,
            BuildLogFileAccessorFactory buildLogFileAccessorFactory) {
        this.webhookUrl = customVariableContext.substituteString(webhookUrl);
        this.plan = plan;
        this.resultsSummary = resultsSummary;
        this.deploymentResult = deploymentResult;
        this.buildLoggerManager = buildLoggerManager;
        this.buildLogFileAccessorFactory = buildLogFileAccessorFactory;
        this.customFeedbackParser = new CustomFeedbackParser(artifactLinkManager, buildLoggerManager, plan);

        URI uri;
        try {
            uri = new URI(webhookUrl);
        }
        catch (URISyntaxException e) {
            logErrorToBuildLog("Unable to set up proxy settings, invalid URI encountered: " + e);
            log.error("Unable to set up proxy settings, invalid URI encountered: " + e);
            return;
        }

        HttpUtils.EndpointSpec proxyForScheme = HttpUtils.getProxyForScheme(uri.getScheme());
        if (proxyForScheme != null) {
            HttpHost proxy = new HttpHost(proxyForScheme.host, proxyForScheme.port);
            DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxy);
            this.client = HttpClients.custom().setRoutePlanner(routePlanner).build();
        }
        else {
            this.client = HttpClients.createDefault();
        }
    }

    public void sendNotification(@NotNull Notification notification) {
        logToBuildLog("Sending notification");
        try {
            HttpPost method = setupPostMethod();
            JSONObject jsonObject = createJSONObject(notification);
            try {
                String secret = (String) jsonObject.get("secret");
                method.addHeader("Authorization", secret);
            }
            catch (JSONException e) {
                logErrorToBuildLog("Error while getting secret from JSONObject: " + e.getMessage());
                log.error("Error while getting secret from JSONObject: " + e.getMessage(), e);
            }

            method.setEntity(new StringEntity(jsonObject.toString(), ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));

            try {
                logToBuildLog("Executing call to " + method.getURI().toString());
                log.debug(method.getURI().toString());
                log.debug(method.getEntity().toString());
                CloseableHttpResponse closeableHttpResponse = client.execute(method);

                logToBuildLog("Call executed");
                if (closeableHttpResponse != null) {
                    logToBuildLog("Response is not null: " + closeableHttpResponse.toString());

                    StatusLine statusLine = closeableHttpResponse.getStatusLine();
                    if (statusLine != null) {
                        logToBuildLog("StatusLine is not null: " + statusLine.toString());
                        logToBuildLog("StatusCode is: " + statusLine.getStatusCode());
                    }
                    else {
                        logErrorToBuildLog("Statusline is null");
                    }

                    HttpEntity httpEntity = closeableHttpResponse.getEntity();
                    if (httpEntity != null) {
                        String response = EntityUtils.toString(httpEntity);
                        logToBuildLog("Response from entity is: " + response);
                        EntityUtils.consume(httpEntity);
                    }
                    else {
                        logErrorToBuildLog("Httpentity is null");
                    }
                }
                else {
                    logErrorToBuildLog("Response is null");
                }
            }
            catch (Exception e) {
                logErrorToBuildLog("Error while sending payload: " + e.getMessage());
                log.error("Error while sending payload: " + e.getMessage(), e);
            }
        }
        catch (URISyntaxException e) {
            logErrorToBuildLog("Error parsing webhook url: " + e.getMessage());
            log.error("Error parsing webhook url: " + e.getMessage(), e);
        }
    }

    private HttpPost setupPostMethod() throws URISyntaxException {
        HttpPost post = new HttpPost((new URI(webhookUrl)));
        post.setHeader("Content-Type", "application/json");
        return post;
    }

    private JSONObject createJSONObject(Notification notification) {
        logToBuildLog("Creating JSON object");
        JSONObject jsonObject = new JSONObject();
        try {
            List<VariableDefinition> variableDefinitions = variableDefinitionManager.getGlobalVariables();
            if (!variableDefinitions.isEmpty()) {
                // Variable name contains "password" to ensure that the secret is hidden in the UI
                Optional<VariableDefinition> optionalVariableDefinition = variableDefinitions.stream().filter(vd -> vd.getKey().equals("SERVER_PLUGIN_SECRET_PASSWORD"))
                        .findFirst();
                if (optionalVariableDefinition.isPresent()) {
                    jsonObject.put("secret", optionalVariableDefinition.get().getValue()); // Used to verify that the request is coming from a legitimate server
                }
                else {
                    jsonObject.put("secret", "SERVER_PLUGIN_SECRET_PASSWORD-NOT-DEFINED");
                    logErrorToBuildLog("Variable SERVER_PLUGIN_SECRET_PASSWORD is not defined");
                }

            }
            else {
                jsonObject.put("secret", "NO-GLOBAL-VARIABLES-ARE-DEFINED");
                logErrorToBuildLog("No global variables are defined");
            }

            jsonObject.put("notificationType", notification.getDescription());

            if (plan != null) {
                JSONObject planDetails = new JSONObject();
                planDetails.put("key", plan.getPlanKey());

                jsonObject.put("plan", planDetails);
            }

            if (resultsSummary == null) {
                return jsonObject;
            }

            TestResultsSummary testResultsSummary = resultsSummary.getTestResultsSummary();

            // We update these variables so that they reflect custom feedback tests.
            int totalTestCount = testResultsSummary.getTotalTestCaseCount();
            int failedTestCount = testResultsSummary.getFailedTestCaseCount();
            int successfulTestCount = testResultsSummary.getSuccessfulTestCaseCount();

            JSONObject buildDetails = new JSONObject();

            JSONArray vcsDetails = new JSONArray();
            for (RepositoryChangeset changeset : resultsSummary.getRepositoryChangesets()) {
                JSONObject changesetDetails = new JSONObject();
                changesetDetails.put("id", changeset.getChangesetId());
                changesetDetails.put("repositoryName", changeset.getRepositoryData().getName());

                JSONArray commits = new JSONArray();
                for (Commit commit : changeset.getCommits()) {
                    JSONObject commitDetails = new JSONObject();
                    commitDetails.put("id", commit.getChangeSetId());
                    commitDetails.put("comment", commit.getComment());

                    commits.put(commitDetails);
                }

                changesetDetails.put("commits", commits);

                vcsDetails.put(changesetDetails);
            }
            buildDetails.put("vcs", vcsDetails);

            if (resultsSummary instanceof ChainResultsSummary) {
                ChainResultsSummary chainResultsSummary = (ChainResultsSummary) resultsSummary;
                JSONArray jobs = new JSONArray();
                for (ChainStageResult chainStageResult : chainResultsSummary.getStageResults()) {
                    for (BuildResultsSummary buildResultsSummary : chainStageResult.getBuildResults()) {
                        JSONObject jobDetails = new JSONObject();
                        jobDetails.put("id", buildResultsSummary.getId());

                        // Parse custom feedbacks that will be injected in the successful and failed tests
                        // Also update the total test count to reflect these ones.
                        Collection<TestResultsWithSuccessInfo> customFeedbacks = customFeedbackParser.extractCustomFeedbacks(buildResultsSummary.getProducedArtifactLinks(),
                                buildResultsSummary.getId());
                        totalTestCount += customFeedbacks.size();

                        logToBuildLog("Loading cached test results for job " + buildResultsSummary.getId());
                        ResultsContainer resultsContainer = ServerNotificationRecipient.getCachedTestResults().get(buildResultsSummary.getPlanResultKey().toString());
                        if (resultsContainer != null) {
                            logToBuildLog("Tests results found");

                            // Retrieve successful custom feedback tests and update the successful test count
                            Collection<TestResults> successfulTests = resultsContainer.getSuccessfulTests();
                            successfulTests.addAll(customFeedbacks.stream().filter(customFeedback -> !customFeedback.hasErrors()).collect(Collectors.toList()));
                            successfulTestCount = successfulTests.size();

                            JSONArray successfulTestDetails = createTestsResultsJSONArray(successfulTests, false);
                            jobDetails.put("successfulTests", successfulTestDetails);

                            JSONArray skippedTestDetails = createTestsResultsJSONArray(resultsContainer.getSkippedTests(), false);
                            jobDetails.put("skippedTests", skippedTestDetails);

                            // Retrieve failed custom feedback tests and update the failed test count
                            Collection<TestResults> failedTests = resultsContainer.getFailedTests();
                            failedTests.addAll(customFeedbacks.stream().filter(TestResults::hasErrors).collect(Collectors.toList()));
                            failedTestCount = failedTests.size();

                            JSONArray failedTestDetails = createTestsResultsJSONArray(failedTests, true);
                            jobDetails.put("failedTests", failedTestDetails);

                            JSONArray taskResults = createTasksJSONArray(resultsContainer.getTaskResults());
                            jobDetails.put("tasks", taskResults);
                        }
                        else {
                            logErrorToBuildLog("Could not load cached test results!");
                        }

                        logToBuildLog("Loading artifacts for job " + buildResultsSummary.getId());
                        JSONArray staticCodeAnalysisReports = createStaticCodeAnalysisReportArray(buildResultsSummary.getProducedArtifactLinks(), buildResultsSummary.getId());
                        jobDetails.put("staticCodeAnalysisReports", staticCodeAnalysisReports);

                        List<LogEntry> logEntries = Collections.emptyList();

                        // Only add log if no tests are found (indicates a build error)
                        if (totalTestCount == 0) {
                            // Loading logs for job
                            try {
                                final BuildLogFileAccessor fileAccessor = this.buildLogFileAccessorFactory.createBuildLogFileAccessor(buildResultsSummary.getPlanResultKey());
                                logEntries = fileAccessor.getLastNLogsOfType(JOB_LOG_MAX_LINES, logEntryTypes);
                                logToBuildLog("Found: " + logEntries.size() + " LogEntries");
                            }
                            catch (IOException ex) {
                                logErrorToBuildLog("Error while loading build log: " + ex.getMessage());
                            }
                        }
                        JSONArray logEntriesArray = new JSONArray();
                        for (LogEntry logEntry : logEntries) {
                            // A lambda here would require us to catch the JSONException inside the lambda, so we use a loop.
                            logEntriesArray.put(createLogEntryJSONObject(logEntry));
                        }
                        jobDetails.put("logs", logEntriesArray); // We add an empty array here in case tests are found to prevent errors while parsing in the client

                        jobs.put(jobDetails);
                    }
                }
                buildDetails.put("jobs", jobs);

                // TODO: This ensures outdated versions of Artemis can still process the new request. Will be removed without further notice in the future
                buildDetails.put("failedJobs", jobs);
            }
            jsonObject.put("build", buildDetails);

            buildDetails.put("number", resultsSummary.getBuildNumber());
            buildDetails.put("reason", resultsSummary.getShortReasonSummary());
            buildDetails.put("successful", resultsSummary.isSuccessful());
            buildDetails.put("buildCompletedDate", ZonedDateTime.ofInstant(resultsSummary.getBuildCompletedDate().toInstant(), ZoneId.systemDefault()));

            // ResultsSummary only contains shared artifacts. Job level artifacts are not available here
            buildDetails.put("artifact", !resultsSummary.getArtifactLinks().isEmpty());

            JSONObject testResultOverview = new JSONObject();
            testResultOverview.put("description", testResultsSummary.getTestSummaryDescription());
            testResultOverview.put("totalCount", totalTestCount);
            testResultOverview.put("failedCount", failedTestCount);
            testResultOverview.put("existingFailedCount", testResultsSummary.getExistingFailedTestCount());
            testResultOverview.put("fixedCount", testResultsSummary.getFixedTestCaseCount());
            testResultOverview.put("newFailedCount", testResultsSummary.getNewFailedTestCaseCount());
            testResultOverview.put("ignoredCount", testResultsSummary.getIgnoredTestCaseCount());
            testResultOverview.put("quarantineCount", testResultsSummary.getQuarantinedTestCaseCount());
            testResultOverview.put("skippedCount", testResultsSummary.getSkippedTestCaseCount());
            testResultOverview.put("successfulCount", successfulTestCount);
            testResultOverview.put("duration", testResultsSummary.getTotalTestDuration());

            buildDetails.put("testSummary", testResultOverview);

        }
        catch (JSONException e) {
            logErrorToBuildLog("JSON construction error :" + e.getMessage());
            log.error("JSON construction error :" + e.getMessage(), e);
        }

        logToBuildLog("JSON object created");
        return jsonObject;
    }

    private Optional<JSONObject> createStaticCodeAnalysisReportJSONObject(File rootFile, String label) {
        /*
         * The rootFile is a directory if the copy pattern matches multiple files, otherwise it is a regular file.
         * Ignore artifact definitions matching multiple files.
         */
        // TODO: Support artifact definitions matching multiple files
        if (rootFile == null || rootFile.isDirectory()) {
            return Optional.empty();
        }

        try {
            logToBuildLog("Creating artifact JSON object for artifact definition: " + label);
            // The report parser is able to identify the tool to which the report belongs
            ReportParser reportParser = new ReportParser();

            String reportJSON = reportParser.transformToJSONReport(rootFile);
            return Optional.of(new JSONObject(reportJSON));
        }
        catch (JSONException e) {
            log.error("Error constructing artifact JSON for artifact definition " + label, e);
            logErrorToBuildLog("Error constructing artifact JSON for artifact definition " + label + ": " + e.getMessage());
        }
        catch (ParserException e) {
            log.error("Error parsing static code analysis report " + label, e);
            logErrorToBuildLog("Error parsing static code analysis report " + label + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    private JSONArray createStaticCodeAnalysisReportArray(Collection<ArtifactLink> artifactLinks, long jobId) {
        JSONArray artifactsArray = new JSONArray();
        Collection<JSONObject> artifactJSONObjects = new ArrayList<>();
        // ArtifactLink refers to a single artifact definition configured on job level
        for (ArtifactLink artifactLink : artifactLinks) {
            MutableArtifact artifact = artifactLink.getArtifact();

            /*
             * The interface ArtifactLinkDataProvider generalizes access to the resulting artifact files.
             * Artifact handler configurations, which define how the results are stored, determine the concrete
             * implementation of the interface.
             */
            ArtifactLinkDataProvider dataProvider = artifactLinkManager.getArtifactLinkDataProvider(artifact);

            if (dataProvider == null) {
                log.debug("ArtifactLinkDataProvider is null for " + artifact.getLabel() + " in job " + jobId);
                logToBuildLog("Could not retrieve data for artifact " + artifact.getLabel() + " in job " + jobId);
                continue;
            }

            /*
             * FileSystemArtifactLinkDataProvider provides access to artifacts stored on the local server.
             * Has to be extended to support other artifact handling configurations.
             */
            if (dataProvider instanceof FileSystemArtifactLinkDataProvider) {
                FileSystemArtifactLinkDataProvider fileDataProvider = (FileSystemArtifactLinkDataProvider) dataProvider;
                File artifactFile = fileDataProvider.getFile();

                if (StaticCodeAnalysisUtils.isStaticCodeAnalysisArtifact(artifact.getLabel(), artifactFile.getName())) {
                    Optional<JSONObject> optionalReport = createStaticCodeAnalysisReportJSONObject(artifactFile, artifact.getLabel());
                    optionalReport.ifPresent(artifactJSONObjects::add);
                }
            }
            else {
                log.debug("Unsupported ArtifactLinkDataProvider " + dataProvider.getClass().getSimpleName()
                        + " encountered for label" + artifact.getLabel() + " in job " + jobId);
                logToBuildLog("Unsupported artifact handler configuration encountered for artifact "
                        + artifact.getLabel() + " in job " + jobId);
            }
        }
        artifactJSONObjects.forEach(artifactsArray::put);
        return artifactsArray;
    }

    private JSONObject createTestsResultsJSONObject(TestResults testResults, boolean addErrors) throws JSONException {
        logToBuildLog("Creating test results JSON object for " + testResults.getActualMethodName());
        JSONObject testResultsJSON = new JSONObject();
        testResultsJSON.put("name", testResults.getActualMethodName());
        testResultsJSON.put("methodName", testResults.getMethodName());
        testResultsJSON.put("className", testResults.getClassName());

        if (addErrors) {
            JSONArray testCaseErrorDetails = new JSONArray();
            for (TestCaseResultError testCaseResultError : testResults.getErrors()) {
                String errorMessageString = testCaseResultError.getContent();
                if (errorMessageString != null && errorMessageString.length() > FEEDBACK_DETAIL_TEXT_MAX_CHARACTERS) {
                    errorMessageString = errorMessageString.substring(0, FEEDBACK_DETAIL_TEXT_MAX_CHARACTERS);
                }
                testCaseErrorDetails.put(errorMessageString);
            }
            testResultsJSON.put("errors", testCaseErrorDetails);
        }

        if (testResults instanceof TestResultsWithSuccessInfo) {
            TestResultsWithSuccessInfo testResultsWithSuccessInfo = (TestResultsWithSuccessInfo) testResults;
            if (testResultsWithSuccessInfo.hasSuccessMessage()) {
                JSONObject messageJson = new JSONObject().put("message", testResultsWithSuccessInfo.getSuccessMessage());
                testResultsJSON.put("successInfos", messageJson);
            }
        }

        return testResultsJSON;
    }

    private JSONArray createTestsResultsJSONArray(Collection<TestResults> testResultsCollection, boolean addErrors) throws JSONException {
        logToBuildLog("Creating test results JSON array");
        JSONArray testResultsArray = new JSONArray();
        for (TestResults testResults : testResultsCollection) {
            testResultsArray.put(createTestsResultsJSONObject(testResults, addErrors));
        }

        return testResultsArray;
    }

    /**
     * Creates an JSONArray containing task name, plugin key, whether the task is final or enabled and the
     * state (SUCCESS, FAILED, ERROR) for each defined task.
     *
     * @param taskResults Collection of all defined tasks with details
     * @return JSONArray containing the name and state
     * @throws JSONException if JSONObject can't be created
     */
    private JSONArray createTasksJSONArray(Collection<TaskResult> taskResults) throws JSONException {
        logToBuildLog("Creating tasks JSON array");
        JSONArray tasksArray = new JSONArray();
        for (TaskResult taskResult : taskResults) {
            JSONObject taskJSON = new JSONObject();
            taskJSON.put("description", taskResult.getTaskIdentifier().getUserDescription());
            taskJSON.put("pluginKey", taskResult.getTaskIdentifier().getPluginKey());
            taskJSON.put("isEnabled", taskResult.getTaskIdentifier().isEnabled());
            taskJSON.put("isFinal", taskResult.getTaskIdentifier().isFinalising());
            taskJSON.put("state", taskResult.getTaskState().name());
            tasksArray.put(taskJSON);
        }
        return tasksArray;
    }

    private JSONObject createLogEntryJSONObject(LogEntry logEntry) throws JSONException {
        JSONObject logEntryObject = new JSONObject();
        logEntryObject.put("log", logEntry.getLog());
        logEntryObject.put("date", ZonedDateTime.ofInstant(logEntry.getDate().toInstant(), ZoneId.systemDefault()));

        return logEntryObject;
    }

    private void logToBuildLog(String s) {
        if (buildLoggerManager != null && plan != null) {
            BuildLogger buildLogger = buildLoggerManager.getLogger(plan.getPlanKey());
            buildLogger.addBuildLogEntry("[BAMBOO-SERVER-NOTIFICATION] " + s);
        }
    }

    private void logErrorToBuildLog(String s) {
        if (buildLoggerManager != null && plan != null) {
            BuildLogger buildLogger = buildLoggerManager.getLogger(plan.getPlanKey());
            buildLogger.addErrorLogEntry("[BAMBOO-SERVER-NOTIFICATION] " + s);
        }
    }
}
