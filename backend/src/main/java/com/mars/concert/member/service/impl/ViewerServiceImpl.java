package com.mars.concert.member.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mars.concert.common.enums.CommonErrorCode;
import com.mars.concert.common.exception.BusinessException;
import com.mars.concert.member.entity.Viewer;
import com.mars.concert.member.mapper.ViewerMapper;
import com.mars.concert.member.model.dto.ViewerSaveDTO;
import com.mars.concert.member.model.vo.ViewerVO;
import com.mars.concert.member.service.ViewerService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ViewerServiceImpl implements ViewerService {

    private final ViewerMapper viewerMapper;

    @Override
    public Long add(Long userId, ViewerSaveDTO dto) {
        Viewer v = new Viewer();
        BeanUtils.copyProperties(dto, v);
        v.setUserId(userId);          // 服务端可信 userId，绝不取前端传值
        if (v.getIsDefault() == null) {
            v.setIsDefault(0);
        }
        viewerMapper.insert(v);
        return v.getId();
    }

    @Override
    public List<ViewerVO> listByUser(Long userId) {
        List<Viewer> list = viewerMapper.selectList(Wrappers.<Viewer>lambdaQuery()
                .eq(Viewer::getUserId, userId)
                .orderByDesc(Viewer::getIsDefault)
                .orderByDesc(Viewer::getId));
        return list.stream().map(v -> {
            ViewerVO vo = new ViewerVO();
            BeanUtils.copyProperties(v, vo);
            return vo;
        }).toList();
    }

    @Override
    public void updateForUser(Long userId, Long id, ViewerSaveDTO dto) {
        // 关键：WHERE id=? AND user_id=?，A 用户永远改不到 B 用户的数据（防水平越权）
        Viewer exist = viewerMapper.selectOne(Wrappers.<Viewer>lambdaQuery()
                .eq(Viewer::getId, id).eq(Viewer::getUserId, userId));
        if (exist == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        Viewer v = new Viewer();
        BeanUtils.copyProperties(dto, v);
        v.setId(id);
        viewerMapper.updateById(v);
    }

    @Override
    public void deleteForUser(Long userId, Long id) {
        Viewer exist = viewerMapper.selectOne(Wrappers.<Viewer>lambdaQuery()
                .eq(Viewer::getId, id).eq(Viewer::getUserId, userId));
        if (exist == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        viewerMapper.deleteById(id);   // 因 @TableLogic，实际是 UPDATE deleted=1
    }
}
