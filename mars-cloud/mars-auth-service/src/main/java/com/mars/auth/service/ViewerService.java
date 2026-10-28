package com.mars.auth.service;

import com.mars.auth.model.dto.ViewerSaveDTO;
import com.mars.auth.model.vo.ViewerVO;

import java.util.List;

public interface ViewerService {
    Long add(Long userId, ViewerSaveDTO dto);
    List<ViewerVO> listByUser(Long userId);
    void updateForUser(Long userId, Long id, ViewerSaveDTO dto);
    void deleteForUser(Long userId, Long id);
}
