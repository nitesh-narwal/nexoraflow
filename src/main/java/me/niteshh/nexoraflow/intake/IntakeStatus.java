package me.niteshh.nexoraflow.intake;

public enum IntakeStatus {
    ACCEPTED,
    /** Accepted, but one or more submitted references were invalid and were preserved, not linked. */
    ACCEPTED_WITH_ERRORS,
    /** The submission could not become a canonical record (e.g. unknown employee). It is preserved here. */
    REJECTED_AT_INTAKE
}
