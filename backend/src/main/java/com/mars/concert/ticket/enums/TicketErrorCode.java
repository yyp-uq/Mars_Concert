package com.mars.concert.ticket.enums;

import com.mars.concert.common.enums.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TicketErrorCode implements ErrorCode {
    TICKET_NOT_FOUND(5001, "电子票不存在"),
    TICKET_USED(5002, "该票已核销，请勿重复入场"),
    TICKET_NOT_VALID(5003, "票已失效，无法核销");

    private final int code;
    private final String message;
}
