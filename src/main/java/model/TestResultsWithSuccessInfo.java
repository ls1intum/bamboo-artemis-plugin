package model;

import com.atlassian.bamboo.results.tests.TestResults;

public class TestResultsWithSuccessInfo extends TestResults {

    private String successMessage;

    public TestResultsWithSuccessInfo(String className, String methodName) {
        super(className, methodName, 0L);
    }

    public String getSuccessMessage() {
        return successMessage;
    }

    public void setSuccessMessage(String successMessage) {
        this.successMessage = successMessage;
    }

    public boolean hasSuccessMessage() {
        return successMessage != null && !this.successMessage.trim().isEmpty();
    }
}
