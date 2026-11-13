package me.niteshh.nexoraflow.expense;

import java.math.BigDecimal;
import java.util.List;

import me.niteshh.nexoraflow.common.ReasonCode;

/** Per-line outcome of an evaluation: status, applicable policy and the reasons behind it. */
public record LineResult(int lineNumber, LineStatus status, List<ReasonCode> reasons, String policyId,
                         BigDecimal amountReporting, String message) {
}
