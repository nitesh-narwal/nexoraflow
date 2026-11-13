package me.niteshh.nexoraflow.export;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Streams table rows in a canonical, deterministic form: columns in ordinal order (generated columns removed),
 * rows ordered by the table's key, values normalised (UTC ISO timestamps, plain decimals, ISO dates).
 */
@Component
public class TableReader {

    public record Column(String name, String sqlType) {
        public boolean isJson() {
            return sqlType.equals("jsonb") || sqlType.equals("json");
        }
    }

    private final JdbcTemplate jdbc;

    public TableReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Writable columns (generated columns are derived by the database and excluded). */
    public List<Column> columns(String qualifiedTable) {
        String schema = qualifiedTable.contains(".") ? qualifiedTable.split("\\.")[0] : "public";
        String table = qualifiedTable.contains(".") ? qualifiedTable.split("\\.")[1] : qualifiedTable;
        return jdbc.query("""
                SELECT column_name, data_type FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ? AND is_generated = 'NEVER'
                ORDER BY ordinal_position
                """, (rs, i) -> new Column(rs.getString(1), rs.getString(2)), schema, table);
    }

    public long count(String qualifiedTable) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + qualifiedTable, Long.class);
        return n == null ? 0 : n;
    }

    /** Calls the consumer once per row with normalised values (null stays null). */
    public void stream(String qualifiedTable, String orderBy, List<Column> columns, Consumer<List<Object>> consumer) {
        String select = String.join(", ", columns.stream().map(c -> c.isJson() ? c.name() + "::text AS " + c.name() : c.name()).toList());
        jdbc.query("SELECT " + select + " FROM " + qualifiedTable + " ORDER BY " + orderBy, (ResultSet rs) -> {
            List<Object> row = new ArrayList<>(columns.size());
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= columns.size(); i++) {
                row.add(normalise(rs, i, md.getColumnTypeName(i)));
            }
            consumer.accept(row);
        });
    }

    private static Object normalise(ResultSet rs, int i, String typeName) throws SQLException {
        return switch (typeName) {
            case "timestamptz" -> {
                OffsetDateTime odt = rs.getObject(i, OffsetDateTime.class);
                yield odt == null ? null : odt.toInstant().toString();
            }
            case "timestamp" -> {
                var ts = rs.getTimestamp(i);
                yield ts == null ? null : ts.toInstant().toString();
            }
            case "date" -> {
                var d = rs.getDate(i);
                yield d == null ? null : d.toLocalDate().toString();
            }
            case "numeric" -> {
                BigDecimal n = rs.getBigDecimal(i);
                yield n == null ? null : n.toPlainString();
            }
            case "bool" -> rs.getObject(i) == null ? null : String.valueOf(rs.getBoolean(i));
            case "int4", "int8", "int2" -> rs.getObject(i) == null ? null : String.valueOf(rs.getLong(i));
            default -> rs.getString(i);
        };
    }
}
