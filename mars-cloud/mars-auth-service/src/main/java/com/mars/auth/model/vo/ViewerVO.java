package com.mars.auth.model.vo;

import lombok.Data;

@Data
public class ViewerVO {
    private Long id;
    private String name;
    private String idCard;
    private String phone;
    private Integer isDefault;
}
