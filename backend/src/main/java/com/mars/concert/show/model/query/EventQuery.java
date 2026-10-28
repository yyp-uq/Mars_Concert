package com.mars.concert.show.model.query;

import lombok.Data;

@Data
public class EventQuery {
    private Long venueId;
    private Integer status;
    private Long pageNum = 1L;
    private Long pageSize = 10L;
}
