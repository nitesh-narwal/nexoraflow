package me.niteshh.nexoraflow.export;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * Logical dataset checksums: SHA-256 over the canonical rows of every table (key order, normalised values),
 * independent of CSV/JSON formatting. Two generations with the same seed, configuration, application version and
 * rule version must yield identical row counts and checksums. Wall-clock operational tables are excluded.
 */
@Service
public class LogicalChecksumService {

    public record TableChecksum(String table, long rows, String sha256) {
    }

    private final TableReader reader;

    public LogicalChecksumService(TableReader reader) {
        this.reader = reader;
    }

    public Map<String, TableChecksum> compute() {
        Map<String, TableChecksum> result = new LinkedHashMap<>();
        for (TableCatalog.ExportTable t : TableCatalog.TABLES) {
            result.put(t.table(), checksum(t.table(), t.orderBy()));
        }
        result.put("ground_truth.label", checksum("ground_truth.label", "scenario_id, entity_type, entity_id"));
        return result;
    }

    /** One checksum over all tables: the single value to compare between runs. */
    public String overall(Map<String, TableChecksum> perTable) {
        MessageDigest digest = sha256();
        perTable.forEach((name, c) -> digest.update((name + ":" + c.rows() + ":" + c.sha256() + "\n").getBytes(StandardCharsets.UTF_8)));
        return HexFormat.of().formatHex(digest.digest());
    }

    private TableChecksum checksum(String table, String orderBy) {
        List<TableReader.Column> columns = reader.columns(table);
        MessageDigest digest = sha256();
        long[] rows = {0};
        reader.stream(table, orderBy, columns, row -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < row.size(); i++) {
                sb.append(columns.get(i).name()).append('=').append(row.get(i) == null ? "\u0000NULL" : row.get(i)).append('\u001f');
            }
            sb.append('\n');
            digest.update(sb.toString().getBytes(StandardCharsets.UTF_8));
            rows[0]++;
        });
        return new TableChecksum(table, rows[0], HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
