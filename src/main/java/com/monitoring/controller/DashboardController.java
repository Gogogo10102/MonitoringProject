package com.monitoring.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    /**
     * 대시보드 메인 페이지
     */
    @GetMapping("/")
    public String index() {
        return "redirect:/monitoring";
    }

    @GetMapping("/monitoring")
    public String monitoring() {
        return "index.html";
    }
}