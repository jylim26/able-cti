package com.itsconv.cti.agent;

import com.itsconv.cti.agent.domain.AgentSession;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService service;

    @GetMapping
    public List<AgentResponse> agents() {
        return service.sessions().stream().map(AgentResponse::from).toList();
    }

    @PostMapping("/{loginId}/login")
    public AgentResponse login(@PathVariable String loginId) {
        return AgentResponse.from(service.login(loginId));
    }

    @PostMapping("/{loginId}/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@PathVariable String loginId) {
        service.logout(loginId);
    }

    @PostMapping("/{loginId}/pause")
    public AgentResponse pause(@PathVariable String loginId, @RequestBody PauseRequest request) {
        return AgentResponse.from(service.pause(loginId, request.reason()));
    }

    @PostMapping("/{loginId}/unpause")
    public AgentResponse unpause(@PathVariable String loginId) {
        return AgentResponse.from(service.unpause(loginId));
    }

    record PauseRequest(String reason) {
    }

    record AgentResponse(long agentId, String loginId, String name, String extension, String status, String pauseReason) {

        static AgentResponse from(AgentSession session) {
            return new AgentResponse(session.getAgentId(), session.getLoginId(), session.getName(), session.getExtension(), session.getStatus().name(), session.getPauseReason());
        }
    }
}
