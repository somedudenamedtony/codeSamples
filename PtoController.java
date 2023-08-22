package com.fishbowllabor.server.controller;

import com.fishbowllabor.commons.data.Search;
import com.fishbowllabor.server.config.json.JSONMapper;
import com.fishbowllabor.server.logic.PtoLogic;
import com.fishbowllabor.server.data.Success;
import com.fishbowllabor.tenant.data.SearchList;
import com.fishbowllabor.tenant.data.timelog.TimeOff;
import com.fishbowllabor.tenant.entity.Pto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.UUID;

@Controller
@RequestMapping("/pto")
public class PtoController {

    @Autowired
    private PtoLogic ptoLogic;

    @GetMapping
    @ResponseBody
    @PreAuthorize("hasRole('Admin') || hasRole('Manager')")
    public SearchList<TimeOff> getPtoList(@RequestParam(value = "search", required = false) String search) throws IOException {
        return ptoLogic.ptoRequestSearch(new JSONMapper().readValue(search, Search.class));
    }

    @GetMapping("my")
    @ResponseBody
    public SearchList<TimeOff> getMyPtoList(@RequestParam(value = "search", required = false) String search) throws IOException {
        return ptoLogic.ptoMyRequestSearch(new JSONMapper().readValue(search, Search.class));
    }

    @GetMapping("{requestId}")
    @ResponseBody
    public TimeOff getPtoList(@PathVariable("requestId") UUID requestId) {
        return ptoLogic.getTimeOff(requestId);
    }

    @PostMapping
    @ResponseBody
    public Success createPtoRequest(@RequestBody TimeOff timeOff) {
        ptoLogic.createPtoRequest(timeOff);
        return new Success();
    }

    @PatchMapping("{requestId}")
    @ResponseBody
    public Success updatePtoRequest(@PathVariable("requestId") UUID requestId, @RequestBody Pto pto) {
        ptoLogic.updatePtoRequest(requestId, pto.getStatus().toString(), pto.getDeletedOn());
        return new Success();
    }
}
