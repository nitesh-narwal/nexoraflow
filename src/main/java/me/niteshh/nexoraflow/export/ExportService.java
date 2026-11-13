package me.niteshh.nexoraflow.export;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import me.niteshh.nexoraflow.configuration.JsonSupport;
import me.niteshh.nexoraflow.configuration.NexoraProperties;
import me.niteshh.nexoraflow.generator.GeneratorSettings;
import tools.jackson.databind.JsonNode;

/**
 * Exports the generated dataset: one CSV and one JSON file per entity (relationships preserved through business
 * IDs), a self-contained SQL seed, the ground-truth files, and the manifests (generation manifest with seed,
 * configuration, versions and row counts; per-file checksums).
 */
@Service
public class ExportService {

    public record ExportResult(Path directory, Map<String, Long> rowCounts, String overallChecksum, int files) {
    }

    private static final int SQL_BATCH = 200;
    private static final String GT_TABLE = "ground_truth.label";
    private static final String GT_ORDER = "entity_type, entity_id, scenario_id";

    private final TableReader reader;
    private final LogicalChecksumService checksums;
    private final JsonSupport json;
    private final NexoraProperties properties;
    private final ObjectProvider<BuildProperties> buildProperties;

    public ExportService(TableReader reader, LogicalChecksumService checksums, JsonSupport json, NexoraProperties properties,
                         ObjectProvider<BuildProperties> buildProperties) {
        this.reader = reader;
        this.checksums = checksums;
        this.json = json;
        this.properties = properties;
        this.buildProperties = buildProperties;
    }

    public Path baseDirectory() {
        return Path.of(properties.generator().outputDir());
    }

    public String applicationVersion() {
        BuildProperties bp = buildProperties.getIfAvailable();
        return bp == null ? "1.0.0" : bp.getVersion();
    }

    public ExportResult exportAll(GeneratorSettings settings) {
        Path base = baseDirectory();
        Path generated = base.resolve("generated");
        Path sqlDir = generated.resolve("sql");
        Path truthDir = base.resolve("ground_truth");
        Path manifests = base.resolve("manifests");
        try {
            for (Path p : List.of(generated, sqlDir, truthDir, manifests, base.resolve("reports"))) {
                Files.createDirectories(p);
            }
            Map<String, Long> counts = new LinkedHashMap<>();
            List<Path> files = new ArrayList<>();
            for (TableCatalog.ExportTable t : TableCatalog.TABLES) {
                List<TableReader.Column> columns = reader.columns(t.table());
                Path csv = generated.resolve(t.fileName() + ".csv");
                Path jsonFile = generated.resolve(t.fileName() + ".json");
                counts.put(t.table(), writeCsvAndJson(t.table(), t.orderBy(), columns, csv, jsonFile));
                files.add(csv);
                files.add(jsonFile);
            }
            List<TableReader.Column> gtColumns = reader.columns(GT_TABLE);
            Path gtCsv = truthDir.resolve("ground_truth.csv");
            Path gtJson = truthDir.resolve("ground_truth.json");
            counts.put(GT_TABLE, writeCsvAndJson(GT_TABLE, GT_ORDER, gtColumns, gtCsv, gtJson));
            files.add(gtCsv);
            files.add(gtJson);

            Path sql = sqlDir.resolve("nexoraflow_seed.sql");
            writeSql(sql, gtColumns);
            files.add(sql);

            var logical = checksums.compute();
            String overall = checksums.overall(logical);
            writeManifest(manifests.resolve("generation_manifest.json"), settings, counts, logical, overall);
            writeChecksums(manifests.resolve("checksums.json"), base, files, overall);
            return new ExportResult(base, counts, overall, files.size() + 2);
        } catch (IOException e) {
            throw new UncheckedIOException("Export failed", e);
        }
    }

    // ------------------------------------------------------------------ CSV + JSON

    private long writeCsvAndJson(String table, String orderBy, List<TableReader.Column> columns, Path csvPath, Path jsonPath) throws IOException {
        long[] rows = {0};
        try (BufferedWriter csv = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8);
             BufferedWriter js = Files.newBufferedWriter(jsonPath, StandardCharsets.UTF_8)) {
            csv.write(String.join(",", columns.stream().map(TableReader.Column::name).toList()));
            csv.write("\n");
            js.write("[");
            reader.stream(table, orderBy, columns, row -> {
                try {
                    csv.write(csvLine(row));
                    csv.write("\n");
                    js.write(rows[0] == 0 ? "\n  " : ",\n  ");
                    js.write(json.toJson(jsonRow(columns, row)));
                    rows[0]++;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            js.write(rows[0] == 0 ? "]\n" : "\n]\n");
        }
        return rows[0];
    }

    private static String csvLine(List<Object> row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Object v = row.get(i);
            if (v != null) {
                String s = v.toString();
                boolean quote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
                sb.append(quote ? "\"" + s.replace("\"", "\"\"") + "\"" : s);
            }
        }
        return sb.toString();
    }

    private Map<String, Object> jsonRow(List<TableReader.Column> columns, List<Object> row) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            map.put(columns.get(i).name(), typed(columns.get(i), row.get(i)));
        }
        return map;
    }

    private Object typed(TableReader.Column c, Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString();
        return switch (c.sqlType()) {
            case "numeric" -> new BigDecimal(s);
            case "integer", "bigint", "smallint" -> Long.parseLong(s);
            case "boolean" -> Boolean.parseBoolean(s);
            case "jsonb", "json" -> {
                JsonNode node = json.mapper().readTree(s);
                yield node;
            }
            default -> s;
        };
    }

    // ------------------------------------------------------------------ SQL seed

    private void writeSql(Path path, List<TableReader.Column> gtColumns) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("-- NexoraFlow synthetic dataset - SQL seed (data only; schema comes from the Flyway migrations)\n");
            w.write("-- Fictional company: Nexora Technologies. No real personal data. Generated deterministically.\n");
            w.write("-- Load into an empty, migrated database:  psql -v ON_ERROR_STOP=1 -f nexoraflow_seed.sql\n");
            w.write("-- Circular references (department head, employee cost center) are filled by UPDATE statements at the end.\n\n");
            w.write("BEGIN;\n\n");
            List<String> updates = new ArrayList<>();
            for (TableCatalog.ExportTable t : TableCatalog.TABLES) {
                writeInserts(w, t.table(), t.orderBy(), reader.columns(t.table()), t, updates);
            }
            writeInserts(w, GT_TABLE, GT_ORDER, gtColumns, null, updates);
            w.write("-- resolve circular foreign keys\n");
            for (String u : updates) {
                w.write(u);
                w.write("\n");
            }
            w.write("\nCOMMIT;\n");
        }
    }

    private void writeInserts(BufferedWriter w, String table, String orderBy, List<TableReader.Column> columns,
                              TableCatalog.ExportTable meta, List<String> updates) throws IOException {
        List<String> deferred = meta == null ? List.of() : meta.deferredColumns();
        String columnList = String.join(", ", columns.stream().map(TableReader.Column::name).toList());
        String pk = meta == null ? null : meta.primaryKeyColumn();
        int pkIndex = pk == null ? -1 : indexOf(columns, pk);
        List<String> batch = new ArrayList<>();
        reader.stream(table, orderBy, columns, row -> {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                boolean isDeferred = deferred.contains(columns.get(i).name());
                if (isDeferred && row.get(i) != null) {
                    updates.add("UPDATE " + table + " SET " + columns.get(i).name() + " = " + literal(columns.get(i), row.get(i))
                            + " WHERE " + pk + " = " + literal(columns.get(pkIndex), row.get(pkIndex)) + ";");
                }
                sb.append(isDeferred ? "NULL" : literal(columns.get(i), row.get(i)));
            }
            sb.append(")");
            batch.add(sb.toString());
            if (batch.size() >= SQL_BATCH) {
                flushBatch(w, table, columnList, batch);
            }
        });
        flushBatch(w, table, columnList, batch);
    }

    private static void flushBatch(BufferedWriter w, String table, String columnList, List<String> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            w.write("INSERT INTO " + table + " (" + columnList + ") VALUES\n  ");
            w.write(String.join(",\n  ", batch));
            w.write(";\n\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        batch.clear();
    }

    private static int indexOf(List<TableReader.Column> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    static String literal(TableReader.Column c, Object v) {
        if (v == null) {
            return "NULL";
        }
        String s = v.toString();
        return switch (c.sqlType()) {
            case "numeric", "integer", "bigint", "smallint" -> s;
            case "boolean" -> s;
            case "jsonb", "json" -> "'" + s.replace("'", "''") + "'::" + c.sqlType();
            case "timestamp with time zone" -> "'" + s + "'::timestamptz";
            case "date" -> "'" + s + "'::date";
            default -> "'" + s.replace("'", "''") + "'";
        };
    }

    // ------------------------------------------------------------------ manifests

    private void writeManifest(Path path, GeneratorSettings settings, Map<String, Long> counts,
                               Map<String, LogicalChecksumService.TableChecksum> logical, String overall) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("name", "NexoraFlow synthetic dataset");
        dataset.put("company", "Nexora Technologies (fictional)");
        dataset.put("datasetVersion", applicationVersion() + "+seed" + settings.seed() + "." + settings.profile().name().toLowerCase());
        dataset.put("containsRealPersonalData", false);
        manifest.put("dataset", dataset);

        Map<String, Object> gen = new LinkedHashMap<>();
        gen.put("seed", settings.seed());
        gen.put("sizeProfile", settings.profile().name());
        gen.put("targetEmployees", settings.profile().employees());
        gen.put("targetLeaveRequests", settings.profile().leaveRequests());
        gen.put("targetExpenseClaims", settings.profile().expenseClaims());
        gen.put("referenceDate", settings.referenceDate().toString());
        gen.put("applicationVersion", applicationVersion());
        gen.put("businessRulesVersion", properties.businessRulesVersion());
        gen.put("fakerLibrary", "net.datafaker:datafaker");
        gen.put("javaVersion", Runtime.version().feature());
        manifest.put("generation", gen);

        Map<String, Object> nondeterministic = new LinkedHashMap<>();
        nondeterministic.put("generatedAtWallClock", Instant.now().toString());
        nondeterministic.put("note", "Wall-clock metadata only. It is excluded from every checksum; all dataset timestamps come from the seeded simulation clock.");
        manifest.put("operationalMetadata", nondeterministic);

        manifest.put("rowCounts", counts);
        Map<String, Object> logicalOut = new LinkedHashMap<>();
        logical.forEach((k, v) -> logicalOut.put(k, Map.of("rows", v.rows(), "sha256", v.sha256())));
        manifest.put("logicalChecksums", logicalOut);
        manifest.put("overallLogicalChecksum", overall);
        Files.writeString(path, json.toPrettyJson(manifest) + "\n", StandardCharsets.UTF_8);
    }

    private void writeChecksums(Path path, Path base, List<Path> files, String overall) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("algorithm", "SHA-256");
        Map<String, Object> entries = new LinkedHashMap<>();
        for (Path f : files) {
            entries.put(base.relativize(f).toString().replace('\\', '/'),
                    Map.of("sha256", fileSha256(f), "bytes", Files.size(f)));
        }
        out.put("files", entries);
        out.put("overallLogicalChecksum", overall);
        Files.writeString(path, json.toPrettyJson(out) + "\n", StandardCharsets.UTF_8);
    }

    private static String fileSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
