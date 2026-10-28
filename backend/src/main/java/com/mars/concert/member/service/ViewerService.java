package com.mars.concert.member.service;

import com.mars.concert.member.model.dto.ViewerSaveDTO;
import com.mars.concert.member.model.vo.ViewerVO;

import java.util.List;

public interface ViewerService {
    Long add(Long userId, ViewerSaveDTO dto);
    List<ViewerVO> listByUser(Long userId);
    void updateForUser(Long userId, Long id, ViewerSaveDTO dto);
    void deleteForUser(Long userId, Long id);
}
