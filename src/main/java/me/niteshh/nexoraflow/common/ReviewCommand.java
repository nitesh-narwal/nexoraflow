package me.niteshh.nexoraflow.common;

public record ReviewCommand(String actorEmployeeId, ReviewAction action, String comment) {
}
