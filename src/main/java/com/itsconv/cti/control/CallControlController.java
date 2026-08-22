package com.itsconv.cti.control;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/calls")
@RequiredArgsConstructor
public class CallControlController {

    private final CallControlService service;

    @PostMapping
    public CallResponse call(@RequestBody CallRequest request) {
        return new CallResponse(service.clickToCall(request.loginId(), request.number()));
    }

    // 202: 명령이 나갔다는 뜻. 결과는 AMI 이벤트가 푸시로 알린다 (ADR-0011)
    @PostMapping("/{callId}/answer")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void answer(@PathVariable String callId, @RequestBody ControlRequest request) {
        service.answer(callId, request.loginId());
    }

    @PostMapping("/{callId}/hangup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void hangup(@PathVariable String callId, @RequestBody ControlRequest request) {
        service.hangup(callId, request.loginId());
    }

    @PostMapping("/{callId}/hold")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void hold(@PathVariable String callId, @RequestBody ControlRequest request) {
        service.hold(callId, request.loginId());
    }

    @PostMapping("/{callId}/unhold")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void unhold(@PathVariable String callId, @RequestBody ControlRequest request) {
        service.unhold(callId, request.loginId());
    }

    record CallRequest(String loginId, String number) {
    }

    record ControlRequest(String loginId) {
    }

    record CallResponse(String callId) {
    }
}
