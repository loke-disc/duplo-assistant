package assistant.service;

import assistant.model.TableInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SchemaService {

    private final DataSource dataSource;
    private String cachedSchema; // Cache the schema for performance
    private List<TableInfo> cachedTableInfos = null;
    private final ObjectMapper mapper;
    private Resource schemaDescription;

    public SchemaService(DataSource dataSource,
                         @Value("classpath:schema/schema_descriptions.json") Resource schemaDescription) {
        this.dataSource = dataSource;
        this.mapper = new ObjectMapper();
        this.schemaDescription = schemaDescription;
    }

    public String getDatabaseSchemaAsPrompt() {
        if (cachedSchema == null) {
            cachedSchema = fetchAndFormatSchema();
        }
        return cachedSchema;
    }

//    private String fetchAndFormatSchema() {
//        StringBuilder schemaBuilder = new StringBuilder();
//        try (Connection connection = dataSource.getConnection()) {
//            DatabaseMetaData metaData = connection.getMetaData();
//
//            // Get Tables
//            ResultSet tables = metaData.getTables(null, null, "%", new String[]{"TABLE"});
//            List<String> tableNames = new ArrayList<>();
//            while (tables.next()) {
//                tableNames.add(tables.getString("TABLE_NAME"));
//            }
//            tables.close();
//
//            for (String tableName : tableNames) {
//                schemaBuilder.append("-- Table: ").append(tableName).append("\n");
//                schemaBuilder.append("CREATE TABLE ").append(tableName).append(" (\n");
//
//                // Get Columns
//                ResultSet columns = metaData.getColumns(null, null, tableName, "%");
//                while (columns.next()) {
//                    String columnName = columns.getString("COLUMN_NAME");
//                    String dataType = columns.getString("TYPE_NAME");
//                    int columnSize = columns.getInt("COLUMN_SIZE");
//                    String isNullable = columns.getString("IS_NULLABLE").equals("YES") ? "" : " NOT NULL";
//
//                    schemaBuilder.append("    ").append(columnName).append(" ").append(dataType);
//                    if (dataType.equalsIgnoreCase("varchar") || dataType.equalsIgnoreCase("character varying")) {
//                        schemaBuilder.append("(").append(columnSize).append(")");
//                    }
//                    schemaBuilder.append(isNullable).append(",\n");
//                }
//                columns.close();
//
//                // Get Primary Keys
//                ResultSet primaryKeys = metaData.getPrimaryKeys(null, null, tableName);
//                while (primaryKeys.next()) {
//                    String pkColumnName = primaryKeys.getString("COLUMN_NAME");
//                    schemaBuilder.append("    PRIMARY KEY (").append(pkColumnName).append("),\n");
//                    break; // Assuming single column PK for simplicity in this example
//                }
//                primaryKeys.close();
//
//                // Get Foreign Keys
//                ResultSet foreignKeys = metaData.getImportedKeys(null, null, tableName);
//                while (foreignKeys.next()) {
//                    String fkColumnName = foreignKeys.getString("FKCOLUMN_NAME");
//                    String pkTableName = foreignKeys.getString("PKTABLE_NAME");
//                    String pkColumnName = foreignKeys.getString("PKCOLUMN_NAME");
//                    schemaBuilder.append("    FOREIGN KEY (").append(fkColumnName).append(") REFERENCES ")
//                            .append(pkTableName).append("(").append(pkColumnName).append("),\n");
//                }
//                foreignKeys.close();
//
//                // Remove trailing comma and newline, add closing parenthesis
//                if (schemaBuilder.charAt(schemaBuilder.length() - 2) == ',') {
//                    schemaBuilder.delete(schemaBuilder.length() - 2, schemaBuilder.length());
//                }
//                schemaBuilder.append(");\n\n");
//            }
//
//            return schemaBuilder.toString();
//        } catch (SQLException e) {
//            System.err.println("Error fetching database schema: " + e.getMessage());
//            return ""; // Return empty or throw specific exception
//        }
//    }

    private List<TableInfo> fetchTableInfos() throws SQLException {
        if (cachedTableInfos != null) {
            return cachedTableInfos;
        }
        List<TableInfo> tables = readTableInfosFromFile();
        if (tables.isEmpty()) {
            try (Connection connection = dataSource.getConnection()) {
                DatabaseMetaData metaData = connection.getMetaData();
                ResultSet rsTables = metaData.getTables(null, null, "%", new String[]{"TABLE"});
                while (rsTables.next()) {
                    String tableName = rsTables.getString("TABLE_NAME");
                    if (tableName.toLowerCase().endsWith("_aud")) {
                        continue;
                    }
                    TableInfo info = new TableInfo();
                    info.setTableName(tableName);

                    ResultSet rsColumns = metaData.getColumns(null, null, tableName, "%");
                    while (rsColumns.next()) {
                        info.getColumns().add(rsColumns.getString("COLUMN_NAME"));
                    }
                    rsColumns.close();

                    ResultSet rsPK = metaData.getPrimaryKeys(null, null, tableName);
                    while (rsPK.next()) {
                        info.getPrimaryKeys().add(rsPK.getString("COLUMN_NAME"));
                    }
                    rsPK.close();

                    ResultSet rsFK = metaData.getImportedKeys(null, null, tableName);
                    while (rsFK.next()) {
                        String fkColumn = rsFK.getString("FKCOLUMN_NAME");
                        String pkTable = rsFK.getString("PKTABLE_NAME");
                        String pkColumn = rsFK.getString("PKCOLUMN_NAME");
                        info.getForeignKeys().put(fkColumn, new TableInfo.ForeignKeyReference(pkTable, pkColumn));
                    }
                    rsFK.close();

                    tables.add(info);
                }
                rsTables.close();
            }
        }
        cachedTableInfos = tables;
        log.info("Fetched table infos: {}", tables);
        return tables;
    }

    public void clearTableInfoCache() {
        cachedTableInfos = null;
    }

    public List<Map<String, Object>> getSchemaDescriptions() {
        List<Map<String, Object>> schemaDescriptions = getSchemaDescriptionsFromFile();
        if (schemaDescriptions.isEmpty()) {
            try {
                for (TableInfo info : fetchTableInfos()) {
                    StringBuilder desc = new StringBuilder();
                    desc.append("The ").append(info.getTableName())
                            .append(" table has columns: ").append(String.join(", ", info.getColumns())).append(".");

                    if (!info.getPrimaryKeys().isEmpty()) {
                        desc.append(" Primary key: ").append(String.join(", ", info.getPrimaryKeys())).append(".");
                    }

                    if (!info.getForeignKeys().isEmpty()) {
                        desc.append(" Foreign keys: ");
                        info.getForeignKeys().forEach((fk, ref) ->
                                desc.append(fk)
                                        .append(" references ")
                                        .append(ref.getReferencedTable())
                                        .append("(")
                                        .append(ref.getReferencedColumn())
                                        .append("); ")
                        );
                    }

                    Map<String, Object> entry = new HashMap<>();
                    entry.put("text", desc.toString().trim());
                    entry.put("type", "schema_desc");
                    schemaDescriptions.add(entry);
                }
            } catch (SQLException e) {
                System.err.println("Error fetching schema descriptions: " + e.getMessage());
            }
        }
        log.info("Schema descriptions: {}", schemaDescriptions);
        return schemaDescriptions;
    }

    private String fetchAndFormatSchema() {
        StringBuilder schemaBuilder = new StringBuilder();
        try {
            for (TableInfo info : fetchTableInfos()) {
                schemaBuilder.append("-- Table: ").append(info.getTableName()).append("\n");
                schemaBuilder.append("Columns: ").append(String.join(", ", info.getColumns())).append("\n");

                if (!info.getPrimaryKeys().isEmpty()) {
                    schemaBuilder.append("Primary key: ").append(String.join(", ", info.getPrimaryKeys())).append("\n");
                }

                if (!info.getForeignKeys().isEmpty()) {
                    schemaBuilder.append("Foreign keys:\n");
                    info.getForeignKeys().forEach((fk, ref) ->
                            schemaBuilder.append("  ")
                                    .append(fk)
                                    .append(" -> ")
                                    .append(ref.getReferencedTable())
                                    .append("(")
                                    .append(ref.getReferencedColumn())
                                    .append(")\n")
                    );
                }
                schemaBuilder.append("\n");
            }
        } catch (SQLException e) {
            System.err.println("Error fetching database schema: " + e.getMessage());
        }
        return schemaBuilder.toString();
    }

    public List<String> getAllTableAndColumnNames() {
        List<String> names = new ArrayList<>();
        try {
            for (TableInfo table : fetchTableInfos()) {
                names.add(table.getTableName());
                names.addAll(table.getColumns());
            }
        } catch (Exception e) {
            log.error("Error fetching table/column names: {}", e.getMessage());
        }
        return names;
    }

    public String getRelevantSchemaFromContext(String ragContext) {
        StringBuilder schemaBuilder = new StringBuilder();
        try {
            List<TableInfo> allTables = fetchTableInfos();
            List<String> relevantTableNames = new ArrayList<>();
            for (TableInfo table : allTables) {
                String tableName = table.getTableName();
                if (ragContext.toLowerCase().contains(tableName.toLowerCase())) {
                    relevantTableNames.add(tableName);
                }
            }
            for (TableInfo info : allTables) {
                if (relevantTableNames.contains(info.getTableName())) {
                    schemaBuilder.append("-- Table: ").append(info.getTableName()).append("\n");
                    schemaBuilder.append("Columns: ").append(String.join(", ", info.getColumns())).append("\n");
                    if (!info.getPrimaryKeys().isEmpty()) {
                        schemaBuilder.append("Primary key: ").append(String.join(", ", info.getPrimaryKeys())).append("\n");
                    }
                    if (!info.getForeignKeys().isEmpty()) {
                        schemaBuilder.append("Foreign keys:\n");
                        info.getForeignKeys().forEach((fk, ref) ->
                                schemaBuilder.append("  ")
                                        .append(fk)
                                        .append(" -> ")
                                        .append(ref.getReferencedTable())
                                        .append("(")
                                        .append(ref.getReferencedColumn())
                                        .append(")\n")
                        );
                    }
                    schemaBuilder.append("\n");
                }
            }
        } catch (Exception e) {
            log.error("Error fetching relevant schema: {}", e.getMessage());
        }
        return schemaBuilder.toString();
    }

    public List<Map<String, Object>> getSchemaDescriptionsFromFile() {
        try {
            return mapper.readValue(
                    schemaDescription.getInputStream(),
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class)
            );
        } catch (Exception e) {
            log.error("Error reading schema_descriptions.json: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<TableInfo> readTableInfosFromFile() {
        try {
            Resource resource = new ClassPathResource("/schema/tables.json");
            return mapper.readValue(
                    resource.getInputStream(),
                    mapper.getTypeFactory().constructCollectionType(List.class, TableInfo.class)
            );
        } catch (Exception e) {
            log.error("Error reading tables.json: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
