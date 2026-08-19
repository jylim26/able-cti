package com.itsconv.cti.push;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.itsconv.cti.call.event.CallConnectedEvent;
import com.itsconv.cti.call.event.CallDialingEvent;
import com.itsconv.cti.call.event.CallEndedEvent;
import com.itsconv.cti.call.event.CallRingingCanceledEvent;
import com.itsconv.cti.call.event.CallRingingEvent;
import com.itsconv.cti.call.event.OutboundCallFailedEvent;

public record CallEventMessage(String type, String event, Data data) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Data(String callId, String customerNumber, String queueName, String direction, Integer reason) {
    }

    public static CallEventMessage ringing(CallRingingEvent event) {
        return new CallEventMessage("CALL", "RINGING", new Data(event.callId(), event.callerNumber(), event.queueName(), null, null));
    }

    public static CallEventMessage ringingCanceled(CallRingingCanceledEvent event) {
        return new CallEventMessage("CALL", "RINGING_CANCELED", new Data(event.callId(), null, null, null, null));
    }

    public static CallEventMessage dialing(CallDialingEvent event) {
        return new CallEventMessage("CALL", "DIALING", new Data(event.callId(), event.customerNumber(), null, null, null));
    }

    public static CallEventMessage answered(CallConnectedEvent event) {
        return new CallEventMessage("CALL", "ANSWERED", new Data(event.callId(), null, null, event.direction(), null));
    }

    public static CallEventMessage ended(CallEndedEvent event) {
        return new CallEventMessage("CALL", "ENDED", new Data(event.callId(), null, null, event.direction(), null));
    }

    public static CallEventMessage outboundFailed(OutboundCallFailedEvent event) {
        return new CallEventMessage("CALL", "OUTBOUND_FAILED", new Data(event.callId(), null, null, null, event.reason()));
    }
}
