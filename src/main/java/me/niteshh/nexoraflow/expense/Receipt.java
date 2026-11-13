package me.niteshh.nexoraflow.expense;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.PersistableEntity;

/** Receipt metadata (no document content is stored - the dataset is synthetic). */
@Entity
@Table(name = "receipt")
@Getter
@Setter
@NoArgsConstructor
public class Receipt extends PersistableEntity {

    @Id
    @Column(name = "receipt_id")
    private String receiptId;

    @Column(name = "employee_id")
    private String employeeId;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_type")
    private String fileType;

    @Column(name = "file_size_bytes")
    private long fileSizeBytes;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @Column(name = "receipt_hash")
    private String receiptHash;

    @Column(name = "receipt_status")
    private String receiptStatus = "VALID";

    @Column(name = "source_reference")
    private String sourceReference;

    @Override
    public String getId() {
        return receiptId;
    }
}
