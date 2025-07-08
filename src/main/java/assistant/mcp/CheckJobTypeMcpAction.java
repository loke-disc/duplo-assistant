package assistant.mcp;

import assistant.service.QueryExecutionService;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Map;
import java.util.UUID;

@Component
public class CheckJobTypeMcpAction implements McpAction {
    private final QueryExecutionService queryService;

    @Autowired
    public CheckJobTypeMcpAction(QueryExecutionService queryService) {
        this.queryService = queryService;
    }

    @Override
    public String getName() {
        return "check_job_type";
    }

    @Override
    public Object execute(Map<String, Object> params) {
        String userQuery = (String) params.get("userQuery");
        if (userQuery == null) {
            return "Missing workOrderId parameter.";
        }
        try {
            String jobType = queryService.getJobTypeForWorkOrder(userQuery);
            boolean isClosedCaptioningAI = "Closed Captioning AI".equalsIgnoreCase(jobType);
            return Map.of(
                    "jobType", jobType,
                    "isClosedCaptioningAI", isClosedCaptioningAI
            );
        } catch (Exception e) {
            return Map.of("error", "Invalid workOrderId or database error: " + e.getMessage());
        }
    }
}