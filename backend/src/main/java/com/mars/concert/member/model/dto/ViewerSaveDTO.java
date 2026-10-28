package com.mars.concert.member.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ViewerSaveDTO {
    @NotBlank(message = "观演人姓名不能为空")
    private String name;

    @NotBlank(message = "证件号不能为空")
    private String idCard;

    private String phone;

    private Integer isDefault;
}
