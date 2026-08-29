package com.vulnflow.ui.auth;

import com.vulnflow.ui.UiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class UiEnabledFilter extends OncePerRequestFilter {
    private final UiProperties properties;
    public UiEnabledFilter(UiProperties properties){this.properties=properties;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        if(!properties.enabled()){response.sendError(HttpServletResponse.SC_NOT_FOUND);return;}chain.doFilter(request,response);
    }
}
