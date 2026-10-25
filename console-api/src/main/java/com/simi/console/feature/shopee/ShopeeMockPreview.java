package com.simi.console.feature.shopee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simi.common.shopee.ShopeeException;
import com.simi.console.feature.adapter.AdapterRequest;
import com.simi.console.feature.adapter.AdapterResult;
import org.springframework.stereotype.Component;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** Fixed synthetic data only. No HTTP client and no provider credentials. */
@Component
public class ShopeeMockPreview {
    private final ShopeeRunRepository runs;
    private final ShopeeExecutionGuard guard;
    private final ObjectMapper json;
    public ShopeeMockPreview(ShopeeRunRepository runs, ShopeeExecutionGuard guard, ObjectMapper json) {
        this.runs=runs; this.guard=guard; this.json=json;
    }
    public AdapterResult execute(AdapterRequest request) {
        guard.checkAction(request.userId(), request.executionId(), request.action());
        var run = runs.findById(request.executionId()).orElseThrow();
        ObjectNode body = request.body();
        String mode = body.path("load_mode").asText("INITIAL");
        String outcome = body.path("outcome").asText("SUCCESS");
        if (!Set.of("INITIAL", "INCREMENTAL").contains(mode)
                || !Set.of("SUCCESS", "FAILED", "TIMEOUT").contains(outcome))
            throw new ShopeeException(400, "Invalid mock preview option");
        if ("TIMEOUT".equals(outcome)) return AdapterResult.timeout("Simulated mock timeout; no vendor request was sent", 0);
        if ("FAILED".equals(outcome)) return AdapterResult.failed(503, json.createObjectNode().put("mode", "mock-only"),
                "Simulated mock failure; no vendor request was sent", 0);
        ObjectNode response = json.createObjectNode(); ObjectNode preview = response.putObject("preview");
        preview.put("mode", "mock-only"); preview.put("liveData", false); preview.put("complete", false);
        preview.put("sourceKind", run.getKind()); preview.put("shopAccountId", run.getShopAccountId());
        preview.put("loadMode", mode); preview.put("timezone", "UTC");
        preview.put("windowFrom", ("INITIAL".equals(mode)
                ? run.getFirstLoadDate().atStartOfDay(ZoneOffset.UTC).toInstant()
                : run.getRunAt().minus(run.getLookbackDays(), ChronoUnit.DAYS)).toString());
        preview.put("windowTo", run.getRunAt().toString());
        var rows = preview.putArray("records");
        for (String type : run.getDataTypes()) {
            rows.addObject().put("fixtureType", type).put("fixtureRecordId", type + "_1").put("fixtureValue", 42);
            rows.addObject().put("fixtureType", type).put("fixtureRecordId", type + "_2").put("fixtureValue", 84);
        }
        preview.put("recordCount", rows.size());
        return AdapterResult.completed(200, response, 0);
    }
}
