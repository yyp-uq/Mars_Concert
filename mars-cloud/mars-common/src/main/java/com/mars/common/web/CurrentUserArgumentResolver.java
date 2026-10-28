package com.mars.common.web;

import com.mars.common.enums.CommonErrorCode;
import com.mars.common.exception.BusinessException;
import com.mars.common.security.LoginUser;
import com.mars.common.security.UserContext;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter p, ModelAndViewContainer m,
                                  NativeWebRequest w, WebDataBinderFactory b) {
        LoginUser user = UserContext.get();
        CurrentUser ann = p.getParameterAnnotation(CurrentUser.class);
        boolean required = ann == null || ann.required();
        if (user == null) {
            if (required) {
                throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
            }
            return null;    // 游客场景（required=false）
        }
        // 参数类型是 Long 就返回 userId，否则返回整个 LoginUser
        if (Long.class.equals(p.getParameterType())) {
            return user.getUserId();
        }
        return user;
    }
}
