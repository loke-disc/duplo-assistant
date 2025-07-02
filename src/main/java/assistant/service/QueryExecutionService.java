package assistant.service;

import assistant.mcp.McpActionDispatcher;
import assistant.model.ConversationHistory;
import assistant.model.ConversationTurn;
import assistant.model.SqlHistory;
import assistant.repository.ConversationHistoryRepository;
import assistant.repository.SqlHistoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;


@Slf4j
@Service
public class QueryExecutionService {
    private final JdbcTemplate jdbcTemplate;
    private final ChatLanguageModel chatModel;
    private final ConversationHistoryRepository conversationHistoryRepository;
    private final SqlHistoryRepository sqlHistoryRepository;
    private final ObjectMapper objectMapper;
    private final SchemaService schemaService;
    private final RAGService ragService;
    private final McpActionDispatcher mcpActionDispatcher;
    private String intent;

    @Value("${llm.model.name}")
    private String llmModelName;

    public QueryExecutionService(JdbcTemplate jdbcTemplate,
                                 @Qualifier("sqlOptimizedModel") ChatLanguageModel chatModel,
                                 ConversationHistoryRepository conversationHistoryRepository,
                                 SchemaService schemaService,
                                 RAGService ragService,
                                 McpActionDispatcher mcpActionDispatcher,
                                 SqlHistoryRepository sqlHistoryRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.chatModel = chatModel;
        this.conversationHistoryRepository = conversationHistoryRepository;
        this.objectMapper = new ObjectMapper();
        this.schemaService = schemaService;
        this.ragService = ragService;
        this.mcpActionDispatcher = mcpActionDispatcher;
        this.sqlHistoryRepository = sqlHistoryRepository;
    }

    public String executeTestQuery(String workOrderId) {
        // test query execution
        UUID workOrderUUID = UUID.fromString(workOrderId);
        String sql = "select wos.display_name from work_order wo \n" +
                "left join work_order_status wos on wos.work_order_status_id = wo.work_order_status_id \n" +
                "where wo.work_order_id = ?";
        return jdbcTemplate.queryForObject(sql, new Object[]{workOrderUUID}, String.class);
    }

    public String processNaturalLanguageQuery(String userQuery, String conversationId) {
        ConversationHistory history = conversationHistoryRepository.findByConversationId(conversationId)
                .orElseGet(() -> {
                    ConversationHistory newHistory = new ConversationHistory();
                    newHistory.setConversationId(conversationId);
                    newHistory.setCreatedAt(Instant.now());
                    newHistory.setUserId("anonymous");
                    return newHistory;
                });

        String previousContext = history.getHistory().stream()
                .map(turn -> "User: " + turn.getUserQuery() + "\nAI: " + turn.getLlmFormattedResponse())
                .collect(Collectors.joining("\n"));

        intent = checkIntent(userQuery);
        if (Objects.equals(intent, "answer")){
           return getDocumentAssistAnswer(userQuery);
        }

        String wo_id = "";
        if (Objects.equals(intent, "check_error")){
            userQuery = String.format("what is the workorder_status of work_order_id = '%s'", wo_id);
        }
        /** *
         * use the classification intent to determine the next steps
         * if "sql" use the existing orchestration logic
         * if "document" use the document intent action - change answer to the document intent action
         * if "check_error" use the check_error action -
         * 1. generate sql to get wo status
         * 2. execute the sql
         * 3. if the sql returns "Caption Failed", proceed with the next actions, if not return "yet to be implemented, currently only works for Caption Failed validations"
         * 4. generate sql to get wo due date
         * 5. execute the sql
         * 6. check if the due date is within SLA(1 day from present date)
         * 7. if failed, return "Caption Failed" due to SLA breach, if not SLA breach, proceed with the next validations
         * 8. check with manifestation api. (discuss tomorrow)
         */

        List<String> relevantRAGChunks = ragService.retrieveRelevantContext(userQuery, previousContext);
        String ragContext = String.join("\n", relevantRAGChunks);
        String databaseSchema = schemaService.getRelevantSchemaFromContext(ragContext);

        String prompt = buildLlmPrompt(userQuery, databaseSchema, ragContext, previousContext, conversationId);
//        log.info("Generated LLM prompt: {}", prompt);
        String llmResponse = chatModel.generate(prompt);
        log.info("LLM response: {}", llmResponse);

        String finalResult = null;
        String lastAction = null;
        Map<String, Object> lastParams = null;

        try {
            while (true) {
                JsonNode node = objectMapper.readTree(llmResponse);
                if (!node.has("action")) {
                    finalResult = llmResponse;
                    break;
                }
                String action = node.get("action").asText();
                Map<String, Object> params = objectMapper.convertValue(node.get("params"), Map.class);
                log.info("LLM requested action: {}, params: {}", action, params);

                Object mcpResult = mcpActionDispatcher.dispatch(action, params);
                log.info("MCP action result: {}", mcpResult);

                if ("validate_user_request".equals(action) && mcpResult instanceof String && !"Valid".equals(mcpResult)) {
                    finalResult = mcpResult.toString();
                    break;
                }
                if ("check_query".equals(action) && mcpResult instanceof String && mcpResult.toString().contains("Failed")) {
                    finalResult = mcpResult.toString();
                    break;
                }
                // Save turn in history
                ConversationTurn turn = new ConversationTurn();
                turn.setTurn(history.getHistory().size() + 1);
                turn.setUserQuery(userQuery);
                turn.setLlmFormattedResponse(objectMapper.writeValueAsString(mcpResult));
                turn.setTimestamp(Instant.now());
                history.getHistory().add(turn);
                conversationHistoryRepository.save(history);

                boolean isTerminal = ("execute_query".equals(action) || "summarize_results".equals(action));
                boolean hasError = mcpResult instanceof Map && ((Map<?, ?>) mcpResult).containsKey("error");

                if (isTerminal && !hasError) {
                    finalResult = objectMapper.writeValueAsString(mcpResult);
                    if ("execute_query".equals(action)) {
                        Map newParams = objectMapper.convertValue(node.get("params"), Map.class);
                        String sql = (String) newParams.get("sql");
                        SqlHistory history1 = new SqlHistory();
                        history1.setQuery(userQuery);
                        history1.setSuccessfulSql(sql);
                        sqlHistoryRepository.save(history1);
                    }
                    break;
                }

                if (hasError) {
                    params.put("failureReason", ((Map<?, ?>) mcpResult).get("error"));
                }

                llmResponse = chatModel.generate(
                        buildFollowupPrompt(userQuery, databaseSchema, ragContext, previousContext, conversationId, action, mcpResult)
                );
                log.info("Followup LLM response: {}", llmResponse);
            }
        } catch (Exception e) {
            log.error("Error in LLM orchestration: {}", e.getMessage(), e);
            finalResult = "Error: " + e.getMessage();
        }

        return finalResult;
    }

    private String buildLlmPrompt(String userQuery, String schema, String ragContext, String previousContext, String conversationId) {
        String conversationHistorySection = (previousContext != null && !previousContext.isEmpty())
                ? String.format("CONVERSATION HISTORY:\n%s\n", previousContext)
                : "";
        return String.format(
                """
                You are an intelligent assistant for a PostgreSQL database with access to the following tools (MCP actions):

                Available action:
                - validate_user_request: Check if the user query is actionable.
    
                Instructions:
                1. Always start with `validate_user_request` for the very first user query.
                2. Do not use any other action at this step.
                3. Respond only with a single JSON object for the action, e.g.:
                  {
                    "action": "validate_user_request",
                    "params": {
                      "userQuery": "<user query>"
                    }
                  }
                - Do not return SQL directly or outside JSON.

                DATABASE SCHEMA:
                %s

                RAG CONTEXT:
                %s

                %s
                User Query: %s
                
                conversationId: %s
                """,
                schema, ragContext, conversationHistorySection, userQuery, conversationId
        );
    }

    private String buildFollowupPrompt(String userQuery, String schema, String ragContext, String previousContext,
                                       String conversationId, String lastAction, Object lastResult) {
        String conversationHistorySection = (previousContext != null && !previousContext.isEmpty())
                ? String.format("CONVERSATION HISTORY:\n%s\n", previousContext)
                : "";
        // Detect if last action was check_query and passed
        boolean checkQueryPassed = "check_query".equals(lastAction)
                && lastResult != null
                && lastResult.toString().toLowerCase().contains("passed");
        String forceExecuteQuery = checkQueryPassed
                ? "\nIMPORTANT: The last action was `check_query` and it passed. The ONLY valid next action is `execute_query` with the SAME SQL. Do NOT use `generate_sql` or any other action. Any other action is a critical error.\n"
                : "";
        return String.format(
                """
                    You are an intelligent assistant for a PostgreSQL database with access to the following tools (MCP actions):
        
                    Available actions:
                    - generate_sql: Generate a SQL statement for a valid user query.
                    - check_query: Check the generated SQL query for safety and correctness.
                    - execute_query: Execute a SQL query and return results.
                    - explain_query: Get the execution plan for a SQL query.
                    - summarize_results: Summarize a large result set.
                    
                    %s
                    Instructions:
                    1. If the last action was `check_query` and it passed, the ONLY valid next action is `execute_query` with the SAME SQL. Do NOT use `generate_sql` or any other action.
                    2. Do not use `validate_user_request` again after the first step.
                    3. If the request is valid, use `generate_sql` to create the SQL.
                    4. After `generate_sql` returns a SQL (starting with SELECT), always use `check_query` next.
                    5. If `execute_query` fails, use `generate_sql` again with the failure reason.
                    6. If the result set is too large, use `explain_query` and then `summarize_results`.
                    7. Do not repeat any action unless the previous step failed.
                    8. Always respond with a single JSON object for the next action.
                    9. Do not return SQL directly or outside JSON.
                    10. Always copy the entire DATABASE SCHEMA and RAG CONTEXT sections exactly as provided above into the corresponding fields.
        
                    JSON examples for each action:
                    {
                      "action": "generate_sql",
                      "params": {
                        "userQuery": "<user query>",
                        "failureReason": "<error message or null>",
                        "databaseSchema": "<schema>",
                        "ragContext": "<rag context>",
                        "previousContext": "<conversation history>",
                        "conversationId": "<conversation id>"
                      }
                    }
                    {
                      "action": "check_query",
                      "params": {
                        "sql": "<sql statement>"
                      }
                    }
                    {
                      "action": "execute_query",
                      "params": {
                        "sql": "<sql statement>"
                      }
                    }
                    {
                      "action": "explain_query",
                      "params": {
                        "sql": "<sql statement>"
                      }
                    }
                    {
                      "action": "summarize_results",
                      "params": {
                        "results": "<result set>"
                      }
                    }
        
                    CONTEXT:
                    Last action: %s
                    Result: %s
        
                    DATABASE SCHEMA:
                    %s
        
                    RAG CONTEXT:
                    %s
        
                    %s
                    User Query: %s
        
                    conversationId: %s
        
                    Based on the above, provide the next MCP action as a JSON object.
                    """,
                forceExecuteQuery, lastAction, lastResult, schema, ragContext, conversationHistorySection, userQuery, conversationId
        );
    }

    private String buildGenerateSqlLlmPrompt(String userQuery, String failureReason, String databaseSchema, String ragContext, String previousContext, String conversationId) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert SQL generator for a PostgreSQL database.\n");
        sb.append("Given the following context, generate a single, safe, executable SELECT SQL statement that answers the user's question.\n\n");
        sb.append("DATABASE SCHEMA:\n").append(databaseSchema).append("\n\n");
        if (ragContext != null && !ragContext.isEmpty()) {
            sb.append("RAG CONTEXT:\n").append(ragContext).append("\n\n");
        }
        if (previousContext != null && !previousContext.isEmpty()) {
            sb.append("CONVERSATION HISTORY:\n").append(previousContext).append("\n\n");
        }
        sb.append("User Query: ").append(userQuery).append("\n");
        if (failureReason != null && !failureReason.isEmpty()) {
            sb.append("Previous SQL execution failed. Error: ").append(failureReason).append("\n");
            sb.append("Regenerate a correct SQL statement that avoids this error.\n");
        }
        sb.append("Rules:\n");
        sb.append("1. Use only SELECT statements.\n");
        sb.append("2. Use exact table and column names from the schema.\n");
        sb.append("3. Do not include DDL or DML statements.\n");
        sb.append("4. Add LIMIT 100 unless otherwise specified.\n");
        sb.append("5. Output only the SQL, either as plain text, in a code block, or as a JSON field named 'sql'.\n");
        sb.append("6. Do not include explanations or comments.\n");
        sb.append("7. Do not ask for user confirmation.\n");
        sb.append("conversationId: ").append(conversationId).append("\n");
        return sb.toString();
    }

    public String extractCodeBlockFromResponse(String llmResponse) {
        // 1. Try to extract from code block
        String[] codeBlocks = llmResponse.split("```");
        if (codeBlocks.length >= 2) {
            String blockContent = codeBlocks[1].replaceFirst("(?i)^sql\\s*", "").trim();
            if (blockContent.toUpperCase().startsWith("SELECT")) {
                return blockContent.replaceAll("\\s+", " ").trim();
            }
        }

        // 2. Try to extract JSON and get the "sql" field
        Pattern jsonPattern = Pattern.compile("\\{(?:[^{}]|\\{[^{}]*\\})*\\}");
        Matcher jsonMatcher = jsonPattern.matcher(llmResponse);
        if (jsonMatcher.find()) {
            String json = jsonMatcher.group();
            try {
                JsonNode node = new ObjectMapper().readTree(json);
                for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                    String field = it.next();
                    if (field.equalsIgnoreCase("sql")) {
                        return node.get(field).asText().replaceAll("\\s+", " ").trim();
                    }
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Found JSON but failed to parse: " + e.getMessage());
            }
        }

        // 3. Try to find a SELECT statement ending with ;
        Pattern selectPattern = Pattern.compile("(?is)SELECT[\\s\\S]*?;");
        Matcher matcher = selectPattern.matcher(llmResponse);
        if (matcher.find()) {
            return matcher.group().replaceAll("\\s+", " ").trim();
        }

        throw new IllegalArgumentException("No SQL query found in LLM response");
    }

    public String validateUserRequest(String userQuery) {
        if (userQuery == null || userQuery.trim().isEmpty()) {
            return "Invalid: UserQuery is empty.";
        }

        if (userQuery.trim().length() < 5) {
            return "Invalid: Query too short.";
        }

        List<String> relevantContext = ragService.retrieveRelevantContext(userQuery, "");
        boolean hasRagContext = relevantContext != null && !relevantContext.isEmpty();

        // ✅ Fallback if embeddings fail: check for schema-relevance
        boolean matchesSchemaDirectly = ragService.isLikelySchemaRelevant(userQuery);

        if (!hasRagContext && !matchesSchemaDirectly) {
            return "Invalid: Query does not match any known tables or columns (embedding or schema check).";
        }

        // Optional: If RAG context was found, ensure it includes schema terms
        if (hasRagContext) {
            boolean containsSchemaTerm = relevantContext.stream()
                    .anyMatch(ctx -> schemaService.getAllTableAndColumnNames().stream()
                            .anyMatch(name -> ctx.toLowerCase().contains(name.toLowerCase())));
            if (!containsSchemaTerm) {
                return "Invalid: Query is not relevant to database schema.";
            }
        }

        return "Valid";
    }

    // Uses LLM to generate SQL from the user query (optionally with failure reason)
    public String generateSql(String userQuery, String failureReason, String databaseSchema, String ragContext, String previousContext, String conversationId) {
        String prompt = buildGenerateSqlLlmPrompt(userQuery, failureReason, databaseSchema, ragContext, previousContext, conversationId);
        String llmResponse = chatModel.generate(prompt);
        return extractCodeBlockFromResponse(llmResponse);
    }

    // Validates the SQL for safety and correctness
    public String validateQuery(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "Query Check Failed: SQL is empty.";
        }

        String lowerSql = sql.toLowerCase().trim();

        // ✅ 2. Blacklist dangerous SQL keywords
        List<String> forbiddenKeywords = List.of(
                "drop", "delete", "truncate", "alter", "update", "insert",
                "merge", "call", "grant", "revoke", "execute", "create", "replace"
        );
        for (String keyword : forbiddenKeywords) {
            if (lowerSql.matches(".*\\b" + keyword + "\\b.*")) {
                return "Query Check Failed: Dangerous SQL keyword detected: " + keyword;
            }
        }

        // ✅ 3. Basic SQL injection prevention
        if (lowerSql.contains("--") || lowerSql.contains("/*") || lowerSql.contains("*/")) {
            return "Query Check Failed: SQL comments are not allowed.";
        }
        if (lowerSql.contains(" or ") && (lowerSql.contains("= ") || lowerSql.contains("=1") || lowerSql.contains("1=1"))) {
            return "Query Check Failed: Potential SQL injection pattern (e.g., OR 1=1).";
        }
        String trimmed = lowerSql.trim();
        int semicolonCount = trimmed.length() - trimmed.replace(";", "").length();
        if (semicolonCount > 1 || (semicolonCount == 1 && !trimmed.endsWith(";"))) {
            return "Query Check Failed: Multiple SQL statements (semicolon) are not allowed.";
        }

        return "Query Check Passed. Can be executed.";
    }

    // Executes the SQL and returns the result
    public Object executeQuery(String sql) {
        try {
            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql);
            return result;
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    // Returns the execution plan for the SQL
    public Object explainQuery(String sql) {
        try {
            List<Map<String, Object>> plan = jdbcTemplate.queryForList("EXPLAIN " + sql);
            return plan;
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    // Summarizes a large result set (simple example)
    public String summarizeResults(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return "No results to summarize.";
        }
        // Simple summary: number of rows and columns
        int rowCount = results.size();
        int colCount = results.get(0).size();
        return "Rows: " + rowCount + ", Columns: " + colCount;
    }

    // Checks the intent of a given text using an external Python script
    public String checkIntent(String text) {
        try {
            File pythonScript = new File(System.getProperty("user.dir") + "/duplo_document_assist/intent.py");
            if (!pythonScript.exists()) {
                throw new RuntimeException("Python script not found: " + pythonScript.getAbsolutePath());
            }
            ProcessBuilder pb = new ProcessBuilder("python3", System.getProperty("user.dir") + "/duplo_document_assist/intent.py", text);
            //pb.directory(new File("duplo_document_assist"));

            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
            process.waitFor();
            return output.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    public String getDocumentAssistAnswer(String query) {
        try {
            File pythonScript = new File(System.getProperty("user.dir") + "/duplo_document_assist/app.py");
            if (!pythonScript.exists()) {
                throw new RuntimeException("Python script not found: " + pythonScript.getAbsolutePath());
            }
            ProcessBuilder pb = new ProcessBuilder("python3", System.getProperty("user.dir") + "/duplo_document_assist/app.py", query);
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
            process.waitFor();
            return output.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}