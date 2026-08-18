package com.itsconv.cti.control;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    record CallRequest(String loginId, String number) {
    }

    record CallResponse(String callId) {
    }
}
