package com.mars.concert.ticket.controller;

import com.mars.concert.common.result.Result;
import com.mars.concert.common.utils.QrCodeUtil;
import com.mars.concert.common.web.CurrentUser;
import com.mars.concert.ticket.entity.Ticket;
import com.mars.concert.ticket.model.vo.TicketVO;
import com.mars.concert.ticket.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "交易-电子票")
@RestController
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @Operation(summary = "我的电子票列表")
    @GetMapping("/api/v1/tickets")
    public Result<List<TicketVO>> myTickets(@CurrentUser Long userId) {
        return Result.success(ticketService.listMine(userId));
    }

    @Operation(summary = "电子票二维码(本人)")
    @GetMapping("/api/v1/tickets/{ticketNo}/qrcode")
    public void qrcode(@CurrentUser Long userId, @PathVariable String ticketNo,
                       HttpServletResponse resp) throws Exception {
        Ticket t = ticketService.getOwnedTicket(userId, ticketNo);   // 必须本人
        byte[] img = QrCodeUtil.generate(t.getQrToken(), 260);
        resp.setContentType("image/png");
        resp.getOutputStream().write(img);
    }

    @Operation(summary = "扫码核销(管理员/检票员)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/v1/admin/tickets/checkin")
    public Result<Void> checkin(@RequestParam String token,
                                @RequestHeader(value = "X-Device", defaultValue = "UNKNOWN") String device) {
        ticketService.checkin(token, device);
        return Result.success();
    }
}
