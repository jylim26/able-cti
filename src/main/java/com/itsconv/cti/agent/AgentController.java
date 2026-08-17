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

    @PostMapping("/{extension}/login")
    public AgentResponse login(@PathVariable String extension) {
        return AgentResponse.from(service.login(extension));
    }

    @PostMapping("/{extension}/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@PathVariable String extension) {
        service.logout(extension);
    }

    @PostMapping("/{extension}/pause")
    public AgentResponse pause(@PathVariable String extension, @RequestBody PauseRequest request) {
        return AgentResponse.from(service.pause(extension, request.reason()));
    }

    @PostMapping("/{extension}/unpause")
    public AgentResponse unpause(@PathVariable String extension) {
        return AgentResponse.from(service.unpause(extension));
    }

    record PauseRequest(String reason) {
    }

    record AgentResponse(long agentId, String name, String extension, String status, String pauseReason) {

        static AgentResponse from(AgentSession session) {
            return new AgentResponse(session.getAgentId(), session.getName(), session.getExtension(), session.getStatus().name(), session.getPauseReason());
        }
    }
}
