package me.niteshh.nexoraflow.expense;

import java.util.List;

import me.niteshh.nexoraflow.intake.IntakeStatus;

/** Outcome of expense intake. {@code claim} is null when the submission was rejected at intake. */
public record ExpenseSubmissionResult(String submissionId, IntakeStatus intakeStatus, ExpenseClaim claim,
                                      List<String> errors, List<String> warnings) {

    public boolean accepted() {
        return intakeStatus != IntakeStatus.REJECTED_AT_INTAKE;
    }
}
