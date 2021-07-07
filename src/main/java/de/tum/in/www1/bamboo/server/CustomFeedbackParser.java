package de.tum.in.www1.bamboo.server;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import model.CustomFeedback;
import model.TestResultsWithSuccessInfo;

import org.apache.log4j.Logger;

import com.atlassian.bamboo.artifact.MutableArtifact;
import com.atlassian.bamboo.build.BuildLoggerManager;
import com.atlassian.bamboo.build.artifact.ArtifactLink;
import com.atlassian.bamboo.build.artifact.ArtifactLinkDataProvider;
import com.atlassian.bamboo.build.artifact.ArtifactLinkManager;
import com.atlassian.bamboo.build.artifact.FileSystemArtifactLinkDataProvider;
import com.atlassian.bamboo.build.logger.BuildLogger;
import com.atlassian.bamboo.plan.cache.ImmutablePlan;
import com.atlassian.bamboo.resultsummary.tests.TestCaseResultError;
import com.atlassian.bamboo.resultsummary.tests.TestCaseResultErrorImpl;
import com.fasterxml.jackson.databind.ObjectMapper;

public class CustomFeedbackParser {

    private static final Logger log = Logger.getLogger(ServerNotificationTransport.class);

    // The label of the artifact defined in the Bamboo build plan
    private final String CUSTOM_FEEDBACK_ARTIFACT_LABEL = "customFeedbacks";

    private final ArtifactLinkManager artifactLinkManager;

    private final BuildLoggerManager buildLoggerManager;

    private final ImmutablePlan plan;

    public CustomFeedbackParser(ArtifactLinkManager artifactLinkManager, BuildLoggerManager buildLoggerManager, ImmutablePlan plan) {
        this.artifactLinkManager = artifactLinkManager;
        this.buildLoggerManager = buildLoggerManager;
        this.plan = plan;
    }

    /**
     * Parses custom feedback from the Bamboo artifact.
     *
     * @param artifactLinks the ArtifactLinks of the Bamboo build
     * @param jobId the job id
     */
    public Collection<TestResultsWithSuccessInfo> extractCustomFeedbacks(Collection<ArtifactLink> artifactLinks, long jobId) {
        // Get the data provider for custom feedbacks so that we can parse the .json files
        Optional<FileSystemArtifactLinkDataProvider> dataProvider = getCustomFeedbacksDataProvider(artifactLinks, jobId);
        if (!dataProvider.isPresent()) {
            return new ArrayList<>();
        }

        return parseCustomFeedbacks(dataProvider.get().getFile());
    }

    private Optional<FileSystemArtifactLinkDataProvider> getCustomFeedbacksDataProvider(Collection<ArtifactLink> artifactLinks, long jobId) {
        // Custom feedbacks are located in an artifact labeled by CUSTOM_FEEDBACK_ARTIFACT_LABEL
        Optional<ArtifactLink> customFeedbackArtifactLink = artifactLinks.stream()
                .filter(artifactLink -> artifactLink.getArtifact().getLabel().equals(CUSTOM_FEEDBACK_ARTIFACT_LABEL)).findFirst();
        if (!customFeedbackArtifactLink.isPresent()) {
            return Optional.empty();
        }

        MutableArtifact artifact = customFeedbackArtifactLink.get().getArtifact();

        ArtifactLinkDataProvider dataProvider = artifactLinkManager.getArtifactLinkDataProvider(artifact);
        if (dataProvider == null) {
            logToDebugAndBuildLog("Could not retrieve data for artifact " + artifact.getLabel() + " in job " + jobId);
            return Optional.empty();
        }

        if (dataProvider instanceof FileSystemArtifactLinkDataProvider) {
            FileSystemArtifactLinkDataProvider fileDataProvider = (FileSystemArtifactLinkDataProvider) dataProvider;
            return Optional.of(fileDataProvider);
        }
        else {
            logToDebugAndBuildLog("Unsupported artifact handler configuration encountered for artifact " + artifact.getLabel() + " in job " + jobId);
            return Optional.empty();
        }
    }

    /**
     * Parses custom feedback json files into CustomFeedback objects if they are present
     * within the directory.
     *
     * @param customFeedbacksDir directory that has the custom feedback json files
     * @return a collection of CustomFeedbacks
     */
    private Collection<TestResultsWithSuccessInfo> parseCustomFeedbacks(File customFeedbacksDir) {
        if (customFeedbacksDir == null || !customFeedbacksDir.isDirectory()) {
            return new ArrayList<>();
        }

        File[] files = customFeedbacksDir.listFiles();
        if (files == null) {
            return new ArrayList<>();
        }

        Collection<CustomFeedback> customFeedbacks = Arrays.stream(files)
                .filter(file -> file.getName().endsWith(".json"))
                .map(this::parseCustomFeedback)
                .filter(Optional::isPresent)
                .map(Optional::get).collect(Collectors.toList());

        return toTestResults(customFeedbacks);
    }

    /**
     * Parses a custom feedback file into an object.
     *
     * @param customFeedbackFile file containing the custom feedback json
     * @return the CustomFeedback
     */
    private Optional<CustomFeedback> parseCustomFeedback(File customFeedbackFile) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            CustomFeedback feedback = mapper.readValue(customFeedbackFile, CustomFeedback.class);
            if (feedback.getMessage() != null && feedback.getMessage().trim().isEmpty()) {
                feedback.setMessage(null);
            }
            validateCustomFeedback(customFeedbackFile.getName(), feedback);
            return Optional.of(feedback);
        }
        catch (IOException e) {
            logToDebugAndBuildLog("Cannot parse custom feedback for file " + customFeedbackFile.getName() + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Checks that the custom feedback has a valid format
     * <p>
     * A custom feedback has to have a non-empty, non only-whitespace name to be able to identify it in Artemis.
     * If it is not successful, there has to be a message explaining a reason why this is the case.
     *
     * @param fileName where the custom feedback was read from.
     * @param feedback the custom feedback to validate.
     * @throws InvalidPropertiesFormatException if one of the invariants described above does not hold.
     */
    private static void validateCustomFeedback(final String fileName, final CustomFeedback feedback) throws InvalidPropertiesFormatException {
        if (feedback.getName() == null || feedback.getName().trim().isEmpty()) {
            throw new InvalidPropertiesFormatException(String.format("Custom feedback from file %s needs to have a name attribute.", fileName));
        }
        if (!feedback.isSuccessful() && feedback.getMessage() == null) {
            throw new InvalidPropertiesFormatException(String.format("Custom non-success feedback from file %s needs to have a message", fileName));
        }
    }

    /**
     * Converts the custom feedback objects into Bamboo TestResults objects.
     *
     * @param customFeedbacks the custom feedbacks
     * @return the TestResults objects
     */
    private Collection<TestResultsWithSuccessInfo> toTestResults(Collection<CustomFeedback> customFeedbacks) {
        return customFeedbacks.stream().map(customFeedback -> {
            String name = customFeedback.getName();
            TestResultsWithSuccessInfo testResults = new TestResultsWithSuccessInfo(name, name);

            if (customFeedback.isSuccessful()) {
                testResults.setSuccessMessage(customFeedback.getMessage());
            }
            else {
                TestCaseResultError error = new TestCaseResultErrorImpl(customFeedback.getMessage());
                testResults.addError(error);
            }
            return testResults;
        }).collect(Collectors.toList());
    }

    private void logToDebugAndBuildLog(String message) {
        log.debug(message);
        if (buildLoggerManager != null && plan != null) {
            BuildLogger buildLogger = buildLoggerManager.getLogger(plan.getPlanKey());
            buildLogger.addBuildLogEntry("[BAMBOO-SERVER-NOTIFICATION] " + message);
        }
    }
}
