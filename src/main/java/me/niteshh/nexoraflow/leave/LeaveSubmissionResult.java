package me.niteshh.nexoraflow.leave;

import java.util.List;

import me.niteshh.nexoraflow.intake.IntakeStatus;

/** Outcome of intake. {@code request} is null when the submission was rejected at intake (it stays in staging). */
public record LeaveSubmissionResult(String submissionId, IntakeStatus intakeStatus, LeaveRequest request, List<String> errors) {

    public boolean accepted() {
        return intakeStatus != IntakeStatus.REJECTED_AT_INTAKE;
    }
}
