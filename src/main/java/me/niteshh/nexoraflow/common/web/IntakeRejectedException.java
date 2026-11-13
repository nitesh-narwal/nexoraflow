package me.niteshh.nexoraflow.common.web;

import java.util.List;

/** Thrown by controllers AFTER intake committed: the submission was rejected but is preserved in staging. */
public class IntakeRejectedException extends RuntimeException {

    private final String submissionId;
    private final List<String> errors;

    public IntakeRejectedException(String submissionId, List<String> errors) {
        super("Submission " + submissionId + " was rejected at intake: " + String.join("; ", errors));
        this.submissionId = submissionId;
        this.errors = errors;
    }

    public String submissionId() {
        return submissionId;
    }

    public List<String> errors() {
        return errors;
    }
}
