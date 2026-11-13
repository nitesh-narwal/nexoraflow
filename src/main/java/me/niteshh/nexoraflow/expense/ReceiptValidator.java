package me.niteshh.nexoraflow.expense;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import me.niteshh.nexoraflow.configuration.NexoraProperties;

/** Validates receipt METADATA (type, size, status). No document content or OCR is involved. */
@Component
public class ReceiptValidator {

    public record Result(boolean valid, String reason) {
        static Result ok() {
            return new Result(true, null);
        }

        static Result invalid(String reason) {
            return new Result(false, reason);
        }
    }

    private final Set<String> supportedTypes;
    private final long maxSizeBytes;

    @Autowired
    public ReceiptValidator(NexoraProperties properties) {
        this(properties.expense().supportedReceiptTypes(), properties.expense().maxReceiptSizeBytes());
    }

    public ReceiptValidator(String supportedTypesCsv, long maxSizeBytes) {
        this.supportedTypes = Arrays.stream(supportedTypesCsv.split(","))
                .map(t -> t.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        this.maxSizeBytes = maxSizeBytes;
    }

    public boolean isSupportedType(String fileType) {
        return fileType != null && supportedTypes.contains(fileType.trim().toUpperCase(Locale.ROOT));
    }

    public Result validate(Receipt receipt) {
        if (!isSupportedType(receipt.getFileType())) {
            return Result.invalid("Unsupported receipt file type '" + receipt.getFileType() + "'");
        }
        if (receipt.getFileSizeBytes() <= 0 || receipt.getFileSizeBytes() > maxSizeBytes) {
            return Result.invalid("Receipt size " + receipt.getFileSizeBytes() + " bytes is outside the allowed range");
        }
        if (!"VALID".equals(receipt.getReceiptStatus())) {
            return Result.invalid("Receipt status is " + receipt.getReceiptStatus());
        }
        return Result.ok();
    }
}
